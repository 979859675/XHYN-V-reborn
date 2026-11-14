// SPDX-License-Identifier: MIT
package com.xhynv.online;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gtavsource.android.lan.OnlineBridge;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Team match ("团队竞技") for relayed rooms: two teams of up to five, first to the host's kill target wins.
 *
 * The lobby server keeps the match (teams, score, result). Each phone reports its own player's deaths
 * and which members its player has just damaged; a death is a point for the other team and the last
 * one to damage the victim gets the kill. Everyone polls the server for the score, so the game's own
 * network protocol is untouched. Test bots fill the smaller team and go down after enough hits.
 *
 * In a public room ("公共对战房") there are no teams: the same reports feed a free-for-all in which the
 * server counts every player's own kills, for ever.
 *
 * Names above heads take the team's colour (red or blue; everyone else is red in a free-for-all).
 *
 * The match is shown in a floating window drawn like the game's own room panel: a small block that
 * can be dragged anywhere and opens into a panel.
 */
public final class Deathmatch {
    /** Frame layout, from the game's LanFrame: flags at 0, then ten 16-byte "damage I dealt to member i+1" records. */
    private static final int DEAD_FLAG = 0x80, DAMAGE = 4596, RECORD = 16, MEMBERS = 10, FRAME_END = 4768;
    private static final int[] TARGETS = { 5, 10, 20, 30, 50 };
    private static final int[] BOT_HITS = { 1, 3, 5, 10 };
    private static final int MINUTES = 15;

    private Deathmatch() {}

    // ---- written by the game thread ---------------------------------------------
    private static boolean wasDead;
    private static final byte[] lastDamage = new byte[RECORD * MEMBERS];
    private static boolean haveDamage;
    private static final int[] hitCounts = new int[MEMBERS];   // guarded by itself: changes of each damage record since the last report
    private static volatile String deathNote;                  // set when the local player has just died
    private static volatile boolean started;

    // ---- written by the poll thread ---------------------------------------------
    private static volatile Map<String, Object> match;   // null: no match in this room
    private static volatile boolean host;
    private static volatile long userId;
    private static volatile boolean inRoom;
    private static String seenState = "";
    private static volatile Map<String, Object> arena;   // the free-for-all of a public room; null elsewhere
    private static volatile long arenaNetwork;           // relay network known to be a public room
    private static int goneCount;
    private static long idleSince, fireAskedAt;
    /** Colour (RGB) of each name drawn above a head. Replaced as a whole, read by the game's draw thread. */
    private static volatile Map<String, Integer> nameColors = java.util.Collections.emptyMap();
    private static final int RED = 0xFF5C5C, BLUE = 0x5AA2FF, GOLD = 0xFFC83D;
    private static long seenEvent;                        // poll thread: the last room announcement shown
    private static volatile Map<String, Object> rank;     // the player's rank this season, as the server last said
    private static long seenRound;                        // poll thread: the last free-for-all round whose result was shown
    private static boolean fireWasOff;

    // ---- the host's choices for the next match (UI thread) ------------------------
    private static int targetChoice = 2, botHitsChoice = 1;
    private static boolean recordChoice = true;

    /** Called by the game with every frame of the local player. Must stay cheap and never throw. */
    public static void frame(byte[] frame) {
        try {
            if (frame == null || frame.length < FRAME_END) return;
            if (!started) start();
            boolean dead = (frame[0] & DEAD_FLAG) != 0;
            if (dead && !wasDead) {
                long now = SystemClock.uptimeMillis();
                lastDeathAt = now;
                // A death the rescue caused is not the player's: nobody scores for it.
                if (now < rescueUntil) rescueUntil = 0;
                else deathNote = hex(frame, DAMAGE, FRAME_END - DAMAGE);
            }
            wasDead = dead;
            for (int member = 0; member < MEMBERS; member++) {
                int at = DAMAGE + member * RECORD;
                boolean differs = false, empty = true;
                for (int i = 0; i < RECORD; i++) {
                    byte value = frame[at + i];
                    if (value != 0) empty = false;
                    if (value != lastDamage[member * RECORD + i]) differs = true;
                    lastDamage[member * RECORD + i] = value;
                }
                if (differs && !empty && haveDamage) {
                    synchronized (hitCounts) { hitCounts[member]++; }
                }
            }
            haveDamage = true;
        } catch (Throwable ignored) {
            // The match must never take the game down.
        }
    }

    private static String hex(byte[] data, int from, int length) {
        StringBuilder out = new StringBuilder(length * 2);
        for (int i = from; i < from + length; i++) {
            out.append(Character.forDigit((data[i] >> 4) & 15, 16)).append(Character.forDigit(data[i] & 15, 16));
        }
        return out.toString();
    }

