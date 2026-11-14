# 星战GTA5Online · 测试版 14（改名 + 换包名）

功能与 online13 相同，这一版只改名字和包名。

## 改动
- 应用名：小春GTA5Online → **星战GTA5Online**（桌面图标名、游戏内“联机中心”标题）。
- 包名：`com.xhynph.xhynv.reborn174` → **`cn.xzcqgzs.zy`**（模组桥接 Provider 的 authority 同步改为 `cn.xzcqgzs.zy.modspawn`）。
- 安装包文件名：`XingZhan-GTA5Online-0.3-Preview2-online14.apk`。
- 网页后台标题：XHYN V 联机中心 → **星战GTA5Online 联机中心**（需更新服务器的 `public/index.html`、`src/app.js`）。

## 安装须知
- 包名变了，系统把它当成**另一个应用**：不会覆盖旧版，会和旧版并存。确认新版正常后再手动卸载旧版。
- 旧版留在手机本机的设置、已导入的游戏数据**不会自动带过来**，新版首次启动要重新走一遍数据导入/授权。
- 账号、角色、战绩、排行榜都在服务器上，登录后照旧。
- 依赖旧 authority（`com.xhynph.xhynv.reborn174.modspawn`）的第三方模组工具需要改用新的 authority。

## 状态
CI 构建并签名通过；未在真机上验证。
