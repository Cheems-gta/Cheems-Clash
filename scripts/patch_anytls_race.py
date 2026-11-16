"""Patch a known AnyTLS v0.0.13 stream close-state race in vendored sources.

Fail closed if upstream changes: never silently skip or disable race tests.
"""
from pathlib import Path

p = Path("core-source/native/quic/vendor/github.com/anytls/sing-anytls/session/stream.go")
s = p.read_text()
changes = [
    ('"sync"\n', '"sync"\n\t"sync/atomic"\n'),
    ('dieErr  error', 'dieErr  atomic.Pointer[streamCloseError]'),
    ('// Stream implements net.Conn', 'type streamCloseError struct { err error }\n\n// Stream implements net.Conn'),
    ('if n == 0 && s.dieErr != nil {\n\t\terr = s.dieErr\n\t}', 'if closed := s.dieErr.Load(); n == 0 && closed != nil {\n\t\terr = closed.err\n\t}'),
    ('if s.dieErr != nil {\n\t\treturn 0, s.dieErr\n\t}', 'if closed := s.dieErr.Load(); closed != nil {\n\t\treturn 0, closed.err\n\t}'),
    ('s.dieErr = net.ErrClosed', 's.dieErr.Store(&streamCloseError{err: net.ErrClosed})'),
    ('s.dieErr = err', 's.dieErr.Store(&streamCloseError{err: err})'),
    ('return s.dieErr\n', 'return s.dieErr.Load().err\n'),
]
for old, new in changes:
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected upstream stream.go: expected one occurrence of {old!r}, got {count}")
    s = s.replace(old, new, 1)
p.write_text(s)
print("Patched AnyTLS stream close state with atomic publication")
