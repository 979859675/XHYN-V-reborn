// SPDX-License-Identifier: MIT
package com.xhynv.online;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Team deathmatch for relayed rooms: two teams of up to five, first to the host's kill target wins.
 *
 * The lobby server keeps the match (teams, score, result). Each phone reports its own player's deaths
 * and which members its player has just damaged; a death is a point for the other team and the last
 * one to damage the victim gets the kill. Everyone polls the server for the score, so the game's own
 * network protocol is untouched.
 */
public final class Deathmatch {
    /** Frame layout, from the game's LanFrame: flags at 0, then ten 16-byte "damage I dealt to member i+1" records. */
    private static final int DEAD_FLAG = 0x80, DAMAGE = 4596, RECORD = 16, MEMBERS = 10, FRAME_END = 4768;
    private static final String[] TARGET_LABELS = { "先到 5 次击杀", "先到 10 次击杀", "先到 20 次击杀", "先到 30 次击杀", "先到 50 次击杀" };
    private static final int[] TARGETS = { 5, 10, 20, 30, 50 };
    private static final int MINUTES = 15;

    private Deathmatch() {}

    // ---- written by the game thread ---------------------------------------------
    private static boolean wasDead;
    private static final byte[] lastDamage = new byte[RECORD * MEMBERS];
    private static boolean haveDamage;
    private static final AtomicInteger hits = new AtomicInteger();   // bit i = damaged member i+1 since the last report
    private static volatile String deathNote;                         // set when the local player has just died
    private static volatile boolean started;

    // ---- written by the poll thread ---------------------------------------------
    private static volatile Map<String, Object> match;   // null: no match in this room
    private static volatile boolean host;
    private static volatile long userId;
    private static volatile boolean inRoom;
    private static volatile String problem = "";
    private static String seenState = "";
    private static boolean fireWasOff;

    /** Called by the game with every frame of the local player. Must stay cheap and never throw. */
    public static void frame(byte[] frame) {
        try {
            if (frame == null || frame.length < FRAME_END) return;
            if (!started) start();
            boolean dead = (frame[0] & DEAD_FLAG) != 0;
            if (dead && !wasDead) deathNote = hex(frame, DAMAGE, FRAME_END - DAMAGE);
            wasDead = dead;
            int changed = 0;
            for (int member = 0; member < MEMBERS; member++) {
                int at = DAMAGE + member * RECORD;
                boolean differs = false, empty = true;
                for (int i = 0; i < RECORD; i++) {
                    byte value = frame[at + i];
                    if (value != 0) empty = false;
                    if (value != lastDamage[member * RECORD + i]) differs = true;
                    lastDamage[member * RECORD + i] = value;
                }
                if (differs && !empty && haveDamage) changed |= 1 << member;
            }
            haveDamage = true;
            if (changed != 0) {
                int old;
                do { old = hits.get(); } while (!hits.compareAndSet(old, old | changed));
            }
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
    private static Map<String, Object> body() {
        Relay.Session session = Relay.active;
        Map<String, Object> body = Api.empty();
        body.put("networkId", session == null ? 0L : session.networkId);
        int slot = 0;
        try { slot = OnlineBridge.localSlot(); } catch (Throwable ignored) { }
        if (slot > 0) body.put("slot", slot);
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
            hits.set(0);
            return;
        }
        Map<String, Object> body = body();
        int mask = hits.getAndSet(0);
        if (mask != 0) {
            List<Object> list = new ArrayList<Object>();
            for (int member = 0; member < MEMBERS; member++) if ((mask & (1 << member)) != 0) list.add(member + 1);
            body.put("hits", list);
        }
        String note = deathNote;
        deathNote = null;
        if (note != null) body.put("debug", note);
        try {
            accept(Online.api.call("POST", note != null ? "/match/death" : "/match/state", body, token));
            problem = "";
        } catch (ApiException e) {
            if (e.status == 403 || e.status == 404) { inRoom = false; match = null; }
            problem = e.getMessage() == null ? "" : e.getMessage();
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
                String line = "枪战开始：先到 " + Json.number(shown, "target") + " 次击杀的队伍获胜";
                if (host) {
                    fireWasOff = !OnlineBridge.friendlyFire();
                    if (fireWasOff) OnlineBridge.setFriendlyFire(activityOrApp(), true);
                    OnlineBridge.announce(line);
                }
                Online.toast(line);
            } else if ("finished".equals(state) && "running".equals(before)) {
                String line = "枪战结束：" + result(shown);
                if (host) {
                    if (fireWasOff) OnlineBridge.setFriendlyFire(activityOrApp(), false);
                    fireWasOff = false;
                    OnlineBridge.announce(line);
                }
                Online.toast(line);
            } else if ("lobby".equals(state)) {
                Online.toast("房主创建了枪战，点屏幕上方的“枪战”选择队伍");
            }
        } catch (Throwable ignored) {
        }
    }

