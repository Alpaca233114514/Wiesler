param(
    [Parameter(Mandatory)][string]$KeyDirectory,
    [Parameter(Mandatory)][string]$KeyTool
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if (Test-Path $KeyDirectory) { throw 'Key directory already exists; never overwrite a release identity.' }
New-Item -ItemType Directory $KeyDirectory | Out-Null
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$acl = [System.Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $KeyDirectory -AclObject $acl
try {
    $env:WIESLER_ANDROID_STORE_PASSWORD = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $protected = ConvertTo-SecureString $env:WIESLER_ANDROID_STORE_PASSWORD -AsPlainText -Force
    $protected | ConvertFrom-SecureString | Set-Content (Join-Path $KeyDirectory 'password.dpapi')
    & $KeyTool -genkeypair -keystore (Join-Path $KeyDirectory 'release.p12') -storetype PKCS12 -alias wiesler-release -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Wiesler Release' -storepass:env WIESLER_ANDROID_STORE_PASSWORD -keypass:env WIESLER_ANDROID_STORE_PASSWORD
    if ($LASTEXITCODE) { throw 'Release key generation failed.' }
    & $KeyTool -exportcert -keystore (Join-Path $KeyDirectory 'release.p12') -alias wiesler-release -storepass:env WIESLER_ANDROID_STORE_PASSWORD -file (Join-Path $KeyDirectory 'release.cer')
    if ($LASTEXITCODE) { throw 'Public certificate export failed.' }
} finally {
    $env:WIESLER_ANDROID_STORE_PASSWORD = $null
}
Write-Output 'Release key saved with user-only ACL and DPAPI-protected password. Keep this directory and Windows user profile backed up.'
