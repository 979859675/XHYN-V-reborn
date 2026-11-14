// SPDX-License-Identifier: MIT
'use strict';
const assert = require('node:assert/strict');
const dgram = require('node:dgram');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test, before, after } = require('node:test');
const { load } = require('../src/config');
const { open } = require('../src/db');
const { createApp } = require('../src/app');
const { createRelay } = require('../src/relay');

let server, base, db, dataDir, relay, relayPort;
const sockets = [];

async function call(method, url, token, body) {
  const response = await fetch(base + url, { method, headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
    body: body === undefined ? undefined : JSON.stringify(body) });
  return { status: response.status, data: await response.json() };
}
async function player(name) {
  const response = await fetch(base + '/api/auth/register', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: name, password: 'correct horse' }) });
  const registered = await response.json();
  assert.ok(registered.token, `${name}: ${JSON.stringify(registered)}`);
  const token = registered.token;
  await call('POST', '/api/characters', token, { name: `${name}C` });
  return token;
}
/** A fake game socket speaking the relay framing. */
async function endpoint(session, port) {
  const socket = dgram.createSocket('udp4');
  sockets.push(socket);
  await new Promise((resolve) => socket.bind(0, '127.0.0.1', resolve));
  const inbox = [], control = [];
  socket.on('message', (message) => message[3] !== 0 ? control.push(message[3]) : inbox.push({
    from: `${message[4]}.${message[5]}.${message[6]}.${message[7]}:${message.readUInt16BE(8)}`, payload: message.subarray(10).toString(), magic: message.toString('latin1', 0, 2) }));
  const frame = (type, dstIp, dstPort, payload, token = session.token) => {
    const head = Buffer.alloc(20);
    head.write('XR', 'latin1'); head[2] = 1; head[3] = type;
    Buffer.from(token, 'hex').copy(head, 4);
    head.writeUInt16BE(port, 12);
    dstIp.split('.').forEach((part, i) => { head[14 + i] = Number(part); });
    head.writeUInt16BE(dstPort, 18);
    return Buffer.concat([head, Buffer.from(payload)]);
  };
  const raw = (buffer) => new Promise((resolve) => socket.send(buffer, relayPort, '127.0.0.1', resolve));
  return { inbox, control, raw, frame,
    keepAlive: () => raw(frame(1, '0.0.0.0', 0, '')),
    send: (dstIp, dstPort, payload) => raw(frame(0, dstIp, dstPort, payload)) };
}
const settle = () => new Promise((resolve) => setTimeout(resolve, 60));

