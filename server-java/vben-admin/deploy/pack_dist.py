import os
import zipfile

SRC = os.path.join(os.path.dirname(__file__), '..', 'apps', 'web-antd', 'dist')
OUT = os.path.join(os.path.dirname(__file__), 'admin-dist.zip')

with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(SRC):
        for f in files:
            full = os.path.join(root, f)
            arc = os.path.relpath(full, SRC).replace(os.sep, '/')
            z.write(full, arc)

print(f'Created {OUT}')
with zipfile.ZipFile(OUT) as z:
    names = z.namelist()
    print(f'{len(names)} entries, sample:')
    for n in names[:10]:
        print(' ', n)
