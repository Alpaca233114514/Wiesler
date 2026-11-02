param(
    [Parameter(Mandatory)][string]$Executable,
    [Parameter(Mandatory)][string]$SignTool,
    [string]$CertificateThumbprint = '6217E040E728117EABE9BCD7FA13BF6D954F4D61'
)
$ErrorActionPreference = 'Stop'
$cert = Get-Item "Cert:/CurrentUser/My/$CertificateThumbprint"
if (!$cert.HasPrivateKey -or $cert.NotAfter -le (Get-Date)) { throw 'Signing certificate unavailable or expired.' }
$file = (Resolve-Path -LiteralPath $Executable).Path
& $SignTool sign /sha1 $CertificateThumbprint /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 $file
if ($LASTEXITCODE) { throw 'Desktop signing failed.' }
$signature = Get-AuthenticodeSignature -LiteralPath $file
if ($signature.SignerCertificate.Thumbprint -ne $CertificateThumbprint -or !$signature.TimeStamperCertificate) {
    throw 'Unexpected signer or missing timestamp.'
}
. "$PSScriptRoot/authenticode-status.ps1"
$trustCode = [WieslerAuthenticode]::Verify($file)
# 自签名保留不受信任状态，不安装根证书。 / Preserve self-signed distrust without installing a root.
if ($trustCode -ne 0 -and !($trustCode -eq [uint32]2148204809 -and $cert.Subject -eq $cert.Issuer)) {
    throw ('Signature validation failed: 0x{0:X8}' -f $trustCode)
}
[pscustomobject]@{File=$file; Publisher=$cert.Subject; Status=$signature.Status; TrustCode=('0x{0:X8}' -f $trustCode); Timestamp=$signature.TimeStamperCertificate.Subject}