before(async () => {
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'xhynv-relay-'));
  const config = load({ dbDriver: 'sqlite', sqliteFile: ':memory:', dataDir, inviteCode: '', authRateLimit: 1000, apiRateLimit: 100000, relayMaxMembers: 3, relayMaxKbps: 100, ffaTarget: 6, ffaBreakSeconds: 2 });
  db = await open(config);
  relay = createRelay(config);
  relayPort = await relay.start(0, '127.0.0.1');
  server = createApp(config, db, relay).listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(async () => {
  for (const socket of sockets) socket.close();
  await new Promise((resolve) => server.close(resolve));
  await relay.close();
  await db.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

test('host and members get addresses; packets reach only the addressed member', async () => {
  const hostToken = await player('relayhost'), aToken = await player('relaya'), bToken = await player('relayb');
  const hostSession = (await call('POST', '/api/relay/host', hostToken)).data.relay;
  assert.equal(hostSession.virtualIp, '10.77.0.1');
  assert.equal(hostSession.port, relayPort);
  assert.deepEqual((await call('POST', '/api/relay/host', hostToken)).data.relay, hostSession, 'asking again keeps the same session');
  const aSession = (await call('POST', '/api/relay/join', aToken, { networkId: hostSession.networkId })).data.relay;
  const bSession = (await call('POST', '/api/relay/join', bToken, { networkId: hostSession.networkId })).data.relay;
  assert.equal(aSession.virtualIp, '10.77.0.2');
  assert.equal(bSession.virtualIp, '10.77.0.3');
  assert.equal(aSession.hostIp, '10.77.0.1');
  assert.notEqual(aSession.token, bSession.token);

  const host = await endpoint(hostSession, 27945), a = await endpoint(aSession, 40001), b = await endpoint(bSession, 40002);
  await host.keepAlive();
  await settle();
  await a.send('10.77.0.1', 27945, 'hello from a');
  await b.send('10.77.0.1', 27945, 'hello from b');
  await settle();
  assert.deepEqual(host.inbox.map((m) => `${m.from} ${m.payload}`).sort(), ['10.77.0.2:40001 hello from a', '10.77.0.3:40002 hello from b']);
  assert.equal(host.inbox[0].magic, 'XR');

  await host.send('10.77.0.2', 40001, 'welcome a');
  await settle();
  assert.deepEqual(a.inbox.map((m) => `${m.from} ${m.payload}`), ['10.77.0.1:27945 welcome a']);
  assert.equal(b.inbox.length, 0, 'b does not see a\'s traffic');

  await host.send('255.255.255.255', 40002, 'to everyone on 40002');
  await host.send('10.77.0.9', 27945, 'nobody home');
  await host.send('10.77.0.3', 5555, 'no such socket');
  await host.send('10.77.0.1', 27945, 'to myself');
  await settle();
  assert.deepEqual(b.inbox.map((m) => m.payload), ['to everyone on 40002']);
  assert.equal(a.inbox.length, 1, 'broadcast only reaches sockets on that port');
  assert.equal(host.inbox.length, 2, 'nothing is echoed back to the sender');
});

test('forged, malformed and oversized datagrams are ignored', async () => {
  const hostToken = await player('forgehost'), guestToken = await player('forgeguest');
  const hostSession = (await call('POST', '/api/relay/host', hostToken)).data.relay;
  const guestSession = (await call('POST', '/api/relay/join', guestToken, { networkId: hostSession.networkId })).data.relay;
  const host = await endpoint(hostSession, 27945), guest = await endpoint(guestSession, 40001);
  await host.keepAlive();
  await guest.raw(guest.frame(0, '10.77.0.1', 27945, 'bad token', 'ffffffffffffffff'));
  await guest.raw(Buffer.from('XR'));
  await guest.raw(Buffer.concat([Buffer.from('ZZ'), guest.frame(0, '10.77.0.1', 27945, 'x').subarray(2)]));
  await guest.raw(guest.frame(0, '10.77.0.1', 27945, 'x'.repeat(1401)));
  await settle();
  assert.equal(host.inbox.length, 0);
  assert.deepEqual(host.control, [1], 'a keep-alive is acknowledged');
  assert.deepEqual(guest.control, [2], 'an unknown seat is told so, once per datagram');
  await guest.send('10.77.0.1', 27945, 'x'.repeat(1400));
  await settle();
  assert.equal(host.inbox.length, 1, 'a full-size game packet still passes');
});

test('networks are isolated, limited in size, and close with the room owner', async () => {
  const oneToken = await player('isoone'), twoToken = await player('isotwo'), guestToken = await player('isoguest');
  const one = (await call('POST', '/api/relay/host', oneToken)).data.relay;
  const two = (await call('POST', '/api/relay/host', twoToken)).data.relay;
  assert.notEqual(one.networkId, two.networkId);
  const hostOne = await endpoint(one, 27945), hostTwo = await endpoint(two, 27945);
  await hostOne.keepAlive(); await hostTwo.keepAlive();
  const guestSession = (await call('POST', '/api/relay/join', guestToken, { networkId: one.networkId })).data.relay;
  const guest = await endpoint(guestSession, 40001);
  await guest.send('10.77.0.1', 27945, 'for network one');
  await settle();
  assert.equal(hostOne.inbox.length, 1);
  assert.equal(hostTwo.inbox.length, 0, 'the same virtual address in another network is a different player');

  for (const name of ['isofill1']) assert.equal((await call('POST', '/api/relay/join', await player(name), { networkId: one.networkId })).status, 200);
  assert.equal((await call('POST', '/api/relay/join', await player('isofill2'), { networkId: one.networkId })).status, 409, 'limit of 3 members');
  assert.equal((await call('POST', '/api/relay/join', guestToken, { networkId: one.networkId })).data.relay.token, guestSession.token, 'rejoining keeps the seat');
  assert.equal((await call('POST', '/api/relay/join', guestToken, { networkId: 999999 })).status, 404);
  assert.equal((await call('POST', '/api/relay/join', guestToken, { networkId: 'x' })).status, 400);

  relay.closeFor((await call('GET', '/api/me', oneToken)).data.user.id);
  await guest.send('10.77.0.1', 27945, 'after close');
  await settle();
  assert.equal(hostOne.inbox.length, 1, 'tokens of a closed network stop working');
  assert.equal((await call('POST', '/api/relay/join', guestToken, { networkId: one.networkId })).status, 404);
});

test('a member cannot exceed the bandwidth cap', async () => {
  const hostToken = await player('caphost'), guestToken = await player('capguest');
  const hostSession = (await call('POST', '/api/relay/host', hostToken)).data.relay;
  const guestSession = (await call('POST', '/api/relay/join', guestToken, { networkId: hostSession.networkId })).data.relay;
  const host = await endpoint(hostSession, 27945), guest = await endpoint(guestSession, 40001);
  await host.keepAlive();
  for (let i = 0; i < 40; i++) await guest.send('10.77.0.1', 27945, 'y'.repeat(1000));   // 40 kB at once against 12.5 kB/s
  await settle();
  assert.ok(host.inbox.length >= 10 && host.inbox.length <= 16, `about one second's worth passes, got ${host.inbox.length}`);
});

test('relay rooms are flagged in the lobby; the relay needs a character and can be switched off', async () => {
  const hostToken = await player('lobbyhost'), guestToken = await player('lobbyguest');
  const session = (await call('POST', '/api/relay/host', hostToken)).data.relay;
  await call('POST', '/api/rooms', hostToken, { hostAddress: '10.77.0.1', roomCode: '123456' });
  const lan = await player('lanhost');
  await call('POST', '/api/rooms', lan, { hostAddress: '192.168.1.5', roomCode: '654321' });
  const fake = await player('fakehost');   // claims the relay address without having a relay network
  await call('POST', '/api/rooms', fake, { hostAddress: '10.77.0.1', roomCode: '111111' });
  const rooms = (await call('GET', '/api/rooms', guestToken)).data.rooms;
  assert.equal(rooms.find((r) => r.hostName === 'lobbyhostC').relayNetwork, session.networkId);
  assert.equal(rooms.find((r) => r.hostName === 'lanhostC').relayNetwork, null);
  assert.equal(rooms.find((r) => r.hostName === 'fakehostC').relayNetwork, null);

  const response = await fetch(base + '/api/auth/register', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: 'nochar', password: 'correct horse' }) });
  assert.equal((await call('POST', '/api/relay/host', (await response.json()).token)).status, 400);
  assert.equal((await fetch(base + '/api/relay/host', { method: 'POST' })).status, 401);
  assert.equal((await (await fetch(base + '/api/info')).json()).relayPort, relayPort);

  const off = createApp(load({ dbDriver: 'sqlite', sqliteFile: ':memory:', dataDir, authRateLimit: 1000, apiRateLimit: 100000 }), db).listen(0, '127.0.0.1');
  await new Promise((resolve) => off.once('listening', resolve));
  const reply = await fetch(`http://127.0.0.1:${off.address().port}/api/relay/host`, { method: 'POST', headers: { authorization: `Bearer ${hostToken}` } });
  assert.equal(reply.status, 503);
  await new Promise((resolve) => off.close(resolve));
});

