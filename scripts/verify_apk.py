"""Audit APK contents and ELF LOAD alignment, in addition to apksigner/zipalign."""
import struct, zipfile
from pathlib import Path
apk=Path('app/build/outputs/apk/debug/app-debug.apk')
with zipfile.ZipFile(apk) as z:
    names=z.namelist()
    assert set(n.split('/')[1] for n in names if n.startswith('lib/')) == {'arm64-v8a','x86_64'}
    for n in names:
        if n.endswith('.dex'):
            data=z.read(n)
            assert b'Lcore/Core;' not in data, 'legacy JNI package present'
            assert b'ClashVpnService' not in data, 'legacy VPN service present'
        if n.endswith('.so'):
            data=z.read(n)
            assert b'github.com/metacubex/mihomo' not in data.lower()
            assert b'github.com/dreamacro/clash' not in data.lower()
            assert data[:4]==b'\x7fELF' and data[4]==2
            off=struct.unpack_from('<Q',data,32)[0]
            size,count=struct.unpack_from('<HH',data,54)
            loads=[struct.unpack_from('<IIQQQQQQ',data,off+i*size) for i in range(count)]
            assert all(p[-1]>=16384 for p in loads if p[0]==1), n
    assert 'assets/licenses/native-modules.json' in names
print('APK audit passed: single Core, both ABIs, 16 KiB ELF LOAD alignment, dependency notices')
