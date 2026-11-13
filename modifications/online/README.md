# 联机中心客户端（online）

在汉化版启动器上接入 `server/` 里的联机中心。

## 做了什么

| 位置 | 改动 |
|---|---|
| 新增 `classes12.dex` | `java/com/xhynv/online/` 编译而来：登录、角色、在线房间对话框，以及后台的房间上报 |
| `LanSession.start()` | 开头调用 `Online.sessionName()`：未登录则拒绝开始联机；已登录则把昵称换成角色名 |
| `LanPage` 构造函数 | 末尾调用 `OnlineUi.attach()`：在页面顶部插入“联机中心”卡片 |

后台线程每 5 秒读取一次 `LanSession.state()`：本机是房主时向服务器登记房间并每 30 秒发心跳，
不再是房主时删除；在房间内且已进入游戏时每 60 秒上报一次在线时长。联机同步本身没有改动。

## 文件

- `java/` — 客户端代码（Java 8 语法，只用 Android SDK 自带的类）。
- `stubs/` — 编译时用来代表游戏自身类的空壳，不会打包。
- `test/SyncTest.java` — 在电脑上的 JVM 里，对着真实服务器测试 `Json`、`Api`、`RoomSync`。
- `build_online.py` — 构建脚本。
- `apk_delta.py` — 把新 APK 表示成相对汉化版 APK 的小补丁，用于发布。

## 构建

需要：JRE、apktool、apksigner、ECJ（Eclipse Java 编译器）、D8 及其依赖、`android.jar`（API 30+）。

```bash
python3 build_online.py --base-apk XHYN-V-0.3-Preview2.apk --work-dir work \
  --java java --apktool apktool.jar --apksigner apksigner.jar \
  --ecj ecj.jar --d8-classpath 'd8.jar:lib/*' --android-jar android.jar \
  --server https://你的域名 \
  --keystore release.p12 --password-file pass.txt --output out.apk
```

服务器地址在构建时写入，必须是 `https://`。

## 测试情况

- `SyncTest`：JSON、接口调用、房间上报逻辑在 JVM 上对本地服务器测试通过。
- 界面代码（`Online`、`OnlineUi`）和两处 smali 调用**没有在真机或模拟器上运行过**，只确认了能编译、能打包、调用签名对得上。
