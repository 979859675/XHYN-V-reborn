#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build the zh-CN launcher with the online lobby client ("联机中心") added.

Starts from the same base APK as ../zh-CN/build_zh.py, applies the same translation, then:
  * compiles java/ into a new classes12.dex,
  * adds a few calls into the game's LAN code (see PATCHES below),
  * sets the launcher name (APP_NAME) and the application id (PACKAGE),
  * turns the game text back to Simplified Chinese (chinese_engine).
Nothing else in the APK changes. The base may be the original release or this repository's zh-CN release.
"""
import argparse, hashlib, json, re, shutil, struct, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'zh-CN'))
import build_zh  # noqa: E402

NEW_DEX = 'classes12.dex'
APP_NAME = '星战GTA5Online'
OLD_NAME = 'XHYN V'
# The application id. The resource table keeps the old name, which the game's own AppResources already allows for.
PACKAGE = 'cn.xzcqgzs.zy'
# The About page shows the name and these two lines, nothing else.
SITE = 'https://zy.xzcqgzs.cn/'
QQ_GROUP = '942876882'
OLD_PACKAGE = 'com.xhynph.xhynv.reborn174'
# The released zh-CN build: the original APK with only the translation applied.
ZH_SHA256 = '8817ffed1f2e8a41fb0d7bbd373b45f71f6edbf44f03b66914a9f1075d0ebfd4'

# (file, text that must occur exactly once, replacement)
PATCHES = [
    # A LAN session may only start for a signed-in user, under the character's name. The same call
    # decides whether the session runs over the lobby server's relay.
    ('smali_classes10/com/gtavsource/android/lan/LanSession.smali',
     '.method private static start(Landroid/content/Context;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V\n    .locals 10\n',
     '.method private static start(Landroid/content/Context;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V\n    .locals 10\n\n'
     '    sget-boolean v0, Lcom/gtavsource/android/lan/LanSession;->active:Z\n\n'
     '    if-nez v0, :online_signed_in\n\n'
     '    sget-boolean v0, Lcom/gtavsource/android/lan/LanSession;->busy:Z\n\n'
     '    if-nez v0, :online_signed_in\n\n'
     '    invoke-static {p0, p3, p1, p2}, Lcom/xhynv/online/Online;->sessionName(Landroid/content/Context;Ljava/lang/String;ZLjava/lang/String;)Ljava/lang/String;\n\n'
     '    move-result-object p3\n\n'
     '    if-nez p3, :online_signed_in\n\n'
     '    sget-object p0, Lcom/xhynv/online/Online;->refusal:Ljava/lang/String;\n\n'
     '    sput-object p0, Lcom/gtavsource/android/lan/LanSession;->last:Ljava/lang/String;\n\n'
     '    return-void\n\n'
     '    :online_signed_in\n'),
    # A relayed session never uses the game's VPN ("virtual LAN") mode.
    ('smali_classes10/com/gtavsource/android/lan/LanSession.smali',
     '    invoke-static {p0}, Lcom/gtavsource/android/lan/LanSession;->virtualNetwork(Landroid/content/Context;)Z\n\n    move-result v5\n',
     '    invoke-static {p0}, Lcom/gtavsource/android/lan/LanSession;->virtualNetwork(Landroid/content/Context;)Z\n\n    move-result v5\n\n'
     '    invoke-static {v5}, Lcom/xhynv/online/Online;->virtualFlag(Z)Z\n\n    move-result v5\n'),
    # The game socket: a relay socket when the session is relayed, else the usual one.
    ('smali_classes10/com/gtavsource/android/lan/LanTransport.smali',
     '    new-instance p9, Ljava/net/DatagramSocket;\n\n    invoke-direct {p9, p8}, Ljava/net/DatagramSocket;-><init>(Ljava/net/SocketAddress;)V\n',
     '    invoke-static {p8}, Lcom/xhynv/online/Relay;->socket(Ljava/net/SocketAddress;)Ljava/net/DatagramSocket;\n\n    move-result-object p9\n'),
    # A relay socket reaches the server over any network, so it is not tied to Wi-Fi or a VPN.
    ('smali_classes10/com/gtavsource/android/lan/LanRouting.smali',
     '.method static bind(Landroid/content/Context;Ljava/net/DatagramSocket;ZLjava/net/InetAddress;)V\n    .locals 3\n',
     '.method static bind(Landroid/content/Context;Ljava/net/DatagramSocket;ZLjava/net/InetAddress;)V\n    .locals 3\n\n'
     '    invoke-static {p1}, Lcom/xhynv/online/Relay;->owns(Ljava/net/DatagramSocket;)Z\n\n'
     '    move-result v0\n\n'
     '    if-eqz v0, :online_plain_socket\n\n'
     '    return-void\n\n'
     '    :online_plain_socket\n'),
    # Mirror test bots replay the frames the local player sends.
    ('smali_classes10/com/gtavsource/android/lan/LanSession$1.smali',
     '    invoke-static {p0}, Lcom/gtavsource/android/lan/LanNative;->snapshot(I)[B\n\n    move-result-object p0\n',
     '    invoke-static {p0}, Lcom/gtavsource/android/lan/LanNative;->snapshot(I)[B\n\n    move-result-object p0\n\n'
     '    invoke-static {p0}, Lcom/gtavsource/android/lan/OnlineBridge;->observe([B)V\n'),
    # Opening the app asks a signed-out user to sign in (once, dismissable).
    ('smali/com/gtavsource/android/GameMenuActivity.smali',
     '    invoke-static {p0}, Lcom/gtavsource/android/XhynV02Settings;->resume(Lcom/gtavsource/android/GameMenuActivity;)V\n\n    return-void\n',
     '    invoke-static {p0}, Lcom/gtavsource/android/XhynV02Settings;->resume(Lcom/gtavsource/android/GameMenuActivity;)V\n\n'
     '    invoke-static {p0}, Lcom/xhynv/online/OnlineUi;->startup(Landroid/app/Activity;)V\n\n    return-void\n'),
    # Weapon and vehicle names in the trainer and room tools lists are shown in Chinese.
    ('smali/com/gtavsource/android/TrainerBridge$CatalogItem.smali',
     '    invoke-static/range {p1 .. p1}, Lcom/gtavsource/android/XhynEnglish;->catalog(Lorg/json/JSONObject;)V\n',
     '    invoke-static/range {p1 .. p1}, Lcom/gtavsource/android/XhynEnglish;->catalog(Lorg/json/JSONObject;)V\n\n'
     '    invoke-static/range {p1 .. p1}, Lcom/xhynv/online/Names;->catalog(Lorg/json/JSONObject;)V\n'),
    ('smali/com/gtavsource/android/TrainerBridge$Entity.smali',
     '    invoke-static {v1, v0}, Lcom/gtavsource/android/TrainerLabels;->prefer(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\n',
     '    invoke-static {v1, v0}, Lcom/xhynv/online/Names;->entity(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\n'),
    # Names above players' heads take the colour of their team (red and blue; enemies red in the public room).
    ('smali_classes10/com/gtavsource/android/lan/LanNameOverlay.smali',
     '    invoke-static {v1, v2, v12, v7, v3}, Lcom/gtavsource/android/XhynEnglishUi;->draw(Landroid/graphics/Canvas;Ljava/lang/String;FFLandroid/graphics/Paint;)V\n',
     '    invoke-static {v2, v3}, Lcom/xhynv/online/Deathmatch;->tint(Ljava/lang/String;Landroid/graphics/Paint;)V\n\n'
     '    invoke-static {v1, v2, v12, v7, v3}, Lcom/gtavsource/android/XhynEnglishUi;->draw(Landroid/graphics/Canvas;Ljava/lang/String;FFLandroid/graphics/Paint;)V\n'),
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


ENGINE = 'lib/arm64-v8a/libgtav.so'
ENGINE_SHA = '373bfb2f359ec68154f1099cbdaac363adf649a8ca30d1962868e1bd1cb9b3c1'
# The base's engine was switched to English by modifications/english-1.7.4/patch_native_english.py.
# These are that script's edits, the other way round: (address, bytes now, bytes of the Chinese original).
ENGINE_WORDS = [
    (0x23204d0, '00008052', '20008052'),  # Asian text formatting
    (0x23204d8, '20008052', 'a0018052'),  # language 13, Simplified Chinese
    (0x23204ec, '20008052', 'a0018052'),
    (0x2320524, '1f040071', '1f340071'),  # the language the engine accepts
]
# (address, register, where the English name sits, where the original name sits, original name)
ENGINE_NAMES = [(0x232069c, 0, 0xfceda4, 0x65cbfb, b'chinese'), (0x23206b4, 0, 0xfceda4, 0x660c40, b'chinesesimp')] + [
    (address, 3, 0xfcedc4, 0x660c40, b'chinesesimp') for address in (0x2321dcc, 0x2321e2c, 0x2321e4c, 0x2323358, 0x2323398, 0x23233d8)]
ENGINE_TEXT = 0x4000  # address minus file offset inside .text; .rodata addresses are file offsets


def engine_pair(pc, target, register):
    """The adrp + add pair loading `target` into `register` at `pc`."""
    page = (((target & ~4095) - (pc & ~4095)) >> 12) & 0x1fffff
    return struct.pack('<II', 0x90000000 | ((page & 3) << 29) | ((page >> 2) << 5) | register,
                       0x91000000 | ((target & 4095) << 10) | (register << 5) | register)


def chinese_engine(data):
    """Give the game its own Simplified Chinese text back. Every site is checked before it is touched."""
    assert hashlib.sha256(data).hexdigest() == ENGINE_SHA, 'the language edits only fit the exact engine of the base'
    out = bytearray(data)
    for address, now, original in ENGINE_WORDS:
        at = address - ENGINE_TEXT
        assert out[at:at + 4] == bytes.fromhex(now), 'engine differs at %#x' % address
        out[at:at + 4] = bytes.fromhex(original)
    for address, register, english, original, name in ENGINE_NAMES:
        at = address - ENGINE_TEXT
        assert out[at:at + 8] == engine_pair(address, english, register), 'engine differs at %#x' % address
        assert data[original:original + len(name) + 1] == name + b'\0', 'name missing at %#x' % original
        out[at:at + 8] = engine_pair(address, original, register)
    assert len(out) == len(data) and sum(x != y for x, y in zip(out, data)) <= 4 * len(ENGINE_WORDS) + 8 * len(ENGINE_NAMES)
    return bytes(out)


def about_page(text):
    """Cut the About page down to the name, the QQ group and one row opening the site."""
    start = text.index('.method public static render(Lcom/gtavsource/android/GameMenuActivity;)V')
    end = text.index('.end method', start)
    body = text[start:end]
    quote = lambda value: '"' + build_zh.escape(value) + '"'

    def swap(pattern, value, source):
        result, count = re.subn(pattern, lambda m: m[1] + quote(value), source)
        assert count == 1, 'About page differs at ' + pattern
        return result

    body = swap(r'(const-string v1, )"XHYN V 0\.3 Preview 2[^"\n]*"', APP_NAME, body)
    # The description is the string loaded right after the heading.
    body = swap(r'(MenuStyle;->heading\(.*?const-string v1, )"(?:[^"\\\n]|\\.)*"', 'QQ\u7fa4\uff1a' + QQ_GROUP, body.replace('\n', '\0')).replace('\0', '\n')
    body = swap(r'(const-string v1, )"XHYN V"', SITE.split('//')[1].strip('/'), body)
    body = swap(r'(const-string v2, )"https://t\.me/xhynv"', SITE, body)
    body = swap(r'(const-string v4, )"Telegram"', '\u5b98\u7f51', body)
    # Everything after the first row, up to the return, goes.
    first = body.index('const-string v1, "@xhyn_ph"')
    first = body.rindex('\n', 0, first) + 1
    last = body.rindex('return-void')
    last = body.rindex('\n', 0, last) + 1
    removed = body[first:last]
    assert removed.count('XhynV02Menu;->link(') == 4 and removed.count('MenuStyle;->text(') == 1 and ':' not in re.sub(r'"(?:[^"\\]|\\.)*"', '', removed), 'About page tail differs'
    return text[:start] + body[:first] + body[last:] + text[end:]


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
    parser.add_argument('--key-alias', default='app')
    parser.add_argument('--password-file', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    run = build_zh.run
    base = args.base_apk.resolve(strict=True)
    base_sha = build_zh.sha(base)
    if base_sha not in (build_zh.BASE_SHA256, ZH_SHA256):
        parser.error('base APK is neither the exact XHYN V 0.3 Preview 2 release nor its zh-CN build')
    translated = base_sha == ZH_SHA256
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
    shipped += sorted(str(p) for p in (classes / 'com/gtavsource/android/lan').glob('OnlineBridge*.class'))
    assert shipped and not list((classes / 'com/xhynv/online').glob('SyncTest*')), 'unexpected classes'
    dex = work / 'dex'
    dex.mkdir()
    run(args.java, '-cp', args.d8_classpath, 'com.android.tools.r8.D8', '--release', '--min-api', '30',
        '--lib', args.android_jar, '--classpath', classes, '--output', dex, *shipped)
    new_dex = (dex / 'classes.dex').read_bytes()

    # 2. Translation + the two hooks
    dec = work / 'decoded'
    run(args.java, '-jar', args.apktool, 'd', '-r', '-o', dec, base)
    report = {'base_sha256': base_sha, 'server': server, 'app_name': APP_NAME}
    changed = set() if translated else set(build_zh.patch_smali(dec, report))
    for name, old, new in PATCHES:
        path = dec / name
        text = path.read_text(encoding='utf-8')
        assert text.count(old) == 1, 'hook anchor not found exactly once in ' + name
        path.write_text(text.replace(old, new), encoding='utf-8')
        changed.add(name.split('/')[0])
    # The mod bridge provider is registered under the new application id; its own constant follows.
    provider = dec / 'smali_classes5/com/gtavsource/android/modbridge/ModSpawnProvider.smali'
    text = provider.read_text(encoding='utf-8')
    assert OLD_PACKAGE + '.modspawn' in text, 'provider authority not found'
    provider.write_text(text.replace(OLD_PACKAGE + '.modspawn', PACKAGE + '.modspawn'), encoding='utf-8')
    changed.add('smali_classes5')
    about = dec / 'smali_classes11/com/gtavsource/android/XhynV02Menu.smali'
    about.write_text(about_page(about.read_text(encoding='utf-8')), encoding='utf-8')
    changed.add('smali_classes11')
    dictionary = json.loads((HERE.parent / 'zh-CN/dictionary.json').read_text(encoding='utf-8'))
    payload = (json.dumps(dictionary, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if translated:
        assert zipfile.ZipFile(base).read('assets/xhyn-english.json') == payload, 'base carries a different dictionary'
    # The launcher title and its About button come from the dictionary.
    renamed = {key: value.replace(OLD_NAME, APP_NAME) for key, value in dictionary.items() if value in (OLD_NAME, '\u5173\u4e8e' + OLD_NAME)}
    assert len(renamed) >= 3, 'launcher title entries not found'
    dictionary.update(renamed)
    payload = (json.dumps(dictionary, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    rebuilt = work / 'rebuilt.apk'
    run(args.java, '-jar', args.apktool, 'b', dec, '-o', rebuilt)
    built = zipfile.ZipFile(rebuilt)
    replace = {'assets/xhyn-english.json': payload}

    # The launcher name sits in the binary manifest. Rebuild the manifest alone from its decoded
    # form with that one attribute changed; resources and everything else stay the base's own.
    named = work / 'named'
    run(args.java, '-jar', args.apktool, 'd', '-s', '-o', named, base)
    manifest = named / 'AndroidManifest.xml'
    text = manifest.read_text(encoding='utf-8')
    label = 'android:label="%s"' % OLD_NAME
    assert text.count(label) == 1, 'application label not found exactly once'
    assert text.count('package="%s"' % OLD_PACKAGE) == 1 and text.count(OLD_PACKAGE) == 2, 'application id not found as expected'
    text = text.replace(OLD_PACKAGE, PACKAGE)
    manifest.write_text(text.replace(label, 'android:label="%s"' % APP_NAME), encoding='utf-8')
    run(args.java, '-jar', args.apktool, 'b', named, '-o', work / 'named.apk')
    binary = zipfile.ZipFile(work / 'named.apk').read('AndroidManifest.xml')
    before = zipfile.ZipFile(base).read('AndroidManifest.xml')
    grew = 2 * (len(APP_NAME) - len(OLD_NAME)) + 4 * (len(PACKAGE) - len(OLD_PACKAGE))
    utf16 = lambda value: value.encode('utf-16-le')
    assert utf16(APP_NAME) in binary and utf16(PACKAGE + '\0') in binary and utf16(PACKAGE + '.modspawn') in binary and utf16(OLD_PACKAGE) not in binary, 'manifest names not as expected'
    assert abs(len(binary) - len(before) - grew) < 8, 'manifest changed by more than the names (%d bytes, expected %d)' % (len(binary) - len(before), grew)
    replace['AndroidManifest.xml'] = binary
    replace[ENGINE] = chinese_engine(zipfile.ZipFile(base).read(ENGINE))
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
