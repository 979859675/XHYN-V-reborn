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
  const token = (await response.json()).token;
  await call('POST', '/api/characters', token, { name: `${name}C` });
  return token;
}
/** A fake game socket speaking the relay framing. */
async function endpoint(session, port) {
  const socket = dgram.createSocket('udp4');
  sockets.push(socket);
  await new Promise((resolve) => socket.bind(0, '127.0.0.1', resolve));
  const inbox = [];
  socket.on('message', (message) => inbox.push({
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
  return { inbox, raw, frame,
    keepAlive: () => raw(frame(1, '0.0.0.0', 0, '')),
    send: (dstIp, dstPort, payload) => raw(frame(0, dstIp, dstPort, payload)) };
}
const settle = () => new Promise((resolve) => setTimeout(resolve, 60));

before(async () => {
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'xhynv-relay-'));
  const config = load({ dbDriver: 'sqlite', sqliteFile: ':memory:', dataDir, inviteCode: '', authRateLimit: 1000, apiRateLimit: 100000, relayMaxMembers: 3, relayMaxKbps: 100 });
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
  assert.equal((await call('POST', `/api/rooms/${room.id}/bots`, host, { count: 4 })).status, 400);
  const set = await call('POST', `/api/rooms/${room.id}/bots`, host, { count: 2, mirror: true });
  assert.equal(set.status, 200);
  assert.equal(set.data.room.bots, 2);
  const beat = await call('POST', `/api/rooms/${room.id}/heartbeat`, host, { players: 1, debug: '等待本机进入故事模式 stats=1,0' });
  assert.deepEqual(beat.data, { ok: true, bots: 2, botMirror: true });
  const find = async (token) => (await call('GET', '/api/rooms', token)).data.rooms.find((r) => r.id === room.id);
  assert.match((await find(host)).debug, /stats=1,0/);
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
