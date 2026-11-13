// SPDX-License-Identifier: MIT
package com.xhynv.online;

import java.util.List;
import java.util.Map;

/** Runs the client's network code on a desktop JVM against a real server: java SyncTest http://127.0.0.1:PORT */
public final class SyncTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private static int status(Api api, String method, String path, Object body, String token) {
        try { api.call(method, path, body, token); return 200; } catch (ApiException e) { return e.status; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> room(Api api, String token, String host) throws ApiException {
        for (Object item : Json.list(api.call("GET", "/rooms", null, token), "rooms")) {
            if (host.equals(Json.string((Map<String, Object>) item, "hostName"))) return (Map<String, Object>) item;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        // JSON
        Map<String, Object> parsed = Json.object(" {\"a\":[1,-2.5,true,null,\"x\\n\\u4e2d\\\"\"],\"b\":{\"c\":\"\"}} ");
        List<Object> a = Json.list(parsed, "a");
        check(a.size() == 5 && a.get(0).equals(1L) && a.get(1).equals(-2.5) && a.get(2).equals(true) && a.get(3) == null && a.get(4).equals("x\n中\""), "JSON parses nested values and escapes");
        String written = Json.write(Json.of("name", "车神\"\\\n\u0001", "n", 3, "ok", false, "none", null));
        check(written.equals("{\"name\":\"车神\\\"\\\\\\n\\u0001\",\"n\":3,\"ok\":false,\"none\":null}"), "JSON writes escapes: " + written);
        check(Json.object(written).get("name").equals("车神\"\\\n\u0001"), "JSON round trip");
        for (String bad : new String[] { "", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "\"abc", "{} x", "<html>", "nul", "[1 2]", "\"\\q\"", "-" }) {
            boolean rejected = false;
            try { Json.parse(bad); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "JSON rejects " + bad);
        }

        // API
        Api api = new Api(args[0] + "/");
        String suffix = Long.toString(System.nanoTime() % 100000);
        String user = "jvm" + suffix, hostName = "房主" + suffix;
        check(status(api, "POST", "/auth/login", Json.of("username", user, "password", "wrong password"), null) == 401, "wrong login is a 401");
        try {
            api.call("POST", "/auth/register", Json.of("username", user, "password", "short"), null);
            check(false, "short password rejected");
        } catch (ApiException e) {
            check(e.status == 400 && e.getMessage().contains("密码"), "server message is passed through: " + e.getMessage());
        }
        final String token = Json.string(api.call("POST", "/auth/register", Json.of("username", user, "password", "long enough pw"), null), "token");
        check(token.length() > 20, "register returns a token");
        Map<String, Object> made = Json.map(api.call("POST", "/characters", Json.of("name", hostName), token), "character");
        check(hostName.equals(Json.string(made, "name")) && Json.number(made, "id") > 0, "character created with a Chinese name");
        check(Json.number(api.call("GET", "/characters", null, token), "activeCharacterId") == Json.number(made, "id"), "it is the active character");
        try {
            new Api("http://127.0.0.1:9").call("GET", "/info", null, null);
            check(false, "unreachable server");
        } catch (ApiException e) {
            check(e.status == 0 && e.getMessage().contains("无法连接"), "unreachable server gives a readable error");
        }
        try {
            new Api(args[0] + "/style.css#").call("GET", "", null, null);
            check(false, "non-JSON reply");
        } catch (ApiException e) {
            check(e.getMessage().contains("无法识别"), "non-JSON reply gives a readable error: " + e.getMessage());
        }

        // Room sync
        final boolean[] rejected = { false };
        final String[] current = { token };
        RoomSync sync = new RoomSync(api, new RoomSync.Account() {
            @Override public String token() { return current[0]; }
            @Override public void rejected() { rejected[0] = true; current[0] = ""; }
        }, "0.3-preview2");
        RoomSync.Snapshot s = new RoomSync.Snapshot();
        long now = 1_000_000;
        sync.tick(s, now);
        check(sync.roomId() == 0 && room(api, token, hostName) == null, "idle: nothing is listed");

        s.hosting = true; s.code = "042137"; s.limit = 6; s.players = 1; s.virtualLan = true;
        sync.tick(s, now += 5000);
        check(sync.roomId() == 0 && sync.status.contains("IP"), "hosting without an address is not listed: " + sync.status);
        s.address = "10.126.126.2";
        sync.tick(s, now += 5000);
        Map<String, Object> listed = room(api, token, hostName);
        check(listed != null && "042137".equals(Json.string(listed, "roomCode")) && "10.126.126.2".equals(Json.string(listed, "hostAddress"))
                && Json.flag(listed, "virtualLan") && Json.number(listed, "maxPlayers") == 6, "hosting lists the room with code, address and limit");
        long first = sync.roomId();

        s.players = 3;
        sync.tick(s, now += 5000);
        check(Json.number(room(api, token, hostName), "players") == 1, "no heartbeat before 10 s");
        sync.tick(s, now += 30000);
        check(Json.number(room(api, token, hostName), "players") == 3, "heartbeat updates the player count");

        s.code = "999000";
        sync.tick(s, now += 5000);
        sync.tick(s, now += 5000);
        listed = room(api, token, hostName);
        check(listed != null && "999000".equals(Json.string(listed, "roomCode")) && sync.roomId() != first, "a new room code replaces the listing");

        // The server forgets the room (restart or expiry): the next heartbeat lists it again.
        api.call("DELETE", "/rooms/" + sync.roomId(), null, token);
        sync.tick(s, now += 30000);
        check(sync.roomId() == 0, "a 404 heartbeat drops the stale id");
        sync.tick(s, now += 5000);
        check(sync.roomId() != 0 && room(api, token, hostName) != null, "and the room is listed again");

        s.playing = true;
        sync.tick(s, now += 60000);
        check(status(api, "POST", "/play/heartbeat", Api.empty(), token) == 200, "play heartbeat is accepted");

        s.hosting = false; s.playing = false;
        sync.tick(s, now += 5000);
        check(sync.roomId() == 0 && room(api, token, hostName) == null && sync.status.isEmpty(), "leaving removes the listing");

        // Signed out: nothing is sent. A dead token is reported once.
        current[0] = "";
        s.hosting = true;
        sync.tick(s, now += 60000);
        check(sync.roomId() == 0 && !rejected[0], "signed out: hosting is not listed");
        current[0] = "not-a-real-token";
        sync.tick(s, now += 60000);
        check(rejected[0] && sync.roomId() == 0, "a rejected token is reported to the account");

        // Race reporting: only while a room is listed, and each race once.
        current[0] = token;
        rejected[0] = false;
        s = new RoomSync.Snapshot();
        s.hosting = true; s.code = "555666"; s.limit = 4; s.players = 2; s.address = "192.168.1.9";
        sync.tick(s, now += 60000);
        java.util.List<Object> results = new java.util.ArrayList<Object>();
        results.add(Json.of("name", hostName, "finished", true, "position", 1, "timeMs", 61234));
        results.add(Json.of("name", "路人" + suffix, "finished", false, "reason", "人物死亡"));
        s.race = Json.of("id", 77L, "courseName", "测试赛道" + suffix, "mode", "circuit", "laps", 2, "checkpoints", 5, "results", results);
        sync.tick(s, now += 5000);
        sync.tick(s, now += 5000);
        int stored = 0;
        for (Object item : Json.list(api.call("GET", "/races", null, token), "races")) {
            @SuppressWarnings("unchecked") Map<String, Object> race = (Map<String, Object>) item;
            if (("测试赛道" + suffix).equals(Json.string(race, "courseName"))) {
                stored++;
                check(Json.number(race, "laps") == 2 && Json.list(race, "results").size() == 2, "race is stored with its results");
            }
        }
        check(stored == 1, "a finished race is reported exactly once, got " + stored);
        s.race = Json.of("id", 78L, "courseName", "", "mode", "sprint", "results", new java.util.ArrayList<Object>());
        sync.tick(s, now += 5000);
        sync.tick(s, now += 5000);
        check(sync.roomId() != 0, "an invalid race report does not disturb the room listing");
        s.hosting = false; s.race = null;
        sync.tick(s, now += 5000);

        // Test bots: the count set on the server reaches the host with its next heartbeat, with the diagnostic line going up.
        Relay.Session mine = Relay.open(api, args[0], token, 0, null, 1000);
        s = new RoomSync.Snapshot();
        s.hosting = true; s.code = "777888"; s.limit = 4; s.players = 1; s.address = Relay.HOST_IP; s.debug = "诊断 stats=[1,0]";
        sync.tick(s, now += 60000);
        check(sync.bots == 0, "no bots unless asked");
        api.call("POST", "/rooms/" + sync.roomId() + "/bots", Json.of("count", 2, "mirror", true), token);
        sync.tick(s, now += 10000);
        check(sync.bots == 2 && sync.botMirror, "the host learns the wanted bots from the heartbeat");
        check("诊断 stats=[1,0]".equals(Json.string(room(api, token, hostName), "debug")), "the diagnostic line is stored for the host");
        Relay.Session bot = Relay.open(api, args[0], token, "/relay/bot", Json.of("index", 1), null, 1000);
        check(bot.networkId == mine.networkId && !java.util.Arrays.equals(bot.token, mine.token), "a bot gets its own seat in the host's network");
        Relay.active = mine;
        Relay.NEXT.set(bot);
        java.net.DatagramSocket botSocket = Relay.socket(null);
        Relay.NEXT.remove();
        java.net.DatagramSocket ownSocket = Relay.socket(null);
        Relay.active = null;
        botSocket.bind(new java.net.InetSocketAddress(0));
        ownSocket.bind(new java.net.InetSocketAddress(0));
        ownSocket.setSoTimeout(1500);
        Thread.sleep(600);
        botSocket.send(new java.net.DatagramPacket(new byte[] { 9 }, 1, java.net.InetAddress.getByName("10.77.0.1"), ownSocket.getLocalPort()));
        java.net.DatagramPacket fromBot = new java.net.DatagramPacket(new byte[16], 16);
        ownSocket.receive(fromBot);
        check(fromBot.getAddress().getHostAddress().equals("10.77.0.2"), "the bot's packets reach the host from the bot's own address");
        botSocket.close();
        ownSocket.close();
        s.hosting = false;
        sync.tick(s, now += 5000);
        check(sync.bots == 0, "bots stop with the room");

        // Relay: two game sockets that only know virtual addresses talk through the server.
        String guestToken = Json.string(api.call("POST", "/auth/register", Json.of("username", "jvmg" + suffix, "password", "long enough pw"), null), "token");
        api.call("POST", "/characters", Json.of("name", "客人" + suffix), guestToken);
        Relay.Session hostSession = Relay.open(api, args[0], token, 0, null, 1000);
        Relay.Session same = Relay.open(api, args[0], token, 0, hostSession, 2000);
        check(same == hostSession && hostSession.confirmedAt == 2000 && hostSession.fresh(2000 + Relay.FRESH_MS - 1) && !hostSession.fresh(2000 + Relay.FRESH_MS), "refreshing reuses the session object");
        Relay.Session guestSession = Relay.open(api, args[0], guestToken, hostSession.networkId, null, 1000);
        check(guestSession.networkId == hostSession.networkId && !java.util.Arrays.equals(guestSession.token, hostSession.token), "guest gets its own token in the host's network");
        try {
            Relay.open(api, args[0], guestToken, 987654, null, 0);
            check(false, "unknown network");
        } catch (ApiException e) {
            check(e.status == 404, "joining a network that is gone is a 404: " + e.getMessage());
        }

        Relay.active = null;
        java.net.DatagramSocket plain = Relay.socket(null);
        check(!Relay.owns(plain) && !plain.isBound(), "without a session the game gets a plain unbound socket");
        plain.close();
        Relay.active = hostSession;
        java.net.DatagramSocket hostSocket = Relay.socket(null);
        Relay.active = guestSession;
        java.net.DatagramSocket guestSocket = Relay.socket(null);
        Relay.active = null;
        check(Relay.owns(hostSocket) && Relay.owns(guestSocket) && !hostSocket.isBound(), "with a session it gets an unbound relay socket");
        hostSocket.bind(new java.net.InetSocketAddress(0));
        guestSocket.bind(new java.net.InetSocketAddress(0));
        hostSocket.setSoTimeout(1500);
        guestSocket.setSoTimeout(1500);
        Thread.sleep(600);   // the keep-alive registers the host before anyone has written to it

        byte[] hello = "HELLO 你好".getBytes("UTF-8");
        java.net.InetAddress hostVirtual = java.net.InetAddress.getByName("10.77.0.1");
        guestSocket.send(new java.net.DatagramPacket(hello, hello.length, hostVirtual, hostSocket.getLocalPort()));
        byte[] buffer = new byte[1200];
        java.net.DatagramPacket got = new java.net.DatagramPacket(buffer, buffer.length);
        hostSocket.receive(got);
        check(new String(buffer, 0, got.getLength(), "UTF-8").equals("HELLO 你好"), "host receives the guest's payload unchanged");
        check(got.getAddress().getHostAddress().startsWith("10.77.0.") && !got.getAddress().getHostAddress().equals("10.77.0.1") && got.getPort() == guestSocket.getLocalPort(), "it appears to come from the guest's virtual address: " + got.getSocketAddress());

        // Reply to the address the packet came from, as the game does, with a full-size packet at an offset.
        byte[] big = new byte[1300];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 7);
        hostSocket.send(new java.net.DatagramPacket(big, 100, 1200, got.getSocketAddress()));
        byte[] target = new byte[1210];
        java.net.DatagramPacket reply = new java.net.DatagramPacket(target, 10, 1200);
        guestSocket.receive(reply);
        boolean intact = reply.getLength() == 1200 && reply.getOffset() == 10;
        for (int i = 0; intact && i < 1200; i++) intact = target[10 + i] == big[100 + i];
        check(intact && reply.getAddress().equals(hostVirtual) && reply.getPort() == hostSocket.getLocalPort(), "a 1200-byte reply survives offsets in both directions");

        reply.setLength(1200);
        byte[] bye = { 1, 2, 3 };
        hostSocket.send(new java.net.DatagramPacket(bye, 3, java.net.InetAddress.getByName("255.255.255.255"), guestSocket.getLocalPort()));
        guestSocket.receive(reply);
        check(reply.getLength() == 3 && target[10] == 1 && target[12] == 3, "broadcast reaches the other member; the packet object can be reused");

        boolean timedOut = false;
        try { hostSocket.receive(got); } catch (java.net.SocketTimeoutException e) { timedOut = true; }
        check(timedOut, "receive still times out like a plain socket");
        boolean tooLarge = false;
        try { hostSocket.send(new java.net.DatagramPacket(new byte[1401], 1401, hostVirtual, 1)); } catch (java.io.IOException e) { tooLarge = true; }
        check(tooLarge, "oversized packets are refused");
        hostSocket.close();
        guestSocket.close();

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
