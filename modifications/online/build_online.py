#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build the zh-CN launcher with the online lobby client ("联机中心") added.

Starts from the same base APK as ../zh-CN/build_zh.py, applies the same translation, then:
  * compiles java/ into a new classes12.dex,
  * adds two calls into the game's LAN code (see PATCHES below).
Nothing else in the APK changes.
"""
import argparse, json, re, shutil, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'zh-CN'))
import build_zh  # noqa: E402

NEW_DEX = 'classes12.dex'

# (file, text that must occur exactly once, replacement)
PATCHES = [
    # A LAN session may only start for a signed-in user, under the character's name.
    ('smali_classes10/com/gtavsource/android/lan/LanSession.smali',
     '.method private static start(Landroid/content/Context;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V\n    .locals 10\n',
     '.method private static start(Landroid/content/Context;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V\n    .locals 10\n\n'
     '    invoke-static {p0, p3}, Lcom/xhynv/online/Online;->sessionName(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;\n\n'
     '    move-result-object p3\n\n'
     '    if-nez p3, :online_signed_in\n\n'
     '    const-string p0, "\\u8bf7\\u5148\\u767b\\u5f55\\u8054\\u673a\\u4e2d\\u5fc3\\u5e76\\u9009\\u62e9\\u89d2\\u8272"\n\n'
     '    sput-object p0, Lcom/gtavsource/android/lan/LanSession;->last:Ljava/lang/String;\n\n'
     '    return-void\n\n'
     '    :online_signed_in\n'),
    # Every LAN page gets the lobby card.
    ('smali_classes10/com/gtavsource/android/LanPage.smali',
     '    :cond_7\n    invoke-direct {v0}, Lcom/gtavsource/android/LanPage;->refresh()V\n\n    return-void\n',
     '    :cond_7\n'
     '    iget-object v1, v0, Lcom/gtavsource/android/LanPage;->name:Landroid/widget/EditText;\n\n'
     '    iget-object v2, v0, Lcom/gtavsource/android/LanPage;->ip:Landroid/widget/EditText;\n\n'
     '    iget-object v3, v0, Lcom/gtavsource/android/LanPage;->code:Landroid/widget/EditText;\n\n'
     '    invoke-static {v0, v1, v2, v3}, Lcom/xhynv/online/OnlineUi;->attach(Landroid/widget/LinearLayout;Landroid/widget/EditText;Landroid/widget/EditText;Landroid/widget/EditText;)V\n\n'
     '    invoke-direct {v0}, Lcom/gtavsource/android/LanPage;->refresh()V\n\n    return-void\n'),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-apk', required=True, type=Path)
    parser.add_argument('--work-dir', required=True, type=Path, help='new directory')
    parser.add_argument('--java', default='java')
    parser.add_argument('--apktool', required=True, type=Path)
    parser.add_argument('--apksigner', required=True, type=Path)
    parser.add_argument('--ecj', required=True, type=Path, help='Eclipse Java compiler jar')
    parser.add_argument('--d8-classpath', required=True, help='class path holding com.android.tools.r8.D8 and its dependencies')
    parser.add_argument('--android-jar', required=True, type=Path, help='android.jar of API 30 or later')
    parser.add_argument('--server', help='lobby server URL to build in (default: the one in Online.java)')
    parser.add_argument('--keystore', type=Path)
    parser.add_argument('--key-alias', default='zh')
    parser.add_argument('--password-file', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    run = build_zh.run
    base = args.base_apk.resolve(strict=True)
    if build_zh.sha(base) != build_zh.BASE_SHA256:
        parser.error('base APK is not the exact XHYN V 0.3 Preview 2 release')
    work = args.work_dir.resolve()
    if work.exists():
        parser.error('work directory already exists')
    work.mkdir(parents=True)

    # 1. Java -> classes12.dex
    sources = work / 'java'
    shutil.copytree(HERE / 'java', sources)
    online = sources / 'com/xhynv/online/Online.java'
    text = online.read_text(encoding='utf-8')
    found = re.search(r'static final String SERVER = "([^"]*)";', text)
    assert found, 'SERVER constant not found'
    server = args.server or found.group(1)
    if not re.fullmatch(r'https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._/-]*)?', server):
        parser.error('--server must be an https:// URL: passwords are sent to it')
    online.write_text(text.replace(found.group(0), 'static final String SERVER = "%s";' % server), encoding='utf-8')
    classes = work / 'classes'
    run(args.java, '-jar', args.ecj, '-source', '1.8', '-target', '1.8', '-nowarn', '-encoding', 'UTF-8',
        '-bootclasspath', args.android_jar, '-d', classes, sources, HERE / 'stubs')
    shipped = sorted(str(p) for p in (classes / 'com/xhynv/online').glob('*.class'))
    assert shipped and not list((classes / 'com/xhynv/online').glob('SyncTest*')), 'unexpected classes'
    dex = work / 'dex'
    dex.mkdir()
    run(args.java, '-cp', args.d8_classpath, 'com.android.tools.r8.D8', '--release', '--min-api', '30',
        '--lib', args.android_jar, '--classpath', classes, '--output', dex, *shipped)
    new_dex = (dex / 'classes.dex').read_bytes()

    # 2. Translation + the two hooks
    dec = work / 'decoded'
    run(args.java, '-jar', args.apktool, 'd', '-r', '-o', dec, base)
    report = {'base_sha256': build_zh.BASE_SHA256, 'server': server}
    changed = set(build_zh.patch_smali(dec, report))
    for name, old, new in PATCHES:
        path = dec / name
        text = path.read_text(encoding='utf-8')
        assert text.count(old) == 1, 'hook anchor not found exactly once in ' + name
        path.write_text(text.replace(old, new), encoding='utf-8')
        changed.add(name.split('/')[0])
    dictionary = json.loads((HERE.parent / 'zh-CN/dictionary.json').read_text(encoding='utf-8'))
    payload = (json.dumps(dictionary, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    rebuilt = work / 'rebuilt.apk'
    run(args.java, '-jar', args.apktool, 'b', dec, '-o', rebuilt)
    built = zipfile.ZipFile(rebuilt)
    replace = {'assets/xhyn-english.json': payload}
    for folder in sorted(changed):
        name = 'classes.dex' if folder == 'smali' else folder.replace('smali_', '') + '.dex'
        replace[name] = built.read(name)
    report['replaced_entries'] = sorted(replace)
    report['added_entries'] = [NEW_DEX]
    unsigned = work / 'unsigned.apk'
    build_zh.repack(base, unsigned, dict(replace), {NEW_DEX: new_dex})

    # 3. Everything not deliberately replaced or added must be byte-identical to the base.
    old, new = zipfile.ZipFile(base), zipfile.ZipFile(unsigned)
    names = [x for x in old.namelist() if not re.fullmatch(r'META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA))', x)]
    assert new.namelist() == names + [NEW_DEX]
    assert new.testzip() is None
    assert new.read(NEW_DEX) == new_dex
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
    report['output_sha256'] = build_zh.sha(args.output)
    (work / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'untranslated_candidates'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
