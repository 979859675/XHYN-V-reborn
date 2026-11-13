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


## online2 新增

- `Relay` / `RelaySocket`：公网中转。游戏创建 UDP 套接字的地方改为调用 `Relay.socket(...)`；会话走中转时得到一个
  `DatagramSocket` 子类，它把游戏发往虚拟地址（`10.77.0.x`）的包封装后发给服务器，并把收到的包还原成“来自对方虚拟地址”的样子。
  游戏自己的协议、房间码校验都不变。帧格式见 `server/src/relay.js`。
- `com.gtavsource.android.lan.OnlineBridge`：读取比赛结算（比赛类是包内可见的，所以这个类放在游戏的包里）。房主每 5 秒检查一次，
  结算界面出现时上报一次 `POST /races`。
- 应用名：`build_online.py` 只重建 `AndroidManifest.xml` 并替换 `android:label`。
- `build_online.py` 的 `--base-apk` 现在也接受本仓库发布的汉化版 APK。

`test/SyncTest.java` 对着本地启动的服务器测试房间同步、成绩上报和中转套接字（不需要 Android）。


## online3 新增

- 启动登录：`GameMenuActivity.onResume` 末尾调用 `OnlineUi.startup(activity)`，每次启动 app 只处理一次。
- 测试人机：人机不是服务器模拟的，而是**房主手机上再开一个游戏自带的 `LanTransport`**（`OnlineBridge.startBot`），
  它拿一个独立的中转位置（`POST /relay/bot`），经服务器加入自己的房间，所以走的是和真实远程玩家完全相同的路径。
  服务器只记录“要几个人机”，房主在心跳响应里得知。镜像人机把房主自己发出的状态帧（`LanSession$1.snapshot` 处截取）
  改掉位置、去掉世界 / 车辆 / 伤害部分后发回去；改出来的帧先过游戏自己的 `LanFrame.valid`，不合法就不发。
- 诊断：`OnlineBridge.diagnostics()` 把 `LanSession.State` 的状态文字和原生层 `stats` 数组随心跳上报。
