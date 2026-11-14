// SPDX-License-Identifier: MIT
package com.xhynv.online;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
            if (dead && !wasDead) deathNote = hex(frame, DAMAGE, FRAME_END - DAMAGE);
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
                        if (current != null && "running".equals(Json.string(current, "state"))) wait = 1500;
                    } catch (Throwable ignored) {
                    }
                    Online.main.post(new Runnable() { @Override public void run() { render(); } });
                    try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                }
            }
        }, "xhyn-deathmatch");
        thread.setDaemon(true);
        thread.start();
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

    private static void poll() {
        Relay.Session session = Relay.active;
        String token = Online.token();
        boolean playing = false;
        try { playing = OnlineBridge.inGame(); } catch (Throwable ignored) { }
        if (session == null || session.networkId <= 0 || token.isEmpty() || !playing) {
            inRoom = false;
            match = null;
            deathNote = null;
            synchronized (hitCounts) { java.util.Arrays.fill(hitCounts, 0); }
            return;
        }
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
            accept(Online.api.call("POST", note != null ? "/match/death" : "/match/state", body, token));
        } catch (ApiException e) {
            if (e.status == 403 || e.status == 404) { inRoom = false; match = null; }
        }
    }

    private static synchronized void accept(Map<String, Object> reply) {
        inRoom = true;
        host = Json.flag(reply, "host");
        userId = Json.number(reply, "userId");
        Map<String, Object> next = Json.map(reply, "match");
        match = next;
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
        Item(int kind, String label, Runnable action) { this.kind = kind; this.label = label; this.action = action; }
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
            boolean visible = activity != null && inRoom && (current != null || host);
            if (!visible) {
                if (root != null) root.setVisibility(View.GONE);
                return;
            }
            if (root == null || windowActivity.get() != activity || root.getParent() == null) build(activity);
            root.setVisibility(View.VISIBLE);
            head.setText(expanded ? summary(current) + "　　收起 ▲" : summary(current));
            ViewGroup.LayoutParams params = root.getLayoutParams();
            int width = expanded ? Math.min(dp(320), activity.getResources().getDisplayMetrics().widthPixels - dp(24)) : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params.width != width) {
                params.width = width;
                root.setLayoutParams(params);
            }
            scroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
            if (!expanded) return;
            List<Item> items = items(current);
            StringBuilder key = new StringBuilder();
            for (Item item : items) key.append(item.kind).append(item.label).append('\n');
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
                box.addView(line, place);
            }
        } catch (Throwable ignored) {
        }
    }

    /** The one-line state shown on the collapsed block and in the title bar. */
    private static String summary(Map<String, Object> current) {
        if (current == null) return "团队竞技";
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
        items.add(new Item(0, "红队 " + players(current, "red").size() + " / 5", null));
        items.add(new Item(1, teamText(current, "red", scores), null));
        items.add(new Item(0, "蓝队 " + players(current, "blue").size() + " / 5", null));
        items.add(new Item(1, teamText(current, "blue", scores), null));
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
            if (!"red".equals(mine)) items.add(new Item(2, "加入红队", post("/match/team", Json.of("team", "red"), "已加入红队")));
            if (!"blue".equals(mine)) items.add(new Item(2, "加入蓝队", post("/match/team", Json.of("team", "blue"), "已加入蓝队")));
            if (!mine.isEmpty()) items.add(new Item(2, "退出队伍", post("/match/team", Json.of("team", null), "已退出队伍")));
            if (host) {
                items.add(new Item(2, "开始", post("/match/start", null, null)));
                items.add(new Item(2, "取消这场比赛", post("/match/end", null, "已取消")));
            }
            return items;
        }
        boolean running = "running".equals(state);
        items.add(new Item(0, running ? rules : result(current), null));
        teams(items, current, true);
        StringBuilder feed = new StringBuilder();
        for (Object entry : Json.list(current, "feed")) {
            if (!(entry instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> line = (Map<String, Object>) entry;
            String killer = Json.string(line, "killer");
            if (feed.length() > 0) feed.append('\n');
            feed.append(killer.isEmpty() ? Json.string(line, "victim") + " 阵亡" : killer + " 击倒了 " + Json.string(line, "victim"));
        }
        if (feed.length() > 0) {
            items.add(new Item(0, "最近", null));
            items.add(new Item(1, feed.toString(), null));
        }
        if (host && running) items.add(new Item(2, "提前结束（按当前比分）", post("/match/end", null, null)));
        if (host && !running) {
            settings(items, "再来一场");
            items.add(new Item(2, "收起记分牌", post("/match/end", null, null)));
        }
        return items;
    }
}
