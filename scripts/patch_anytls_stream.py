#!/usr/bin/env python3
"""Patch pinned sing-anytls v0.0.13 stream-close race in a vendored copy.

The upstream Stream stores an error interface in two machine words while
another goroutine reads it. Publish the immutable value with an atomic pointer.
Fail closed if upstream sources change, instead of silently skipping the fix.
The source archive must contain the patched dependency as vendored source.
"""
from pathlib import Path
import stat
import subprocess

module_cache = Path(subprocess.check_output(["go", "env", "GOMODCACHE"], text=True).strip())
path = module_cache / "github.com/anytls/sing-anytls@v0.0.13/session/stream.go"
path.chmod(path.stat().st_mode | stat.S_IWUSR)
source = path.read_text(encoding="utf-8")

def replace_once(before: str, after: str) -> None:
    global source
    hits = source.count(before)
    if hits != 1:
        raise RuntimeError(f"unexpected sing-anytls source (expected 1 match, found {hits}): {before[:80]!r}")
    source = source.replace(before, after, 1)

replace_once('\t"sync"\n', '\t"sync"\n\t"sync/atomic"\n')
replace_once('\tdieErr  error\n', '\tdieErr  atomic.Pointer[streamCloseError]\n')
replace_once('// newStream initiates a Stream struct', '''// streamCloseError publishes an error interface atomically on concurrent shutdown.
type streamCloseError struct { err error }

func (s *Stream) closeError() error {
    if state := s.dieErr.Load(); state != nil {
        return state.err
    }
    return nil
}

// newStream initiates a Stream struct''')
replace_once('''\tif n == 0 && s.dieErr != nil {
\t\terr = s.dieErr
\t}''', '''\tif n == 0 {
\t\tif closeErr := s.closeError(); closeErr != nil {
\t\t\terr = closeErr
\t\t}
\t}''')
replace_once('''\tif s.dieErr != nil {
\t\treturn 0, s.dieErr
\t}''', '''\tif closeErr := s.closeError(); closeErr != nil {
\t\treturn 0, closeErr
\t}''')
replace_once('\t\ts.dieErr = net.ErrClosed\n', '\t\ts.dieErr.Store(&streamCloseError{err: net.ErrClosed})\n')
replace_once('\t\ts.dieErr = err\n', '\t\ts.dieErr.Store(&streamCloseError{err: err})\n')
replace_once('\t\treturn s.dieErr\n', '\t\treturn s.closeError()\n')
path.write_text(source, encoding="utf-8")
print("Patched pinned sing-anytls Stream shutdown race in Go module cache")