test('test bots: host or admin sets the count, the host reads it on heartbeat and gets bot seats', async () => {
  const host = await player('bothost');
  const other = await player('botother');
  assert.equal((await call('POST', '/api/relay/bot', host, { index: 1 })).status, 404);
  const mine = (await call('POST', '/api/relay/host', host, {})).data.relay;
  const lan = (await call('POST', '/api/rooms', host, { hostAddress: '192.168.1.5', roomCode: '123456' })).data.room;
  assert.equal((await call('POST', `/api/rooms/${lan.id}/bots`, host, { count: 1 })).status, 400);
  const room = (await call('POST', '/api/rooms', host, { hostAddress: '10.77.0.1', roomCode: '123456' })).data.room;
  assert.equal(room.relayNetwork, mine.networkId);
  assert.equal((await call('POST', `/api/rooms/${room.id}/bots`, other, { count: 2 })).status, 403);
  assert.equal((await call('POST', `/api/rooms/${room.id}/bots`, host, { count: 10 })).status, 400);
  assert.equal((await call('POST', `/api/rooms/${room.id}/bots`, host, { fill: true })).data.room.bots, room.maxPlayers - 1, 'fill takes every free seat');
  const set = await call('POST', `/api/rooms/${room.id}/bots`, host, { count: 2, mirror: true });
  assert.equal(set.status, 200);
  assert.equal(set.data.room.bots, 2);
  const beat = await call('POST', `/api/rooms/${room.id}/heartbeat`, host, { players: 1, debug: '等待本机进入故事模式 stats=1,0' });
  assert.deepEqual(beat.data, { ok: true, bots: 2, botMirror: true });
  const find = async (token) => (await call('GET', '/api/rooms', token)).data.rooms.find((r) => r.id === room.id);
  assert.match((await find(host)).debug, /stats=1,0/);
  assert.equal((await find(host)).relayReached, false);
  assert.equal((await find(other)).relayReached, undefined);
  assert.equal((await find(other)).debug, undefined);
  const one = (await call('POST', '/api/relay/bot', host, { index: 1 })).data.relay;
  const again = (await call('POST', '/api/relay/bot', host, { index: 1 })).data.relay;
  const two = (await call('POST', '/api/relay/bot', host, { index: 2 })).data.relay;
  assert.equal(one.networkId, mine.networkId);
  assert.equal(one.token, again.token);
  assert.equal(one.virtualIp, '10.77.0.2');
  assert.equal(two.virtualIp, '10.77.0.3');
  assert.notEqual(one.token, mine.token);
});

