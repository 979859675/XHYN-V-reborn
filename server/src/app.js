// SPDX-License-Identifier: MIT
'use strict';
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { promisify } = require('node:util');
const express = require('express');

const scrypt = promisify(crypto.scrypt);
const now = () => Math.floor(Date.now() / 1000);
const sha256 = (value) => crypto.createHash('sha256').update(value).digest('hex');
const key = (text) => text.normalize('NFKC').toLowerCase();
const { createRelay, HOST_IP } = require('./relay');

class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const bad = (message, code = 'invalid') => new ApiError(400, code, message);
const isDuplicate = (error) => error && (error.code === 'ER_DUP_ENTRY' || /UNIQUE constraint failed/.test(String(error.message)));

async function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = await scrypt(password, salt, 64, { N: 16384, r: 8, p: 1 });
  return `scrypt$16384$${salt.toString('base64')}$${hash.toString('base64')}`;
}
async function verifyPassword(password, stored) {
  const [scheme, cost, salt, hash] = String(stored).split('$');
  if (scheme !== 'scrypt') return false;
  const expected = Buffer.from(hash, 'base64');
  const actual = await scrypt(password, Buffer.from(salt, 'base64'), expected.length, { N: Number(cost), r: 8, p: 1 });
  return crypto.timingSafeEqual(actual, expected);
}

// ---- input validation -------------------------------------------------------
function text(value, label, min, max, pattern) {
  if (typeof value !== 'string') throw bad(`${label}不能为空`);
  const trimmed = value.trim();
  if (trimmed.length < min || trimmed.length > max) throw bad(`${label}长度需为 ${min}～${max} 个字符`);
  if (pattern && !pattern.test(trimmed)) throw bad(`${label}包含不支持的字符`);
  return trimmed;
}
function whole(value, label, min, max) {
  if (!Number.isInteger(value) || value < min || value > max) throw bad(`${label}需为 ${min}～${max} 的整数`);
  return value;
}
const USERNAME = /^[\p{L}\p{N}_]+$/u;
const CHARACTER = /^[\p{L}\p{N}_\-]+$/u;
const MODEL = /^(0x[0-9a-fA-F]{1,8}|[A-Za-z0-9_]{1,48})$/;
const TITLE = /^[^\p{C}]+$/u;
const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;

function rateLimiter(limit, windowSeconds) {
  const hits = new Map();
  return (req, _res, next) => {
    const stamp = now();
    if (hits.size > 10000) for (const [ip, entry] of hits) if (entry.reset <= stamp) hits.delete(ip);
    let entry = hits.get(req.ip);
    if (!entry || entry.reset <= stamp) { entry = { count: 0, reset: stamp + windowSeconds }; hits.set(req.ip, entry); }
    if (++entry.count > limit) return next(new ApiError(429, 'rate_limited', '操作太频繁，请稍后再试'));
    next();
  };
}

