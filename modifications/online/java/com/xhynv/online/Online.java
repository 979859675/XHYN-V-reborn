// SPDX-License-Identifier: MIT
package com.xhynv.online;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.gtavsource.android.lan.LanSession;
import com.gtavsource.android.lan.OnlineBridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Account state and the background link between the LAN session and the lobby server. */
public final class Online {
    static final String SERVER = "https://zy.xzcqgzs.cn";
    static final String GAME_VERSION = "0.3-preview2";
    static final String NEED_LOGIN = "请先登录联机中心并选择角色";
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9.])[0-9]{1,3}(\\.[0-9]{1,3}){3}(?![0-9.])");

    static final Api api = new Api(SERVER);
    static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Context app;
    private static boolean reporting;
    /** Why {@link #sessionName} last refused; the game shows it as its LAN status. */
    public static volatile String refusal = NEED_LOGIN;
    /** State of the host's relay session, for the card. */
    static volatile String relayStatus = "";

    /** The player is in, or on the way into, a public free-for-all room ("公共对战房"). */
    static volatile boolean publicMode;
    /** What the room this phone is in was made for: "race", "team", "ffa", or "" (a room that does not say). */
    static volatile String roomMode = "";
    private static final String RACE_ROOM = " 的赛车房", TEAM_ROOM = " 的团队竞技房", FFA_ROOM = " 的个人战房";
    static String modeName(String mode) { return "race".equals(mode) ? "赛车房" : "team".equals(mode) ? "团队竞技房" : "ffa".equals(mode) ? "个人战房" : "房间"; }

    /** The mode a room's name in the list stands for. */
    static String modeOf(String title) {
        if (title == null) return "";
        return title.endsWith(RACE_ROOM) ? "race" : title.endsWith(TEAM_ROOM) ? "team" : title.endsWith(FFA_ROOM) ? "ffa" : "";
    }
    private static volatile boolean enteringPublic, startingPublic, starting;

    interface Work<T> { T run() throws ApiException; }

    interface Done<T> {
        void ok(T value);
        void failed(String message);
    }

    private Online() {}

    static void init(Context context) {
        if (app == null && context != null) {
            Context application = context.getApplicationContext();
            app = application != null ? application : context;
        }
    }

    private static SharedPreferences prefs() { return app.getSharedPreferences("xhyn_online", Context.MODE_PRIVATE); }

    static Context context() { return app; }

    static String token() { return app == null ? "" : prefs().getString("token", ""); }
    static String username() { return app == null ? "" : prefs().getString("username", ""); }
    static String characterName() { return app == null ? "" : prefs().getString("character", ""); }
    static boolean signedIn() { return !token().isEmpty(); }
    /** Every room goes through the server's relay: playing over Wi-Fi, a hotspot or a VPN was removed. */
    static boolean relayEnabled() { return app != null; }

    static void signIn(String token, String username) {
        prefs().edit().putString("token", token).putString("username", username).remove("character").apply();
        Relay.hostSession = null;
        Relay.joinSession = null;
    }

    /**
     * Signs in or registers, stores the session and picks up the character already chosen on the
     * website or another phone. Returns that character's name, "" when there is none yet.
     */
    static String authenticate(boolean register, Map<String, Object> body) throws ApiException {
        Map<String, Object> session = api.call("POST", register ? "/auth/register" : "/auth/login", body, null);
        String token = Json.string(session, "token");
        if (token.isEmpty()) throw new ApiException(502, "联机中心没有返回登录信息");
        signIn(token, Json.string(Json.map(session, "user"), "username"));
        Map<String, Object> mine = api.call("GET", "/characters", null, token);
        long active = Json.number(mine, "activeCharacterId");
        for (Object item : Json.list(mine, "characters")) {
            @SuppressWarnings("unchecked") Map<String, Object> character = (Map<String, Object>) item;
            if (Json.number(character, "id") == active) setCharacter(Json.string(character, "name"));
        }
        return characterName();
    }

    static void setCharacter(String name) { prefs().edit().putString("character", name).apply(); }

    static void signOut() {
        if (app != null) prefs().edit().remove("token").remove("character").apply();
        Relay.hostSession = null;
        Relay.joinSession = null;
        relayStatus = "";
        publicMode = false;
    }

    /**
     * Shows a short message. Toasts alone are not enough: several Android skins hide them when the
     * app's notifications are switched off, so the message is also written into the lobby card.
     */
    static void toast(final String message) {
        main.post(new Runnable() {
            @Override public void run() {
                OnlineUi.notice = message;
                OnlineUi.renderAll();
                try {
                    if (app != null) Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void render() {
        main.post(new Runnable() { @Override public void run() { OnlineUi.renderAll(); } });
    }

    /** Copies the game's own LAN status line into the card, so pressing create/join always shows a result. */
    static void showLanStatus() {
        try {
            LanSession.State state = LanSession.state();
            String status = state.status == null ? "" : state.status.trim();
            if (!status.equals(OnlineUi.lanStatus)) {
                OnlineUi.lanStatus = status;
                render();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Runs {@code work} off the UI thread and delivers the outcome on it. A rejected token signs the user out. */
    static <T> void async(final Work<T> work, final Done<T> done) {
        final String used = token();
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                T value = null;
                String error = null;
                try {
                    value = work.run();
                } catch (ApiException e) {
                    error = e.getMessage();
                    if (e.status == 401 && !used.isEmpty() && used.equals(token())) {
                        signOut();
                        error = "登录已失效，请重新登录";
                    }
                } catch (RuntimeException e) {
                    error = "出错了：" + e.getClass().getSimpleName();
                }
                final T result = value;
                final String message = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (message == null) done.ok(result);
                        else done.failed(message);
                        OnlineUi.renderAll();
                    }
                });
            }
        }, "xhyn-online-request");
        thread.setDaemon(true);
        thread.start();
    }

    private static String refuse(String reason, boolean dialog) {
        refusal = reason;
        if (dialog) main.post(new Runnable() { @Override public void run() { OnlineUi.askToSignIn(); } });
        else toast(reason);
        return null;
    }

    /**
     * Called by the game when a LAN session starts. Returns the name to play under, or null to refuse
     * (the reason is left in {@link #refusal}). Also decides whether this session goes through the relay.
     * Sessions only run over the server's relay; anything else is refused.
     */
    public static String sessionName(Context context, String typed, boolean host, String address) {
        try {
            init(context);
            startReporter();
            Relay.active = null;
            // A session started from the game's own buttons is an ordinary room.
            if (!startingPublic) publicMode = false;
            if (!starting) roomMode = "";
            String name = characterName();
            if (!signedIn() || name.isEmpty()) return refuse(NEED_LOGIN, true);
            long now = System.currentTimeMillis();
            Relay.Session relay;
            if (host) {
                relay = Relay.hostSession;
                if (relay == null || !relay.fresh(now)) {
                    prepareHostRelay();
                    return refuse("服务器联机还没有准备好，请过几秒再点一次。", false);
                }
            } else {
                if (!Relay.HOST_IP.equals(address == null ? "" : address.trim())) {
                    return refuse("本版本只支持服务器联机：请在上面玩法卡片的房间列表里选择房间。", false);
                }
                relay = Relay.joinSession;
                if (relay == null || !relay.fresh(now)) {
                    return refuse("这个房间的连接信息已过期，请在玩法卡片的房间列表里重新选择它。", false);
                }
            }
            Relay.active = relay;
            OnlineUi.notice = "";
            // The game fills in its status just after this returns, and again once the socket is open.
            for (long delay : new long[] { 300, 1500, 4000 }) {
                main.postDelayed(new Runnable() { @Override public void run() { showLanStatus(); } }, delay);
            }
            return name;
        } catch (Throwable t) {
            Relay.active = null;
            return refuse("联机中心出错了（" + t.getClass().getSimpleName() + "），请重试。", false);
        }
    }

    /** Called by the game with its "virtual LAN" (VPN) setting as a session starts: that mode is not used any more. */
    public static boolean virtualFlag(boolean setting) { return false; }

    /** Enters the public free-for-all room; also used to come back after its host has left. */
    static void enterPublic(Context context) { launch(context, "public", null); }

    /** Opens a room of this phone's own, listed for others to join. */
    static void createRoom(Context context, String mode) {
        pendingMode = mode == null ? "" : mode;
        launch(context, "create", null);
    }
    private static volatile String pendingMode = "";

    /** Joins a room picked from the list. */
    static void joinRoom(Context context, Map<String, Object> room) { launch(context, "join", room); }

    /**
     * Starts a session through the server, the only way to play together in this build. For the public
     * room the server says what to do: join the one that is open or, when nobody hosts one, open it here.
     */
    private static void launch(final Context context, final String kind, final Map<String, Object> picked) {
        init(context);
        final String token = token();
        final String name = characterName();
        final boolean open = "public".equals(kind);
        if (token.isEmpty() || name.isEmpty()) { toast(NEED_LOGIN); return; }
        boolean busy = false;
        try { busy = OnlineBridge.sessionActive(); } catch (Throwable ignored) { }
        if (busy && !open) { toast("已经在一个房间里了。先在下面点“断开”，再创建或加入别的房间。"); return; }
        synchronized (Online.class) {
            if (enteringPublic) return;
            enteringPublic = true;
        }
        publicMode = open;
        toast(open ? "正在进入公共对战房…" : "create".equals(kind) ? "正在创建房间…" : "正在加入房间…");
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                String failure = null;
                try {
                    startReporter();
                    // Leave the room this phone is in, and wait until the game has let go of it.
                    if (OnlineBridge.sessionActive()) {
                        main.post(new Runnable() { @Override public void run() { try { LanSession.disconnect(); } catch (Throwable ignored) { } } });
                        for (int i = 0; i < 50 && (i < 2 || OnlineBridge.sessionActive()); i++) Thread.sleep(300);
                    }
                    publicMode = open;
                    Map<String, Object> room = picked;
                    String action = kind.equals("create") ? "host" : "join";
                    if (open) {
                        Map<String, Object> reply = null;
                        action = "wait";
                        for (int attempt = 0; attempt < 12 && "wait".equals(action); attempt++) {
                            if (attempt > 0) Thread.sleep(3000);
                            reply = api.call("POST", "/public/enter", Api.empty(), token);
                            action = Json.string(reply, "action");
                        }
                        room = Json.map(reply, "room");
                    }
                    final boolean host = "host".equals(action);
                    final String wanted = open ? "" : "create".equals(kind) ? pendingMode
                            : room != null && Json.number(room, "public") > 0 ? "ffa" : modeOf(room == null ? null : Json.string(room, "title"));
                    final String code;
                    if (host) {
                        refreshHostRelay(token);
                        Relay.Session session = Relay.hostSession;
                        if (session == null || !session.fresh(System.currentTimeMillis())) throw new ApiException(0, "服务器联机没有准备好（" + relayStatus + "）");
                        code = "";
                    } else if ("join".equals(action) && room != null) {
                        long network = Json.number(room, "relayNetwork");
                        if (network <= 0) throw new ApiException(0, "这个房间不是服务器联机房间（旧版本创建），本版本不能加入");
                        Relay.joinSession = Relay.open(api, SERVER, token, network, null, System.currentTimeMillis());
                        code = Json.string(room, "roomCode");
                    } else {
                        throw new ApiException(0, "公共对战房正在由别人开启，请稍后再点一次");
                    }
                    main.post(new Runnable() {
                        @Override public void run() {
                            startingPublic = open;
                            starting = true;
                            roomMode = wanted;
                            try {
                                if (host) LanSession.host(context, name);
                                else LanSession.join(context, Relay.HOST_IP, name, code);     // the game takes the address before the name
                            } catch (Throwable t) {
                                toast("没能开始联机：" + t.getClass().getSimpleName());
                            } finally {
                                startingPublic = false;
                                starting = false;
                                enteringPublic = false;
                            }
                            boolean started = false;
                            try { started = OnlineBridge.sessionActive(); } catch (Throwable ignored) { }
                            if (!started) {
                                publicMode = false;
                                toast("没能开始联机：" + refusal);
                            } else if (open) {
                                toast(host ? "公共对战房里现在没有人，已由你开启。进入游戏后其他人会陆续加入。" : "已加入公共对战房。进入游戏即可开打。");
                                if (host) main.postDelayed(new Runnable() {
                                    @Override public void run() { try { LanSession.setPlayerLimit(context, 10); } catch (Throwable ignored) { } }
                                }, 1500);
                            } else {
                                String what = modeName(wanted);
                                toast(host ? what + "已创建，其他玩家可以在对应玩法的房间列表里加入。现在进入游戏即可。" : "已加入" + what + "。现在进入游戏即可。");
                            }
                        }
                    });
                    return;
                } catch (ApiException e) {
                    failure = e.getMessage();
                } catch (InterruptedException e) {
                    failure = "已取消";
                } catch (Throwable t) {
                    failure = "出错了：" + t.getClass().getSimpleName();
                }
                publicMode = false;
                enteringPublic = false;
                toast("没能开始联机：" + failure);
            }
        }, "xhyn-room-launch");
        thread.setDaemon(true);
        thread.start();
    }

    static boolean enteringPublic() { return enteringPublic; }

    /** Leaves the public room for good (no automatic return). */
    static void leavePublic() {
        publicMode = false;
        main.post(new Runnable() { @Override public void run() { try { LanSession.disconnect(); } catch (Throwable ignored) { } } });
    }

    /** Gets (or refreshes) this user's relay network in the background. */
    static void prepareHostRelay() {
        final String token = token();
        if (token.isEmpty() || characterName().isEmpty()) return;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { refreshHostRelay(token); }
        }, "xhyn-relay-prepare");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized void refreshHostRelay(String token) {
        String before = relayStatus;
        try {
            Relay.hostSession = Relay.open(api, SERVER, token, 0, Relay.hostSession, System.currentTimeMillis());
            relayStatus = "已就绪";
        } catch (ApiException e) {
            Relay.Session session = Relay.hostSession;
            if (session == null || !session.fresh(System.currentTimeMillis())) relayStatus = "未就绪（" + e.getMessage() + "）";
        } catch (RuntimeException e) {
            relayStatus = "未就绪";
        }
        if (!relayStatus.equals(before)) render();
    }

    static synchronized void startReporter() {
        if (reporting) return;
        reporting = true;
        final RoomSync sync = new RoomSync(api, new RoomSync.Account() {
            @Override public String token() { return Online.token(); }
            @Override public void rejected() {
                signOut();
                toast("联机中心登录已失效，请重新登录");
            }
        }, GAME_VERSION);
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                String shown = "";
                long relayCheckedAt = 0;
                while (true) {
                    try {
                        Thread.sleep(relayCheckedAt == 0 ? 500 : 5000);
                        long now = System.currentTimeMillis();
                        // Keep the host's relay network alive so "create room" works at once, and so a
                        // server restart during a game is picked up by the open socket.
                        Relay.Session hosted = Relay.hostSession;
                        boolean lost = hosted != null && hosted.rejected && now - relayCheckedAt >= 10_000;
                        if (now - relayCheckedAt >= 120_000 || relayCheckedAt == 0 || lost) {
                            relayCheckedAt = now;
                            String token = token();
                            if (relayEnabled() && !token.isEmpty() && !characterName().isEmpty()) refreshHostRelay(token);
                        }
                        showLanStatus();
                        RoomSync.Snapshot snapshot = snapshot();
                        sync.tick(snapshot, now);
                        manageBots(snapshot, sync.bots, sync.botMirror);
                        String status = sync.status;
                        if (!status.equals(shown)) {
                            shown = status;
                            OnlineUi.syncStatus = status;
                            render();
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable ignored) {
                        // The lobby must never take the game down.
                    }
                }
            }
        }, "xhyn-online-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private static final Relay.Session[] botSessions = new Relay.Session[OnlineBridge.MAX_BOTS];
    private static final long[] botRetryAt = new long[OnlineBridge.MAX_BOTS];
    private static volatile String botError = "";

    /**
     * Runs the test bots the server asked for. They only exist for relayed rooms: each bot gets its own
     * seat in the host's relay network and joins the room through the server like a remote player.
     */
    private static void manageBots(RoomSync.Snapshot s, int wanted, boolean mirror) {
        boolean relayed = s.hosting && Relay.active != null && Relay.active == Relay.hostSession;
        String token = token();
        // Turning the mirror on is a request to see a bot, so it brings one along.
        if (mirror && wanted == 0) wanted = 1;
        if (wanted > 0 && (!relayed || token.isEmpty())) {
            botError = token.isEmpty() ? "人机未启动:未登录" : !s.hosting ? "人机未启动:本机不是房主"
                    : "人机未启动:房间没有走服务器中转";
            wanted = 0;
        } else if (botError.startsWith("人机未启动")) {
            botError = "";
        }
        for (int index = 1; index <= OnlineBridge.MAX_BOTS; index++) {
            try {
                if (index > wanted) {
                    OnlineBridge.stopBot(index);
                    continue;
                }
                if (OnlineBridge.botAlive(index, s.code)) continue;
                // A bot that could not join is retried, but slowly: its last reason stays visible meanwhile.
                long now = System.currentTimeMillis();
                if (now < botRetryAt[index - 1]) continue;
                botRetryAt[index - 1] = now + 20_000;
                botSessions[index - 1] = Relay.open(api, SERVER, token, "/relay/bot", Json.of("index", index),
                        botSessions[index - 1], System.currentTimeMillis());
                Relay.NEXT.set(botSessions[index - 1]);
                try {
                    OnlineBridge.startBot(index, "人机" + index, Relay.HOST_IP, s.code, mirror);
                } finally {
                    Relay.NEXT.remove();
                }
            } catch (Throwable t) {
                botError = "人机" + index + "启动失败:" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
        }
        try { OnlineBridge.setMirror(mirror); } catch (Throwable ignored) { }
    }

    private static String relayNote() {
        try {
            String note = Relay.diagnostics();
            return note.isEmpty() ? "" : " | " + note;
        } catch (Throwable t) {
            return "";
        }
    }

    private static RoomSync.Snapshot snapshot() {
        RoomSync.Snapshot s = new RoomSync.Snapshot();
        LanSession.State state = LanSession.state();
        s.hosting = state.active && state.host && !state.busy && !state.cleanup;
        s.playing = state.active && state.gameReady;
        s.code = state.code == null ? "" : state.code;
        s.players = state.players;
        s.limit = state.playerLimit;
        if (s.hosting) {
            boolean relayed = Relay.active != null && Relay.active == Relay.hostSession;
            s.virtualLan = !relayed && state.virtualNetwork;
            s.publicRoom = relayed && publicMode;
            String mode = roomMode;
            if (!s.publicRoom && !mode.isEmpty()) s.title = characterName() + ("race".equals(mode) ? RACE_ROOM : "ffa".equals(mode) ? FFA_ROOM : TEAM_ROOM);
            if (!s.publicRoom && "ffa".equals(mode)) s.mode = "ffa";
            s.address = relayed ? Relay.HOST_IP : address(state.virtualNetwork);
            try {
                s.debug = OnlineBridge.diagnostics() + relayNote() + (botError.isEmpty() ? "" : " | " + botError);
            } catch (Throwable ignored) {
            }
            try {
                s.race = OnlineBridge.finishedRace();
            } catch (Throwable ignored) {
                // Race reporting is optional; the room listing must not depend on it.
            }
        }
        return s;
    }

    /** The IPv4 address other players should join: what the game shows, else the best matching interface. */
    static String address(boolean virtual) {
        try {
            for (String shown : LanSession.addresses(app)) {
                Matcher matcher = IPV4.matcher(shown);
                if (matcher.find()) return matcher.group();
            }
        } catch (Throwable ignored) {
        }
        String best = "";
        int bestScore = 0;
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                String name = nic.getName().toLowerCase();
                boolean tunnel = name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") || name.startsWith("wg");
                boolean local = name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("eth");
                int score = virtual ? (tunnel ? 2 : 0) : (local ? 2 : 0);
                if (score == 0) continue;   // never publish a mobile-data or unrelated address
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && score > bestScore) {
                        best = address.getHostAddress();
                        bestScore = score;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }
}