test('team deathmatch: teams, deaths score for the other side, target ends it, result is stored', async () => {
  const hostToken = await player('dmhost'), guestToken = await player('dmguest'), outsider = await player('dmout');
  const net = (await call('POST', '/api/relay/host', hostToken)).data.relay.networkId;
  await call('POST', '/api/relay/join', guestToken, { networkId: net });
  const body = (more = {}) => ({ networkId: net, ...more });

  assert.equal((await call('POST', '/api/match/state', outsider, body())).status, 403, 'only people in the room see it');
  assert.equal((await call('POST', '/api/match/state', guestToken, body())).data.match, null);
  assert.equal((await call('POST', '/api/match/create', guestToken, body({ target: 2 }))).status, 403, 'only the host creates');
  const created = (await call('POST', '/api/match/create', hostToken, body({ target: 2, minutes: 5 }))).data;
  assert.equal(created.host, true);
  assert.equal(created.match.state, 'lobby');

  assert.equal((await call('POST', '/api/match/start', hostToken, body())).status, 400, 'both teams need a player');
  await call('POST', '/api/match/team', hostToken, body({ team: 'red' }));
  assert.equal((await call('POST', '/api/match/team', guestToken, body({ team: 'green' }))).status, 400);
  const joined = (await call('POST', '/api/match/team', guestToken, body({ team: 'blue' }))).data.match;
  assert.deepEqual(joined.players.map((p) => `${p.name}:${p.team}`).sort(), ['dmguestC:blue', 'dmhostC:red']);
  assert.equal((await call('POST', '/api/match/start', guestToken, body())).status, 403);
  assert.equal((await call('POST', '/api/match/death', guestToken, body())).data.match.blue, 0, 'deaths before the start do not count');
  assert.equal((await call('POST', '/api/match/start', hostToken, body())).data.match.state, 'running');
  assert.equal((await call('POST', '/api/match/team', guestToken, body({ team: 'red' }))).status, 409, 'no switching mid-match');

  await call('POST', '/api/match/state', guestToken, body({ slot: 2 }));
  await call('POST', '/api/match/state', hostToken, body({ slot: 1, hits: [2] }));
  let state = (await call('POST', '/api/match/death', guestToken, body({ slot: 2, debug: 'aa' }))).data.match;
  assert.deepEqual([state.red, state.blue], [1, 0]);
  assert.equal(state.players.find((p) => p.name === 'dmhostC').kills, 1, 'the last one to damage the victim gets the kill');
  assert.equal(state.feed[0].killer, 'dmhostC');
  state = (await call('POST', '/api/match/death', guestToken, body())).data.match;
  assert.deepEqual([state.red, state.blue], [1, 0], 'a second report right away is the same death');
  state = (await call('POST', '/api/match/death', hostToken, body())).data.match;
  assert.deepEqual([state.red, state.blue], [1, 1]);
  state = (await call('POST', '/api/match/state', guestToken, body({ hits: [1] }))).data.match;
  assert.equal(state.players.find((p) => p.name === 'dmguestC').kills, 1, 'a damage report that arrives just after the death still counts');
  await new Promise((resolve) => setTimeout(resolve, 3100));
  state = (await call('POST', '/api/match/death', hostToken, body())).data.match;
  assert.deepEqual([state.red, state.blue, state.state, state.winner, state.reason], [1, 2, 'finished', 'blue', 'target']);

  const stored = (await call('GET', '/api/matches', outsider)).data.matches[0];
  assert.deepEqual([stored.red, stored.blue, stored.winner, stored.players.length], [1, 2, 'blue', 2]);
  const board = (await call('GET', '/api/leaderboard', outsider)).data.leaderboard;
  const guestRow = board.find((row) => row.name === 'dmguestC'), hostRow = board.find((row) => row.name === 'dmhostC');
  assert.deepEqual([guestRow.dmMatches, guestRow.dmWins, guestRow.dmDeaths], [1, 1, 1]);
  assert.deepEqual([hostRow.dmWins, hostRow.dmKills, hostRow.dmDeaths], [0, 1, 2]);
  assert.equal(guestRow.dmKills, 1);
  assert.equal((await call('POST', '/api/match/create', hostToken, body({ target: 5 }))).data.match.state, 'lobby', 'a finished match can be replaced');
});

