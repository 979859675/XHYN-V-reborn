// SPDX-License-Identifier: MIT
package com.xhynv.online;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

/** Internet play through the lobby server's UDP relay. */
public final class Relay {
    /** Virtual address of every relay room's host. */
    static final String HOST_IP = "10.77.0.1";
    /** How long a session may go without being confirmed by the server before it is not trusted for a new game. */
    static final long FRESH_MS = 5 * 60_000;

    static final class Session {
        volatile long networkId;
        volatile byte[] token;
        volatile InetAddress server;
        volatile int port;
        volatile long confirmedAt;
        /** Set by the socket when the relay says it does not know this seat, e.g. after a server restart. */
        volatile boolean rejected;
        /** The last game socket opened on this session. */
        volatile RelaySocket socket;

        boolean fresh(long now) { return now - confirmedAt < FRESH_MS; }
    }

    /** Prepared sessions: one for hosting, one for the room last picked from the list. */
    static volatile Session hostSession, joinSession;
    /** The session the next game socket will use; null means plain LAN. */
    static volatile Session active;

    /** Overrides {@link #active} for a socket created on this thread: a test bot has its own seat in the network. */
    static final ThreadLocal<Session> NEXT = new ThreadLocal<Session>();

    private Relay() {}

    /** One part of the diagnostics line: whether the host's packets get to the relay and back. */
    static String diagnostics() {
        Session session = hostSession;
        if (session == null || active != session) return "";
        RelaySocket socket = session.socket;
        if (socket == null) return "中转:还没有打开游戏端口";
        String address = session.server == null ? "?" : session.server.getHostAddress();
        return "中转 " + address + ":" + session.port + " 发" + socket.sent + " 应答" + socket.acked
                + " 收" + socket.delivered + (socket.rejected > 0 ? " 被拒" + socket.rejected : "")
                + (socket.isClosed() ? " 端口已关" : "") + (socket.error.isEmpty() ? "" : " 错误:" + socket.error);
    }

    /** Called by the game in place of {@code new DatagramSocket(address)}. */
    public static DatagramSocket socket(SocketAddress address) throws SocketException {
        Session session = NEXT.get();
        if (session == null) session = active;
        return session == null ? new DatagramSocket(address) : new RelaySocket(address, session);
    }

    /** True for sockets that must not be bound to a Wi-Fi or VPN network by the game. */
    public static boolean owns(DatagramSocket socket) { return socket instanceof RelaySocket; }

    /**
     * Asks the server for a relay session and fills {@code into} (or a new session). Blocking.
     * Reusing the object lets a socket that is already open pick up a replaced token.
     */
    static Session open(Api api, String serverUrl, String token, long joinNetwork, Session into, long now) throws ApiException {
        return joinNetwork > 0 ? open(api, serverUrl, token, "/relay/join", Json.of("networkId", joinNetwork), into, now)
                : open(api, serverUrl, token, "/relay/host", Api.empty(), into, now);
    }

    static Session open(Api api, String serverUrl, String token, String path, Map<String, Object> body, Session into, long now) throws ApiException {
        Map<String, Object> reply = api.call("POST", path, body, token);
        Map<String, Object> relay = Json.map(reply, "relay");
        String hex = Json.string(relay, "token");
        int port = (int) Json.number(relay, "port");
        if (hex.length() != 16 || port <= 0 || port > 65535) throw new ApiException(502, "联机中心返回的中转信息不完整");
        byte[] bytes = new byte[8];
        try {
            for (int i = 0; i < 8; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException e) {
            throw new ApiException(502, "联机中心返回的中转信息不完整");
        }
        InetAddress server;
        try {
            server = InetAddress.getByName(URI.create(serverUrl).getHost());
        } catch (UnknownHostException | RuntimeException e) {
            throw new ApiException(0, "无法解析联机中心的地址，请检查网络");
        }
        Session session = into != null ? into : new Session();
        session.server = server;
        session.port = port;
        session.token = bytes;
        session.networkId = Json.number(relay, "networkId");
        session.confirmedAt = now;
        session.rejected = false;
        return session;
    }
}
