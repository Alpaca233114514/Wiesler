from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/android-dns-fixed.yaml':
            self.send_error(404)
            return
        content = (Path.home() / '.ssh' / 'wireguard-lightsail' / 'android-dns-fixed.yaml').read_bytes()
        self.send_response(200)
        self.send_header('Content-Type', 'application/yaml')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *args):
        pass

HTTPServer(('127.0.0.1', 39185), Handler).serve_forever()