test('an administrator can set a new password for a user', async () => {
  const userToken = await player('forgetful'), adminToken = await player('pwadmin');
  await db.run("UPDATE users SET is_admin = 1 WHERE username = 'pwadmin'");
  const target = await db.get("SELECT id FROM users WHERE username = 'forgetful'");
  assert.equal((await call('POST', `/api/admin/users/${target.id}/password`, userToken, { newPassword: 'another secret' })).status, 403);
  assert.equal((await call('POST', `/api/admin/users/${target.id}/password`, adminToken, { newPassword: 'short' })).status, 400);
  assert.equal((await call('POST', `/api/admin/users/${target.id}/password`, adminToken, { newPassword: 'another secret' })).status, 200);
  assert.equal((await call('GET', '/api/me', userToken)).status, 401, 'the old sign-in stops working');
  const login = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: 'forgetful', password: 'another secret' }) });
  assert.equal(login.status, 200);
});

test('team deathmatch with bots: they fill the smaller team, go down after enough hits, and can be knocked down from the web page', async () => {
  const hostToken = await player('tdmhost'), admin = await player('botadmin'), stranger = await player('botstranger');
  await db.run("UPDATE users SET is_admin = 1 WHERE username = 'botadmin'");
  const opened = await call('POST', '/api/relay/host', hostToken);
  assert.equal(opened.status, 200, JSON.stringify(opened.data));
  const net = opened.data.relay.networkId;
  const bots = [{ index: 1, slot: 2 }, { index: 2, slot: 3 }, { index: 3, slot: 4 }];
  const body = (more = {}) => ({ networkId: net, slot: 1, bots, ...more });

  let state = (await call('POST', '/api/match/create', hostToken, body({ target: 3, botHits: 2, record: false }))).data.match;
  assert.deepEqual([state.botHits, state.record], [2, false]);
  assert.deepEqual(state.players.map((p) => `${p.name}:${p.team}`), ['人机1:red', '人机2:blue', '人机3:red']);
  state = (await call('POST', '/api/match/team', hostToken, body({ team: 'red' }))).data.match;
  assert.deepEqual(state.players.map((p) => `${p.name}:${p.team}`).sort(), ['tdmhostC:red', '人机1:blue', '人机2:red', '人机3:blue'], 'bots move to keep the teams even');
  state = (await call('POST', '/api/match/state', hostToken, { networkId: net, slot: 1, bots: bots.slice(0, 2) })).data.match;
  assert.equal(state.players.length, 3, 'a bot that left the room leaves the match');
  assert.equal((await call('POST', '/api/match/start', hostToken, body({ bots: bots.slice(0, 2) }))).data.match.state, 'running');

  // 人机1 is blue with member number 2, 人机2 is red (the host's own team) with member number 3.
  state = (await call('POST', '/api/match/state', hostToken, body({ hits: [2, 3], counts: [1, 5] }))).data.match;
  assert.deepEqual([state.red, state.blue], [0, 0], 'one hit is not enough, and hitting your own bot never scores');
  state = (await call('POST', '/api/match/state', hostToken, body({ hits: [2] }))).data.match;
  assert.deepEqual([state.red, state.blue], [1, 0]);
  assert.equal(state.players.find((p) => p.name === 'tdmhostC').kills, 1);
  assert.equal(state.feed[0].killer, 'tdmhostC');

  const victim = state.players.find((p) => p.name === '人机2'), me = state.players.find((p) => p.name === 'tdmhostC');
  assert.equal((await call('POST', '/api/match/simulate', stranger, { networkId: net, victim: victim.id })).status, 403);
  state = (await call('POST', '/api/match/simulate', admin, { networkId: net, victim: victim.id })).data.match;
  assert.deepEqual([state.red, state.blue], [1, 1]);
  state = (await call('POST', '/api/match/simulate', hostToken, { networkId: net, victim: me.id })).data.match;
  assert.deepEqual([state.red, state.blue], [1, 2]);
  state = (await call('POST', '/api/match/simulate', hostToken, { networkId: net, victim: me.id })).data.match;
  assert.deepEqual([state.blue, state.state, state.winner], [3, 'finished', 'blue']);
  const before = (await call('GET', '/api/matches', admin)).data.matches.filter((m) => m.hostName === 'tdmhostC');
  assert.equal(before.length, 0, 'a match marked as not recorded is not stored');
});

