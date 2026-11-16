import json, pathlib, shutil, subprocess
raw = subprocess.check_output(['go', 'list', '-m', '-json', 'all'], cwd='core-source/native/quic', text=True)
decoder = json.JSONDecoder(); modules = []
while raw.strip():
    value, end = decoder.raw_decode(raw.lstrip()); modules.append(value); raw = raw.lstrip()[end:]
assert not any('metacubex/mihomo' in m['Path'].lower() or 'dreamacro/clash' in m['Path'].lower() for m in modules)
root = pathlib.Path('app/src/main/assets/licenses'); root.mkdir(parents=True, exist_ok=True)
shutil.copy2('LICENSE', root/'GPL-3.0.txt')
shutil.copy2('THIRD_PARTY_NOTICES.md', root/'THIRD_PARTY_NOTICES.md')
for module in modules:
    directory = module.get('Dir')
    if not directory: continue
    for f in pathlib.Path(directory).iterdir():
        if f.is_file() and f.name.lower().startswith(('license','copying','notice')):
            dest = root/'native'/module['Path']; dest.mkdir(parents=True, exist_ok=True); shutil.copy2(f, dest/f.name)
(root/'native-modules.json').write_text(json.dumps([{k:m.get(k) for k in ('Path','Version','Sum')} for m in modules], indent=2))
