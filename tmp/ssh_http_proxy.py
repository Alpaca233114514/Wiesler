import socket
import sys
import threading

import msvcrt
import os

msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)
sock = socket.create_connection(("127.0.0.1", 7897), timeout=15)
sock.sendall(b"CONNECT 54.66.111.171:22 HTTP/1.1\r\nHost: 54.66.111.171:22\r\n\r\n")
response = b""
while not response.endswith(b"\r\n\r\n"):
    part = sock.recv(1)
    if not part or len(response) > 8192:
        raise SystemExit("Proxy connection failed")
    response += part
if b" 200 " not in response.split(b"\r\n", 1)[0]:
    raise SystemExit("Proxy refused CONNECT")
sock.settimeout(None)

def send_input():
    while chunk := os.read(sys.stdin.fileno(), 65536):
        sock.sendall(chunk)
    sock.shutdown(socket.SHUT_WR)

threading.Thread(target=send_input, daemon=True).start()
while chunk := sock.recv(65536):
    os.write(sys.stdout.fileno(), chunk)
