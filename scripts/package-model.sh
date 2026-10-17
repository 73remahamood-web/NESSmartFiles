#!/usr/bin/env bash
# Build a portable, integrity-checkable offline package; GGUF is NOT uploaded to GitHub source.
set -euo pipefail
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: bash scripts/package-model.sh /path/to/model.gguf" >&2; exit 2
fi
python3 - "$1" <<'PY'
import hashlib,json,os,sys,zipfile
src=sys.argv[1]
if not src.lower().endswith('.gguf'): raise SystemExit('Input must be a GGUF file')
h=hashlib.sha256()
with open(src,'rb') as f:
    while True:
        block=f.read(1024*1024)
        if not block: break
        h.update(block)
name=os.path.basename(src)
dest=f'NES-model-pack-{os.path.splitext(name)[0]}.zip'
with zipfile.ZipFile(dest,'w',allowZip64=True) as z:
    z.writestr('manifest.json', json.dumps({'format':1,'model':name,'sha256':h.hexdigest()},ensure_ascii=False))
    z.write(src,name,compress_type=zipfile.ZIP_STORED)
print(f'Created: {dest}\nSHA-256: {h.hexdigest()}')
PY
