import json
import pathlib
import re
import tomllib

root = pathlib.Path(__file__).resolve().parents[1]
version = tomllib.loads((root / 'Cargo.toml').read_text())['package']['version']
desktop = tomllib.loads((root / 'desktop/Cargo.toml').read_text())['package']['version']
tauri = json.loads((root / 'desktop/tauri.conf.json').read_text())['version']
android = re.search(r'versionName = "([^"]+)"', (root / 'android/app/build.gradle.kts').read_text())[1]
assert version == desktop == tauri == android, (version, desktop, tauri, android)
assert re.fullmatch(r'\d+\.\d+\.\d+', version), version
print(f'All package versions: {version}')