test('public free-for-all room: first in hosts, others join, kills are stored and ranked', async () => {
  const a = await player('pubone');
  const b = await player('pubtwo');
  const c = await player('pubthree');
  const admin = await player('pubadmin');
  await db.run("UPDATE users SET is_admin = 1 WHERE username = 'pubadmin'");
  const enter = (token) => call('POST', '/api/public/enter', token, {});

  const first = await enter(a);
  assert.equal(first.data.action, 'host');
  assert.equal(first.data.number, 1);
  assert.equal((await enter(b)).data.action, 'wait', 'nobody opens a second room while the first is being opened');
  const net = (await call('POST', '/api/relay/host', a, {})).data.relay;
  const room = (await call('POST', '/api/rooms', a, { hostAddress: '10.77.0.1', roomCode: '654321', public: true })).data.room;
  assert.equal(room.public, 1);
  assert.equal(room.title, '公共对战房 1');
  // A room made without the promise is an ordinary one.
  assert.equal((await call('POST', '/api/rooms', c, { hostAddress: '10.77.0.1', roomCode: '111111', public: true })).data.room.public, 0);
  await call('DELETE', `/api/rooms/${(await call('GET', '/api/rooms', c)).data.rooms.find((r) => r.hostName === 'pubthreeC').id}`, c);

  const second = await enter(b);
  assert.equal(second.data.action, 'join');
  assert.equal(second.data.room.id, room.id);
  assert.equal((await call('POST', `/api/rooms/${room.id}/bots`, a, { count: 1 })).status, 400, 'no bots in the public room');
  const list = (await call('GET', '/api/rooms', b)).data;
  assert.equal(list.publicOpen, true);
  assert.equal(list.rooms[0].id, room.id, 'public rooms are listed first');

  const state = (token, extra = {}) => call('POST', '/api/ffa/state', token, { networkId: net.networkId, ...extra });
  assert.equal((await state(b)).status, 403, 'not in the room yet');
  await call('POST', '/api/relay/join', b, { networkId: net.networkId });
  await call('POST', '/api/relay/join', c, { networkId: net.networkId });
  assert.equal((await call('POST', '/api/match/create', a, { networkId: net.networkId })).status, 409, 'no team match in the public room');
  assert.equal((await call('POST', '/api/match/state', b, { networkId: net.networkId })).data.public, 1);
  const hosted = await state(a, { slot: 1 });
  assert.equal(hosted.data.host, true);
  assert.equal(hosted.data.alive, true);
  await state(b, { slot: 2 });
  await state(c, { slot: 3 });

  // b damages a, a dies: b's kill. c dies alone: nobody's kill.
  await state(b, { slot: 2, hits: [1] });
  const died = await state(a, { slot: 1, death: true });
  assert.equal(died.data.me.deaths, 1);
  assert.deepEqual(died.data.feed.map((f) => [f.killer, f.victim]), [['pubtwoC', 'puboneC']]);
  assert.equal((await state(a, { slot: 1, death: true })).data.me.deaths, 1, 'one death per respawn');
  await state(c, { slot: 3, death: true });
  // Damage reported just after the death still counts for it.
  const late = await state(a, { slot: 1, hits: [3] });
  assert.deepEqual(late.data.players.map((p) => [p.name, p.kills, p.deaths]), [['pubtwoC', 1, 0], ['puboneC', 1, 1], ['pubthreeC', 0, 1]]);
  assert.equal(late.data.me.totalKills, 1);

  for (const range of ['day', 'week', 'all']) {
    const board = (await call('GET', `/api/ffa/board?range=${range}`, a)).data;
    assert.deepEqual(board.board.map((r) => [r.name, r.kills, r.deaths]), [['pubtwoC', 1, 0], ['puboneC', 1, 1], ['pubthreeC', 0, 1]], range);
  }
  assert.equal((await call('GET', '/api/leaderboard', a)).data.leaderboard.find((r) => r.name === 'pubtwoC').ffaKills, 1);
  assert.equal((await call('GET', '/api/admin/ffa', a)).status, 403);
  const log = (await call('GET', '/api/admin/ffa', admin)).data;
  assert.equal(log.kills.length, 2);
  assert.equal(log.players.length, 3);

  // The host leaves: members are told, and the next one in becomes the host.
  await call('DELETE', `/api/rooms/${room.id}`, a);
  const gone = await state(b, { slot: 2 });
  assert.equal(gone.data.alive, false);
  assert.equal((await enter(b)).data.action, 'host');

  const victim = log.players.find((r) => r.name === 'pubthreeC').characterId;
  assert.equal((await call('POST', '/api/admin/ffa/reset', admin, { characterId: victim })).data.removed, 1);
  assert.equal((await call('POST', '/api/admin/ffa/reset', admin, { all: true })).data.removed, 1);
  assert.equal((await call('GET', '/api/ffa/board', a)).data.board.length, 0);
  // b opens the room it was promised and closes it again, so nothing is left pending.
  await call('POST', '/api/relay/host', b, {});
  const reopened = (await call('POST', '/api/rooms', b, { hostAddress: '10.77.0.1', roomCode: '333333', public: true })).data.room;
  assert.equal(reopened.public, 1);
  await call('DELETE', `/api/rooms/${reopened.id}`, b);
});

