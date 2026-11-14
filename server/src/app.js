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
    relayNetwork: row.host_address === HOST_IP ? relay.networkOf(Number(row.user_id)) : null });
  const freshSince = () => now() - config.roomTimeoutSeconds;
  // Test bots asked for and the host's last diagnostic line, per room. Not worth a schema change: both are
  // only meaningful while the room is live, and the host re-reads them on every heartbeat.
  const MAX_BOTS = 3;
  const extras = new Map();
  const extra = (id) => extras.get(Number(id)) || { bots: 0, mirror: false, debug: '' };
  const roomFor = (row, viewer) => {
    const more = extra(row.id);
    const room = { ...publicRoom(row), bots: more.bots, botMirror: more.mirror };
    if (viewer.is_admin || Number(row.user_id) === Number(viewer.id)) {
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
    res.json({ rooms: rows.map((row) => roomFor(row, req.user)) });
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
    const stamp = now();
    const id = await db.tx(async (tx) => {
      await tx.run('DELETE FROM rooms WHERE user_id = ?', [req.user.id]);
      const result = await tx.run(
        'INSERT INTO rooms (user_id, host_name, title, host_address, room_code, virtual_lan, max_players, game_version, players, created_at, heartbeat_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)',
        [req.user.id, ...values, stamp, stamp]);
      return result.insertId;
    });
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
    extras.set(id, { ...extra(id), bots: whole(req.body.count, '人机数量', 0, MAX_BOTS), mirror: !!req.body.mirror });
    res.json({ room: roomFor(row, req.user) });
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
    races: Number(row.races), finishes: Number(row.finishes), wins: Number(row.wins) });

  api.get('/leaderboard', route(async (_req, res) => {
    const rows = await db.all(`${STATS} GROUP BY c.id, c.name, c.model, c.play_seconds ORDER BY wins DESC, finishes DESC, c.play_seconds DESC, c.id LIMIT 50`);
    res.json({ leaderboard: rows.map(publicStats) });
  }));

  api.get('/me/stats', authed(async (req, res) => {
    const rows = await db.all(`${STATS} WHERE c.user_id = ? GROUP BY c.id, c.name, c.model, c.play_seconds ORDER BY c.id`, [req.user.id]);
    res.json({ characters: rows.map(publicStats) });
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
