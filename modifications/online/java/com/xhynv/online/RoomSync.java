// SPDX-License-Identifier: MIT
package com.xhynv.online;

import java.util.Map;

/**
 * Keeps the lobby in step with the local LAN session: lists the room while this device hosts,
 * removes it afterwards, and reports play time. Driven by {@link #tick}; holds no Android state.
 */
final class RoomSync {
    /** What the game says about the LAN session right now. */
    static final class Snapshot {
        boolean hosting;
        boolean playing;
        boolean virtualLan;
        /** This room is a public free-for-all room the server asked this phone to open. */
        boolean publicRoom;
        /** The room's name in the list; "" lets the server name it. */
        String title = "";
        String code = "";
        String address = "";
        int players;
        int limit;
        /** Body of POST /races plus an "id" entry, while the results of a finished race are on screen. */
        Map<String, Object> race;
        /** Diagnostic line sent with the heartbeat. */
        String debug = "";
    }

    /** Test bots the server last asked this room's host to run. */
    volatile int bots;
    volatile boolean botMirror;

    /** Where the login token comes from, and where to report that the server rejected it. */
    interface Account {
        String token();
        void rejected();
    }

    static final long ROOM_BEAT_MS = 10_000, PLAY_BEAT_MS = 60_000, RETRY_MS = 10_000;

    private final Api api;
    private final Account account;
    private final String gameVersion;
    private long roomId;
    private String roomToken = "";
    private String roomKey = "";
    private long nextRoomAt, nextPlayAt;
    private long reportedRace = Long.MIN_VALUE;
    private int raceAttempts;
    /** Short Chinese status for the UI; empty when there is nothing to say. */
    volatile String status = "";

    RoomSync(Api api, Account account, String gameVersion) {
        this.api = api;
        this.account = account;
        this.gameVersion = gameVersion;
    }

    long roomId() { return roomId; }

    void tick(Snapshot s, long now) {
        String token = account.token();
        boolean signedIn = token != null && !token.isEmpty();
        boolean listable = signedIn && s.hosting && s.code.matches("[0-9]{6}");
        String key = listable ? s.code + '|' + s.address + '|' + s.virtualLan + '|' + s.publicRoom + '|' + s.title : "";
        try {
            // Hosting stopped, or the room became a different one: take the old listing down first.
            if (roomId != 0 && !key.equals(roomKey)) closeRoom();
            if (listable) {
                if (s.address.isEmpty()) {
                    status = "未获取到本机 IP，房间未显示在在线房间列表";
                } else if (roomId == 0) {
                    if (now >= nextRoomAt) {
                        nextRoomAt = now + RETRY_MS;
                        Map<String, Object> body = Json.of(
                                "hostAddress", s.address, "roomCode", s.code, "virtualLan", s.virtualLan, "public", s.publicRoom,
                                "maxPlayers", clamp(s.limit, 2, 10), "gameVersion", gameVersion);
                        if (s.title != null && !s.title.isEmpty()) body.put("title", s.title);
                        Map<String, Object> made = api.call("POST", "/rooms", body, token);
                        roomId = Json.number(Json.map(made, "room"), "id");
                        roomToken = token;
                        roomKey = key;
                        nextRoomAt = now + ROOM_BEAT_MS;
                        status = "房间已显示在在线房间列表";
                    }
                } else if (now >= nextRoomAt) {
                    nextRoomAt = now + ROOM_BEAT_MS;
                    try {
                        Map<String, Object> beat = api.call("POST", "/rooms/" + roomId + "/heartbeat", Json.of(
                                "players", clamp(s.players, 1, 10), "maxPlayers", clamp(s.limit, 2, 10), "debug", s.debug == null ? "" : s.debug), token);
                        bots = clamp((int) Json.number(beat, "bots"), 0, 9);
                        botMirror = Json.flag(beat, "botMirror");
                    } catch (ApiException e) {
                        if (e.status != 404) throw e;
                        roomId = 0;          // expired on the server; list it again on the next tick
                        nextRoomAt = now;
                    }
                }
            } else if (!s.hosting) {
                status = "";
            }
            if (roomId == 0) bots = 0;
            if (roomId != 0 && s.race != null && Json.number(s.race, "id") != reportedRace) reportRace(s.race, token);
            if (signedIn && s.playing && now >= nextPlayAt) {
                nextPlayAt = now + PLAY_BEAT_MS;
                try {
                    api.call("POST", "/play/heartbeat", Api.empty(), token);
                } catch (ApiException e) {
                    if (e.status != 400) throw e;   // no active character: nothing to credit
                }
            }
        } catch (ApiException e) {
            if (e.status == 401 || e.status == 403) {
                roomId = 0;
                roomKey = "";
                status = "";
                if (e.status == 401) account.rejected();
            } else if (listable && roomId == 0) {
                status = "房间未能显示在在线房间列表：" + e.getMessage();
            }
        }
    }

    /** Sends a finished race once. Only the host reports, so each race is stored a single time. */
    private void reportRace(Map<String, Object> race, String token) throws ApiException {
        long id = Json.number(race, "id");
        Map<String, Object> body = new java.util.LinkedHashMap<String, Object>(race);
        body.remove("id");
        try {
            api.call("POST", "/races", body, token);
        } catch (ApiException e) {
            // Refused as invalid: sending the same data again cannot succeed. Anything else is retried a few times.
            if (e.status != 400 && ++raceAttempts < 5) {
                if (e.status == 401) throw e;
                return;
            }
        }
        reportedRace = id;
        raceAttempts = 0;
    }

    private void closeRoom() {
        long id = roomId;
        roomId = 0;
        roomKey = "";
        nextRoomAt = 0;
        status = "";
        try {
            api.call("DELETE", "/rooms/" + id, null, roomToken);
        } catch (ApiException ignored) {
            // The listing expires by itself after 90 seconds without a heartbeat.
        }
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
