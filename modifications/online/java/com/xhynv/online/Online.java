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

    static String token() { return app == null ? "" : prefs().getString("token", ""); }
    static String username() { return app == null ? "" : prefs().getString("username", ""); }
    static String characterName() { return app == null ? "" : prefs().getString("character", ""); }
    static boolean signedIn() { return !token().isEmpty(); }
    /** Whether rooms created on this phone are opened to the internet through the relay. */
    static boolean relayEnabled() { return app != null && prefs().getBoolean("relay", true); }

    static void setRelayEnabled(boolean enabled) {
        prefs().edit().putBoolean("relay", enabled).apply();
        if (!enabled) relayStatus = "";
    }

    static void signIn(String token, String username) {
        prefs().edit().putString("token", token).putString("username", username).remove("character").apply();
        Relay.hostSession = null;
        Relay.joinSession = null;
    }

    static void setCharacter(String name) { prefs().edit().putString("character", name).apply(); }

    static void signOut() {
        if (app != null) prefs().edit().remove("token").remove("character").apply();
        Relay.hostSession = null;
        Relay.joinSession = null;
        relayStatus = "";
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
     * Any unexpected failure falls back to the typed name and plain LAN so local play keeps working.
     */
    public static String sessionName(Context context, String typed, boolean host, String address) {
        try {
            init(context);
            startReporter();
            Relay.active = null;
            String name = characterName();
            if (!signedIn() || name.isEmpty()) return refuse(NEED_LOGIN, true);
            long now = System.currentTimeMillis();
            Relay.Session relay = null;
            if (host) {
                if (relayEnabled()) {
                    relay = Relay.hostSession;
                    if (relay == null || !relay.fresh(now)) {
                        prepareHostRelay();
                        return refuse("公网联机还没有准备好，请过几秒再点一次。如果一直不行，可以先关闭“公网联机”，用同一 Wi-Fi 或热点联机。", false);
                    }
                }
            } else if (Relay.HOST_IP.equals(address == null ? "" : address.trim())) {
                relay = Relay.joinSession;
                if (relay == null || !relay.fresh(now)) {
                    return refuse("这个公网房间的连接信息已过期，请在“在线房间”里重新选择它。", false);
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
            return typed;
        }
    }

    /**
     * Called by the game with its "virtual LAN" setting as a session starts. A relayed session uses
     * whatever network the phone has, so the game's VPN mode (which insists on a connected VPN app) is off for it.
     */
    public static boolean virtualFlag(boolean setting) { return Relay.active == null && setting; }

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
                        if (now - relayCheckedAt >= 120_000 || relayCheckedAt == 0) {
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

    private static final Relay.Session[] botSessions = new Relay.Session[3];

    /**
     * Runs the test bots the server asked for. They only exist for relayed rooms: each bot gets its own
     * seat in the host's relay network and joins the room through the server like a remote player.
     */
    private static void manageBots(RoomSync.Snapshot s, int wanted, boolean mirror) {
        boolean relayed = s.hosting && Relay.active != null && Relay.active == Relay.hostSession;
        String token = token();
        if (!relayed || token.isEmpty()) wanted = 0;
        for (int index = 1; index <= 3; index++) {
            try {
                if (index > wanted) {
                    OnlineBridge.stopBot(index);
                    continue;
                }
                if (OnlineBridge.botAlive(index, s.code)) continue;
                botSessions[index - 1] = Relay.open(api, SERVER, token, "/relay/bot", Json.of("index", index),
                        botSessions[index - 1], System.currentTimeMillis());
                Relay.NEXT.set(botSessions[index - 1]);
                try {
                    OnlineBridge.startBot(index, "人机" + index, Relay.HOST_IP, s.code, mirror);
                } finally {
                    Relay.NEXT.remove();
                }
            } catch (Throwable ignored) {
                // Tried again on the next tick; the diagnostic line shows the bot's state.
            }
        }
        try { OnlineBridge.setMirror(mirror); } catch (Throwable ignored) { }
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
            s.address = relayed ? Relay.HOST_IP : address(state.virtualNetwork);
            try {
                s.debug = OnlineBridge.diagnostics();
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
