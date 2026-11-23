#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Describe one APK as a small patch on top of another, and rebuild it from that patch.

    apk_delta.py make  OLD.apk NEW.apk PATCH_DIR
    apk_delta.py apply OLD.apk PATCH_DIR NEW.apk

Entries whose stored bytes are identical in both files are copied from OLD; everything else is kept
in PATCH_DIR/delta.bin. `apply` checks the SHA-256 of both the input and the result.
"""
import hashlib, json, struct, sys, zipfile
from pathlib import Path


def sha(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def payloads(path):
    """name -> (payload offset, stored length, method, crc), plus entries in file order."""
    out = {}
    with open(path, 'rb') as raw:
        for info in zipfile.ZipFile(path).infolist():
            raw.seek(info.header_offset + 26)
            n, m = struct.unpack('<HH', raw.read(4))
            out[info.filename] = (info.header_offset + 30 + n + m, info.compress_size, info.compress_type, info.CRC)
    return out


def same(a, a_at, b, b_at, length):
    a.seek(a_at)
    b.seek(b_at)
    while length:
        step = min(length, 1 << 20)
        if a.read(step) != b.read(step):
            return False
        length -= step
    return True


def make(old, new, folder):
    folder.mkdir(parents=True, exist_ok=True)
    source, target = payloads(old), payloads(new)
    ops, position, literal = [], 0, 0
    with open(old, 'rb') as a, open(new, 'rb') as b, open(folder / 'delta.bin', 'wb') as blob:
        def keep(until):
            nonlocal position, literal
            if until > position:
                b.seek(position)
                blob.write(b.read(until - position))
                if ops and ops[-1][0] == 'd':
                    ops[-1][1] += until - position
                else:
                    ops.append(['d', until - position])
                literal += until - position
                position = until
        for name, (at, length, method, crc) in sorted(target.items(), key=lambda item: item[1][0]):
            match = source.get(name)
            if match and length and match[1:] == (length, method, crc) and same(a, match[0], b, at, length):
                keep(at)
                ops.append(['c', match[0], length])
                position = at + length
        keep(Path(new).stat().st_size)
    manifest = {'old_sha256': sha(old), 'new_sha256': sha(new), 'new_size': Path(new).stat().st_size, 'ops': ops}
    (folder / 'delta.json').write_text(json.dumps(manifest, separators=(',', ':')) + '\n')
    print('patch data: %d bytes for a %d byte file' % (literal, manifest['new_size']))


def apply(old, folder, new):
    manifest = json.loads((folder / 'delta.json').read_text())
    if sha(old) != manifest['old_sha256']:
        sys.exit('the input is not the file this patch was made from')
    with open(old, 'rb') as a, open(folder / 'delta.bin', 'rb') as blob, open(new, 'wb') as out:
        for op in manifest['ops']:
            if op[0] == 'c':
                a.seek(op[1])
                left = op[2]
                while left:
                    block = a.read(min(left, 1 << 20))
                    if not block:
                        sys.exit('input ended early')
                    out.write(block)
                    left -= len(block)
            else:
                block = blob.read(op[1])
                if len(block) != op[1]:
                    sys.exit('patch data ended early')
                out.write(block)
    if Path(new).stat().st_size != manifest['new_size'] or sha(new) != manifest['new_sha256']:
        Path(new).unlink()
        sys.exit('the rebuilt file does not match the expected SHA-256')
    print('rebuilt', new, manifest['new_sha256'])


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[1] == 'make':
        make(sys.argv[2], sys.argv[3], Path(sys.argv[4]))
    elif len(sys.argv) == 5 and sys.argv[1] == 'apply':
        apply(sys.argv[2], Path(sys.argv[3]), sys.argv[4])
    else:
        sys.exit(__doc__)
