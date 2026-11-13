# XHYN V 联机中心（服务器端）

给 XHYN V 局域网联机用的账号、角色、房间大厅和数据服务，带一个网页前端。

**它不是游戏服务器。** 画面同步仍由房主手机上的游戏完成；这里只负责登录注册、角色、在线房间列表、比赛成绩、在线时长和云存档。

## 当前进度

| 部分 | 状态 |
|---|---|
| 后端接口 + 网页 | 已完成，有自动化测试 |
| 游戏客户端接入（登录、角色、房间上报、在线时长） | 已做，见 `modifications/online/`，**未经真机测试** |
| 比赛成绩自动上报 | **未做** |
| 公网 UDP 中转 | **未做** |

比赛成绩上报做完之前，“最近比赛”和排行榜的胜场不会有数据。

## 环境要求

- Node.js **22.5 或更高**（用到了内置的 SQLite）。
- 默认使用 SQLite，数据就是 `data/` 目录下的一个文件，不需要另装数据库。
- 也可以切到 MySQL（`DB_DRIVER=mysql`）。**MySQL 这条路径没有实际跑过测试**，建议先用 SQLite；几十到几百人的规模 SQLite 足够。

## 在宝塔上部署

宝塔不同版本的菜单名称可能略有出入，按意思找即可。

1. **装 Node**：软件商店 → 安装“Node.js 版本管理器” → 在里面安装 **v22**。
2. **放代码**：把本仓库的 `server` 目录上传到服务器，例如 `/www/wwwroot/xhynv-server`。
3. **配置**：在该目录里把 `.env.example` 复制为 `.env`。建议至少改一项：
   - `INVITE_CODE=你定的邀请码`：只让知道邀请码的人注册。留空则任何人都能注册。
4. **建项目**：网站 → Node 项目 → 添加 Node 项目：
   - 项目目录：`/www/wwwroot/xhynv-server`
   - 启动选项：`start`
   - 项目端口：`3080`
   - Node 版本：v22
   - 包管理器：npm（会自动执行 `npm install`）
5. **对外访问**（二选一）：
   - **有域名**：在项目设置里绑定域名并打开“外网映射”，再到 SSL 页申请证书。`.env` 保持 `HOST=127.0.0.1`、`TRUST_PROXY=1`。
   - **没有域名**：把 `.env` 里的 `HOST` 改成 `0.0.0.0`、`TRUST_PROXY` 改成 `0`，然后在宝塔“安全”页**和**云服务商的安全组里都放行 TCP 3080，用 `http://服务器IP:3080` 访问。这种方式没有 HTTPS，密码在网络上是明文传输的，只适合临时测试。
6. **第一个注册的账号自动成为管理员**，部署完请自己先注册。

改了 `.env` 之后要在宝塔里重启项目才生效。

### 备份

备份整个 `data/` 目录即可，里面是数据库文件和云存档。

### 不用宝塔，手动运行

```bash
cd server
npm install
cp .env.example .env
npm start
```

## 测试

```bash
npm test
```

## 接口

所有接口在 `/api` 下，请求和响应都是 JSON。登录后在请求头带 `Authorization: Bearer <token>`。出错时返回 `{"error":{"code":"...","message":"中文说明"}}`。

| 方法和路径 | 说明 |
|---|---|
| `GET /info` | 服务信息：是否需要邀请码、角色数上限 |
| `POST /auth/register` | `{username, password, inviteCode?}` → `{token, user}` |
| `POST /auth/login` | `{username, password}` → `{token, user}` |
| `POST /auth/logout` | 使当前令牌失效 |
| `POST /auth/password` | `{currentPassword, newPassword}`，其他设备会被登出 |
| `GET /me` | 当前用户 |
| `GET /characters` | 我的角色和当前使用的角色 |
| `POST /characters` | `{name, model?}` 创建角色，角色名全服唯一 |
| `PATCH /characters/:id` | `{model}` 修改人物模型 |
| `POST /characters/:id/select` | 设为当前角色 |
| `DELETE /characters/:id` | 删除角色及其云存档 |
| `PUT /characters/:id/save` | 上传云存档，`Content-Type: application/octet-stream` |
| `GET /characters/:id/save` | 下载云存档 |
| `POST /play/heartbeat` | 在房间内约每分钟调用一次，累计在线时长 |
| `GET /rooms` | 在线房间列表（含房主 IP 和房间码，需登录） |
| `POST /rooms` | 房主上报：`{hostAddress, roomCode, title?, virtualLan?, maxPlayers?, gameVersion?}` |
| `POST /rooms/:id/heartbeat` | 房主每 30 秒调用：`{players, maxPlayers?}`；90 秒没心跳房间自动消失 |
| `DELETE /rooms/:id` | 房主关闭房间 |
| `POST /races` | 房主上报比赛：`{courseName, mode: "sprint"\|"circuit", laps?, checkpoints?, results:[{name, finished, position?, timeMs?, reason?}]}` |
| `GET /races` | 最近 20 场比赛 |
| `GET /leaderboard` | 排行榜（公开） |
| `GET /me/stats` | 我的角色战绩 |
| `GET /admin/users` | 管理员：用户列表 |
| `POST /admin/users/:id/ban` | 管理员：`{banned: true\|false}` |

## 安全说明

- 密码用 scrypt 加盐哈希保存；登录令牌只保存哈希，30 天过期。
- 登录和注册接口每个 IP 每分钟限 10 次。
- **比赛成绩和在线时长由游戏客户端上报，服务器无法验证真伪。** 能做的限制都做了（只有在线房间的房主能上报成绩；时长按真实经过的时间封顶），但改过的客户端仍然可以伪造。排行榜只适合娱乐。
- 房间列表会把房主的 IP 展示给所有登录用户。局域网和虚拟局域网地址问题不大；不要上报公网 IP。
