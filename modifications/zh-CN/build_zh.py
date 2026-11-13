#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build a Simplified-Chinese launcher edition from the released XHYN V 0.3 Preview 2 APK.

The original launcher text is Chinese; the released APK turns it into English at
run time through assets/xhyn-english.json.  This script

* switches that run-time English replacement off (original Chinese shows again),
* translates the English text of the XHYN-added pages to Chinese,
* swaps only the changed dex files and the dictionary inside the released APK
  (every other entry is copied byte for byte), then aligns and signs.

The in-game language patch in libgtav.so is NOT touched (launcher only).
"""
import argparse, hashlib, json, re, shutil, struct, subprocess, sys, zlib, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASE_SHA256 = 'a681765d9aae1404c59e3570682f3078bf9062ff800abfc7c5e1972f2783309f'
PKG = 'com/gtavsource/android'
# Classes whose English literals are user-interface text. Report/log/exception
# producers stay English: diagnostics code matches their text and reports are
# meant to be shared with the upstream maintainers.
UI_CLASSES = ['XhynV02Advice', 'XhynV02Comparison', 'XhynV02Compatibility', 'XhynV02Config',
              'XhynV02DriverPackage', 'XhynV02DriverPolicy', 'XhynV02DxvkOptions', 'XhynV02Information',
              'XhynV02Menu', 'XhynV02ProbeActivity', 'XhynV02Runtime', 'XhynV02SettingHelp',
              'XhynV02Settings', 'XhynV02Reports', 'XhynV02Graphics']
LITERAL = re.compile(r'(const-string(?:/jumbo)? [vp]\d+, )"((?:[^"\\]|\\.)*)"')
ESC = {'n': '\n', 't': '\t', 'r': '\r', "'": "'", '"': '"', '\\': '\\', 'b': '\b', 'f': '\f'}


def unescape(s):
    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', lambda m: chr(int(m[1][1:], 16)) if m[1][0] == 'u' and len(m[1]) == 5 else ESC[m[1]], s)


def escape(s):
    out = []
    for ch in s:
        if ch == '\n': out.append('\\n')
        elif ch == '\t': out.append('\\t')
        elif ch == '\r': out.append('\\r')
        elif ch in '"\'\\': out.append('\\' + ch)
        elif 32 <= ord(ch) < 127: out.append(ch)
        else: out.append('\\u%04x' % ord(ch))
    return ''.join(out)


def load_table(path):
    """Lines of `English prefix => 中文`. `\\n` is a newline, `␣` an explicit edge space."""
    rows = []
    for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        if not line.strip() or line.startswith('#'):
            continue
        if ' => ' not in line:
            raise SystemExit(f'{path.name}:{number}: missing " => "')
        key, value = line.split(' => ', 1)
        rows.append((key.replace('\\n', '\n').replace('␣', ' '), value.replace('\\n', '\n').replace('␣', ' '), number))
    return rows


def edge(text):
    lead = re.match(r'\n*', text)[0]
    trail = re.search(r'\n*\Z', text)[0] if len(lead) < len(text) else ''
    return lead, trail


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(*command):
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True)
    if result.returncode:
        sys.stderr.write(result.stdout + result.stderr)
        raise SystemExit('failed: ' + ' '.join(str(x) for x in command[:4]))
    return result.stdout + result.stderr


def patch_smali(dec, report):
    changed = set()

    def save(path, text):
        if text != path.read_text(encoding='utf-8'):
            path.write_text(text, encoding='utf-8')
            changed.add(path.relative_to(dec).parts[0])

    def find(name):
        hits = list(dec.glob(f'smali*/{PKG}/{name}.smali'))
        assert len(hits) == 1, name
        return hits[0]

    # 1. Stop the run-time English replacement. With chinese() == false the
    #    helper only performs exact dictionary look-ups, accepts Chinese values
    #    and leaves every original Chinese string (and its punctuation) alone.
    p = find('XhynEnglish')
    s = p.read_text(encoding='utf-8')
    method = re.search(r'^\.method public static chinese\(Ljava/lang/String;\)Z\n.*?^\.end method', s, re.M | re.S)
    assert method
    save(p, s.replace(method[0], '.method public static chinese(Ljava/lang/String;)Z\n    .locals 0\n\n    const/4 p0, 0x0\n\n    return p0\n.end method'))

    # 2. Translate literals of the XHYN pages.
    rows = load_table(HERE / 'literals.txt')
    files = {}
    for name in UI_CLASSES:
        for path in dec.glob(f'smali*/{PKG}/{name}*.smali'):
            if path.stem == name or path.stem.startswith(name + '$'):
                files[path] = path.read_text(encoding='utf-8')
    literals = {}
    for text in files.values():
        for m in LITERAL.finditer(text):
            literals.setdefault(unescape(m[2]), 0)
    protected = set(json.loads((HERE / 'dictionary.json').read_text(encoding='utf-8')))
    protected |= {'True', 'False', 'Auto'}
    mapping = {}
    for key, value, number in rows:
        if key in literals:
            target = key
        else:
            head, _, tail = key.partition('…')
            hits = [x for x in literals if (x.lstrip('\n').startswith(head) or x.startswith(head)) and x.rstrip('\n').endswith(tail)]
            if len(hits) != 1:
                raise SystemExit(f'literals.txt:{number}: {len(hits)} matches for {key[:60]!r}')
            target = hits[0]
        if target in protected:
            raise SystemExit(f'literals.txt:{number}: {target!r} is a logic key; translate it in dictionary.json')
        if target in mapping:
            raise SystemExit(f'literals.txt:{number}: duplicate {target[:60]!r}')
        lead, trail = edge(target)
        mapping[target] = lead + value.strip('\n') + trail
        fmt = re.compile(r'%[-+ #0]*[0-9]*(?:\.[0-9]+)?[sdiufxX]')
        assert fmt.findall(target) == fmt.findall(mapping[target]), target
    count = 0
    for path, text in files.items():
        def swap(m):
            nonlocal count
            original = unescape(m[2])
            if original not in mapping:
                return m[0]
            count += 1
            return m[1] + '"' + escape(mapping[original]) + '"'
        save(path, LITERAL.sub(swap, text))
    report['translated_literals'] = len(mapping)
    report['literal_sites'] = count
    report['untranslated_candidates'] = sorted(
        x for x in literals if x not in mapping and x not in protected and re.search(r'[A-Za-z]{3,} [A-Za-z]{2,}', x))

    # 3. Route text the XHYN pages set directly through the same exact look-up,
    #    so run-time generated labels (dictionary.json) are translated as well.
    cs = 'Ljava/lang/CharSequence;'
    builder = 'Landroid/app/AlertDialog$Builder;'
    listener = 'Landroid/content/DialogInterface$OnClickListener;'
    ui = 'Lcom/gtavsource/android/XhynEnglishUi;->'
    hooks = {}
    for owner in ['Landroid/widget/TextView;', 'Landroid/widget/Button;', 'Landroid/widget/Switch;', 'Landroid/widget/CheckBox;']:
        hooks[owner + '->setText(' + cs + ')V'] = ui + 'text(Landroid/widget/TextView;' + cs + ')V'
    for owner in [builder, 'Lcom/gtavsource/android/MenuDialogBuilder;']:
        for name, hook in [('setTitle', 'title'), ('setMessage', 'message')]:
            hooks[owner + '->' + name + '(' + cs + ')' + builder] = ui + hook + '(' + builder + cs + ')' + builder
        for name in ['Positive', 'Negative', 'Neutral']:
            hooks[owner + '->set' + name + 'Button(' + cs + listener + ')' + builder] = ui + name.lower() + '(' + builder + cs + listener + ')' + builder
        hooks[owner + '->setItems([' + cs + listener + ')' + builder] = ui + 'items(' + builder + '[' + cs + listener + ')' + builder
        hooks[owner + '->setSingleChoiceItems([' + cs + 'I' + listener + ')' + builder] = ui + 'single(' + builder + '[' + cs + 'I' + listener + ')' + builder
    hooks['Landroid/widget/Toast;->makeText(Landroid/content/Context;' + cs + 'I)Landroid/widget/Toast;'] = ui + 'toast(Landroid/content/Context;' + cs + 'I)Landroid/widget/Toast;'
    # MenuDialogBuilder overrides must not be bypassed: only hook it when the
    # class does not declare the method itself.
    menu_dialog = find('MenuDialogBuilder').read_text(encoding='utf-8')
    for target in [x for x in hooks if x.startswith('Lcom/gtavsource/android/MenuDialogBuilder;')]:
        if '.method public ' + target.split('->')[1] in menu_dialog or '.method public bridge synthetic ' + target.split('->')[1] in menu_dialog:
            del hooks[target]
    hooked = 0
    for path in files:
        text = path.read_text(encoding='utf-8')
        def rewrite(m):
            nonlocal hooked
            if m[3] not in hooks:
                return m[0]
            hooked += 1
            return 'invoke-static' + (m[1] or '') + ' {' + m[2] + '}, ' + hooks[m[3]]
        save(path, re.sub(r'invoke-(?:virtual|static)(/range)? \{([^}]+)\}, (\S+)', rewrite, text))
    report['hooked_calls'] = hooked

    # 4. Undo the static English edits of the released build that affect text.
    p = find('lan/LanSession')
    s = p.read_text(encoding='utf-8')
    assert s.count('"Player"') == 2
    save(p, s.replace('"Player"', '"\\u73a9\\u5bb6"'))
    language = 0
    for base in dec.glob('smali*'):
        for sub in ['com/gtavsource/android', 'com/deepseek/quarkdownload']:
            for path in (base / sub).rglob('*.smali') if (base / sub).is_dir() else []:
                s = path.read_text(encoding='utf-8')
                if '"en-US,en;q=0.9"' in s:
                    language += s.count('"en-US,en;q=0.9"')
                    save(path, s.replace('"en-US,en;q=0.9"', '"zh-CN,zh;q=0.9,en;q=0.5"'))
    report['accept_language_sites'] = language
    return changed


def repack(base, out, replace, add=None):
    """Copy `base` entry by entry (raw bytes), replacing only `replace`; drop the old signature.

    `add` maps new entry names to data; they are appended, compressed.
    """
    source = zipfile.ZipFile(base)
    raw = open(base, 'rb')
    central = []
    add = dict(add or {})
    assert not set(add) & set(source.namelist()), 'added entries must be new'
    extras = []
    for name in sorted(add):
        info = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 1, 1, 2))
        info.compress_type = zipfile.ZIP_DEFLATED
        extras.append(info)
    replace.update(add)
    with open(out, 'wb') as target:
        for info in source.infolist() + extras:
            name = info.filename
            if re.fullmatch(r'META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))', name):
                continue
            method = info.compress_type
            if name in replace:
                data = replace.pop(name)
                crc, size = zlib.crc32(data) & 0xffffffff, len(data)
                if method == zipfile.ZIP_DEFLATED:
                    packer = zlib.compressobj(9, zlib.DEFLATED, -15)
                    payload = packer.compress(data) + packer.flush()
                else:
                    payload = data
            else:
                raw.seek(info.header_offset + 26)
                n, m = struct.unpack('<HH', raw.read(4))
                raw.seek(info.header_offset + 30 + n + m)
                payload = raw.read(info.compress_size)
                crc, size = info.CRC, info.file_size
            encoded = name.encode('utf-8')
            flags = info.flag_bits & 0x800
            offset = target.tell()
            extra = b''
            if method == zipfile.ZIP_STORED:
                boundary = 16384 if name.endswith('.so') else 4
                extra = b'\0' * (-(offset + 30 + len(encoded)) % boundary)
            date = info.date_time
            dostime = date[3] << 11 | date[4] << 5 | date[5] // 2
            dosdate = (date[0] - 1980) << 9 | date[1] << 5 | date[2]
            target.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, flags, method, dostime, dosdate, crc, len(payload), size, len(encoded), len(extra)) + encoded + extra)
            target.write(payload)
            central.append(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, flags, method, dostime, dosdate, crc, len(payload), size, len(encoded), 0, 0, 0, 0, 0, offset) + encoded)
        start = target.tell()
        target.write(b''.join(central))
        target.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central), len(central), target.tell() - start, start, 0))
    assert not replace, list(replace)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-apk', required=True, type=Path, help='XHYN-V-0.3-Preview2.apk from the upstream release')
    parser.add_argument('--work-dir', required=True, type=Path, help='new directory')
    parser.add_argument('--java', default='java')
    parser.add_argument('--apktool', required=True, type=Path)
    parser.add_argument('--apksigner', required=True, type=Path)
    parser.add_argument('--keystore', type=Path, help='PKCS#12 key store; omit for an unsigned APK')
    parser.add_argument('--key-alias', default='zh')
    parser.add_argument('--password-file', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    base = args.base_apk.resolve(strict=True)
    if sha(base) != BASE_SHA256:
        parser.error('base APK is not the exact XHYN V 0.3 Preview 2 release')
    work = args.work_dir.resolve()
    if work.exists():
        parser.error('work directory already exists')
    work.mkdir(parents=True)
    dec = work / 'decoded'
    run(args.java, '-jar', args.apktool, 'd', '-r', '-o', dec, base)
    report = {'base_sha256': BASE_SHA256}
    changed = patch_smali(dec, report)
    dictionary = json.loads((HERE / 'dictionary.json').read_text(encoding='utf-8'))
    payload = (json.dumps(dictionary, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    rebuilt = work / 'rebuilt.apk'
    run(args.java, '-jar', args.apktool, 'b', dec, '-o', rebuilt)
    built = zipfile.ZipFile(rebuilt)
    replace = {'assets/xhyn-english.json': payload}
    for folder in sorted(changed):
        name = 'classes.dex' if folder == 'smali' else folder.replace('smali_', '') + '.dex'
        replace[name] = built.read(name)
    report['replaced_entries'] = sorted(replace)
    unsigned = work / 'unsigned.apk'
    repack(base, unsigned, dict(replace))
    # Everything that was not deliberately replaced must be identical.
    old, new = zipfile.ZipFile(base), zipfile.ZipFile(unsigned)
    names = [x for x in old.namelist() if not re.fullmatch(r'META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA))', x)]
    assert names == new.namelist()
    assert new.testzip() is None
    for name in names:
        a, b = old.getinfo(name), new.getinfo(name)
        assert a.compress_type == b.compress_type, name
        if name not in replace:
            assert (a.CRC, a.file_size, a.compress_size) == (b.CRC, b.file_size, b.compress_size), name
    report['identical_entries'] = len(names) - len(replace)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.keystore:
        run(args.java, '-jar', args.apksigner, 'sign', '--ks', args.keystore, '--ks-type', 'PKCS12', '--ks-key-alias', args.key_alias,
            '--ks-pass', 'file:' + str(args.password_file), '--v4-signing-enabled', 'false', '--out', args.output, unsigned)
        report['signature'] = [x for x in run(args.java, '-jar', args.apksigner, 'verify', '--verbose', '--print-certs', args.output).splitlines()
                               if x.startswith(('Verif', 'Signer', 'V3', 'Number')) and 'WARNING' not in x]
    else:
        shutil.copyfile(unsigned, args.output)
    report['output_sha256'] = sha(args.output)
    (work / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'untranslated_candidates'}, ensure_ascii=False, indent=2))
    print('untranslated candidates:', len(report['untranslated_candidates']))


if __name__ == '__main__':
    main()
