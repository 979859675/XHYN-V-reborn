# XHYN V 0.3 Preview 2 汉化版安装包

GitHub 单个文件不能超过 100 MB，所以 APK 被切成三段保存在这里。`Publish zh-CN release` 工作流会把它们合并并发布到 Release。

手动合并：

```bash
cat XHYN-V-0.3-Preview2-zh-CN.apk.part0 XHYN-V-0.3-Preview2-zh-CN.apk.part1 XHYN-V-0.3-Preview2-zh-CN.apk.part2 > XHYN-V-0.3-Preview2-zh-CN.apk
sha256sum -c SHA256SUMS.txt
```

Windows（cmd）：

```bat
copy /b XHYN-V-0.3-Preview2-zh-CN.apk.part0+XHYN-V-0.3-Preview2-zh-CN.apk.part1+XHYN-V-0.3-Preview2-zh-CN.apk.part2 XHYN-V-0.3-Preview2-zh-CN.apk
```

说明见 [RELEASE-NOTES.md](RELEASE-NOTES.md)。
