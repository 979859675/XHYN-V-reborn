# XHYN V 0.3 Preview 2 · 启动器汉化（非官方）

把上游发布的 `XHYN-V-0.3-Preview2.apk` 的**启动器界面**改回/译为简体中文。游戏内语言补丁（`libgtav.so`）**没有改动**。

## 做了什么

| 部分 | 处理方式 |
|---|---|
| 原版启动器界面 | 原文本来就是中文，发布版在运行时通过 `assets/xhyn-english.json` 替换成英文。这里让 `XhynEnglish.chinese()` 恒为 `false` 并换掉词典，原始中文（含标点）原样显示。 |
| XHYN 新增页面（显示/性能/Vulkan 驱动/DXVK 调校/诊断/关于） | 441 条英文字面量直接译为中文，见 [literals.txt](literals.txt)。 |
| 既是标签又是逻辑键的文字、运行时生成的标签 | 保持代码里的英文不变，只在显示时通过词典 [dictionary.json](dictionary.json) 精确替换。 |
| 局域网默认玩家名、网络请求的 `Accept-Language` | 改回中文。 |
| 写入诊断报告/日志的技术性文字 | **保留英文**（诊断逻辑要匹配这些文字，报告也需要给上游看）。 |

APK 里只替换了 `classes2.dex`、`classes10.dex`、`classes11.dex` 和 `assets/xhyn-english.json`，其余 436 个文件（原生库、资源、受校验的美术文件、清单）与上游发布包逐字节一致，构建脚本会自动核对。

## 重新构建

需要 Java 17+、[Apktool](https://github.com/iBotPeaches/Apktool) 和 Android SDK 的 `apksigner.jar`。

```bash
python modifications/zh-CN/build_zh.py \
  --base-apk XHYN-V-0.3-Preview2.apk \
  --work-dir /tmp/zh-build \
  --apktool apktool.jar --apksigner apksigner.jar \
  --keystore release.p12 --key-alias zh --password-file pass.txt \
  --output XHYN-V-0.3-Preview2-zh-CN.apk
```

脚本只接受 SHA-256 为 `a681765d…83309f` 的上游 0.3 Preview 2 安装包。

## 注意

- 使用了新的签名，**不能覆盖安装**官方英文版，需要先卸载（包名相同：`com.xhynph.xhynv.reborn174`）。
- 没有在真机上测试过。上游自己也标注该版本未经真机验证。
- APK 不含游戏数据，需要自备正版 GTA V Legacy 文件。原作者与第三方组件的署名和许可不变。
