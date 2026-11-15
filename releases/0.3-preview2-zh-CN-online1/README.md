# 0.3 Preview 2 · 汉化 + 联机中心（online1）

这里不存放完整 APK，只存放它相对汉化版 APK 的差异（`delta.bin`、`delta.json`，约 0.5 MB）。

GitHub Actions（`.github/workflows/publish-online.yml`）会把 `../0.3-preview2-zh-CN/` 里的分片拼成汉化版 APK，
套用差异，核对 SHA-256，然后发布到 Release `v0.3-preview2-zh-CN-online1`。

手动还原：

```bash
cat ../0.3-preview2-zh-CN/XHYN-V-0.3-Preview2-zh-CN.apk.part? > zh.apk
python3 ../../modifications/online/apk_delta.py apply zh.apk . XHYN-V-0.3-Preview2-zh-CN-online1.apk
```
