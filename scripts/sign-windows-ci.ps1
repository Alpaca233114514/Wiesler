param(
    [Parameter(Mandatory)][string]$WindowsPackage,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$SourceCommit,
    [Parameter(Mandatory)][string]$BuildUrl,
    [string]$TimestampUrl = 'http://timestamp.digicert.com',
    [bool]$AllowSelfSigned = $true,
    [string]$SignTool = ''
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Invalid semantic version: $Version"
}
if (Test-Path -LiteralPath $OutputDirectory) {
    throw 'Output directory already exists; select a clean staging directory.'
}
$commitFile = Join-Path $WindowsPackage 'SOURCE_COMMIT.txt'
if (-not (Test-Path -LiteralPath $commitFile) -or (Get-Content -LiteralPath $commitFile -Raw).Trim() -ne $SourceCommit) {
    throw 'Windows artifact source commit does not match expected commit.'
}

if ([string]::IsNullOrWhiteSpace($env:WINDOWS_SIGN_PFX_BASE64) -or [string]::IsNullOrWhiteSpace($env:WINDOWS_SIGN_PFX_PASSWORD)) {
    throw 'Missing WINDOWS_SIGN_PFX_BASE64 or WINDOWS_SIGN_PFX_PASSWORD in environment.'
}

if ([string]::IsNullOrWhiteSpace($SignTool)) {
    $kitsRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    if (-not (Test-Path -LiteralPath $kitsRoot)) {
        throw "Windows SDK bin directory not found: $kitsRoot"
    }
    $sdkVersions = Get-ChildItem -LiteralPath $kitsRoot -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending
    foreach ($dir in $sdkVersions) {
        $candidate = Join-Path $dir.FullName 'x64\signtool.exe'
        if (Test-Path -LiteralPath $candidate) {
            $SignTool = $candidate
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($SignTool)) {
        throw "signtool.exe (x64) not found under $kitsRoot"
    }
}

. "$PSScriptRoot/authenticode-status.ps1"

$pfxBytes = $null
$securePassword = $null
$importedCerts = @()
$signingThumbprint = $null
$store = $null

try {
    # 在内存中解包 PFX 并导入临时用户证书库，避免私钥落盘或密码进入命令行参数。
    # Decode PFX in memory and import into CurrentUser\My so neither PFX nor password hits disk or CLI args.
    $pfxBytes = [Convert]::FromBase64String(($env:WINDOWS_SIGN_PFX_BASE64 -replace '\s+', ''))
    $securePassword = ConvertTo-SecureString -String $env:WINDOWS_SIGN_PFX_PASSWORD -AsPlainText -Force

    $flags = [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::UserKeySet -bor
             [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::PersistKeySet
    $collection = [System.Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
    $collection.Import($pfxBytes, $securePassword, $flags)

    $now = Get-Date
    $candidates = @($collection | Where-Object { $_.HasPrivateKey })
    if ($candidates.Count -eq 0) {
        throw 'PFX bundle does not contain a certificate with a private key.'
    }
    $codeSignOid = '1.3.6.1.5.5.7.3.3'
    $signingCert = $candidates | Where-Object {
        ($_.Extensions | Where-Object { $_ -is [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension] } |
            ForEach-Object { $_.EnhancedKeyUsages } |
            Where-Object { $_.Value -eq $codeSignOid }).Count -gt 0
    } | Select-Object -First 1
    if (-not $signingCert) {
        $signingCert = $candidates | Select-Object -First 1
    }
    if ($signingCert.NotBefore -gt $now -or $signingCert.NotAfter -le $now) {
        throw "Signing certificate is not currently valid ($($signingCert.NotBefore) .. $($signingCert.NotAfter))."
    }

    $signingThumbprint = $signingCert.Thumbprint
    $isSelfSigned = ($signingCert.Subject -eq $signingCert.Issuer)
    if ($isSelfSigned -and -not $AllowSelfSigned) {
        throw 'Self-signed certificate rejected because AllowSelfSigned is false.'
    }

    $store = [System.Security.Cryptography.X509Certificates.X509Store]::new(
        [System.Security.Cryptography.X509Certificates.StoreName]::My,
        [System.Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser
    )
    $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
    foreach ($c in $collection) {
        # 只导入签名叶证书与中间 CA，绝不修改受信任根证书库。
        # Import only signing leaf and intermediates; never install into Trusted Root store.
        if ($c.Thumbprint -eq $signingThumbprint -or $c.Subject -ne $c.Issuer) {
            $store.Add($c)
            $importedCerts += $c.Thumbprint
        }
    }
    $store.Close()

    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
    $stage = Join-Path $OutputDirectory "Wiesler-$Version-windows-x64"
    Copy-Item -LiteralPath $WindowsPackage -Destination $stage -Recurse

    $windowsEntries = @()
    foreach ($name in @('wiesler.exe', 'wiesler-desktop.exe')) {
        $file = Join-Path $stage $name
        if (-not (Test-Path -LiteralPath $file)) {
            throw "Expected binary missing from package: $name"
        }

        $signed = $false
        for ($attempt = 1; $attempt -le 2; $attempt++) {
            $PSNativeCommandUseErrorActionPreference = $false
            & $SignTool sign /sha1 $signingThumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $file
            $signExit = $LASTEXITCODE
            $PSNativeCommandUseErrorActionPreference = $true
            if ($signExit -eq 0) {
                $signed = $true
                break
            }
            if ($attempt -lt 2) {
                Write-Warning "SignTool attempt $attempt failed for $name (exit $signExit); retrying in 5s..."
                Start-Sleep -Seconds 5
            }
        }
        if (-not $signed) { throw "Signing failed: $name" }

        $signature = Get-AuthenticodeSignature -LiteralPath $file
        if (-not $signature.SignerCertificate -or $signature.SignerCertificate.Thumbprint -ne $signingThumbprint -or -not $signature.TimeStamperCertificate) {
            throw "Unexpected signer or missing RFC 3161 timestamp: $name"
        }

        # 仅接受可信链 (0x00000000) 或显式允许的自签名不受信任根 (0x800B0109)，其余错误一律阻断。
        # Accept only trusted chain (0x0) or explicitly allowed self-signed untrusted root (0x800B0109).
        $PSNativeCommandUseErrorActionPreference = $false
        $verification = & $SignTool verify /pa /v $file 2>&1 | Out-String
        $verifyExit = $LASTEXITCODE
        $PSNativeCommandUseErrorActionPreference = $true

        $trustCode = [WieslerAuthenticode]::Verify((Resolve-Path -LiteralPath $file).Path)
        $selfSignedAllowed = ($AllowSelfSigned -and $isSelfSigned -and $trustCode -eq [uint32]2148204809)
        if ($trustCode -ne [uint32]0 -and -not $selfSignedAllowed) {
            throw ("Authenticode WinVerifyTrust failed for {0}: 0x{1:X8}`n{2}" -f $name, $trustCode, $verification)
        }
        if ($verifyExit -ne 0 -and -not ($verifyExit -eq 1 -and $selfSignedAllowed -and $signature.Status -in @('UnknownError', 'NotTrusted'))) {
            throw "SignTool verify failed for $name (exit $verifyExit)`n$verification"
        }

        $windowsEntries += [ordered]@{
            file            = $name
            signer          = $signingCert.Subject
            issuer          = $signingCert.Issuer
            thumbprint      = $signingCert.Thumbprint
            selfSigned      = $isSelfSigned
            status          = "$($signature.Status)"
            trustCode       = ('0x{0:X8}' -f $trustCode)
            timestampSigner = $signature.TimeStamperCertificate.Subject
            verification    = $verification.Trim()
        }
    }

    & (Join-Path $stage 'wiesler.exe') --version
    if ($LASTEXITCODE) { throw 'Signed CLI smoke check failed.' }

    $cerPath = Join-Path $OutputDirectory 'Wiesler-windows-signing.cer'
    [System.IO.File]::WriteAllBytes(
        $cerPath,
        $signingCert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
    )

    $zipPath = Join-Path $OutputDirectory "Wiesler-$Version-windows-x64.zip"
    Compress-Archive -Path "$stage/*" -DestinationPath $zipPath -CompressionLevel Optimal

    $report = [ordered]@{
        version      = $Version
        sourceCommit = $SourceCommit
        buildUrl     = $BuildUrl
        windows      = $windowsEntries
    }
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'windows-signature-report.json') -Encoding utf8

    Remove-Item -LiteralPath $stage -Recurse -Force
    Write-Output "Signed Windows package ready: $zipPath (selfSigned=$isSelfSigned)"
} finally {
    if ($null -ne $pfxBytes) {
        [Array]::Clear($pfxBytes, 0, $pfxBytes.Length)
    }
    if ($null -ne $securePassword) {
        $securePassword.Dispose()
    }
    $env:WINDOWS_SIGN_PFX_BASE64 = $null
    $env:WINDOWS_SIGN_PFX_PASSWORD = $null
    if ($null -ne $store) {
        try { $store.Close() } catch {}
    }
    foreach ($tp in ($importedCerts | Select-Object -Unique)) {
        try {
            $certPath = "Cert:\CurrentUser\My\$tp"
            if (Test-Path -LiteralPath $certPath) {
                Remove-Item -LiteralPath $certPath -DeleteKey -Force -ErrorAction SilentlyContinue
            }
            $PSNativeCommandUseErrorActionPreference = $false
            & certutil.exe -user -delstore My $tp *> $null
            $PSNativeCommandUseErrorActionPreference = $true
        } catch {}
    }
}
