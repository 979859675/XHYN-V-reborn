# XHYN V 联机中心（服务器端）

给 XHYN V 局域网联机用的账号、角色、房间大厅和数据服务，带一个网页前端。

**它不是游戏服务器。** 画面同步仍由房主手机上的游戏完成；这里只负责登录注册、角色、在线房间列表、比赛成绩、在线时长和云存档。

## 当前进度

| 部分 | 状态 |
|---|---|
| 后端接口 + 网页 | 已完成，有自动化测试 |
| 游戏客户端接入（登录、角色、房间上报、在线时长） | 已做，见 `modifications/online/`，**未经真机测试** |
| 比赛成绩自动上报 | 已做（房主的客户端上报），**未经真机测试** |
| 公网 UDP 中转（不用组网软件联机） | 已做，服务器端有自动化测试，客户端**未经真机测试** |

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
| `POST /rooms/:id/heartbeat` | 房主每 10 秒调用：`{players, maxPlayers?, debug?}` → `{ok, bots, botMirror}`；90 秒没心跳房间自动消失 |
| `DELETE /rooms/:id` | 房主关闭房间 |
| `POST /races` | 房主上报比赛：`{courseName, mode: "sprint"\|"circuit", laps?, checkpoints?, results:[{name, finished, position?, timeMs?, reason?}]}` |
| `GET /races` | 最近 20 场比赛 |
| `GET /leaderboard` | 排行榜（公开） |
| `GET /me/stats` | 我的角色战绩 |
| `POST /relay/host` | 房主申请（或续期）一个中转网络 → `{relay:{networkId, token, virtualIp, hostIp, port}}` |
| `POST /relay/join` | `{networkId}` 加入房主的中转网络，返回同上 |
| `POST /relay/bot` | `{index: 1~3}` 房主为人机申请中转网络里的一个位置 |
| `POST /rooms/:id/bots` | 房主或管理员：`{count: 0~3, mirror?}` 设置测试人机数量；房主手机在下次心跳时得知 |
| `GET /admin/users` | 管理员：用户列表 |
| `POST /admin/users/:id/ban` | 管理员：`{banned: true\|false}` |

## 公网中转（UDP）

让不在同一网络的玩家不装组网软件也能联机：游戏数据不再直接发给对方，而是发到本服务器，由服务器转发。

- 默认监听 **UDP 27950**（`RELAY_PORT`，设为 `0` 关闭）。它**不经过** Nginx 反向代理，必须单独放行：
  宝塔“安全”里放行 UDP 27950，云厂商的安全组 / 防火墙里也要放行 UDP 27950。
- 每个房主一个虚拟局域网 `10.77.0.x`，房主固定是 `10.77.0.1`，最多 10 人；10 分钟没有任何数据就回收。
- 只有登录且有角色的用户能拿到中转令牌；每个成员的转发速率上限是 `RELAY_MAX_KBPS`（默认 6000 kbps）。
- 状态只在内存里：服务器重启后正在进行的房间会断开，需要重新创建。
- 所有游戏流量都会占用服务器带宽。带宽小的机器同时能撑的房间数很有限。

帧格式和转发规则见 `src/relay.js` 开头的注释。

## 安全说明

- 密码用 scrypt 加盐哈希保存；登录令牌只保存哈希，30 天过期。
- 登录和注册接口每个 IP 每分钟限 10 次。
- **比赛成绩和在线时长由游戏客户端上报，服务器无法验证真伪。** 能做的限制都做了（只有在线房间的房主能上报成绩；时长按真实经过的时间封顶），但改过的客户端仍然可以伪造。排行榜只适合娱乐。
- 房间列表会把房主的 IP 展示给所有登录用户。局域网和虚拟局域网地址问题不大；不要上报公网 IP。

## 枪战模式与密码重置（联机测试版 10 起）

枪战只在“公网联机”（中转）房间里可用。比赛进行中的状态保存在内存里，结束时写入数据库；服务器重启会丢掉正在进行的那一场。

| 接口 | 谁能调用 | 说明 |
|---|---|---|
| `POST /api/match/state {networkId, slot?, hits?}` | 房间里的人 | 取当前枪战；`hits` 是本机玩家刚打伤的成员编号 |
| `POST /api/match/create {networkId, target, minutes}` | 房主 | 创建（目标击杀数 1～200，时间上限 1～60 分钟） |
| `POST /api/match/team {networkId, team, userId?}` | 房间里的人 | 选 `red` / `blue`，`null` 为退出；每队最多 5 人；房主可带 `userId` 安排别人 |
| `POST /api/match/start {networkId}` / `end` | 房主 | 开始；提前结束（按当前比分）或取消 |
| `POST /api/match/death {networkId, debug?}` | 房间里的人 | 本机玩家阵亡：对方队伍 +1，最后打伤他的人记 1 次击杀 |
| `GET /api/matches` | 登录用户 | 最近 20 场枪战 |
| `POST /api/admin/users/:id/password {newPassword}` | 管理员 | 重置某个用户的密码，对方所有登录失效 |

战绩和赛车成绩一样由玩家的游戏上报，不防作弊。