test('free-for-all rounds, kill streaks with bounties, and season rank points', async () => {
  const a = await player('rndone'), b = await player('rndtwo'), c = await player('rndthree');
  const admin = await player('rndadmin');
  await db.run("UPDATE users SET is_admin = 1 WHERE username = 'rndadmin'");
  assert.equal((await call('POST', '/api/public/enter', a, {})).data.action, 'host');
  const net = (await call('POST', '/api/relay/host', a, {})).data.relay;
  await call('POST', '/api/rooms', a, { hostAddress: '10.77.0.1', roomCode: '222222', public: true });
  await call('POST', '/api/relay/join', b, { networkId: net.networkId });
  await call('POST', '/api/relay/join', c, { networkId: net.networkId });
  const state = async (token, extra = {}) => (await call('POST', '/api/ffa/state', token, { networkId: net.networkId, ...extra })).data;
  const wait = () => new Promise((resolve) => setTimeout(resolve, 5100));
  const first = await state(a, { slot: 1 });
  assert.deepEqual([first.round.number, first.round.state, first.round.target], [1, 'running', 6]);
  assert.equal(first.rank.name, '青铜');
  await state(b, { slot: 2 });
  await state(c, { slot: 3 });

  // a knocks b down three times: a streak of three, announced, and a bounty on a.
  let seen;
  for (let i = 0; i < 3; i++) {
    await state(a, { slot: 1, hits: [2] });
    seen = await state(b, { slot: 2, death: true });
    if (i < 2) await wait();
  }
  const holder = seen.players.find((p) => p.name === 'rndoneC');
  assert.deepEqual([holder.kills, holder.score, holder.streak, holder.bounty], [3, 3, 3, true]);
  assert.ok(seen.events.some((e) => e.text.includes('rndoneC') && e.text.includes('连杀 3 次')), JSON.stringify(seen.events));
  assert.equal(seen.rank.points, 0, 'points never go below zero');

  // c ends the streak: two points for one kill.
  await state(c, { slot: 3, hits: [1] });
  seen = await state(a, { slot: 1, death: true });
  const hunter = seen.players.find((p) => p.name === 'rndthreeC');
  assert.deepEqual([hunter.kills, hunter.score], [1, 2]);
  assert.equal(seen.me.streak, 0);
  assert.equal(seen.players.find((p) => p.name === 'rndoneC').bounty, false);
  assert.ok(seen.events.some((e) => e.text.includes('rndthreeC') && e.text.includes('悬赏')));
  assert.equal((await state(c, { slot: 3 })).rank.points, 4);
  assert.equal(seen.rank.points, 5, 'three kills at 2, one death at -1');

  // a reaches the target of 6 points: the round ends, and the first three get their prizes.
  for (let i = 0; i < 3; i++) {
    await wait();
    await state(a, { slot: 1, hits: [2] });
    seen = await state(b, { slot: 2, death: true });
  }
  assert.deepEqual([seen.round.state, seen.round.winner, seen.round.reason], ['ended', 'rndoneC', 'target']);
  assert.deepEqual(seen.round.top.map((p) => [p.name, p.score]), [['rndoneC', 6], ['rndthreeC', 2], ['rndtwoC', 0]]);
  assert.equal((await state(a, { slot: 1 })).rank.points, 5 + 6 + 25);
  assert.equal((await state(c, { slot: 3 })).rank.points, 4 + 15);
  // Nothing counts in the break; then the next round starts from zero.
  await state(c, { slot: 3, hits: [1] });
  assert.equal((await state(a, { slot: 1, death: true })).me.deaths, 1);
  await new Promise((resolve) => setTimeout(resolve, 2100));
  seen = await state(a, { slot: 1 });
  assert.deepEqual([seen.round.number, seen.round.state, seen.me.kills, seen.me.score], [2, 'running', 0, 0]);
  assert.equal(seen.me.totalKills, 6);

  const board = (await call('GET', '/api/season/board', a)).data;
  assert.deepEqual(board.board.filter((r) => r.name.startsWith('rnd')).slice(0, 2).map((r) => [r.name, r.points, r.kills, r.wins, r.rank]), [['rndoneC', 36, 6, 1, '青铜'], ['rndthreeC', 19, 1, 0, '青铜']]);
  assert.equal(board.ranks.length, 7);
  assert.equal((await call('GET', '/api/me/rank', a)).data.rank.next, 100);

  assert.equal((await call('POST', '/api/admin/season/end', a)).status, 403);
  const ended = (await call('POST', '/api/admin/season/end', admin)).data;
  assert.equal(ended.season.id, ended.ended + 1);
  const after = (await call('GET', '/api/season/board', a)).data;
  assert.equal(after.board.length, 0);
  assert.equal(after.last.top[0].name, 'rndoneC');
  assert.equal((await state(a, { slot: 1 })).rank.points, 0);
});

