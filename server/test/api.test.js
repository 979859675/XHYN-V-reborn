// SPDX-License-Identifier: MIT
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test, before, after } = require('node:test');
const { load } = require('../src/config');
const { open } = require('../src/db');
const { createApp } = require('../src/app');

let server, base, db, dataDir, config;

async function call(method, url, { token, body, raw } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  let payload;
  if (raw) { headers['content-type'] = 'application/octet-stream'; payload = raw; }
  else if (body !== undefined) { headers['content-type'] = 'application/json'; payload = JSON.stringify(body); }
  const response = await fetch(base + url, { method, headers, body: payload });
  const type = response.headers.get('content-type') || '';
  const data = type.includes('json') ? await response.json() : Buffer.from(await response.arrayBuffer());
  return { status: response.status, data };
}
async function account(username) {
  const result = await call('POST', '/api/auth/register', { body: { username, password: 'correct horse' } });
  assert.equal(result.status, 201, JSON.stringify(result.data));
  return result.data.token;
}

before(async () => {
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'xhynv-'));
  config = load({ dbDriver: 'sqlite', sqliteFile: ':memory:', dataDir, inviteCode: '', maxCharacters: 2, maxSaveBytes: 1024, authRateLimit: 1000, apiRateLimit: 100000 });
  db = await open(config);
  server = createApp(config, db).listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(async () => {
  await new Promise((resolve) => server.close(resolve));
  await db.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

test('register, login, logout and password rules', async () => {
  const token = await account('Alice');
  const me = await call('GET', '/api/me', { token });
  assert.equal(me.data.user.username, 'Alice');
  assert.equal(me.data.user.isAdmin, true, 'first account is the administrator');

  assert.equal((await call('POST', '/api/auth/register', { body: { username: 'alice', password: 'correct horse' } })).status, 409, 'names are case-insensitive');
  assert.equal((await call('POST', '/api/auth/register', { body: { username: 'bob', password: 'short' } })).status, 400);
  assert.equal((await call('POST', '/api/auth/register', { body: { username: 'a b', password: 'correct horse' } })).status, 400);
  assert.equal((await call('POST', '/api/auth/login', { body: { username: 'ALICE', password: 'wrong password' } })).status, 401);
  assert.equal((await call('POST', '/api/auth/login', { body: { username: 'nobody', password: 'wrong password' } })).status, 401);

  const login = await call('POST', '/api/auth/login', { body: { username: 'ALICE', password: 'correct horse' } });
  assert.equal(login.status, 200);
  assert.equal((await call('POST', '/api/auth/logout', { token: login.data.token })).status, 200);
  assert.equal((await call('GET', '/api/me', { token: login.data.token })).status, 401, 'logged-out token is dead');
  assert.equal((await call('GET', '/api/me')).status, 401);

  const stored = await db.get('SELECT password_hash FROM users WHERE username_key = ?', ['alice']);
  assert.match(stored.password_hash, /^scrypt\$/);
  assert.ok(!stored.password_hash.includes('correct horse'));
});

test('changing the password signs out other sessions', async () => {
  const first = await account('carol');
  const second = (await call('POST', '/api/auth/login', { body: { username: 'carol', password: 'correct horse' } })).data.token;
  assert.equal((await call('POST', '/api/auth/password', { token: first, body: { currentPassword: 'nope', newPassword: 'another long one' } })).status, 401);
  assert.equal((await call('POST', '/api/auth/password', { token: first, body: { currentPassword: 'correct horse', newPassword: 'another long one' } })).status, 200);
  assert.equal((await call('GET', '/api/me', { token: first })).status, 200);
  assert.equal((await call('GET', '/api/me', { token: second })).status, 401);
  assert.equal((await call('POST', '/api/auth/login', { body: { username: 'carol', password: 'another long one' } })).status, 200);
});

test('characters: create, limits, unique names, select, delete, ownership', async () => {
  const dave = await account('dave');
  const erin = await account('erin');
  const first = await call('POST', '/api/characters', { token: dave, body: { name: '车神', model: 'player_one' } });
  assert.equal(first.status, 201);
  assert.equal((await call('GET', '/api/me', { token: dave })).data.user.activeCharacterId, first.data.character.id, 'first character becomes active');
  assert.equal((await call('POST', '/api/characters', { token: erin, body: { name: '车神' } })).status, 409);
  assert.equal((await call('POST', '/api/characters', { token: dave, body: { name: 'x' } })).status, 400);
  assert.equal((await call('POST', '/api/characters', { token: dave, body: { name: 'Racer', model: 'bad model!' } })).status, 400);
  const second = await call('POST', '/api/characters', { token: dave, body: { name: 'Racer' } });
  assert.equal(second.status, 201);
  assert.equal(second.data.character.model, 'player_zero');
  assert.equal((await call('POST', '/api/characters', { token: dave, body: { name: 'Third' } })).status, 400, 'limit of 2');

  assert.equal((await call('POST', `/api/characters/${second.data.character.id}/select`, { token: erin })).status, 404, "cannot select someone else's");
  assert.equal((await call('DELETE', `/api/characters/${second.data.character.id}`, { token: erin })).status, 404);
  assert.equal((await call('POST', `/api/characters/${second.data.character.id}/select`, { token: dave })).status, 200);
  assert.equal((await call('DELETE', `/api/characters/${second.data.character.id}`, { token: dave })).status, 200);
  const list = await call('GET', '/api/characters', { token: dave });
  assert.equal(list.data.characters.length, 1);
  assert.equal(list.data.activeCharacterId, first.data.character.id, 'active falls back to a remaining character');
});

test('cloud save round trip, size limit and ownership', async () => {
  const frank = await account('frank');
  const grace = await account('grace');
  const id = (await call('POST', '/api/characters', { token: frank, body: { name: 'Saver' } })).data.character.id;
  assert.equal((await call('GET', `/api/characters/${id}/save`, { token: frank })).status, 404);
  const blob = Buffer.from([0, 1, 2, 3, 255, 254]);
  assert.equal((await call('PUT', `/api/characters/${id}/save`, { token: frank, raw: blob })).status, 200);
  assert.equal((await call('PUT', `/api/characters/${id}/save`, { token: frank, raw: Buffer.from('v2') })).status, 200, 'overwrite');
  const back = await call('GET', `/api/characters/${id}/save`, { token: frank });
  assert.equal(back.data.toString(), 'v2');
  assert.equal((await call('GET', '/api/characters', { token: frank })).data.characters[0].save.size, 2);
  assert.equal((await call('PUT', `/api/characters/${id}/save`, { token: frank, raw: Buffer.alloc(2048) })).status, 413);
  assert.equal((await call('GET', `/api/characters/${id}/save`, { token: grace })).status, 404);
  assert.equal((await call('PUT', `/api/characters/${id}/save`, { token: grace, raw: blob })).status, 404);
  assert.equal((await call('PUT', `/api/characters/${id}/save`, { raw: blob })).status, 401);
});

test('room lobby: create, list, heartbeat, expiry, one room per host', async () => {
  const host = await account('host1');
  const guest = await account('guest1');
  const room = { hostAddress: '192.168.1.20', roomCode: '123456', maxPlayers: 4 };
  assert.equal((await call('POST', '/api/rooms', { token: host, body: room })).status, 400, 'needs a character');
  await call('POST', '/api/characters', { token: host, body: { name: 'HostChar' } });
  assert.equal((await call('POST', '/api/rooms', { token: host, body: { ...room, hostAddress: '999.1.1.1' } })).status, 400);
  assert.equal((await call('POST', '/api/rooms', { token: host, body: { ...room, roomCode: '12ab56' } })).status, 400);
  assert.equal((await call('POST', '/api/rooms', { token: host, body: { ...room, maxPlayers: 11 } })).status, 400);
  const created = await call('POST', '/api/rooms', { token: host, body: room });
  assert.equal(created.status, 201);
  assert.equal(created.data.room.hostName, 'HostChar');
  assert.equal((await call('GET', '/api/rooms')).status, 401, 'join details are for signed-in players only');

  const again = await call('POST', '/api/rooms', { token: host, body: { ...room, roomCode: '654321' } });
  let list = (await call('GET', '/api/rooms', { token: guest })).data.rooms.filter((r) => r.hostName === 'HostChar');
  assert.equal(list.length, 1, 'a second room replaces the first');
  assert.equal(list[0].roomCode, '654321');

  const id = again.data.room.id;
  assert.equal((await call('POST', `/api/rooms/${id}/heartbeat`, { token: guest, body: { players: 2 } })).status, 404, 'only the host');
  assert.equal((await call('POST', `/api/rooms/${id}/heartbeat`, { token: host, body: { players: 3 } })).status, 200);
  list = (await call('GET', '/api/rooms', { token: guest })).data.rooms.filter((r) => r.id === id);
  assert.equal(list[0].players, 3);

  await db.run('UPDATE rooms SET heartbeat_at = heartbeat_at - 1000 WHERE id = ?', [id]);
  list = (await call('GET', '/api/rooms', { token: guest })).data.rooms.filter((r) => r.id === id);
  assert.equal(list.length, 0, 'stale rooms disappear');
  assert.equal((await call('POST', `/api/rooms/${id}/heartbeat`, { token: host, body: { players: 1 } })).status, 404);
});

test('races: host-only reporting, validation, statistics and leaderboard', async () => {
  const host = await account('racehost');
  const other = await account('racer2');
  await call('POST', '/api/characters', { token: host, body: { name: 'Speedy' } });
  await call('POST', '/api/characters', { token: other, body: { name: 'Slowpoke' } });
  const race = { courseName: '街头赛道 1', mode: 'sprint', checkpoints: 5, results: [
    { name: 'speedy', finished: true, position: 1, timeMs: 61000 },
    { name: 'Slowpoke', finished: true, position: 2, timeMs: 70000 },
    { name: 'Stranger', finished: false, reason: '人物死亡' } ] };
  assert.equal((await call('POST', '/api/races', { token: host, body: race })).status, 403, 'must host a live room');
  await call('POST', '/api/rooms', { token: host, body: { hostAddress: '10.0.0.2', roomCode: '000111' } });
  assert.equal((await call('POST', '/api/races', { token: other, body: race })).status, 403);
  assert.equal((await call('POST', '/api/races', { token: host, body: { ...race, mode: 'drag' } })).status, 400);
  assert.equal((await call('POST', '/api/races', { token: host, body: { ...race, results: [race.results[0], { ...race.results[1], position: 1 }] } })).status, 400, 'duplicate position');
  assert.equal((await call('POST', '/api/races', { token: host, body: { ...race, results: [race.results[0], race.results[0]] } })).status, 400, 'duplicate driver');
  assert.equal((await call('POST', '/api/races', { token: host, body: { ...race, results: [{ name: 'Speedy', finished: true, position: 1, timeMs: -5 }] } })).status, 400);
  assert.equal((await call('POST', '/api/races', { token: host, body: race })).status, 201);
  // A solo practice run is recorded but is not a win.
  assert.equal((await call('POST', '/api/races', { token: host, body: { ...race, results: [race.results[0]] } })).status, 201);

  const mine = (await call('GET', '/api/me/stats', { token: host })).data.characters[0];
  assert.deepEqual({ races: mine.races, finishes: mine.finishes, wins: mine.wins }, { races: 2, finishes: 2, wins: 1 });
  const board = (await call('GET', '/api/leaderboard')).data.leaderboard;
  assert.equal(board[0].name, 'Speedy');
  const slow = board.find((row) => row.name === 'Slowpoke');
  assert.deepEqual({ races: slow.races, finishes: slow.finishes, wins: slow.wins }, { races: 1, finishes: 1, wins: 0 });
  const recent = (await call('GET', '/api/races', { token: other })).data.races;
  assert.equal(recent[1].results.length, 3);
  assert.equal(recent[1].results[2].reason, '人物死亡');
});

test('playtime is bounded by real elapsed time', async () => {
  const token = await account('player9');
  assert.equal((await call('POST', '/api/play/heartbeat', { token })).status, 400);
  await call('POST', '/api/characters', { token, body: { name: 'Clock' } });
  assert.equal((await call('POST', '/api/play/heartbeat', { token })).data.credited, 0, 'first call only starts the clock');
  assert.equal((await call('POST', '/api/play/heartbeat', { token })).data.credited, 0, 'no time has passed');
  await db.run('UPDATE users SET last_play_at = last_play_at - 60 WHERE username_key = ?', ['player9']);
  assert.equal((await call('POST', '/api/play/heartbeat', { token })).data.credited, 60);
  await db.run('UPDATE users SET last_play_at = last_play_at - 5000 WHERE username_key = ?', ['player9']);
  assert.equal((await call('POST', '/api/play/heartbeat', { token })).data.credited, 0, 'a long gap is not credited');
  assert.equal((await call('GET', '/api/characters', { token })).data.characters[0].playSeconds, 60);
});

test('admin: ban signs the user out and blocks login', async () => {
  const adminToken = (await call('POST', '/api/auth/login', { body: { username: 'alice', password: 'correct horse' } })).data.token;
  const victim = await account('troll');
  assert.equal((await call('GET', '/api/admin/users', { token: victim })).status, 403);
  const users = (await call('GET', '/api/admin/users', { token: adminToken })).data.users;
  const id = users.find((u) => u.username === 'troll').id;
  assert.equal((await call('POST', `/api/admin/users/${id}/ban`, { token: adminToken, body: { banned: true } })).status, 200);
  assert.equal((await call('GET', '/api/me', { token: victim })).status, 401);
  assert.equal((await call('POST', '/api/auth/login', { body: { username: 'troll', password: 'correct horse' } })).status, 403);
  assert.equal((await call('POST', `/api/admin/users/${id}/ban`, { token: adminToken, body: { banned: false } })).status, 200);
  assert.equal((await call('POST', '/api/auth/login', { body: { username: 'troll', password: 'correct horse' } })).status, 200);
});

test('invite code and rate limiting', async () => {
  const limited = createApp({ ...config, inviteCode: 'letmein', authRateLimit: 3 }, db).listen(0, '127.0.0.1');
  await new Promise((resolve) => limited.once('listening', resolve));
  const url = `http://127.0.0.1:${limited.address().port}/api/auth/register`;
  const post = (body) => fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }).then((r) => r.status);
  assert.equal(await post({ username: 'invited1', password: 'correct horse' }), 403);
  assert.equal(await post({ username: 'invited1', password: 'correct horse', inviteCode: 'wrong' }), 403);
  assert.equal(await post({ username: 'invited1', password: 'correct horse', inviteCode: 'letmein' }), 201);
  assert.equal(await post({ username: 'invited2', password: 'correct horse', inviteCode: 'letmein' }), 429);
  await new Promise((resolve) => limited.close(resolve));
});

test('malformed requests get clean errors, never a 500', async () => {
  const bad = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{oops' });
  assert.equal(bad.status, 400);
  assert.equal((await call('POST', '/api/auth/login', { body: { username: 123, password: null } })).status, 400);
  assert.equal((await call('POST', '/api/auth/login')).status, 400);
  assert.equal((await call('GET', '/api/nope')).status, 404);
  const token = await account('edgecase');
  assert.equal((await call('DELETE', '/api/characters/abc', { token })).status, 404);
  assert.equal((await call('POST', '/api/rooms/xyz/heartbeat', { token, body: {} })).status, 404);
});
