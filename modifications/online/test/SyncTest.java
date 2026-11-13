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
        check(Json.number(room(api, token, hostName), "players") == 1, "no heartbeat before 30 s");
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

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
