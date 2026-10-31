#!/bin/sh
set -eu
sudo python3 - <<'PY'
import datetime, os, pathlib, pwd, subprocess

def run(*args, data=None):
    return subprocess.check_output(args, input=data, text=True).strip()

os.umask(0o077)
root = pathlib.Path('/etc/wireguard')
client_dir = pathlib.Path('/home/admin/wireguard-clients')
sysctl = pathlib.Path('/etc/sysctl.d/90-wireguard-forward.conf')
dropin = pathlib.Path('/etc/systemd/system/wg-quick@wg0.service.d')
for p in (root / 'wg0.conf', client_dir, sysctl, dropin):
    if p.exists():
        raise SystemExit('Refusing to overwrite existing path: ' + str(p))
route = run('ip', '-4', 'route', 'get', '1.1.1.1').split()
interface = route[route.index('dev') + 1]
if not interface.replace('-', '').replace('_', '').isalnum():
    raise SystemExit('Invalid network interface')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup = pathlib.Path('/root') / ('wireguard-preinstall-' + stamp)
backup.mkdir(mode=0o700)
(backup / 'iptables.rules').write_text(run('iptables-save') + '\n')
(backup / 'ip_forward').write_text(pathlib.Path('/proc/sys/net/ipv4/ip_forward').read_text())
(backup / 'route.txt').write_text(run('ip', '-4', 'route') + '\n')
root.mkdir(mode=0o700, exist_ok=True)
client_dir.mkdir(mode=0o700)
admin = pwd.getpwnam('admin')
os.chown(client_dir, admin.pw_uid, admin.pw_gid)

def keys():
    private = run('wg', 'genkey')
    public = run('wg', 'pubkey', data=private + '\n')
    return private, public

server_private, server_public = keys()
clients = [(name, address, *keys()) for name, address in [('windows', '10.66.66.2'), ('android', '10.66.66.3')]]
config = f'''[Interface]
Address = 10.66.66.1/24
ListenPort = 51820
PrivateKey = {server_private}
PostUp = iptables -w -A FORWARD -i %i -o {interface} -s 10.66.66.0/24 -j ACCEPT; iptables -w -A FORWARD -i {interface} -o %i -d 10.66.66.0/24 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; iptables -w -t nat -A POSTROUTING -s 10.66.66.0/24 -o {interface} -j MASQUERADE
PostDown = iptables -w -D FORWARD -i %i -o {interface} -s 10.66.66.0/24 -j ACCEPT; iptables -w -D FORWARD -i {interface} -o %i -d 10.66.66.0/24 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; iptables -w -t nat -D POSTROUTING -s 10.66.66.0/24 -o {interface} -j MASQUERADE
'''
for name, address, private, public in clients:
    config += f'\n[Peer]\n# {name}\nPublicKey = {public}\nAllowedIPs = {address}/32\n'
    conf = f'''[Interface]
PrivateKey = {private}
Address = {address}/32
DNS = 1.1.1.1
MTU = 1420

[Peer]
PublicKey = {server_public}
AllowedIPs = 0.0.0.0/0
Endpoint = 54.66.111.171:51820
PersistentKeepalive = 25
'''
    yaml = f'''mixed-port: 7890
allow-lan: false
mode: rule
ipv6: false
dns:
  enable: true
  ipv6: false
  listen: 127.0.0.1:1053
  nameserver:
    - https://1.1.1.1/dns-query
proxies:
  - name: WireGuard-{name}
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
    proxies: [WireGuard-{name}, DIRECT]
rules:
  - MATCH,Route
'''
    for suffix, contents in (('.conf', conf), ('.yaml', yaml)):
        p = client_dir / (name + suffix)
        p.write_text(contents)
        os.chown(p, admin.pw_uid, admin.pw_gid)
(root / 'wg0.conf').write_text(config)
sysctl.write_text('net.ipv4.ip_forward = 1\n')
os.chmod(sysctl, 0o644)
dropin.mkdir(parents=True, mode=0o755)
override = dropin / 'recovery.conf'
override.write_text('[Service]\nRestart=on-failure\nRestartSec=5s\n')
os.chmod(override, 0o644)
print('Backup:', backup)
print('Client files:', client_dir)
print('Detected outbound interface:', interface)
PY
sudo sysctl -p /etc/sysctl.d/90-wireguard-forward.conf
sudo systemctl daemon-reload
sudo systemctl enable --now wg-quick@wg0
sudo systemctl is-active wg-quick@wg0
sudo wg show wg0 listen-port
sudo wg show wg0 latest-handshakes
sudo iptables -S FORWARD
sudo iptables -t nat -S POSTROUTING
sudo ss -uln | grep ':51820 '
