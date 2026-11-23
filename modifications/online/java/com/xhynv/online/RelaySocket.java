// SPDX-License-Identifier: MIT
package com.xhynv.online;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * A datagram socket whose "network" is a relay network on the lobby server. The game uses it like a
 * normal LAN socket: packets it sends to a virtual address (10.77.0.x) are wrapped and sent to the
 * relay, and packets from the relay arrive looking as if they came from the sender's virtual address.
 * See server/src/relay.js for the framing.
 */
final class RelaySocket extends DatagramSocket {
    static final int UP_HEADER = 20, DOWN_HEADER = 10, MAX_PAYLOAD = 1400;
    private static final long KEEP_ALIVE_MS = 10_000;

    private final Relay.Session session;
    /** Counters for the diagnostics line. */
    volatile int sent, acked, rejected, delivered;
    volatile String error = "";
    private final byte[] inbox = new byte[DOWN_HEADER + MAX_PAYLOAD + 64];

    RelaySocket(SocketAddress bind, Relay.Session session) throws SocketException {
        super(bind);
        this.session = session;
        session.socket = this;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { keepAlive(); }
        }, "xhyn-relay-keepalive");
        thread.setDaemon(true);
        thread.start();
    }

    /** Tells the relay where this socket is, so others can reach it before it has sent anything, and keeps NAT open. */
    private void keepAlive() {
        try {
            Thread.sleep(200);   // the game binds the socket right after creating it
            while (!isClosed()) {
                if (isBound()) {
                    try { transmit(1, null, 0, null, 0, 0); } catch (IOException ignored) { }
                }
                Thread.sleep(KEEP_ALIVE_MS);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private void transmit(int type, InetAddress to, int toPort, byte[] data, int offset, int length) throws IOException {
        byte[] out = new byte[UP_HEADER + length];
        out[0] = 'X'; out[1] = 'R'; out[2] = 1; out[3] = (byte) type;
        System.arraycopy(session.token, 0, out, 4, 8);
        int local = getLocalPort();
        out[12] = (byte) (local >> 8); out[13] = (byte) local;
        if (to != null) System.arraycopy(to.getAddress(), 0, out, 14, 4);
        out[18] = (byte) (toPort >> 8); out[19] = (byte) toPort;
        if (length > 0) System.arraycopy(data, offset, out, UP_HEADER, length);
        try {
            super.send(new DatagramPacket(out, out.length, session.server, session.port));
            sent++;
        } catch (IOException | RuntimeException e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ":" + e.getMessage());
            throw e;
        }
    }

    @Override public void send(DatagramPacket packet) throws IOException {
        InetAddress to = packet.getAddress();
        if (!(to instanceof Inet4Address)) throw new IOException("IPv4 destination required");
        if (packet.getLength() > MAX_PAYLOAD) throw new IOException("Packet too large");
        transmit(0, to, packet.getPort(), packet.getData(), packet.getOffset(), packet.getLength());
    }

    @Override public void receive(DatagramPacket packet) throws IOException {
        synchronized (inbox) {
            while (true) {
                DatagramPacket wrapped = new DatagramPacket(inbox, inbox.length);
                super.receive(wrapped);   // SocketTimeoutException passes through to the game, as on a plain socket
                int length = wrapped.getLength();
                if (length < DOWN_HEADER || inbox[0] != 'X' || inbox[1] != 'R' || inbox[2] != 1) continue;
                if (wrapped.getPort() != session.port || !session.server.equals(wrapped.getAddress())) continue;
                if (inbox[3] == 1) { acked++; continue; }                       // the relay heard a keep-alive
                if (inbox[3] == 2) { rejected++; session.rejected = true; continue; }   // the relay does not know this seat
                if (inbox[3] != 0) continue;
                delivered++;
                byte[] data = packet.getData();
                int offset = packet.getOffset();
                int payload = Math.min(length - DOWN_HEADER, data.length - offset);
                System.arraycopy(inbox, DOWN_HEADER, data, offset, payload);
                packet.setLength(payload);
                packet.setAddress(InetAddress.getByAddress(new byte[] { inbox[4], inbox[5], inbox[6], inbox[7] }));
                packet.setPort(((inbox[8] & 0xff) << 8) | (inbox[9] & 0xff));
                return;
            }
        }
    }
}
