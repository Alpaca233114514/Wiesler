param(
    [Parameter(Mandatory)][string]$WindowsPackage,
    [Parameter(Mandatory)][string]$UnsignedApk,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$AndroidKeyDirectory,
    [Parameter(Mandatory)][string]$AndroidBuildTools,
    [Parameter(Mandatory)][string]$SignTool,
    [Parameter(Mandatory)][string]$SourceCommit,
    [Parameter(Mandatory)][string]$BuildUrl,
    [string]$CertificateThumbprint = '6217E040E728117EABE9BCD7FA13BF6D954F4D61'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
. "$PSScriptRoot/authenticode-status.ps1"
if (Test-Path $OutputDirectory) { throw 'Output already exists; select a new directory.' }
if ((Get-Content (Join-Path $WindowsPackage 'SOURCE_COMMIT.txt')).Trim() -ne $SourceCommit) {
    throw 'Windows artifact source commit does not match.'
}
$cert = Get-Item "Cert:/CurrentUser/My/$CertificateThumbprint"
if (!$cert.HasPrivateKey -or $cert.NotAfter -le (Get-Date)) { throw 'Signing certificate unavailable or expired.' }
New-Item -ItemType Directory $OutputDirectory | Out-Null
$stage = Join-Path $OutputDirectory 'Wiesler-0.1.0-windows-x64'
Copy-Item -LiteralPath $WindowsPackage -Destination $stage -Recurse
$report = [ordered]@{version='0.1.0'; sourceCommit=$SourceCommit; buildUrl=$BuildUrl; windows=@(); android=$null}

foreach ($name in @('wiesler.exe', 'wiesler-desktop.exe')) {
    $file = Join-Path $stage $name
    & $SignTool sign /sha1 $CertificateThumbprint /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 $file
    if ($LASTEXITCODE) { throw "Signing failed: $name" }
    $signature = Get-AuthenticodeSignature $file
    if ($signature.SignerCertificate.Thumbprint -ne $CertificateThumbprint -or !$signature.TimeStamperCertificate) {
        throw "Unexpected signer or missing timestamp: $name"
    }
    # Accept only the expected self-signed root error / 仅接受预期的自签名根错误。
    $PSNativeCommandUseErrorActionPreference = $false
    $verification = & $SignTool verify /pa /v $file 2>&1 | Out-String
    $verifyExit = $LASTEXITCODE
    $PSNativeCommandUseErrorActionPreference = $true
    $trustCode = [WieslerAuthenticode]::Verify((Resolve-Path $file).Path)
    if ($trustCode -notin @([uint32]0, [uint32]2148204809) -or ($verifyExit -ne 0 -and !($verifyExit -eq 1 -and $trustCode -eq [uint32]2148204809 -and $signature.Status -in @('UnknownError', 'NotTrusted')))) {
        throw "Authenticode verification failed: $name`n$verification"
    }
    $report.windows += [ordered]@{file=$name; signer=$cert.Subject; thumbprint=$cert.Thumbprint; status="$($signature.Status)"; trustCode=('0x{0:X8}' -f $trustCode); timestampSigner=$signature.TimeStamperCertificate.Subject; verification=$verification.Trim()}
}
Export-Certificate -Cert $cert -FilePath (Join-Path $OutputDirectory 'Wiesler-windows-signing.cer') | Out-Null

$apk = Join-Path $OutputDirectory 'Wiesler-0.1.0-android.apk'
$aligned = Join-Path $OutputDirectory 'android-aligned-unsigned.apk'
& (Join-Path $AndroidBuildTools 'zipalign.exe') -p -f 4 $UnsignedApk $aligned
if ($LASTEXITCODE) { throw 'APK alignment failed.' }
try {
    $protected = (Get-Content (Join-Path $AndroidKeyDirectory 'password.dpapi') -Raw).Trim() | ConvertTo-SecureString
    $env:WIESLER_ANDROID_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', $protected).Password
    & (Join-Path $AndroidBuildTools 'apksigner.bat') sign --ks (Join-Path $AndroidKeyDirectory 'release.p12') --ks-key-alias wiesler-release --ks-pass env:WIESLER_ANDROID_STORE_PASSWORD --key-pass env:WIESLER_ANDROID_STORE_PASSWORD --out $apk $aligned
    if ($LASTEXITCODE) { throw 'APK signing failed.' }
} finally {
    $env:WIESLER_ANDROID_STORE_PASSWORD = $null
}
$verification = & (Join-Path $AndroidBuildTools 'apksigner.bat') verify --verbose --print-certs $apk | Out-String
if ($LASTEXITCODE) { throw 'APK signature verification failed.' }
& (Join-Path $AndroidBuildTools 'zipalign.exe') -c -p 4 $apk
if ($LASTEXITCODE) { throw 'Signed APK alignment verification failed.' }
$androidCertPath = Join-Path $AndroidKeyDirectory 'release.cer'
$androidCert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($androidCertPath)
$fingerprint = $androidCert.GetCertHashString([System.Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant()
if (!$verification.ToLowerInvariant().Contains($fingerprint)) { throw 'Unexpected Android signer.' }
Copy-Item $androidCertPath (Join-Path $OutputDirectory 'Wiesler-android-signing.cer')
$report.android = [ordered]@{file=(Split-Path $apk -Leaf); certificateSha256=$fingerprint; verification=$verification.Trim()}
$report | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutputDirectory 'signature-report.json') -Encoding utf8
Compress-Archive -Path "$stage/*" -DestinationPath (Join-Path $OutputDirectory 'Wiesler-0.1.0-windows-x64.zip')
Write-Output 'Signed packages and verification report ready. Upload only the ZIP, signed APK, public certificates and report.'