function createApp(config, db, relay = createRelay(config)) {
  const app = express();
  app.disable('x-powered-by');
  if (config.trustProxy) app.set('trust proxy', 1);
  const savesDir = path.join(config.dataDir, 'saves');
  fs.mkdirSync(savesDir, { recursive: true });

  app.use((_req, res, next) => {
    res.set({ 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' });
    // Refuse to be embedded in other sites (clickjacking) unless explicitly allowed.
    if (config.allowEmbedding) res.set('Content-Security-Policy', "default-src 'self'");
    else res.set({ 'X-Frame-Options': 'DENY', 'Content-Security-Policy': "default-src 'self'; frame-ancestors 'none'" });
    next();
  });
  app.use(express.static(path.join(__dirname, '..', 'public'), { maxAge: '5m' }));

  const api = express.Router();
  app.use('/api', api);
  api.use((_req, res, next) => { res.set('Cache-Control', 'no-store'); next(); });
  api.use(rateLimiter(config.apiRateLimit || 300, 60));
  api.use(express.json({ limit: '64kb' }));
  const authLimit = rateLimiter(config.authRateLimit || 10, 60);
  const route = (handler) => (req, res, next) => handler(req, res).catch(next);

  async function authenticate(req) {
    const match = /^Bearer ([A-Za-z0-9_-]{20,100})$/.exec(req.get('authorization') || '');
    if (!match) throw new ApiError(401, 'unauthorized', '请先登录');
    const user = await db.get(
      'SELECT u.*, t.id AS token_id FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ? AND t.expires_at > ?',
      [sha256(match[1]), now()]);
    if (!user) throw new ApiError(401, 'unauthorized', '登录已失效，请重新登录');
    if (user.banned) throw new ApiError(403, 'banned', '账号已被封禁');
    return user;
  }
  const authed = (handler) => route(async (req, res) => { req.user = req.user || await authenticate(req); return handler(req, res); });
  // For routes with large bodies: reject anonymous callers before reading the body.
  const requireAuth = (req, _res, next) => authenticate(req).then((user) => { req.user = user; next(); }, next);
  const admin = (handler) => authed(async (req, res) => {
    if (!req.user.is_admin) throw new ApiError(403, 'forbidden', '需要管理员权限');
    return handler(req, res);
  });

  async function issueToken(userId) {
    const token = crypto.randomBytes(32).toString('base64url');
    const stamp = now();
    await db.run('DELETE FROM tokens WHERE expires_at <= ?', [stamp]);
    await db.run('INSERT INTO tokens (user_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)',
      [userId, sha256(token), stamp, stamp + config.tokenDays * 86400]);
    return token;
  }
  const publicUser = (user) => ({ id: Number(user.id), username: user.username, isAdmin: !!user.is_admin,
    activeCharacterId: user.active_character_id == null ? null : Number(user.active_character_id) });
  const publicCharacter = (row) => ({ id: Number(row.id), name: row.name, model: row.model, playSeconds: Number(row.play_seconds),
    createdAt: Number(row.created_at), save: row.save_size == null ? null : { size: Number(row.save_size), updatedAt: Number(row.save_updated_at) } });

  // ---- accounts -------------------------------------------------------------
  api.get('/info', route(async (_req, res) => {
    res.json({ name: 'XHYN V Online', version: '0.2.0', inviteRequired: !!config.inviteCode, maxCharacters: config.maxCharacters,
      relayPort: relay.port, relayStats: { ...relay.stats, networks: relay.networks } });
  }));

  api.post('/auth/register', authLimit, route(async (req, res) => {
    const username = text(req.body.username, '用户名', 3, 20, USERNAME);
    const password = text(req.body.password, '密码', 8, 72);
    if (config.inviteCode) {
      const given = Buffer.from(String(req.body.inviteCode || ''));
      const expected = Buffer.from(config.inviteCode);
      if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) throw new ApiError(403, 'invite', '邀请码不正确');
    }
    const passwordHash = await hashPassword(password);
    let id;
    try {
      id = await db.tx(async (tx) => {
        const first = !(await tx.get('SELECT id FROM users LIMIT 1'));
        const result = await tx.run('INSERT INTO users (username, username_key, password_hash, is_admin, created_at, last_login_at) VALUES (?, ?, ?, ?, ?, ?)',
          [username, key(username), passwordHash, first ? 1 : 0, now(), now()]);
        return result.insertId;
      });
    } catch (error) {
      if (isDuplicate(error)) throw new ApiError(409, 'username_taken', '用户名已被使用');
      throw error;
    }
    const user = await db.get('SELECT * FROM users WHERE id = ?', [id]);
    res.status(201).json({ token: await issueToken(id), user: publicUser(user) });
  }));

  api.post('/auth/login', authLimit, route(async (req, res) => {
    const username = text(req.body.username, '用户名', 1, 20);
    const password = text(req.body.password, '密码', 1, 72);
    const user = await db.get('SELECT * FROM users WHERE username_key = ?', [key(username)]);
    // Hash even for unknown users so response time does not reveal which names exist.
    const ok = user ? await verifyPassword(password, user.password_hash) : (await hashPassword(password), false);
    if (!ok) throw new ApiError(401, 'bad_credentials', '用户名或密码不正确');
    if (user.banned) throw new ApiError(403, 'banned', '账号已被封禁');
    await db.run('UPDATE users SET last_login_at = ? WHERE id = ?', [now(), user.id]);
    res.json({ token: await issueToken(user.id), user: publicUser(user) });
  }));

  api.post('/auth/logout', authed(async (req, res) => {
    await db.run('DELETE FROM tokens WHERE id = ?', [req.user.token_id]);
    res.json({ ok: true });
  }));

  api.post('/auth/password', authLimit, authed(async (req, res) => {
    const current = text(req.body.currentPassword, '当前密码', 1, 72);
    const next = text(req.body.newPassword, '新密码', 8, 72);
    if (!(await verifyPassword(current, req.user.password_hash))) throw new ApiError(401, 'bad_credentials', '当前密码不正确');
    await db.run('UPDATE users SET password_hash = ? WHERE id = ?', [await hashPassword(next), req.user.id]);
    await db.run('DELETE FROM tokens WHERE user_id = ? AND id <> ?', [req.user.id, req.user.token_id]);
    res.json({ ok: true });
  }));

  api.get('/me', authed(async (req, res) => res.json({ user: publicUser(req.user) })));

  // ---- characters -----------------------------------------------------------
  const listCharacters = (userId) => db.all(
    'SELECT c.*, s.size AS save_size, s.updated_at AS save_updated_at FROM characters c LEFT JOIN saves s ON s.character_id = c.id WHERE c.user_id = ? ORDER BY c.id',
    [userId]);
  async function ownCharacter(req) {
    const id = Number(req.params.id);
    const row = Number.isInteger(id) ? await db.get('SELECT * FROM characters WHERE id = ? AND user_id = ?', [id, req.user.id]) : null;
    if (!row) throw new ApiError(404, 'not_found', '角色不存在');
    return row;
  }

  api.get('/characters', authed(async (req, res) => {
    res.json({ characters: (await listCharacters(req.user.id)).map(publicCharacter), activeCharacterId: publicUser(req.user).activeCharacterId });
  }));

  api.post('/characters', authed(async (req, res) => {
    const name = text(req.body.name, '角色名', 2, 16, CHARACTER);
    const model = text(req.body.model || 'player_zero', '人物模型', 1, 48, MODEL);
    let id;
    try {
      id = await db.tx(async (tx) => {
        const owned = await tx.all('SELECT id FROM characters WHERE user_id = ?', [req.user.id]);
        if (owned.length >= config.maxCharacters) throw bad(`每个账号最多 ${config.maxCharacters} 个角色`, 'character_limit');
        const result = await tx.run('INSERT INTO characters (user_id, name, name_key, model, created_at) VALUES (?, ?, ?, ?, ?)',
          [req.user.id, name, key(name), model, now()]);
        if (owned.length === 0) await tx.run('UPDATE users SET active_character_id = ? WHERE id = ?', [result.insertId, req.user.id]);
        return result.insertId;
      });
    } catch (error) {
      if (isDuplicate(error)) throw new ApiError(409, 'name_taken', '角色名已被使用');
      throw error;
    }
    const rows = await listCharacters(req.user.id);
    res.status(201).json({ character: publicCharacter(rows.find((row) => Number(row.id) === id)) });
  }));

  api.patch('/characters/:id', authed(async (req, res) => {
    const row = await ownCharacter(req);
    const model = text(req.body.model, '人物模型', 1, 48, MODEL);
    await db.run('UPDATE characters SET model = ? WHERE id = ?', [model, row.id]);
    res.json({ ok: true });
  }));

  api.post('/characters/:id/select', authed(async (req, res) => {
    const row = await ownCharacter(req);
    await db.run('UPDATE users SET active_character_id = ? WHERE id = ?', [row.id, req.user.id]);
    res.json({ ok: true, activeCharacterId: Number(row.id) });
  }));

  api.delete('/characters/:id', authed(async (req, res) => {
    const row = await ownCharacter(req);
    await db.tx(async (tx) => {
      await tx.run('DELETE FROM characters WHERE id = ?', [row.id]);
      await tx.run('DELETE FROM saves WHERE character_id = ?', [row.id]);
      // Past race rows keep the name but no longer point at a character.
      await tx.run('UPDATE race_results SET character_id = NULL WHERE character_id = ?', [row.id]);
      const next = await tx.get('SELECT id FROM characters WHERE user_id = ? ORDER BY id LIMIT 1', [req.user.id]);
      if (Number(req.user.active_character_id) === Number(row.id)) {
        await tx.run('UPDATE users SET active_character_id = ? WHERE id = ?', [next ? next.id : null, req.user.id]);
      }
    });
    fs.rmSync(path.join(savesDir, `${Number(row.id)}.bin`), { force: true });
    res.json({ ok: true });
  }));

  // ---- cloud save (one opaque file per character) ---------------------------
  api.put('/characters/:id/save', requireAuth, express.raw({ type: 'application/octet-stream', limit: config.maxSaveBytes }), authed(async (req, res) => {
    const row = await ownCharacter(req);
    if (!Buffer.isBuffer(req.body) || req.body.length === 0) throw bad('存档内容为空，需以 application/octet-stream 上传');
    const file = path.join(savesDir, `${Number(row.id)}.bin`);
    fs.writeFileSync(file + '.tmp', req.body);
    fs.renameSync(file + '.tmp', file);
    const digest = sha256(req.body);
    await db.tx(async (tx) => {
      await tx.run('DELETE FROM saves WHERE character_id = ?', [row.id]);
      await tx.run('INSERT INTO saves (character_id, size, sha256, updated_at) VALUES (?, ?, ?, ?)', [row.id, req.body.length, digest, now()]);
    });
    res.json({ ok: true, size: req.body.length, sha256: digest });
  }));

  api.get('/characters/:id/save', authed(async (req, res) => {
    const row = await ownCharacter(req);
    const file = path.join(savesDir, `${Number(row.id)}.bin`);
    if (!fs.existsSync(file)) throw new ApiError(404, 'not_found', '该角色还没有云存档');
    res.set({ 'Content-Type': 'application/octet-stream', 'Content-Disposition': `attachment; filename="save-${Number(row.id)}.bin"` });
    res.send(fs.readFileSync(file));
  }));

  // ---- playtime -------------------------------------------------------------
  // Clients call this about once a minute while in a room. Credit is bounded by
  // real elapsed time, so repeated calls cannot inflate the total.
  api.post('/play/heartbeat', authed(async (req, res) => {
    if (req.user.active_character_id == null) throw bad('请先创建并选择角色', 'no_character');
    const stamp = now();
    const elapsed = stamp - Number(req.user.last_play_at);
    const credit = elapsed > 0 && elapsed <= 120 ? elapsed : 0;
    await db.run('UPDATE users SET last_play_at = ? WHERE id = ?', [stamp, req.user.id]);
    if (credit) await db.run('UPDATE characters SET play_seconds = play_seconds + ? WHERE id = ?', [credit, req.user.active_character_id]);
    res.json({ ok: true, credited: credit });
  }));

  // ---- relay ----------------------------------------------------------------
  const relayReady = () => { if (!relay.port) throw new ApiError(503, 'relay_disabled', '这台服务器没有开启公网联机中转'); };

  api.post('/relay/host', authed(async (req, res) => {
    relayReady();
    if (req.user.active_character_id == null) throw bad('请先创建并选择角色', 'no_character');
    res.json({ relay: relay.host(Number(req.user.id)) });
  }));

  api.post('/relay/join', authed(async (req, res) => {
    relayReady();
    if (req.user.active_character_id == null) throw bad('请先创建并选择角色', 'no_character');
    const joined = relay.join(Number(req.user.id), whole(req.body.networkId, '中转网络', 1, Number.MAX_SAFE_INTEGER));
    if (!joined) throw new ApiError(404, 'relay_gone', '这个房间的公网联机已经结束，请刷新房间列表');
    if (joined === 'full') throw new ApiError(409, 'relay_full', '这个房间的公网联机名额已满');
    res.json({ relay: joined });
  }));

  api.post('/relay/bot', authed(async (req, res) => {
    relayReady();
    const joined = relay.bot(Number(req.user.id), whole(req.body.index, '人机编号', 1, MAX_BOTS));
    if (joined === 'full') throw new ApiError(409, 'relay_full', '房间的公网联机名额已满');
    if (!joined) throw new ApiError(404, 'relay_gone', '请先用公网联机创建房间');
    res.json({ relay: joined });
  }));

  // ---- room lobby -----------------------------------------------------------
  // A room whose address is the relay's host address, and whose host has a live relay network, is joined through the relay.
  const publicRoom = (row) => ({ id: Number(row.id), title: row.title, hostName: row.host_name, hostAddress: row.host_address,
    roomCode: row.room_code, virtualLan: !!row.virtual_lan, maxPlayers: Number(row.max_players), players: Number(row.players),
    gameVersion: row.game_version, createdAt: Number(row.created_at),
    relayNetwork: row.host_address === HOST_IP ? relay.networkOf(Number(row.user_id)) : null,
    public: extra(row.id).public || 0 });
  const freshSince = () => now() - config.roomTimeoutSeconds;
  // Test bots asked for and the host's last diagnostic line, per room. Not worth a schema change: both are
  // only meaningful while the room is live, and the host re-reads them on every heartbeat.
  const MAX_BOTS = 9;
  const extras = new Map();
  const extra = (id) => extras.get(Number(id)) || { bots: 0, mirror: false, debug: '', public: 0 };
  const roomFor = (row, viewer) => {
    const more = extra(row.id);
    const room = { ...publicRoom(row), bots: more.bots, botMirror: more.mirror };
    if (viewer.is_admin || Number(row.user_id) === Number(viewer.id)) {
      if (room.relayNetwork) room.match = publicMatch(matches.get(room.relayNetwork));
      if (room.public && room.relayNetwork) room.ffa = publicArena(arenas.get(room.relayNetwork), 0);
      room.debug = more.debug;
      // Rooms are listed over HTTPS; the game itself needs the UDP port. Say whether that part works.
      if (room.relayNetwork) room.relayReached = relay.reached(Number(row.user_id));
    }
    return room;
  };

  api.get('/rooms', authed(async (req, res) => {
    await db.run('DELETE FROM rooms WHERE heartbeat_at < ?', [freshSince()]);
    const rows = await db.all('SELECT * FROM rooms ORDER BY created_at DESC LIMIT 100');
    const live = new Set(rows.map((row) => Number(row.id)));
    for (const id of extras.keys()) if (!live.has(id)) extras.delete(id);
    // The public free-for-all rooms come first; "publicOpen" is false when nobody is hosting one right now.
    const rooms = rows.map((row) => roomFor(row, req.user)).sort((a, b) => (a.public || 99) - (b.public || 99));
    res.json({ rooms, publicOpen: rooms.some((room) => room.public) });
  }));

  // Creating a room replaces the caller's previous one: a phone hosts one room.
  api.post('/rooms', authed(async (req, res) => {
    const character = req.user.active_character_id == null ? null
      : await db.get('SELECT * FROM characters WHERE id = ?', [req.user.active_character_id]);
    if (!character) throw bad('请先创建并选择角色', 'no_character');
    const body = req.body;
    const values = [
      character.name,
      text(body.title || `${character.name} 的房间`, '房间名', 1, 40, TITLE),
      text(body.hostAddress, '房主 IP', 7, 15, IPV4),
      text(String(body.roomCode ?? ''), '房间码', 6, 6, /^\d{6}$/),
      body.virtualLan ? 1 : 0,
      whole(body.maxPlayers ?? 10, '人数上限', 2, 10),
      String(body.gameVersion || '').slice(0, 32),
    ];
    // The room of someone who was just told to open the public room is that public room.
    const promised = pendingHosts.get(Number(req.user.id));
    pendingHosts.delete(Number(req.user.id));
    const number = body.public && promised && promised.until >= now() && values[2] === HOST_IP ? promised.number : 0;
    if (number) { values[1] = publicTitle(number); values[5] = 10; }
    const stamp = now();
    const id = await db.tx(async (tx) => {
      await tx.run('DELETE FROM rooms WHERE user_id = ?', [req.user.id]);
      const result = await tx.run(
        'INSERT INTO rooms (user_id, host_name, title, host_address, room_code, virtual_lan, max_players, game_version, players, created_at, heartbeat_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)',
        [req.user.id, ...values, stamp, stamp]);
      return result.insertId;
    });
    if (number) extras.set(id, { ...extra(id), public: number });
    res.status(201).json({ room: publicRoom(await db.get('SELECT * FROM rooms WHERE id = ?', [id])), heartbeatSeconds: 10 });
  }));

  api.post('/rooms/:id/heartbeat', authed(async (req, res) => {
    const players = whole(req.body.players ?? 1, '当前人数', 1, 10);
    const sets = ['players = ?', 'heartbeat_at = ?'];
    const params = [players, now()];
    if (req.body.maxPlayers !== undefined) { sets.push('max_players = ?'); params.push(whole(req.body.maxPlayers, '人数上限', 2, 10)); }
    const result = await db.run(`UPDATE rooms SET ${sets.join(', ')} WHERE id = ? AND user_id = ?`, [...params, Number(req.params.id) || 0, req.user.id]);
    if (!result.changes) throw new ApiError(404, 'not_found', '房间不存在或已过期，请重新创建');
    const more = extra(req.params.id);
    if (typeof req.body.debug === 'string') extras.set(Number(req.params.id), { ...more, debug: req.body.debug.slice(0, 700) });
    res.json({ ok: true, bots: more.bots, botMirror: more.mirror });
  }));

  // Test bots: the host's phone runs them, so this only records how many are wanted. Host or administrator.
  api.post('/rooms/:id/bots', authed(async (req, res) => {
    const id = Number(req.params.id) || 0;
    const row = await db.get('SELECT * FROM rooms WHERE id = ? AND heartbeat_at >= ?', [id, freshSince()]);
    if (!row) throw new ApiError(404, 'not_found', '房间不存在或已过期');
    if (!req.user.is_admin && Number(row.user_id) !== Number(req.user.id)) throw new ApiError(403, 'forbidden', '只有房主或管理员可以设置人机');
    if (row.host_address !== HOST_IP) throw bad('只有用“公网联机”创建的房间可以加人机', 'not_relayed');
    if (extra(id).public) throw bad('公共对战房不能加人机', 'public_room');
    // "fill" asks for as many bots as there are free seats.
    const count = req.body.fill
      ? Math.max(0, Math.min(MAX_BOTS, Number(row.max_players) - Math.max(1, relay.realCount(Number(row.user_id)))))
      : whole(req.body.count, '人机数量', 0, MAX_BOTS);
    extras.set(id, { ...extra(id), bots: count, mirror: !!req.body.mirror });
    res.json({ room: roomFor(row, req.user) });
  }));

  // ---- public free-for-all rooms ("公共对战房") -----------------------------------
  // The game needs a phone as the host, so a room that is "always open" works like this: whoever
  // enters while nobody hosts becomes the host, everyone else joins that room, and when the host
  // leaves the others come back here and the first of them takes over. A full room opens the next one.
  const pendingHosts = new Map();   // user id -> { number, until }: told to host, room not listed yet
  const publicTitle = (number) => `公共对战房 ${number}`;
  /** Whether the host of this room is still there: listed recently and, once heard on UDP, not silent. */
  const hostAlive = (row) => {
    if (!row || Number(row.heartbeat_at) < freshSince() || !relay.networkOf(Number(row.user_id))) return false;
    const silence = relay.hostSilence(Number(row.user_id));
    return silence == null || silence < 35000;
  };
  async function publicRows() {
    const rows = await db.all('SELECT * FROM rooms WHERE heartbeat_at >= ? ORDER BY id', [freshSince()]);
    return rows.filter((row) => extra(row.id).public && row.host_address === HOST_IP)
      .sort((a, b) => extra(a.id).public - extra(b.id).public);
  }
  /** The live public room behind a relay network, or null. */
  async function publicRoomOf(networkId) {
    for (const row of await publicRows()) if (relay.networkOf(Number(row.user_id)) === networkId) return row;
    return null;
  }

  api.post('/public/enter', authed(async (req, res) => {
    relayReady();
    if (req.user.active_character_id == null) throw bad('请先创建并选择角色', 'no_character');
    const me = Number(req.user.id);
    const rows = await publicRows();
    for (const [id, entry] of pendingHosts) if (entry.until < now()) pendingHosts.delete(id);
    for (const row of rows) {
      if (Number(row.user_id) === me || !hostAlive(row)) continue;
      if (Number(row.players) < Number(row.max_players)) return res.json({ action: 'join', number: extra(row.id).public, room: publicRoom(row) });
    }
    const taken = new Set([...rows.filter((row) => Number(row.user_id) !== me && hostAlive(row)).map((row) => extra(row.id).public),
      ...[...pendingHosts].filter(([id]) => id !== me).map(([, entry]) => entry.number)]);
    // Someone else is opening a room at this moment: wait for it rather than opening a second one.
    if ([...pendingHosts.keys()].some((id) => id !== me)) return res.json({ action: 'wait', seconds: 3 });
    let number = 1;
    while (taken.has(number)) number++;
    pendingHosts.set(me, { number, until: now() + 60 });
    res.json({ action: 'host', number, title: publicTitle(number) });
  }));

  api.delete('/rooms/:id', authed(async (req, res) => {
    const id = Number(req.params.id) || 0;
    if (req.user.is_admin) await db.run('DELETE FROM rooms WHERE id = ?', [id]);
    else await db.run('DELETE FROM rooms WHERE id = ? AND user_id = ?', [id, req.user.id]);
    res.json({ ok: true });
  }));

  // ---- races ----------------------------------------------------------------
  // Only the host of a live room may report, mirroring the game where the host
  // owns race state. Results are still client-reported and not tamper-proof.
  api.post('/races', authed(async (req, res) => {
    const room = await db.get('SELECT id FROM rooms WHERE user_id = ? AND heartbeat_at >= ?', [req.user.id, freshSince()]);
    if (!room) throw new ApiError(403, 'not_host', '只有在线房间的房主可以上报比赛成绩');
    const body = req.body;
    const courseName = text(body.courseName, '赛道名', 1, 40, TITLE);
    if (!['sprint', 'circuit'].includes(body.mode)) throw bad('赛制需为 sprint 或 circuit');
    const laps = whole(body.laps ?? 1, '圈数', 1, 5);
    const checkpoints = whole(body.checkpoints ?? 0, '检查点数', 0, 32);
    if (!Array.isArray(body.results) || body.results.length < 1 || body.results.length > 10) throw bad('成绩列表需包含 1～10 名车手');
    const seen = new Set();
    const results = body.results.map((entry) => {
      const name = text(entry && entry.name, '车手名', 1, 32, TITLE);
      if (seen.has(key(name))) throw bad('成绩列表中有重复的车手');
      seen.add(key(name));
      const finished = !!entry.finished;
      return { name, finished,
        position: finished ? whole(entry.position, '名次', 1, 10) : null,
        timeMs: finished ? whole(entry.timeMs, '用时', 1, 15 * 60 * 1000) : null,
        reason: finished ? '' : String(entry.reason || '').slice(0, 64) };
    });
    const positions = results.filter((r) => r.finished).map((r) => r.position);
    if (new Set(positions).size !== positions.length) throw bad('名次不能重复');
    const id = await db.tx(async (tx) => {
      const race = await tx.run('INSERT INTO races (reporter_user_id, course_name, mode, laps, checkpoints, entrants, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
        [req.user.id, courseName, body.mode, laps, checkpoints, results.length, now()]);
      for (const entry of results) {
        const character = await tx.get('SELECT id FROM characters WHERE name_key = ?', [key(entry.name)]);
        await tx.run('INSERT INTO race_results (race_id, character_id, character_name, position, time_ms, finished, reason) VALUES (?, ?, ?, ?, ?, ?, ?)',
          [race.insertId, character ? character.id : null, entry.name, entry.position, entry.timeMs, entry.finished ? 1 : 0, entry.reason]);
      }
      return race.insertId;
    });
    res.status(201).json({ ok: true, raceId: id });
  }));

  api.get('/races', authed(async (_req, res) => {
    const races = await db.all('SELECT * FROM races ORDER BY id DESC LIMIT 20');
    const out = [];
    for (const race of races) {
      const rows = await db.all('SELECT character_name, position, time_ms, finished, reason FROM race_results WHERE race_id = ? ORDER BY finished DESC, position, id', [race.id]);
      out.push({ id: Number(race.id), courseName: race.course_name, mode: race.mode, laps: Number(race.laps), entrants: Number(race.entrants), createdAt: Number(race.created_at),
        results: rows.map((r) => ({ name: r.character_name, finished: !!r.finished, position: r.position == null ? null : Number(r.position),
          timeMs: r.time_ms == null ? null : Number(r.time_ms), reason: r.reason })) });
    }
    res.json({ races: out });
  }));

  const STATS = `SELECT c.id, c.name, c.model, c.play_seconds,
      COUNT(rr.id) AS races, COALESCE(SUM(rr.finished), 0) AS finishes,
      COALESCE(SUM(CASE WHEN rr.finished = 1 AND rr.position = 1 AND r.entrants >= 2 THEN 1 ELSE 0 END), 0) AS wins
    FROM characters c LEFT JOIN race_results rr ON rr.character_id = c.id LEFT JOIN races r ON r.id = rr.race_id`;
  const publicStats = (row) => ({ id: Number(row.id), name: row.name, model: row.model, playSeconds: Number(row.play_seconds),
    races: Number(row.races), finishes: Number(row.finishes), wins: Number(row.wins),
    dmMatches: 0, dmWins: 0, dmKills: 0, dmDeaths: 0, ffaKills: 0, ffaDeaths: 0 });
  /** Adds each character's team deathmatch totals to its statistics row. */
  async function withDeathmatch(stats) {
    const rows = await db.all(`SELECT character_id, COUNT(*) AS matches, COALESCE(SUM(won), 0) AS wins,
      COALESCE(SUM(kills), 0) AS kills, COALESCE(SUM(deaths), 0) AS deaths FROM dm_players WHERE character_id IS NOT NULL GROUP BY character_id`);
    const byId = new Map(rows.map((row) => [Number(row.character_id), row]));
    for (const entry of stats) {
      const row = byId.get(entry.id);
      if (row) Object.assign(entry, { dmMatches: Number(row.matches), dmWins: Number(row.wins), dmKills: Number(row.kills), dmDeaths: Number(row.deaths) });
    }
    const ffa = new Map((await ffaTotals(0)).map((row) => [row.characterId, row]));
    for (const entry of stats) {
      const row = ffa.get(entry.id);
      if (row) Object.assign(entry, { ffaKills: row.kills, ffaDeaths: row.deaths });
    }
    return stats;
  }

  api.get('/leaderboard', route(async (_req, res) => {
    const rows = await db.all(`${STATS} GROUP BY c.id, c.name, c.model, c.play_seconds ORDER BY wins DESC, finishes DESC, c.play_seconds DESC, c.id LIMIT 50`);
    res.json({ leaderboard: await withDeathmatch(rows.map(publicStats)) });
  }));

  api.get('/me/stats', authed(async (req, res) => {
    const rows = await db.all(`${STATS} WHERE c.user_id = ? GROUP BY c.id, c.name, c.model, c.play_seconds ORDER BY c.id`, [req.user.id]);
    res.json({ characters: await withDeathmatch(rows.map(publicStats)) });
  }));

  // ---- team deathmatch ("团队竞技") ---------------------------------------------
  // One match per relay network, kept in memory while it is being played and written to the database
  // when it ends. The host owns it; every phone reports its own player's deaths, and a death is a
  // point for the other team. Like race results this is client-reported and not tamper-proof.
  // Test bots take part too: they fill the smaller team, and a bot goes down when players on the
  // other team have damaged it often enough.
  const TEAMS = ['red', 'blue'];
  const TEAM_SIZE = 5;
  const HIT_SECONDS = 15, LATE_HIT_SECONDS = 4, RESPAWN_SECONDS = 3;
  const matches = new Map();   // network id -> match
  const other = (team) => (team === 'red' ? 'blue' : 'red');
  const teamOf = (current, team) => [...current.players.values()].filter((p) => p.team === team);
  const newPlayer = (fields) => ({ userId: 0, bot: 0, characterId: null, team: '', kills: 0, deaths: 0, diedAt: 0, slot: 0, hit: null, death: null, hits: 0, ...fields });

  function matchContext(req) {
    const networkId = whole(req.body.networkId, '中转网络', 1, Number.MAX_SAFE_INTEGER);
    const role = relay.roleIn(networkId, Number(req.user.id));
    if (!role) throw new ApiError(403, 'not_in_room', '你不在这个公网联机房间里');
    return { networkId, host: role === 'host', match: matches.get(networkId) || null };
  }
  const hostOnly = (context) => { if (!context.host) throw new ApiError(403, 'not_host', '只有房主可以操作团队竞技'); };
  const needMatch = (context) => { if (!context.match) throw new ApiError(404, 'no_match', '房主还没有创建团队竞技'); return context.match; };

  async function finishMatch(match, reason) {
    if (match.state !== 'running') return;
    match.state = 'finished';
    match.reason = reason;
    match.endedAt = now();
    match.winner = match.score.red === match.score.blue ? 'draw' : (match.score.red > match.score.blue ? 'red' : 'blue');
    const players = [...match.players.values()].filter((p) => p.team);
    if (!players.length || !match.record) return;
    await db.tx(async (tx) => {
      const saved = await tx.run('INSERT INTO dm_matches (host_user_id, host_name, target, minutes, red_score, blue_score, winner, reason, started_at, ended_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [match.hostId, match.hostName, match.target, match.minutes, match.score.red, match.score.blue, match.winner, reason, match.startedAt, match.endedAt]);
      for (const player of players) {
        await tx.run('INSERT INTO dm_players (match_id, character_id, character_name, team, kills, deaths, won) VALUES (?, ?, ?, ?, ?, ?, ?)',
          [saved.insertId, player.characterId, player.name, player.team, player.kills, player.deaths, player.team === match.winner ? 1 : 0]);
      }
    });
  }
  /** Ends a match whose time is up, and forgets finished ones after a while. */
  async function settle(networkId) {
    const match = matches.get(networkId);
    if (!match) return null;
    if (match.state === 'running' && now() >= match.endsAt) await finishMatch(match, 'time');
    if (match.state !== 'running' && now() - (match.endedAt || match.createdAt) > 3600) { matches.delete(networkId); return null; }
    return match;
  }
  function publicMatch(match) {
    if (!match) return null;
    return { state: match.state, target: match.target, minutes: match.minutes, botHits: match.botHits, record: match.record, hostName: match.hostName,
      red: match.score.red, blue: match.score.blue, winner: match.winner || '', reason: match.reason || '',
      startedAt: match.startedAt || 0, endsAt: match.endsAt || 0, endedAt: match.endedAt || 0, now: now(),
      players: [...match.players.entries()].filter(([, p]) => p.team).map(([id, p]) => ({ id: String(id), userId: p.userId, bot: !!p.bot, name: p.name, team: p.team, kills: p.kills, deaths: p.deaths })),
      feed: match.feed.slice(-5).map((f) => ({ at: f.at, victim: f.victim, killer: f.killer, team: f.team })) };
  }

  const credit = (victim, killer) => {
    if (!killer || killer === victim || killer.team === victim.team || victim.death.killer) return;
    killer.kills++;
    victim.death.killer = killer.name;
  };
  /** One death: a point for the other team, and a kill for whoever did it (given, or the last to damage the victim). */
  async function kill(current, victim, killer) {
    const scorer = other(victim.team);
    victim.diedAt = now();
    victim.deaths++;
    victim.hits = 0;
    current.score[scorer]++;
    victim.death = { at: now(), victim: victim.name, killer: '', team: scorer };
    current.feed.push(victim.death);
    if (current.feed.length > 20) current.feed.shift();
    if (killer) credit(victim, killer);
    else if (victim.hit && now() - victim.hit.at <= HIT_SECONDS) credit(victim, current.players.get(victim.hit.by));
    victim.hit = null;
    if (current.score[scorer] >= current.target) await finishMatch(current, 'target');
  }

  /** Bots take the seats that are left, smaller team first, so real players always get the team they pick. */
  function balanceBots(current) {
    const size = { red: 0, blue: 0 };
    for (const p of current.players.values()) if (!p.bot && p.team) size[p.team]++;
    const bots = [...current.players.values()].filter((p) => p.bot).sort((a, b) => a.bot - b.bot);
    for (const bot of bots) {
      const first = size.red <= size.blue ? 'red' : 'blue';
      bot.team = size[first] < TEAM_SIZE ? first : (size[other(first)] < TEAM_SIZE ? other(first) : '');
      if (bot.team) size[bot.team]++;
    }
  }
  /** The host's phone says which bots are in the room and their member numbers. */
  function syncBots(current, list) {
    if (!current || current.state === 'finished') return;
    const seen = new Map();
    for (const entry of list.slice(0, MAX_BOTS)) {
      if (!entry || !Number.isInteger(entry.index) || entry.index < 1 || entry.index > MAX_BOTS) continue;
      seen.set(entry.index, Number.isInteger(entry.slot) && entry.slot >= 1 && entry.slot <= 10 ? entry.slot : 0);
    }
    if (current.state === 'running') {
      for (const p of current.players.values()) if (p.bot && seen.has(p.bot)) p.slot = seen.get(p.bot);
      return;
    }
    for (const [id, p] of current.players) if (p.bot && !seen.has(p.bot)) current.players.delete(id);
    for (const [index, slot] of seen) {
      const id = `bot:${index}`;
      if (!current.players.has(id)) current.players.set(id, newPlayer({ bot: index, name: `人机${index}` }));
      current.players.get(id).slot = slot;
    }
    balanceBots(current);
  }

  /**
   * Every request from a phone carries its member number and the member numbers its player has just
   * damaged (the game keeps that table for its own damage sync). The last one to damage a player who
   * then dies gets the kill, also when that report arrives a moment after the death. A bot on the
   * other team goes down once it has been damaged "botHits" times.
   */
  async function noteClient(req, current) {
    if (!current || current.state !== 'running') return;
    const player = current.players.get(Number(req.user.id));
    if (!player || !player.team) return;
    if (Number.isInteger(req.body.slot) && req.body.slot >= 1 && req.body.slot <= 10) player.slot = req.body.slot;
    if (!Array.isArray(req.body.hits)) return;
    const counts = Array.isArray(req.body.counts) ? req.body.counts : [];
    const hits = req.body.hits.slice(0, 10);
    for (let i = 0; i < hits.length && current.state === 'running'; i++) {
      const victim = [...current.players.values()].find((p) => p.team && p.slot === hits[i] && p !== player);
      if (!victim) continue;
      if (victim.bot) {
        if (victim.team === player.team || now() - victim.diedAt < RESPAWN_SECONDS) continue;
        victim.hits += Number.isInteger(counts[i]) && counts[i] > 0 ? Math.min(counts[i], 50) : 1;
        if (victim.hits >= current.botHits) await kill(current, victim, player);
      } else if (victim.death && now() - victim.death.at <= LATE_HIT_SECONDS) {
        credit(victim, player);     // damage reported just after the death belongs to that death
      } else {
        victim.hit = { by: player.userId, at: now() };
      }
    }
  }

  const match = (handler) => authed(async (req, res) => {
    const context = matchContext(req);
    context.match = await settle(context.networkId);
    if (context.host && Array.isArray(req.body.bots)) syncBots(context.match, req.body.bots);
    await noteClient(req, context.match);
    await handler(req, context);
    const room = await publicRoomOf(context.networkId);
    res.json({ host: context.host, userId: Number(req.user.id), public: room ? extra(room.id).public : 0, match: publicMatch(await settle(context.networkId)) });
  });

  api.post('/match/state', match(async () => {}));

  // Creating replaces a match that is not being played.
  api.post('/match/create', match(async (req, context) => {
    hostOnly(context);
    if (await publicRoomOf(context.networkId)) throw new ApiError(409, 'public_room', '公共对战房只有个人对战，不能开团队竞技');
    if (context.match && context.match.state === 'running') throw new ApiError(409, 'running', '团队竞技正在进行，请先结束');
    const character = req.user.active_character_id == null ? null : await db.get('SELECT name FROM characters WHERE id = ?', [req.user.active_character_id]);
    const created = { state: 'lobby', hostId: Number(req.user.id), hostName: character ? character.name : req.user.username,
      target: whole(req.body.target ?? 20, '目标击杀数', 1, 200), minutes: whole(req.body.minutes ?? 15, '时间上限', 1, 60),
      botHits: whole(req.body.botHits ?? 3, '击倒人机所需命中数', 1, 50), record: req.body.record !== false,
      score: { red: 0, blue: 0 }, players: new Map(), feed: [], debug: [], createdAt: now() };
    matches.set(context.networkId, created);
    if (Array.isArray(req.body.bots)) syncBots(created, req.body.bots);
  }));

  // Choosing a team (or leaving with team = null). The host may place anyone who is in the room.
  api.post('/match/team', match(async (req, context) => {
    const current = needMatch(context);
    if (current.state !== 'lobby') throw new ApiError(409, 'not_lobby', current.state === 'running' ? '团队竞技已经开始，不能换队' : '这场团队竞技已经结束');
    let userId = Number(req.user.id);
    if (req.body.userId !== undefined && Number(req.body.userId) !== userId) {
      hostOnly(context);
      userId = whole(req.body.userId, '玩家', 1, Number.MAX_SAFE_INTEGER);
      if (!relay.roleIn(context.networkId, userId)) throw new ApiError(404, 'not_in_room', '这名玩家不在房间里');
    }
    const team = req.body.team;
    if (team == null) { current.players.delete(userId); balanceBots(current); return; }
    if (!TEAMS.includes(team)) throw bad('队伍需为 red 或 blue');
    const mates = teamOf(current, team).filter((p) => !p.bot && p.userId !== userId);
    if (mates.length >= TEAM_SIZE) throw new ApiError(409, 'team_full', `${team === 'red' ? '红' : '蓝'}队已满 ${TEAM_SIZE} 人`);
    const owner = await db.get('SELECT u.username, c.id AS character_id, c.name FROM users u LEFT JOIN characters c ON c.id = u.active_character_id WHERE u.id = ?', [userId]);
    if (!owner) throw new ApiError(404, 'not_found', '玩家不存在');
    current.players.set(userId, newPlayer({ userId, characterId: owner.character_id == null ? null : Number(owner.character_id),
      name: owner.name || owner.username, team }));
    balanceBots(current);
  }));

  api.post('/match/start', match(async (_req, context) => {
    hostOnly(context);
    const current = needMatch(context);
    if (current.state !== 'lobby') throw new ApiError(409, 'not_lobby', '这场团队竞技已经开始或结束，请重新创建');
    if (!teamOf(current, 'red').length || !teamOf(current, 'blue').length) throw bad('两队都至少要有 1 人（或人机）才能开始', 'teams_empty');
    Object.assign(current, { state: 'running', startedAt: now(), endsAt: now() + current.minutes * 60 });
  }));

  // The host stops a match early (the score so far stands) or drops one that has not started.
  api.post('/match/end', match(async (_req, context) => {
    hostOnly(context);
    const current = needMatch(context);
    if (current.state === 'running') await finishMatch(current, 'host');
    else matches.delete(context.networkId);
  }));

  // A phone reports that its own player died.
  api.post('/match/death', match(async (req, context) => {
    const current = context.match;
    if (!current || current.state !== 'running') return;
    const player = current.players.get(Number(req.user.id));
    if (!player || !player.team || now() - player.diedAt < RESPAWN_SECONDS) return;      // one death per respawn
    if (typeof req.body.debug === 'string') {
      current.debug.push({ at: now(), name: player.name, text: req.body.debug.slice(0, 2000) });
      if (current.debug.length > 30) current.debug.shift();
    }
    await kill(current, player, null);
  }));

  // For testing from the web page: the host or an administrator makes a player or bot go down once.
  api.post('/match/simulate', authed(async (req, res) => {
    const networkId = whole(req.body.networkId, '中转网络', 1, Number.MAX_SAFE_INTEGER);
    if (!req.user.is_admin && relay.roleIn(networkId, Number(req.user.id)) !== 'host') throw new ApiError(403, 'forbidden', '只有房主或管理员可以模拟阵亡');
    const current = await settle(networkId);
    if (!current || current.state !== 'running') throw new ApiError(409, 'not_running', '团队竞技没有在进行');
    const find = (id) => current.players.get(/^\d+$/.test(String(id)) ? Number(id) : String(id));
    const victim = find(req.body.victim);
    if (!victim || !victim.team) throw new ApiError(404, 'not_found', '这名队员不在比赛里');
    const killer = req.body.killer == null ? null : find(req.body.killer);
    victim.hit = null;
    await kill(current, victim, killer && killer.team ? killer : null);
    res.json({ match: publicMatch(current) });
  }));

  // ---- free-for-all ("个人对战") in the public rooms --------------------------------
  // No teams and no end: every death is stored, with the last player to damage the victim as the
  // killer. A death nobody caused (a fall, the police) counts for the victim only.
  const arenas = new Map();   // network id -> { number, players: Map(user id -> player), feed }
  const dayOffset = (config.boardUtcOffsetHours ?? 8) * 3600;
  /** Start of today, of this week (Monday) and of everything, in the boards' time zone. */
  function since(range) {
    const today = Math.floor((now() + dayOffset) / 86400) * 86400 - dayOffset;
    if (range === 'day') return today;
    if (range === 'week') return today - ((Math.floor((now() + dayOffset) / 86400) + 3) % 7) * 86400;   // day 0 was a Thursday
    return 0;
  }
  async function ffaTotals(from, characterId) {
    const one = characterId == null ? '' : ' AND %s = ?';
    const params = characterId == null ? [from] : [from, characterId];
    const kills = await db.all(`SELECT killer_character_id AS id, MAX(killer_name) AS name, COUNT(*) AS n, MAX(at) AS last FROM ffa_kills WHERE at >= ? AND killer_character_id IS NOT NULL${one.replace('%s', 'killer_character_id')} GROUP BY killer_character_id`, params);
    const deaths = await db.all(`SELECT victim_character_id AS id, MAX(victim_name) AS name, COUNT(*) AS n, MAX(at) AS last FROM ffa_kills WHERE at >= ? AND victim_character_id IS NOT NULL${one.replace('%s', 'victim_character_id')} GROUP BY victim_character_id`, params);
    const byId = new Map();
    const row = (r) => byId.get(Number(r.id)) || byId.set(Number(r.id), { characterId: Number(r.id), name: r.name, kills: 0, deaths: 0, lastAt: 0 }).get(Number(r.id));
    for (const r of kills) { const entry = row(r); entry.kills = Number(r.n); entry.lastAt = Math.max(entry.lastAt, Number(r.last)); }
    for (const r of deaths) { const entry = row(r); entry.deaths = Number(r.n); entry.lastAt = Math.max(entry.lastAt, Number(r.last)); }
    return [...byId.values()].sort((a, b) => b.kills - a.kills || a.deaths - b.deaths || a.characterId - b.characterId);
  }
  function publicArena(arena, viewer) {
    if (!arena) return { players: [], feed: [] };
    const present = [...arena.players.values()].filter((p) => now() - p.seenAt <= 30);
    return { players: present.sort((a, b) => b.kills - a.kills || a.deaths - b.deaths)
        .map((p) => ({ userId: p.userId, name: p.name, kills: p.kills, deaths: p.deaths, me: p.userId === viewer })),
      feed: arena.feed.slice(-5).map((f) => ({ at: f.at, victim: f.victim, killer: f.killer })) };
  }
  async function ffaCredit(death, killer, victim) {
    if (!killer || killer === victim || death.killer) return;
    death.killer = killer.name;
    killer.kills++; killer.total.kills++;
    await db.run('UPDATE ffa_kills SET killer_character_id = ?, killer_name = ? WHERE id = ?', [killer.characterId, killer.name, death.id]);
  }

  // One call does everything a phone needs: says it is here, reports who it damaged and whether it
  // just died, and gets the room's standing back. "alive" false tells a member the host has gone.
  api.post('/ffa/state', authed(async (req, res) => {
    const networkId = whole(req.body.networkId, '中转网络', 1, Number.MAX_SAFE_INTEGER);
    const me = Number(req.user.id);
    const role = relay.roleIn(networkId, me);
    if (!role) throw new ApiError(403, 'not_in_room', '你不在这个公网联机房间里');
    const room = await publicRoomOf(networkId);
    if (!room) {
      // Not listed (yet, or any more). For a room that was public this means its host has left.
      if (!arenas.has(networkId)) throw new ApiError(404, 'not_public', '这不是公共对战房');
      return res.json({ public: arenas.get(networkId).number, host: role === 'host', alive: false, userId: me, ...publicArena(arenas.get(networkId), me) });
    }
    for (const [id, old] of arenas) if (now() - old.touchedAt > 3600) arenas.delete(id);
    let arena = arenas.get(networkId);
    if (!arena) { arena = { number: extra(room.id).public, players: new Map(), feed: [] }; arenas.set(networkId, arena); }
    arena.touchedAt = now();
    let player = arena.players.get(me);
    if (!player || player.characterId !== Number(req.user.active_character_id)) {
      const character = req.user.active_character_id == null ? null : await db.get('SELECT id, name FROM characters WHERE id = ?', [req.user.active_character_id]);
      if (!character) throw bad('请先创建并选择角色', 'no_character');
      const [total] = await ffaTotals(0, Number(character.id));
      player = { userId: me, characterId: Number(character.id), name: character.name, slot: 0, kills: 0, deaths: 0, diedAt: 0, hit: null, death: null, seenAt: 0,
        total: { kills: total ? total.kills : 0, deaths: total ? total.deaths : 0 } };
      arena.players.set(me, player);
    }
    player.seenAt = now();
    if (Number.isInteger(req.body.slot) && req.body.slot >= 1 && req.body.slot <= 10) player.slot = req.body.slot;
    if (Array.isArray(req.body.hits)) {
      for (const slot of req.body.hits.slice(0, 10)) {
        const victim = [...arena.players.values()].find((p) => p.slot === slot && p !== player && now() - p.seenAt <= 30);
        if (!victim) continue;
        if (victim.death && now() - victim.death.at <= LATE_HIT_SECONDS) await ffaCredit(victim.death, player, victim);
        else victim.hit = { by: me, at: now() };
      }
    }
    if (req.body.death && now() - player.diedAt >= RESPAWN_SECONDS) {      // one death per respawn
      player.diedAt = now();
      player.deaths++; player.total.deaths++;
      const saved = await db.run('INSERT INTO ffa_kills (at, room, killer_character_id, killer_name, victim_character_id, victim_name) VALUES (?, ?, NULL, ?, ?, ?)',
        [now(), arena.number, '', player.characterId, player.name]);
      player.death = { id: saved.insertId, at: now(), victim: player.name, killer: '' };
      arena.feed.push(player.death);
      if (arena.feed.length > 20) arena.feed.shift();
      if (player.hit && now() - player.hit.at <= HIT_SECONDS) await ffaCredit(player.death, arena.players.get(player.hit.by), player);
      player.hit = null;
    }
    res.json({ public: arena.number, host: role === 'host', alive: hostAlive(room), userId: me,
      me: { kills: player.kills, deaths: player.deaths, totalKills: player.total.kills, totalDeaths: player.total.deaths },
      ...publicArena(arena, me) });
  }));

  api.get('/ffa/board', route(async (req, res) => {
    const range = ['day', 'week', 'all'].includes(req.query.range) ? req.query.range : 'all';
    res.json({ range, since: since(range), board: (await ffaTotals(since(range))).slice(0, 50) });
  }));

  api.get('/admin/ffa', admin(async (_req, res) => {
    const rows = await db.all('SELECT * FROM ffa_kills ORDER BY id DESC LIMIT 200');
    res.json({ players: await ffaTotals(0), today: await ffaTotals(since('day')),
      kills: rows.map((r) => ({ id: Number(r.id), at: Number(r.at), room: Number(r.room), killer: r.killer_name, victim: r.victim_name })) });
  }));

  // Clears one character's free-for-all record (kills and deaths), or everything.
  api.post('/admin/ffa/reset', admin(async (req, res) => {
    let result;
    if (req.body.all === true) result = await db.run('DELETE FROM ffa_kills');
    else {
      const id = whole(req.body.characterId, '角色', 1, Number.MAX_SAFE_INTEGER);
      result = await db.run('DELETE FROM ffa_kills WHERE killer_character_id = ? OR victim_character_id = ?', [id, id]);
    }
    for (const arena of arenas.values()) arena.players.clear();
    res.json({ ok: true, removed: result.changes });
  }));

  api.get('/matches', authed(async (_req, res) => {
    const rows = await db.all('SELECT * FROM dm_matches ORDER BY id DESC LIMIT 20');
    const out = [];
    for (const row of rows) {
      const players = await db.all('SELECT character_name, team, kills, deaths FROM dm_players WHERE match_id = ? ORDER BY team DESC, deaths, id', [row.id]);
      out.push({ id: Number(row.id), hostName: row.host_name, target: Number(row.target), red: Number(row.red_score), blue: Number(row.blue_score),
        winner: row.winner, reason: row.reason, startedAt: Number(row.started_at), endedAt: Number(row.ended_at),
        players: players.map((p) => ({ name: p.character_name, team: p.team, kills: Number(p.kills), deaths: Number(p.deaths) })) });
    }
    res.json({ matches: out });
  }));

  // What the phones attached to their death reports, for working out who killed whom.
  api.get('/admin/match-debug', admin(async (_req, res) => {
    res.json({ matches: [...matches.entries()].map(([networkId, m]) => ({ networkId, state: m.state, debug: m.debug })) });
  }));

  // ---- admin ----------------------------------------------------------------
  api.get('/admin/users', admin(async (_req, res) => {
    const rows = await db.all('SELECT id, username, is_admin, banned, created_at, last_login_at FROM users ORDER BY id DESC LIMIT 200');
    res.json({ users: rows.map((u) => ({ id: Number(u.id), username: u.username, isAdmin: !!u.is_admin, banned: !!u.banned,
      createdAt: Number(u.created_at), lastLoginAt: Number(u.last_login_at) })) });
  }));

  api.post('/admin/users/:id/ban', admin(async (req, res) => {
    const id = Number(req.params.id) || 0;
    if (id === Number(req.user.id)) throw bad('不能封禁自己');
    const banned = req.body.banned ? 1 : 0;
    if (banned) relay.closeFor(Number(req.params.id));
    const result = await db.run('UPDATE users SET banned = ? WHERE id = ?', [banned, id]);
    if (!result.changes && !(await db.get('SELECT id FROM users WHERE id = ?', [id]))) throw new ApiError(404, 'not_found', '用户不存在');
    if (banned) {
      await db.run('DELETE FROM tokens WHERE user_id = ?', [id]);
      await db.run('DELETE FROM rooms WHERE user_id = ?', [id]);
    }
    res.json({ ok: true });
  }));

  // Setting a new password for someone who forgot theirs signs them out everywhere.
  api.post('/admin/users/:id/password', admin(async (req, res) => {
    const id = Number(req.params.id) || 0;
    const next = text(req.body.newPassword, '新密码', 8, 72);
    const result = await db.run('UPDATE users SET password_hash = ? WHERE id = ?', [await hashPassword(next), id]);
    if (!result.changes && !(await db.get('SELECT id FROM users WHERE id = ?', [id]))) throw new ApiError(404, 'not_found', '用户不存在');
    if (id !== Number(req.user.id)) await db.run('DELETE FROM tokens WHERE user_id = ?', [id]);
    res.json({ ok: true });
  }));

  api.use((_req, _res, next) => next(new ApiError(404, 'not_found', '接口不存在')));
  // eslint-disable-next-line no-unused-vars
  app.use((error, _req, res, _next) => {
    let status = error.status || 500;
    let code = error.code || 'server_error';
    let message = error.message;
    if (error.type === 'entity.too.large') { status = 413; code = 'too_large'; message = '上传内容过大'; }
    else if (error.type === 'entity.parse.failed') { status = 400; code = 'invalid'; message = '请求格式不正确'; }
    else if (status >= 500) { console.error(error); message = '服务器内部错误'; code = 'server_error'; }
    res.status(status).json({ error: { code, message } });
  });
  return app;
}

module.exports = { createApp };
