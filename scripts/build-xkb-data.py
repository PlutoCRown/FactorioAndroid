#!/usr/bin/env python3
"""Repack public xkeyboard-config DEB data; never run package scripts."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def data_tar(package):
    blob = package.read_bytes()
    if blob[:8] != b'!<arch>\n':
        raise ValueError('Expected a DEB/ar file')
    offset = 8
    while offset + 60 <= len(blob):
        header = blob[offset:offset + 60]
        if header[58:60] != b'`\n':
            raise ValueError('Invalid ar member')
        size = int(header[48:58]); name = header[:16].decode('ascii').strip().rstrip('/')
        offset += 60
        member = blob[offset:offset + size]
        if len(member) != size:
            raise ValueError('Truncated DEB')
        if name.startswith('data.tar.'):
            return member
        offset += size + size % 2
    raise ValueError('Missing data archive')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'app/src/main/assets/window-xkb.zip')
    parser.add_argument('--update-manifests', action='store_true')
    args = parser.parse_args()
    rows, aliases = {}, {}
    with tarfile.open(fileobj=io.BytesIO(data_tar(args.package)), mode='r:*') as archive:
        for entry in archive:
            marker = next((m for m in ('/share/xkeyboard-config-2/', '/share/X11/xkb/') if m in entry.name), None)
            if marker is None or entry.isdir():
                continue
            name = entry.name.split(marker, 1)[1]
            path = PurePosixPath(name)
            if path.is_absolute() or any(x in ('', '.', '..') for x in name.split('/')):
                raise ValueError('Unsafe XKB path')
            if entry.issym():
                if name not in ('rules/xorg', 'rules/xorg.lst', 'rules/xorg.xml') or entry.linkname not in ('base', 'base.lst', 'base.xml'):
                    raise ValueError('Unexpected XKB link')
                aliases[name] = str(path.parent / entry.linkname)
            elif entry.isfile():
                if entry.size > 8 * 1024 * 1024:
                    raise ValueError('XKB member too large')
                content = archive.extractfile(entry).read()
                if name in rows and rows[name] != content:
                    raise ValueError('Conflicting XKB member')
                rows[name] = content
            else:
                raise ValueError('Unexpected XKB member type')
    for name, source in aliases.items():
        rows[name] = rows[source]
    if not rows:
        raise ValueError('No XKB data in package')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w') as archive:
        for name, content in sorted(rows.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, content, compresslevel=6)
    if args.update_manifests:
        expected = ROOT / 'app/src/main/assets/window-xkb.zip'
        if args.output.resolve() != expected.resolve():
            raise ValueError('Only update manifests for the normal asset output')
        digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
        manifest = ROOT / 'app/src/main/assets/window-runtime.json'
        value = json.loads(manifest.read_text())
        value.update(xkbArchiveSha256=digest, xkbFiles=len(rows), xkbUnpackedBytes=sum(map(len, rows.values())))
        manifest.write_text(json.dumps(value, indent=2) + '\n')
        inventory = ROOT / 'scripts/runtime-files.tsv'
        lines = [line for line in inventory.read_text().splitlines() if not line.endswith('\tapp/src/main/assets/window-xkb.zip')]
        lines.append(f'{digest}\t{args.output.stat().st_size}\tapp/src/main/assets/window-xkb.zip')
        inventory.write_text('\n'.join(lines) + '\n')
    print(f'Packed {len(rows)} files, {sum(map(len, rows.values()))} unpacked bytes: {args.output}')

if __name__ == '__main__':
    main()
