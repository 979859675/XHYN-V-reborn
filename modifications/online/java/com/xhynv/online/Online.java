// SPDX-License-Identifier: MIT
package com.xhynv.online;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.gtavsource.android.lan.LanSession;

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

    static void signIn(String token, String username) {
        prefs().edit().putString("token", token).putString("username", username).remove("character").apply();
    }

    static void setCharacter(String name) { prefs().edit().putString("character", name).apply(); }

    static void signOut() {
        if (app != null) prefs().edit().remove("token").remove("character").apply();
    }

    static void toast(final String message) {
        main.post(new Runnable() {
            @Override public void run() {
                if (app != null) Toast.makeText(app, message, Toast.LENGTH_LONG).show();
            }
        });
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

    /**
     * Called by the game when a LAN session starts. Returns the name to play under, or null to refuse
     * because nobody is signed in. Any unexpected failure falls back to the typed name so LAN play keeps working.
     */
    public static String sessionName(Context context, String typed) {
        try {
            init(context);
            startReporter();
            String name = characterName();
            if (!signedIn() || name.isEmpty()) {
                toast(NEED_LOGIN);
                return null;
            }
            return name;
        } catch (Throwable t) {
            return typed;
        }
    }

    static synchronized void startReporter() {
        if (reporting) return;
        reporting = true;
        final RoomSync sync = new RoomSync(api, new RoomSync.Account() {
            @Override public String token() { return Online.token(); }
            @Override public void rejected() {
                signOut();
                toast("联机中心登录已失效，请重新登录");
                main.post(new Runnable() { @Override public void run() { OnlineUi.renderAll(); } });
            }
        }, GAME_VERSION);
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                String shown = "";
                while (true) {
                    try {
                        Thread.sleep(5000);
                        sync.tick(snapshot(), System.currentTimeMillis());
                        String status = sync.status;
                        if (!status.equals(shown)) {
                            shown = status;
                            OnlineUi.syncStatus = status;
                            main.post(new Runnable() { @Override public void run() { OnlineUi.renderAll(); } });
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

    private static RoomSync.Snapshot snapshot() {
        RoomSync.Snapshot s = new RoomSync.Snapshot();
        LanSession.State state = LanSession.state();
        s.hosting = state.active && state.host && !state.busy && !state.cleanup;
        s.playing = state.active && state.gameReady;
        s.virtualLan = state.virtualNetwork;
        s.code = state.code == null ? "" : state.code;
        s.players = state.players;
        s.limit = state.playerLimit;
        if (s.hosting) s.address = address(state.virtualNetwork);
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