    private static android.content.Context activityOrApp() {
        Activity activity = gameActivity();
        return activity != null ? activity : Online.context();
    }

    private static String result(Map<String, Object> shown) {
        String winner = Json.string(shown, "winner");
        String score = "红队 " + Json.number(shown, "red") + " : " + Json.number(shown, "blue") + " 蓝队";
        return ("red".equals(winner) ? "红队获胜 · " : "blue".equals(winner) ? "蓝队获胜 · " : "平局 · ") + score;
    }

    /** Runs a host or player action, then shows the panel again with the new state. */
    private static void act(final String path, final Map<String, Object> body, final String done) {
        final String token = Online.token();
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
                        openPanel();
                    }
                });
            }
        }, "xhyn-deathmatch-action");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- UI -------------------------------------------------------------------------
    private static WeakReference<Activity> chipActivity = new WeakReference<Activity>(null);
    private static WeakReference<TextView> chipView = new WeakReference<TextView>(null);
    private static WeakReference<AlertDialog> panel = new WeakReference<AlertDialog>(null);

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

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** UI thread: keeps the small score chip at the top of the game screen up to date. */
    private static void render() {
        try {
            Activity activity = gameActivity();
            TextView chip = chipView.get();
            Map<String, Object> current = match;
            boolean visible = activity != null && inRoom && (current != null || host);
            if (!visible) {
                if (chip != null) chip.setVisibility(View.GONE);
                return;
            }
            if (chip == null || chipActivity.get() != activity || chip.getParent() == null) {
                chip = new TextView(activity);
                chip.setTextColor(Color.WHITE);
                chip.setTextSize(13);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(activity, 12), dp(activity, 5), dp(activity, 12), dp(activity, 5));
                GradientDrawable back = new GradientDrawable();
                back.setColor(0xCC15171C);
                back.setCornerRadius(dp(activity, 14));
                chip.setBackground(back);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { openPanel(); }
                });
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                params.topMargin = dp(activity, 58);
                activity.addContentView(chip, params);
                chipView = new WeakReference<TextView>(chip);
                chipActivity = new WeakReference<Activity>(activity);
            }
            chip.setVisibility(View.VISIBLE);
            chip.setText(chipText(current));
        } catch (Throwable ignored) {
        }
    }

    private static String chipText(Map<String, Object> current) {
        if (current == null) return "枪战";
        String state = Json.string(current, "state");
        if ("lobby".equals(state)) return "枪战 · 点这里选队";
        String score = "红 " + Json.number(current, "red") + " : " + Json.number(current, "blue") + " 蓝";
        if ("running".equals(state)) {
            long left = Math.max(0, Json.number(current, "endsAt") - Json.number(current, "now"));
            return score + " · 目标 " + Json.number(current, "target") + " · " + left / 60 + ":" + (left % 60 < 10 ? "0" : "") + left % 60;
        }
        return "枪战结束 · " + result(current);
    }

    private static String teamList(Map<String, Object> current, String team, boolean scores) {
        StringBuilder out = new StringBuilder();
        for (Object entry : Json.list(current, "players")) {
            if (!(entry instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> player = (Map<String, Object>) entry;
            if (!team.equals(Json.string(player, "team"))) continue;
            out.append("\n    ").append(Json.string(player, "name"));
            if (Json.number(player, "userId") == userId) out.append("（我）");
            if (scores) out.append("  击杀 ").append(Json.number(player, "kills")).append(" · 阵亡 ").append(Json.number(player, "deaths"));
        }
        return out.length() == 0 ? "\n    （还没有人）" : out.toString();
    }

    private static String myTeam(Map<String, Object> current) {
        for (Object entry : Json.list(current, "players")) {
            if (!(entry instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> player = (Map<String, Object>) entry;
            if (Json.number(player, "userId") == userId) return Json.string(player, "team");
        }
        return "";
    }

    private static void button(LinearLayout box, String label, final Runnable action) {
        Button button = new Button(box.getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                AlertDialog open = panel.get();
                if (open != null) open.dismiss();
                action.run();
            }
        });
        box.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static Runnable post(final String path, final Map<String, Object> extra, final String done) {
        return new Runnable() {
            @Override public void run() {
                Map<String, Object> body = body();
                if (extra != null) body.putAll(extra);
                act(path, body, done);
            }
        };
    }

    /** UI thread: the match panel, with what this player can do right now. */
    private static void openPanel() {
        try {
            final Activity activity = gameActivity();
            if (activity == null) return;
            AlertDialog old = panel.get();
            if (old != null && old.isShowing()) old.dismiss();
            final Map<String, Object> current = match;
            final String state = current == null ? "" : Json.string(current, "state");
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 18);
            box.setPadding(pad, dp(activity, 8), pad, 0);
            TextView text = new TextView(activity);
            text.setTextSize(15);
            box.addView(text);

            if (current == null) {
                text.setText(host
                        ? "两队对战，每队最多 5 人。队伍先达到目标击杀数就获胜，最长 " + MINUTES + " 分钟，时间到按比分定胜负。\n\n"
                            + "开始后会自动打开“队友伤害”，结束后恢复。对方每阵亡一次本队得 1 分：摔死、被警察击倒、被队友误伤都算对方得分。"
                        : "房主还没有创建枪战。");
                if (host) button(box, "创建枪战", new Runnable() {
                    @Override public void run() { chooseTarget(activity); }
                });
            } else if ("lobby".equals(state)) {
                String mine = myTeam(current);
                text.setText("目标：先到 " + Json.number(current, "target") + " 次击杀 · 最长 " + Json.number(current, "minutes") + " 分钟\n\n"
                        + "红队：" + teamList(current, "red", false) + "\n\n蓝队：" + teamList(current, "blue", false)
                        + "\n\n" + (mine.isEmpty() ? "请选择队伍。" : "你在" + ("red".equals(mine) ? "红队" : "蓝队") + "，等房主开始。"));
                if (!"red".equals(mine)) button(box, "加入红队", post("/match/team", Json.of("team", "red"), "已加入红队"));
                if (!"blue".equals(mine)) button(box, "加入蓝队", post("/match/team", Json.of("team", "blue"), "已加入蓝队"));
                if (!mine.isEmpty()) button(box, "退出队伍", post("/match/team", Json.of("team", null), "已退出队伍"));
                if (host) {
                    button(box, "开始枪战", post("/match/start", null, null));
                    button(box, "取消这场枪战", post("/match/end", null, "已取消"));
                }
            } else {
                boolean running = "running".equals(state);
                text.setText((running ? "进行中 · 目标 " + Json.number(current, "target") + "\n红队 " + Json.number(current, "red") + " : "
                                + Json.number(current, "blue") + " 蓝队" : result(current))
                        + "\n\n红队：" + teamList(current, "red", true) + "\n\n蓝队：" + teamList(current, "blue", true) + feed(current));
                if (host && running) button(box, "提前结束（按当前比分）", post("/match/end", null, null));
                if (host && !running) {
                    button(box, "再来一场", new Runnable() {
                        @Override public void run() { chooseTarget(activity); }
                    });
                    button(box, "收起记分牌", post("/match/end", null, null));
                }
            }
            button(box, "刷新", new Runnable() {
                @Override public void run() { act("/match/state", body(), null); }
            });
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(box);
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("枪战模式").setView(scroll).setNegativeButton("关闭", null).create();
            panel = new WeakReference<AlertDialog>(dialog);
            dialog.show();
        } catch (Throwable ignored) {
        }
    }

    private static String feed(Map<String, Object> current) {
        StringBuilder out = new StringBuilder();
        for (Object entry : Json.list(current, "feed")) {
            if (!(entry instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> line = (Map<String, Object>) entry;
            String killer = Json.string(line, "killer");
            out.append("\n    ").append(killer.isEmpty() ? Json.string(line, "victim") + " 阵亡" : killer + " 击倒了 " + Json.string(line, "victim"));
        }
        return out.length() == 0 ? "" : "\n\n最近：" + out;
    }

    private static void chooseTarget(Activity activity) {
        try {
            new AlertDialog.Builder(activity).setTitle("目标击杀数").setItems(TARGET_LABELS, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    post("/match/create", Json.of("target", TARGETS[which], "minutes", MINUTES), "枪战已创建，请大家选择队伍").run();
                }
            }).setNegativeButton("取消", null).show();
        } catch (Throwable ignored) {
        }
    }
}
