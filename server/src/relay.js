// SPDX-License-Identifier: MIT
'use strict';
/**
 * UDP relay: a virtual LAN for players who are not on the same network.
 *
 * Every member of a relay network gets a virtual IPv4 address (the host is always 10.77.0.1).
 * The game client wraps each datagram it would have sent on the LAN and sends it here; the relay
 * forwards it to the member that owns the destination address. The payload is never inspected.
 *
 *   client -> relay   "XR" 1 type  token[8]  srcPort[2]  dstIp[4]  dstPort[2]  payload
 *   relay  -> client  "XR" 1 0     srcIp[4]  srcPort[2]  payload
 *
 * type 0 carries a payload; type 1 is a keep-alive that only refreshes the sender's address.
 * Networks live in memory: they are short-lived and a restart simply makes clients reconnect.
 */
const crypto = require('node:crypto');
const dgram = require('node:dgram');

const HOST_IP = '10.77.0.1';
const PREFIX = '10.77.0.';
const BROADCASTS = new Set(['255.255.255.255', '10.77.0.255']);
const UP_HEADER = 20, DOWN_HEADER = 10, MAX_PAYLOAD = 1400, MAX_PORTS = 4;

// Replies to the phone. Same header as a forwarded packet, with a type the game socket never delivers.
const ACK = Buffer.from([0x58, 0x52, 1, 1, 0, 0, 0, 0, 0, 0]);
const REJECT = Buffer.from([0x58, 0x52, 1, 2, 0, 0, 0, 0, 0, 0]);

