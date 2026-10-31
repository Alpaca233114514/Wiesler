$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Set-Location (Split-Path $PSScriptRoot -Parent)

cargo fmt --all -- --check
cargo fmt --manifest-path desktop/Cargo.toml --all -- --check
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
node --test desktop/tests/ui.test.cjs
cargo test --manifest-path desktop/Cargo.toml --locked
cargo build --release --locked
cargo build --manifest-path desktop/Cargo.toml --release --locked

$package = 'dist/windows-x64'
if (Test-Path $package) { throw 'Package directory already exists; use a fresh staging directory.' }
New-Item -ItemType Directory $package | Out-Null
Copy-Item target/release/wiesler.exe, desktop/target/release/wiesler-desktop.exe, LICENSE, THIRD_PARTY_NOTICES.md $package
Copy-Item docs/release-portable.md "$package/README.md"
$revision = git rev-parse HEAD
$revision.Trim() | Set-Content "$package/SOURCE_COMMIT.txt"
& "$package/wiesler.exe" --version
if ($LASTEXITCODE) { throw 'CLI smoke check failed.' }