    private static synchronized void start() {
        if (started) return;
        started = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    long wait = 3000;
                    try {
                        poll();
                        Map<String, Object> current = match;
                        if (arena != null || (current != null && "running".equals(Json.string(current, "state")))) wait = 1500;
                    } catch (Throwable ignored) {
                    }
                    Online.main.post(new Runnable() { @Override public void run() { render(); } });
                    try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                }
            }
        }, "xhyn-deathmatch");
        thread.setDaemon(true);
        thread.start();
        Online.main.postDelayed(new Runnable() {
            @Override public void run() {
                watch();
                Online.main.postDelayed(this, 1000);
            }
        }, 1000);
    }

    // ---- black screen after a respawn ----------------------------------------------
    // The game itself sometimes comes back from a death with rendering stopped (a black screen, native
    // FPS 0) until the player has died again once or twice. The fix lives in the game's native code,
    // so this works around it: when the game's own frame counter stays at zero after a death, the
    // game's "suicide" room tool is run for the player, and deaths caused that way are not reported.
    private static volatile long lastDeathAt, rescueUntil;
    private static long frozenSince, fpsSeenAt, lastRescueAt;
    private static int rescues;
    private static Method fpsCurrent;
    private static Field fpsInput, fpsState;
    private static int fpsDisabled, fpsPaused;
    private static boolean fpsBroken;

    /** The game's native frame rate from its frame statistics, or -1 when they are switched off or paused. */
    private static double nativeFps() {
        if (fpsBroken) return -1;
        try {
            if (fpsCurrent == null) {
                Class<?> snapshot = Class.forName("com.gtavsource.android.FrameGenerationStats$Snapshot");
                fpsInput = snapshot.getDeclaredField("inputFps");
                fpsState = snapshot.getDeclaredField("state");
                Field disabled = snapshot.getDeclaredField("DISABLED"), paused = snapshot.getDeclaredField("PAUSED");
                for (Field field : new Field[] { fpsInput, fpsState, disabled, paused }) field.setAccessible(true);
                fpsDisabled = disabled.getInt(null);
                fpsPaused = paused.getInt(null);
                Method current = Class.forName("com.gtavsource.android.FrameGenerationStats").getDeclaredMethod("current");
                current.setAccessible(true);
                fpsCurrent = current;
            }
            Object snapshot = fpsCurrent.invoke(null);
            int state = fpsState.getInt(snapshot);
            if (state == fpsDisabled || state == fpsPaused) return -1;
            double fps = fpsInput.getDouble(snapshot);
            return Double.isNaN(fps) ? -1 : fps;
        } catch (Throwable t) {
            fpsBroken = true;
            return -1;
        }
    }

    /** Whether the black screen can be noticed without the player's help. */
    private static boolean canWatch() { return fpsSeenAt != 0; }

    /** UI thread, once a second. */
    private static void watch() {
        try {
            long now = SystemClock.uptimeMillis();
            double fps = nativeFps();
            if (fps >= 5) {
                fpsSeenAt = now;
                frozenSince = 0;
                if (now - lastRescueAt > 5000) rescues = 0;
            } else if (fps >= 0 && fps < 1) {
                if (frozenSince == 0) frozenSince = now;
            } else {
                frozenSince = 0;
            }
            long died = lastDeathAt;
            boolean afterDeath = died != 0 && now - died >= 6000 && now - died < 90_000;
            if (afterDeath && canWatch() && frozenSince != 0 && now - frozenSince >= 8000 && rescues < 4 && now - lastRescueAt >= 9000
                    && OnlineBridge.inGame()) {
                if (rescues == 0) Online.toast("检测到复活后黑屏，正在自动自救（不计战绩）…");
                rescue();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Runs the game's suicide tool once; the death it causes is not reported. */
    private static void rescue() {
        Activity activity = gameActivity();
        if (activity == null) return;
        long now = SystemClock.uptimeMillis();
        lastRescueAt = now;
        rescues++;
        rescueUntil = now + 25_000;
        try {
            OnlineBridge.suicide(activity);
        } catch (Throwable t) {
            rescueUntil = 0;
        }
    }

    // ---- server ---------------------------------------------------------------------
    /** What every request carries: the room, this player's member number and, from the host, the bots in the room. */
    private static Map<String, Object> body() {
        Relay.Session session = Relay.active;
        Map<String, Object> body = Api.empty();
        body.put("networkId", session == null ? 0L : session.networkId);
        try {
            int slot = OnlineBridge.localSlot();
            if (slot > 0) body.put("slot", slot);
            if (session != null && session == Relay.hostSession) {
                List<Object> bots = new ArrayList<Object>();
                int[] slots = OnlineBridge.botSlots();
                for (int i = 0; i < slots.length; i++) if (slots[i] >= 0) bots.add(Json.of("index", i + 1, "slot", slots[i]));
                body.put("bots", bots);
            }
        } catch (Throwable ignored) {
        }
        return body;
    }

    /** Called by the game just before it draws a player's name tag: gives the paint the team's colour. */
    public static void tint(String shown, Paint paint) {
        try {
            Map<String, Integer> colors = nameColors;
            if (colors.isEmpty() || shown == null || paint == null) return;
            Integer color = colors.get(shown);
            if (color == null) {
                // A long name is cut short with "…"; a tag may also carry something after the name.
                String stem = shown.endsWith("…") ? shown.substring(0, shown.length() - 1) : null;
                for (Map.Entry<String, Integer> entry : colors.entrySet()) {
                    if (shown.startsWith(entry.getKey()) || (stem != null && !stem.isEmpty() && entry.getKey().startsWith(stem))) { color = entry.getValue(); break; }
                }
            }
            if (color != null) paint.setColor((paint.getColor() & 0xFF000000) | color);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void recolor() {
        Map<String, Integer> colors = new java.util.HashMap<String, Integer>();
        Map<String, Object> field = arena, current = match;
        if (field != null) {
            for (Object entry : Json.list(field, "players")) {
                if (!(entry instanceof Map) || Json.flag((Map<String, Object>) entry, "me")) continue;
                Map<String, Object> player = (Map<String, Object>) entry;
                colors.put(Json.string(player, "name"), Json.flag(player, "bounty") ? GOLD : RED);
            }
        } else if (current != null && !"finished".equals(Json.string(current, "state"))) {
            for (Object entry : Json.list(current, "players")) {
                if (!(entry instanceof Map)) continue;
                Map<String, Object> player = (Map<String, Object>) entry;
                colors.put(Json.string(player, "name"), Json.flag(player, "bounty") ? GOLD : "red".equals(Json.string(player, "team")) ? RED : BLUE);
            }
        }
        nameColors = colors;
    }

    /** Shows the room's announcements (kill streaks, bounties, round results) that have not been shown yet. */
    @SuppressWarnings("unchecked")
    private static void announcements(Map<String, Object> from) {
        if (from == null) return;
        for (Object entry : Json.list(from, "events")) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> event = (Map<String, Object>) entry;
            long id = Json.number(event, "id");
            if (id <= seenEvent) continue;
            seenEvent = id;
            String text = Json.string(event, "text");
            if (!text.isEmpty()) Online.toast(text);
        }
    }

    /** "黄金 · 260 分（距 铂金 还差 240）" */
    private static String rankText() {
        Map<String, Object> mine = rank;
        if (mine == null || Json.string(mine, "name").isEmpty()) return "";
        long points = Json.number(mine, "points"), next = Json.number(mine, "next");
        return "赛季段位：" + Json.string(mine, "name") + " · " + points + " 分" + (next > 0 ? "（再得 " + (next - points) + " 分升段）" : "（最高段位）");
    }

    private static String clock(long seconds) {
        seconds = Math.max(0, seconds);
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    private static void poll() {
        Relay.Session session = Relay.active;
        String token = Online.token();
        boolean playing = false, active = false;
        try { playing = OnlineBridge.inGame(); } catch (Throwable ignored) { }
        try { active = OnlineBridge.sessionActive(); } catch (Throwable ignored) { }
        if (session == null || session.networkId <= 0 || token.isEmpty() || !playing) {
            inRoom = false;
            match = null;
            arena = null;
            deathNote = null;
            synchronized (hitCounts) { java.util.Arrays.fill(hitCounts, 0); }
            recolor();
            if (!active) lostPublic(session, token);
            else idleSince = 0;
            return;
        }
        idleSince = 0;
        Map<String, Object> body = body();
        List<Object> hits = new ArrayList<Object>(), counts = new ArrayList<Object>();
        synchronized (hitCounts) {
            for (int member = 0; member < MEMBERS; member++) {
                if (hitCounts[member] == 0) continue;
                hits.add(member + 1);
                counts.add(hitCounts[member]);
                hitCounts[member] = 0;
            }
        }
        if (!hits.isEmpty()) {
            body.put("hits", hits);
            body.put("counts", counts);
        }
        String note = deathNote;
        deathNote = null;
        if (note != null) body.put("debug", note);
        try {
            if (arenaNetwork == session.networkId || Online.publicMode) {
                try {
                    if (note != null) body.put("death", true);
                    acceptArena(Online.api.call("POST", "/ffa/state", body, token), session.networkId);
                    return;
                } catch (ApiException e) {
                    if (e.status != 404) throw e;
                    arenaNetwork = 0;       // an ordinary room after all (or not listed yet)
                    arena = null;
                    body.remove("death");
                }
            }
            Map<String, Object> reply = Online.api.call("POST", note != null ? "/match/death" : "/match/state", body, token);
            if (Json.number(reply, "public") > 0) arenaNetwork = session.networkId;
            arena = null;
            accept(reply);
        } catch (ApiException e) {
            if (e.status == 403 || e.status == 404) { inRoom = false; match = null; arena = null; }
        }
        recolor();
    }

    /** The public room's standing. The host keeps friendly fire on; a member whose host has gone goes back in. */
    private static void acceptArena(Map<String, Object> reply, long network) {
        arenaNetwork = network;
        inRoom = true;
        host = Json.flag(reply, "host");
        userId = Json.number(reply, "userId");
        match = null;
        arena = reply;
        if (Json.map(reply, "rank") != null) rank = Json.map(reply, "rank");
        recolor();
        announcements(reply);
        Map<String, Object> round = Json.map(reply, "round");
        if (round != null && "running".equals(Json.string(round, "state")) && Json.number(round, "number") != seenRound) {
            seenRound = Json.number(round, "number");
            Online.toast("个人对战第 " + seenRound + " 局：先到 " + Json.number(round, "target") + " 分获胜，终结连杀者得双倍分");
        }
        long now = System.currentTimeMillis();
        if (host) {
            goneCount = 0;
            if (now - fireAskedAt > 10_000) {
                fireAskedAt = now;
                Online.main.post(new Runnable() {
                    @Override public void run() {
                        try { if (!OnlineBridge.friendlyFire()) OnlineBridge.setFriendlyFire(activityOrApp(), true); } catch (Throwable ignored) { }
                    }
                });
            }
        } else if (Json.flag(reply, "alive")) {
            goneCount = 0;
        } else if (++goneCount >= 2 && Online.publicMode && !Online.enteringPublic()) {
            goneCount = 0;
            arena = null;
            Online.toast("公共对战房的房主离开了，正在重新进入…");
            Online.enterPublic(activityOrApp());
        }
    }

    /**
     * The session ended while this phone was in a public room. If the room is still there the player
     * left on purpose; if its host has gone, go back in (the first one back opens the room again).
     */
    private static void lostPublic(Relay.Session session, String token) {
        if (!Online.publicMode || Online.enteringPublic()) { idleSince = 0; return; }
        long now = System.currentTimeMillis();
        if (idleSince == 0) { idleSince = now; return; }
        if (now - idleSince < 8000) return;
        idleSince = 0;
        boolean gone = false;
        try {
            if (session != null && session.networkId > 0 && session != Relay.hostSession && !token.isEmpty()) {
                Map<String, Object> reply = Online.api.call("POST", "/ffa/state", Json.of("networkId", session.networkId), token);
                gone = !Json.flag(reply, "alive");
            }
        } catch (ApiException e) {
            gone = e.status == 404;
        }
        if (gone) {
            Online.toast("公共对战房的房主离开了，正在重新进入…");
            Online.enterPublic(activityOrApp());
        } else {
            Online.publicMode = false;
        }
    }

    private static synchronized void accept(Map<String, Object> reply) {
        inRoom = true;
        host = Json.flag(reply, "host");
        userId = Json.number(reply, "userId");
        Map<String, Object> next = Json.map(reply, "match");
        match = next;
        if (Json.map(reply, "rank") != null) rank = Json.map(reply, "rank");
        recolor();
        if (next != null && "running".equals(Json.string(next, "state"))) announcements(next);
        final String state = next == null ? "" : Json.string(next, "state");
        if (state.equals(seenState)) return;
        final String before = seenState;
        seenState = state;
        final Map<String, Object> shown = next;
        Online.main.post(new Runnable() {
            @Override public void run() { changed(before, state, shown); }
        });
    }

    /** UI thread: the match moved on. The host's phone switches the room rules and tells everyone. */
    private static void changed(String before, String state, Map<String, Object> shown) {
        try {
            if ("running".equals(state)) {
                String line = "团队竞技开始：先到 " + Json.number(shown, "target") + " 次击杀的队伍获胜";
                if (host) {
                    fireWasOff = !OnlineBridge.friendlyFire();
                    if (fireWasOff) OnlineBridge.setFriendlyFire(activityOrApp(), true);
                    OnlineBridge.announce(line);
                }
                Online.toast(line);
            } else if ("finished".equals(state) && "running".equals(before)) {
                String line = "团队竞技结束：" + result(shown);
                if (host) {
                    if (fireWasOff) OnlineBridge.setFriendlyFire(activityOrApp(), false);
                    fireWasOff = false;
                    OnlineBridge.announce(line);
                }
                Online.toast(line);
            } else if ("lobby".equals(state)) {
                Online.toast("房主创建了团队竞技，点悬浮窗选择队伍");
            }
        } catch (Throwable ignored) {
        }
    }

    private static Context activityOrApp() {
        Activity activity = gameActivity();
        return activity != null ? activity : Online.context();
    }

    private static String result(Map<String, Object> shown) {
        String winner = Json.string(shown, "winner");
        return ("red".equals(winner) ? "红队获胜 · " : "blue".equals(winner) ? "蓝队获胜 · " : "平局 · ") + score(shown);
    }

    private static String score(Map<String, Object> shown) {
        return "红 " + Json.number(shown, "red") + " : " + Json.number(shown, "blue") + " 蓝";
    }

    /** Runs a host or player action off the UI thread, then redraws. */
    private static void act(final String path, final Map<String, Object> extra, final String done) {
        final String token = Online.token();
        final Map<String, Object> body = body();
        if (extra != null) body.putAll(extra);
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                String message = done;
                try {
                    accept(Online.api.call("POST", path, body, token));
                } catch (ApiException e) {
                    message = e.getMessage();
                } catch (RuntimeException e) {
                    message = "出错了：" + e.getClass().getSimpleName();
                }
                final String shown = message;
                Online.main.post(new Runnable() {
                    @Override public void run() {
                        if (shown != null && !shown.isEmpty()) Online.toast(shown);
                        render();
                    }
                });
            }
        }, "xhyn-deathmatch-action");
        thread.setDaemon(true);
        thread.start();
    }

    private static Runnable post(final String path, final Map<String, Object> extra, final String done) {
        return new Runnable() { @Override public void run() { act(path, extra, done); } };
    }

    // ---- floating window ----------------------------------------------------------
    private static final int PANEL = 0xE614161B, BUTTON = 0x24FFFFFF, BUTTON_EDGE = 0x3DFFFFFF, MUTED = 0xFFAEB6C2;

    /** One line of the open panel: a heading, a paragraph or a button. */
    private static final class Item {
        final int kind;          // 0 heading, 1 text, 2 button
        final String label;
        final Runnable action;
        final int color;         // 0: the usual colour of its kind
        Item(int kind, String label, Runnable action) { this(kind, label, action, 0); }
        Item(int kind, String label, Runnable action, int color) { this.kind = kind; this.label = label; this.action = action; this.color = color; }
    }

    private static WeakReference<Activity> windowActivity = new WeakReference<Activity>(null);
    private static LinearLayout root, box;
    private static TextView head;
    private static ScrollView scroll;
    private static boolean expanded;
    private static String drawn = "";

    /** The activity the game is running in: the game's own floating window remembers it. */
    private static Activity gameActivity() {
        try {
            Field field = Class.forName("com.gtavsource.android.lan.LanFloatingWindow").getDeclaredField("activity");
            field.setAccessible(true);
            Object reference = field.get(null);
            Object activity = reference instanceof WeakReference ? ((WeakReference<?>) reference).get() : null;
            if (activity instanceof Activity && !((Activity) activity).isFinishing()) return (Activity) activity;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int dp(float value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable shape(int color, float radius, int edge) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        if (edge != 0) shape.setStroke(Math.max(1, dp(1)), edge);
        return shape;
    }

    private static SharedPreferences prefs() {
        return Online.context().getSharedPreferences("xhyn_online", Context.MODE_PRIVATE);
    }

    /** Keeps the window on the screen. */
    private static void clamp() {
        try {
            if (root == null || !(root.getParent() instanceof View)) return;
            View parent = (View) root.getParent();
            float maxX = Math.max(0, parent.getWidth() - root.getWidth()), maxY = Math.max(0, parent.getHeight() - root.getHeight());
            root.setX(Math.max(0, Math.min(maxX, root.getX())));
            root.setY(Math.max(0, Math.min(maxY, root.getY())));
        } catch (Throwable ignored) {
        }
    }

    private static void build(final Activity activity) {
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(shape(PANEL, dp(20), 0x33FFFFFF));
        head = new TextView(activity);
        head.setTextColor(Color.WHITE);
        head.setTextSize(14);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(9), dp(14), dp(9));
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final int limit = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.62f);
        scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), 0, dp(14), dp(14));
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setVisibility(View.GONE);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The title bar moves the window when dragged and opens or closes it when tapped.
        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        head.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startX, startY;
            private boolean dragging;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = root.getX(); startY = root.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                        if (!dragging && Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                        dragging = true;
                        root.setX(startX + dx);
                        root.setY(startY + dy);
                        clamp();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            prefs().edit().putFloat("match_x", root.getX()).putFloat("match_y", root.getY()).apply();
                        } else {
                            expanded = !expanded;
                            drawn = "";
                            render();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        expanded = false;
        drawn = "";
        activity.addContentView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
        SharedPreferences saved = prefs();
        root.setX(saved.getFloat("match_x", dp(16)));
        root.setY(saved.getFloat("match_y", dp(110)));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) clamp();
            }
        });
        windowActivity = new WeakReference<Activity>(activity);
    }

    /** UI thread: draws the window for the current state. Cheap when nothing changed. */
    private static void render() {
        try {
            Activity activity = gameActivity();
            Map<String, Object> current = match;
            Map<String, Object> field = arena;
            // A racing room has no window of its own: the race is run from the game's host tools.
            boolean visible = activity != null && inRoom && (field != null || !"race".equals(Online.roomMode));
            if (!visible) {
                if (root != null) root.setVisibility(View.GONE);
                return;
            }
            if (root == null || windowActivity.get() != activity || root.getParent() == null) build(activity);
            root.setVisibility(View.VISIBLE);
            String title = field != null ? arenaSummary(field) : summary(current);
            head.setText(colored(expanded ? title + "　　收起 ▲" : title, field == null && current != null));
            ViewGroup.LayoutParams params = root.getLayoutParams();
            int width = expanded ? Math.min(dp(320), activity.getResources().getDisplayMetrics().widthPixels - dp(24)) : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params.width != width) {
                params.width = width;
                root.setLayoutParams(params);
            }
            scroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
            if (!expanded) return;
            List<Item> items = field != null ? arenaItems(field) : items(current);
            items.add(new Item(0, canWatch() ? "复活后黑屏会自动自救；没有恢复时也可以手动点。" : "复活后黑屏（原版游戏的问题）时点这里，可能要点两三次。打开屏幕顶部的帧率显示后可以自动自救。", null));
            items.add(new Item(2, "黑屏自救（不计战绩）", new Runnable() {
                @Override public void run() {
                    rescue();
                    Online.toast("正在自救，这次阵亡不计入战绩");
                }
            }));
            StringBuilder key = new StringBuilder();
            for (Item item : items) key.append(item.kind).append(item.color).append(item.label).append('\n');
            if (key.toString().equals(drawn)) return;
            drawn = key.toString();
            box.removeAllViews();
            for (final Item item : items) {
                TextView line = new TextView(activity);
                line.setText(item.label);
                LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (item.kind == 0) {
                    line.setTextColor(MUTED);
                    line.setTextSize(13);
                    place.topMargin = dp(10);
                } else if (item.kind == 1) {
                    line.setTextColor(Color.WHITE);
                    line.setTextSize(14);
                    line.setLineSpacing(dp(3), 1f);
                    place.topMargin = dp(4);
                } else {
                    line.setTextColor(Color.WHITE);
                    line.setTextSize(15);
                    line.setGravity(Gravity.CENTER);
                    line.setPadding(dp(10), dp(11), dp(10), dp(11));
                    line.setBackground(shape(BUTTON, dp(14), BUTTON_EDGE));
                    line.setClickable(true);
                    line.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) { item.action.run(); }
                    });
                    place.topMargin = dp(8);
                }
                if (item.color != 0) line.setTextColor(0xFF000000 | item.color);
                box.addView(line, place);
            }
        } catch (Throwable ignored) {
        }
    }

    /** The score line with "红 3" in red and "5 蓝" in blue. */
    private static CharSequence colored(String line, boolean teams) {
        int colon = line.indexOf(" : "), red = line.indexOf("红 "), blue = line.indexOf(" 蓝");
        if (!teams || colon < 0 || red < 0 || red > colon || blue < colon) return line;
        SpannableString text = new SpannableString(line);
        text.setSpan(new ForegroundColorSpan(0xFF000000 | RED), red, colon, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(0xFF000000 | BLUE), colon + 3, blue + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    // ---- free-for-all in a public room ---------------------------------------------
    private static String arenaSummary(Map<String, Object> field) {
        Map<String, Object> me = Json.map(field, "me"), round = Json.map(field, "round");
        if (round == null) return "个人对战 · 击杀 " + (me == null ? 0 : Json.number(me, "kills")) + " / 阵亡 " + (me == null ? 0 : Json.number(me, "deaths"));
        long now = Json.number(round, "now");
        if (!"running".equals(Json.string(round, "state"))) return "本局结束 · " + Json.string(round, "winner") + " 获胜 · " + clock(Json.number(round, "nextAt") - now) + " 后下一局";
        return "个人对战 " + (me == null ? 0 : Json.number(me, "score")) + "/" + Json.number(round, "target") + " 分 · " + clock(Json.number(round, "endsAt") - now);
    }

    private static List<Item> arenaItems(Map<String, Object> field) {
        List<Item> items = new ArrayList<Item>();
        Map<String, Object> me = Json.map(field, "me");
        items.add(new Item(0, "公共对战房 " + Json.number(field, "public") + (host ? " · 你是房主" : ""), null));
        Map<String, Object> round = Json.map(field, "round");
        boolean ended = round != null && !"running".equals(Json.string(round, "state"));
        if (round != null && ended) {
            StringBuilder top = new StringBuilder("第 " + Json.number(round, "number") + " 局结束，" + Json.string(round, "winner") + " 获胜！");
            int order = 0;
            for (Object entry : Json.list(round, "top")) {
                if (!(entry instanceof Map)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> one = (Map<String, Object>) entry;
                top.append('\n').append(++order).append(". ").append(Json.string(one, "name")).append("　").append(Json.number(one, "score")).append(" 分 · 击杀 ")
                        .append(Json.number(one, "kills")).append(" · 阵亡 ").append(Json.number(one, "deaths"));
            }
            top.append("\n").append(clock(Json.number(round, "nextAt") - Json.number(round, "now"))).append(" 后开始下一局，休息时间不计分");
            items.add(new Item(1, top.toString(), null, GOLD));
        } else if (round != null) {
            items.add(new Item(1, "第 " + Json.number(round, "number") + " 局 · 先到 " + Json.number(round, "target") + " 分获胜 · 剩余 "
                    + clock(Json.number(round, "endsAt") - Json.number(round, "now")) + "\n时间到按得分定胜负；击杀 1 分，终结金色名字（连杀 3 次以上）的玩家 2 分", null));
        }
        if (me != null) items.add(new Item(1, "本局：" + Json.number(me, "score") + " 分 · 击杀 " + Json.number(me, "kills") + " · 阵亡 " + Json.number(me, "deaths")
                + (Json.number(me, "streak") >= 2 ? " · 连杀 " + Json.number(me, "streak") : "")
                + "\n累计：击杀 " + Json.number(me, "totalKills") + " · 阵亡 " + Json.number(me, "totalDeaths"), null));
        String standing = rankText();
        if (!standing.isEmpty()) items.add(new Item(1, standing, null, GOLD));
        items.add(new Item(0, "房间内排名（本局）", null));
        int place = 0;
        for (Object entry : Json.list(field, "players")) {
            if (!(entry instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> player = (Map<String, Object>) entry;
            boolean mine = Json.flag(player, "me");
            boolean bounty = Json.flag(player, "bounty");
            items.add(new Item(1, ++place + ". " + Json.string(player, "name") + (mine ? "（我）" : "") + (Json.string(player, "rank").isEmpty() ? "" : " [" + Json.string(player, "rank") + "]")
                    + "　" + Json.number(player, "score") + " 分 · 击杀 " + Json.number(player, "kills") + " · 阵亡 " + Json.number(player, "deaths")
                    + (bounty ? " · 悬赏中（连杀 " + Json.number(player, "streak") + "）" : ""), null, bounty ? GOLD : mine ? 0 : RED));
        }
        String feed = feed(field);
        if (!feed.isEmpty()) {
            items.add(new Item(0, "最近", null));
            items.add(new Item(1, feed, null));
        }
        items.add(new Item(0, "各打各的，只算自己的击杀；赛季段位榜、今日榜、本周榜和总榜在网页“排行榜”里。"
                + (host ? "\n你退出后，房间会由下一位玩家接手。" : ""), null));
        items.add(new Item(2, "退出公共对战房", new Runnable() {
            @Override public void run() {
                Online.leavePublic();
                Online.toast("已退出公共对战房");
            }
        }));
        return items;
    }

    private static String feed(Map<String, Object> from) {
        StringBuilder feed = new StringBuilder();
        for (Object entry : Json.list(from, "feed")) {
            if (!(entry instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> line = (Map<String, Object>) entry;
            String killer = Json.string(line, "killer");
            if (feed.length() > 0) feed.append('\n');
            feed.append(killer.isEmpty() ? Json.string(line, "victim") + " 阵亡" : killer + " 击倒了 " + Json.string(line, "victim"));
        }
        return feed.toString();
    }

    /** The one-line state shown on the collapsed block and in the title bar. */
    private static String summary(Map<String, Object> current) {
        if (current == null) return host ? "团队竞技" : "联机";
        String state = Json.string(current, "state");
        if ("lobby".equals(state)) return "团队竞技 · 选队中";
        if ("running".equals(state)) {
            long left = Math.max(0, Json.number(current, "endsAt") - Json.number(current, "now"));
            return score(current) + " · " + left / 60 + ":" + (left % 60 < 10 ? "0" : "") + left % 60;
        }
        return "结束 · " + score(current);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> players(Map<String, Object> current, String team) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object entry : Json.list(current, "players")) {
            if (entry instanceof Map && team.equals(Json.string((Map<String, Object>) entry, "team"))) out.add((Map<String, Object>) entry);
        }
        return out;
    }

    private static boolean isMe(Map<String, Object> player) {
        return !Json.flag(player, "bot") && Json.number(player, "userId") == userId;
    }

    private static String teamText(Map<String, Object> current, String team, boolean scores) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> player : players(current, team)) {
            if (out.length() > 0) out.append('\n');
            out.append(Json.string(player, "name"));
            if (isMe(player)) out.append("（我）");
            if (scores) out.append("　击杀 ").append(Json.number(player, "kills")).append(" · 阵亡 ").append(Json.number(player, "deaths"));
            if (scores && Json.flag(player, "bounty")) out.append(" · 悬赏中（连杀 ").append(Json.number(player, "streak")).append("）");
        }
        return out.length() == 0 ? "（还没有人）" : out.toString();
    }

    private static String myTeam(Map<String, Object> current) {
        for (String team : new String[] { "red", "blue" }) {
            for (Map<String, Object> player : players(current, team)) if (isMe(player)) return team;
        }
        return "";
    }

    private static void teams(List<Item> items, Map<String, Object> current, boolean scores) {
        items.add(new Item(0, "红队 " + players(current, "red").size() + " / 5", null, RED));
        items.add(new Item(1, teamText(current, "red", scores), null, RED));
        items.add(new Item(0, "蓝队 " + players(current, "blue").size() + " / 5", null, BLUE));
        items.add(new Item(1, teamText(current, "blue", scores), null, BLUE));
    }

    /** The host's settings for the next match, each a button that steps through its choices. */
    private static void settings(List<Item> items, String create) {
        items.add(new Item(0, "比赛设置（点一下切换）", null));
        items.add(new Item(2, "目标击杀数：" + TARGETS[targetChoice], new Runnable() {
            @Override public void run() { targetChoice = (targetChoice + 1) % TARGETS.length; render(); }
        }));
        items.add(new Item(2, "击倒人机需命中：" + BOT_HITS[botHitsChoice] + " 次", new Runnable() {
            @Override public void run() { botHitsChoice = (botHitsChoice + 1) % BOT_HITS.length; render(); }
        }));
        items.add(new Item(2, recordChoice ? "本场战绩：记录" : "本场战绩：不记录（测试用）", new Runnable() {
            @Override public void run() { recordChoice = !recordChoice; render(); }
        }));
        items.add(new Item(2, create, new Runnable() {
            @Override public void run() {
                act("/match/create", Json.of("target", TARGETS[targetChoice], "minutes", MINUTES,
                        "botHits", BOT_HITS[botHitsChoice], "record", recordChoice), "已创建，请大家选择队伍");
            }
        }));
    }

    private static List<Item> items(Map<String, Object> current) {
        List<Item> items = new ArrayList<Item>();
        String state = current == null ? "" : Json.string(current, "state");
        if (current == null && !host) {
            items.add(new Item(1, "team".equals(Online.roomMode) ? "房主还没有开团队竞技，等房主创建后在这里选队。" : "房主还没有开团队竞技。\n赛车由房主在“房主专属”里开启。", null));
            return items;
        }
        if (current == null) {
            items.add(new Item(1, "两队对战，每队最多 5 人，先达到目标击杀数的队伍获胜；最长 " + MINUTES + " 分钟，时间到按比分定胜负。\n"
                    + "开始后自动打开“队友伤害”，结束后恢复。对方每阵亡一次本队得 1 分。\n房间里的人机会自动补进人少的一队。", null));
            settings(items, "创建团队竞技");
            return items;
        }
        String rules = "目标 " + Json.number(current, "target") + " · 最长 " + Json.number(current, "minutes") + " 分钟 · 人机命中 "
                + Json.number(current, "botHits") + " 次" + (Json.flag(current, "record") ? "" : " · 本场不记录");
        if ("lobby".equals(state)) {
            String mine = myTeam(current);
            items.add(new Item(0, rules, null));
            teams(items, current, false);
            items.add(new Item(0, mine.isEmpty() ? "请选择队伍" : "你在" + ("red".equals(mine) ? "红队" : "蓝队") + "，等房主开始", null));
            if (!"red".equals(mine)) items.add(new Item(2, "加入红队", post("/match/team", Json.of("team", "red"), "已加入红队"), RED));
            if (!"blue".equals(mine)) items.add(new Item(2, "加入蓝队", post("/match/team", Json.of("team", "blue"), "已加入蓝队"), BLUE));
            if (!mine.isEmpty()) items.add(new Item(2, "退出队伍", post("/match/team", Json.of("team", null), "已退出队伍")));
            if (host) {
                items.add(new Item(2, "开始", post("/match/start", null, null)));
                items.add(new Item(2, "取消这场比赛", post("/match/end", null, "已取消")));
            }
            return items;
        }
        boolean running = "running".equals(state);
        items.add(new Item(0, running ? rules + "\n终结金色名字（连杀 3 次以上）的玩家，本队得 2 分" : result(current), null));
        teams(items, current, true);
        String standing = rankText();
        if (!standing.isEmpty()) items.add(new Item(1, standing, null, GOLD));
        String feed = feed(current);
        if (!feed.isEmpty()) {
            items.add(new Item(0, "最近", null));
            items.add(new Item(1, feed, null));
        }
        if (host && running) items.add(new Item(2, "提前结束（按当前比分）", post("/match/end", null, null)));
        if (host && !running) {
            settings(items, "再来一场");
            items.add(new Item(2, "收起记分牌", post("/match/end", null, null)));
        }
        return items;
    }
}