function createRelay(config) {
  const idleMs = (config.relayIdleSeconds || 600) * 1000;
  const maxMembers = config.relayMaxMembers || 10;
  const bytesPerSecond = (config.relayMaxKbps || 6000) * 125;
  const networks = new Map();   // id -> network
  const byOwner = new Map();    // user id -> network
  const byToken = new Map();    // hex token -> member
  const stats = { received: 0, forwarded: 0, dropped: 0, unknown: 0 };
  let socket = null, timer = null, nextId = 1, port = 0;

  const now = () => Date.now();

  function remove(network) {
    networks.delete(network.id);
    if (byOwner.get(network.ownerId) === network) byOwner.delete(network.ownerId);
    for (const member of network.members.values()) byToken.delete(member.token);
  }

  function sweep() {
    const cutoff = now() - idleMs;
    for (const network of [...networks.values()]) if (network.activeAt < cutoff) remove(network);
  }

  function addMember(network, userId, last) {
    const member = { network, userId, ip: PREFIX + last, token: crypto.randomBytes(8).toString('hex'),
      endpoints: new Map(), allowance: bytesPerSecond, refilledAt: now() };
    network.members.set(userId, member);
    byToken.set(member.token, member);
    return member;
  }

  const describe = (member) => ({ networkId: member.network.id, token: member.token, virtualIp: member.ip, hostIp: HOST_IP, port });

  /** The caller's own network, created on first use. Calling again keeps it alive and returns the same token. */
  function host(userId) {
    sweep();
    let network = byOwner.get(userId);
    if (!network) {
      network = { id: nextId++, ownerId: userId, members: new Map(), activeAt: now() };
      networks.set(network.id, network);
      byOwner.set(userId, network);
      addMember(network, userId, 1);
    }
    network.activeAt = now();
    return describe(network.members.get(userId));
  }

  /** Joins someone else's network. Returns null if it is gone, 'full' if there is no free address. */
  function join(userId, networkId) {
    sweep();
    const network = networks.get(networkId);
    if (!network) return null;
    let member = network.members.get(userId);
    if (!member) {
      if (network.members.size >= maxMembers) return 'full';
      const used = new Set([...network.members.values()].map((m) => m.ip));
      let last = 2;
      while (used.has(PREFIX + last)) last++;
      member = addMember(network, userId, last);
    }
    return describe(member);
  }

  /**
   * An extra member of the caller's own network, for a test bot that runs on the host's phone.
   * Returns null without a network, 'full' if there is no free address. Asking again returns the same member.
   */
  function bot(ownerId, index) {
    const network = byOwner.get(ownerId);
    if (!network) return null;
    network.activeAt = now();
    return join(`bot:${ownerId}:${index}`, network.id);
  }

  /** Id of the live network owned by this user, or null. */
  function networkOf(userId) {
    const network = byOwner.get(userId);
    return network && network.activeAt >= now() - idleMs ? network.id : null;
  }

  /** Whether any UDP datagram from the owner's phone has reached this relay: null without a network. */
  function reached(userId) {
    const network = byOwner.get(userId);
    if (!network) return null;
    const member = network.members.get(userId);
    return !!member && member.endpoints.size > 0;
  }

  function closeFor(userId) {
    const network = byOwner.get(userId);
    if (network) remove(network);
  }

  function ipText(buffer, at) { return `${buffer[at]}.${buffer[at + 1]}.${buffer[at + 2]}.${buffer[at + 3]}`; }

  function deliver(target, dstPort, packet) {
    const endpoint = target.endpoints.get(dstPort);
    if (!endpoint) { stats.dropped++; return; }
    socket.send(packet, endpoint.port, endpoint.address);
    stats.forwarded++;
  }

  function onMessage(message, remote) {
    stats.received++;
    if (message.length < UP_HEADER || message.length > UP_HEADER + MAX_PAYLOAD
      || message[0] !== 0x58 || message[1] !== 0x52 || message[2] !== 1) { stats.dropped++; return; }
    const member = byToken.get(message.toString('hex', 4, 12));
    if (!member) {
      // Tell the phone its seat is gone (the server was restarted, or the network expired) so it asks for a new one.
      stats.dropped++; stats.unknown++;
      socket.send(REJECT, remote.port, remote.address);
      return;
    }
    const time = now();
    const srcPort = message.readUInt16BE(12);
    let endpoint = member.endpoints.get(srcPort);
    if (!endpoint) {
      if (member.endpoints.size >= MAX_PORTS) {
        // Forget the socket that has been silent the longest (an old discovery or a previous session).
        let oldest = null;
        for (const [key, value] of member.endpoints) if (!oldest || value.seenAt < oldest[1].seenAt) oldest = [key, value];
        member.endpoints.delete(oldest[0]);
      }
      endpoint = {};
      member.endpoints.set(srcPort, endpoint);
    }
    endpoint.address = remote.address;   // follows the sender through NAT rebinding and network changes
    endpoint.port = remote.port;
    endpoint.seenAt = time;
    member.network.activeAt = time;
    if (message[3] !== 0) {              // keep-alive: answered, so the phone knows the relay hears it
      socket.send(ACK, remote.port, remote.address);
      return;
    }

    member.allowance = Math.min(bytesPerSecond, member.allowance + (time - member.refilledAt) * bytesPerSecond / 1000);
    member.refilledAt = time;
    if (member.allowance < message.length) { stats.dropped++; return; }
    member.allowance -= message.length;

    const packet = Buffer.allocUnsafe(DOWN_HEADER + message.length - UP_HEADER);
    packet[0] = 0x58; packet[1] = 0x52; packet[2] = 1; packet[3] = 0;
    const parts = member.ip.split('.');
    for (let i = 0; i < 4; i++) packet[4 + i] = Number(parts[i]);
    packet.writeUInt16BE(srcPort, 8);
    message.copy(packet, DOWN_HEADER, UP_HEADER);

    const dstIp = ipText(message, 14), dstPort = message.readUInt16BE(18);
    if (BROADCASTS.has(dstIp)) {
      for (const other of member.network.members.values()) if (other !== member) deliver(other, dstPort, packet);
      return;
    }
    for (const other of member.network.members.values()) {
      if (other.ip === dstIp) { if (other !== member) deliver(other, dstPort, packet); return; }
    }
    stats.dropped++;
  }

  function start(listenPort, listenHost) {
    return new Promise((resolve, reject) => {
      const created = dgram.createSocket('udp4');
      created.once('error', reject);
      created.on('message', onMessage);
      created.bind(listenPort, listenHost, () => {
        created.removeListener('error', reject);
        created.on('error', (error) => console.error('relay socket error:', error.message));
        socket = created;
        port = created.address().port;
        timer = setInterval(sweep, 60000);
        timer.unref();
        resolve(port);
      });
    });
  }

  function close() {
    if (timer) clearInterval(timer);
    return new Promise((resolve) => {
      if (!socket) return resolve();
      const closing = socket;
      socket = null;
      port = 0;
      closing.close(resolve);
    });
  }

  return { host, join, bot, reached, networkOf, closeFor, start, close, stats,
    get port() { return port; }, get networks() { return networks.size; }, HOST_IP };
}

module.exports = { createRelay, HOST_IP };
