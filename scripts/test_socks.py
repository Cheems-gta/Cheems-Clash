"""Offline, loopback-only SOCKS fixture. Does not use provider credentials."""
import socket, socketserver, struct, threading

class Handler(socketserver.BaseRequestHandler):
    def recv(self, n):
        data = b''
        while len(data) < n:
            chunk = self.request.recv(n-len(data))
            if not chunk: raise EOFError()
            data += chunk
        return data
    def handle(self):
        self.request.settimeout(20)
        try:
            ver, count = self.recv(2)
            methods = self.recv(count)
            assert ver == 5 and 0 in methods
            self.request.sendall(b'\x05\x00')
            ver, cmd, _, kind = self.recv(4)
            assert ver == 5 and cmd == 1
            if kind == 1: host = socket.inet_ntoa(self.recv(4))
            elif kind == 4: host = socket.inet_ntop(socket.AF_INET6, self.recv(16))
            elif kind == 3: host = self.recv(self.recv(1)[0]).decode()
            else: raise ValueError()
            port = struct.unpack('!H', self.recv(2))[0]
            self.request.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
            # Return marker only through SOCKS; no HTTP server listens on the target port.
            # A direct/bypassed tunnel therefore cannot make the test pass.
            if port not in (80, 8765, 8766): return
            headers = b''
            while b'\r\n\r\n' not in headers and len(headers) < 16384:
                headers += self.recv(1)
            body = b'CHEEMS_CORE_TUN_OK\\n' if port != 8766 else b'CHEEMS_CORE_LARGE_OK\\n' + b'X' * (256 * 1024)
            self.request.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: '+str(len(body)).encode()+b'\r\nConnection: close\r\n\r\n'+body)
        except (OSError, EOFError, AssertionError, ValueError): pass

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
with Server(('127.0.0.1', 10880), Handler) as server:
    server.serve_forever()