test('team deathmatch: ending a streak scores double and both sides get rank points', async () => {
  const hostToken = await player('bthost'), guestToken = await player('btguest');
  const net = (await call('POST', '/api/relay/host', hostToken)).data.relay.networkId;
  await call('POST', '/api/relay/join', guestToken, { networkId: net });
  const body = (more = {}) => ({ networkId: net, ...more });
  await call('POST', '/api/match/create', hostToken, body({ target: 5, minutes: 5 }));
  await call('POST', '/api/match/team', hostToken, body({ team: 'red' }));
  await call('POST', '/api/match/team', guestToken, body({ team: 'blue' }));
  await call('POST', '/api/match/start', hostToken, body());
  await call('POST', '/api/match/state', guestToken, body({ slot: 2 }));
  let state;
  for (let i = 0; i < 3; i++) {
    if (i) await new Promise((resolve) => setTimeout(resolve, 5100));
    await call('POST', '/api/match/state', hostToken, body({ slot: 1, hits: [2] }));
    state = (await call('POST', '/api/match/death', guestToken, body({ slot: 2 }))).data.match;
  }
  assert.deepEqual([state.red, state.blue], [3, 0]);
  assert.equal(state.players.find((p) => p.name === 'bthostC').bounty, true);
  assert.ok(state.events.some((e) => e.text.includes('连杀 3 次')));
  await call('POST', '/api/match/state', guestToken, body({ slot: 2, hits: [1] }));
  state = (await call('POST', '/api/match/death', hostToken, body({ slot: 1 }))).data.match;
  assert.deepEqual([state.red, state.blue], [3, 2], 'the bounty is worth two points');
  const done = (await call('POST', '/api/match/end', hostToken, body())).data;
  assert.equal(done.match.winner, 'red');
  assert.equal(done.rank.points, 20 + 3 * 2 - 1);
  assert.equal((await call('POST', '/api/match/state', guestToken, body())).data.rank.points, 2 * 2 - 3);
});

test('a room made as a free-for-all keeps its name, is listed with its players and runs the free-for-all', async () => {
  const a = await player('soloone'), b = await player('solotwo');
  const net = (await call('POST', '/api/relay/host', a, {})).data.relay;
  const room = (await call('POST', '/api/rooms', a, { hostAddress: '10.77.0.1', roomCode: '444444', mode: 'ffa', title: 'solooneC 的个人战房', maxPlayers: 10 })).data.room;
  assert.equal(room.title, 'solooneC 的个人战房');
  assert.ok(room.public > 0);
  await call('POST', `/api/rooms/${room.id}/heartbeat`, a, { players: 2 });
  const listed = (await call('GET', '/api/rooms', b)).data.rooms.find((r) => r.id === room.id);
  assert.deepEqual([listed.public, listed.players, listed.maxPlayers], [room.public, 2, 10]);
  await call('POST', '/api/relay/join', b, { networkId: net.networkId });
  const state = (await call('POST', '/api/ffa/state', b, { networkId: net.networkId, slot: 2 })).data;
  assert.equal(state.round.state, 'running');
  assert.equal((await call('POST', '/api/match/create', a, { networkId: net.networkId })).status, 409);
  // Without the mode it is an ordinary room.
  const plain = (await call('POST', '/api/rooms', a, { hostAddress: '10.77.0.1', roomCode: '444444', title: 'solooneC 的赛车房' })).data.room;
  assert.equal(plain.public, 0);
  await call('DELETE', `/api/rooms/${plain.id}`, a);
});
