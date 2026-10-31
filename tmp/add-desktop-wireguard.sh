#!/bin/sh
set -eu
sudo python3 - <<'PY'
import os, pathlib, pwd, shutil, subprocess, datetime

os.umask(0o077)
conf = pathlib.Path('/etc/wireguard/wg0.conf')
profile = pathlib.Path('/home/admin/wireguard-clients/desktop.yaml')
address = '10.66.66.4'
if profile.exists():
    raise SystemExit('Desktop profile already exists; refusing to overwrite')
allowed = subprocess.check_output(['wg', 'show', 'wg0', 'allowed-ips'], text=True)
if address in allowed:
    raise SystemExit('Desktop address already in use')
private = subprocess.check_output(['wg', 'genkey'], text=True).strip()
public = subprocess.check_output(['wg', 'pubkey'], input=private+'\n', text=True).strip()
server_public = subprocess.check_output(['wg', 'show', 'wg0', 'public-key'], text=True).strip()
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = pathlib.Path('/root') / ('wireguard-before-desktop-' + stamp + '.conf')
shutil.copy2(conf, backup)
yaml = f'''mixed-port: 7890
allow-lan: false
mode: rule
ipv6: false
dns:
  enable: true
  ipv6: false
  listen: 127.0.0.1:1053
  nameserver:
    - https://1.1.1.1/dns-query#WireGuard-desktop
proxies:
  - name: WireGuard-desktop
    type: wireguard
    ip: {address}
    private-key: "{private}"
    server: 54.66.111.171
    port: 51820
    public-key: "{server_public}"
    allowed-ips: [0.0.0.0/0]
    udp: true
    mtu: 1420
proxy-groups:
  - name: Route
    type: select
    proxies: [WireGuard-desktop, DIRECT]
rules:
  - MATCH,Route
'''
with profile.open('x') as f:
    f.write(yaml)
admin = pwd.getpwnam('admin')
os.chown(profile, admin.pw_uid, admin.pw_gid)
with conf.open('a') as f:
    f.write(f'\n[Peer]\n# desktop\nPublicKey = {public}\nAllowedIPs = {address}/32\n')
subprocess.run(['wg', 'set', 'wg0', 'peer', public, 'allowed-ips', address+'/32'], check=True)
print('Desktop client created:', address)
print('Backup:', backup)
print('Profile:', profile)
PY
