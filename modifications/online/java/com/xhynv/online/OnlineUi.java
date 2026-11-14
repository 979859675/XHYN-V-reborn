// SPDX-License-Identifier: MIT
package com.xhynv.online;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** The "联机中心" card added to the game's LAN pages, with its sign-in, character and room dialogs. */
public final class OnlineUi {
    private static final List<WeakReference<OnlineUi>> CARDS = new ArrayList<WeakReference<OnlineUi>>();
    static volatile String syncStatus = "";
    /** The game's LAN status line, and the latest message from the lobby; both are shown in the card. */
    static volatile String lanStatus = "", notice = "";

    private final Activity activity;
    private final TextView status, message;
    private final LinearLayout buttons, modes;
    private final EditText name, ip, code;

    /** Called from the LanPage constructor. Fields a page does not have are null. */
    public static void attach(LinearLayout page, EditText name, EditText ip, EditText code) {
        try {
            Context context = page.getContext();
            if (!(context instanceof Activity)) return;
            Online.init(context);
            Online.startReporter();
            strip(page);
            OnlineUi card = new OnlineUi((Activity) context, page, name, ip, code);
            CARDS.add(new WeakReference<OnlineUi>(card));
            card.render();
        } catch (Throwable ignored) {
            // The LAN page must still open if the card cannot be built.
        }
    }

    /**
     * Playing over Wi-Fi, a hotspot or a VPN was removed: rooms are created and joined through the
     * server only. Takes the game's controls for those ways off its page.
     */
    private static void strip(LinearLayout page) {
        try {
            for (String tag : new String[] { "lan-virtual-network", "lan-mode-hint", "lan-ip", "lan-code", "lan-join", "lan-search",
                    "lan-join-hint", "lan-room-info", "lan-copy" }) {
                View view = page.findViewWithTag(tag);
                if (view == null) continue;
                // A field sits in a row with its label; take the whole row.
                while (view.getParent() instanceof View && view.getParent() != page) view = (View) view.getParent();
                if (view.getParent() == page) page.removeView(view);
            }
            retext(page);
        } catch (Throwable ignored) {
            // Sessions that do not go through the server are refused anyway.
        }
    }

    private static void retext(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof android.view.ViewGroup) { retext((android.view.ViewGroup) child); continue; }
            if (!(child instanceof TextView) || child instanceof EditText) continue;
            String text = String.valueOf(((TextView) child).getText());
            if (text.contains("最多 10 人") && text.contains("热点")) ((TextView) child).setText("最多 10 人 · 服务器联机");
            else if (text.contains("由一台手机创建房间")) ((TextView) child).setText("创建后房间会出现在“在线房间”里，其他玩家从列表直接加入。可设置 2～10 人（含房主）。");
            else if (text.contains("Astral") || text.contains("蒲公英") || text.contains("虚拟局域网") || text.contains("热点")) child.setVisibility(View.GONE);
        }
    }

    static void renderAll() {
        for (Iterator<WeakReference<OnlineUi>> it = CARDS.iterator(); it.hasNext(); ) {
            OnlineUi card = it.next().get();
            if (card == null || card.activity.isDestroyed()) it.remove();
            else card.render();
        }
    }

    private static boolean greeted;

    /**
     * Called when the launcher comes to the front. Once per app start: asks a signed-out user to sign in,
     * and checks a stored sign-in with the server. The dialog can be dismissed; single-player needs no account.
     */
    public static void startup(final Activity activity) {
        try {
            Online.init(activity);
            Online.startReporter();
            if (activity == null) return;
            // Nobody gets past the sign-in screen; it comes back on every return to the launcher.
            Gate.show(activity);
            if (greeted || !Online.signedIn()) return;
            greeted = true;
            Online.main.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) { greeted = false; return; }
                        final OnlineUi ui = new OnlineUi(activity);
                        if (!Online.signedIn()) { greeted = false; Gate.show(activity); return; }
                        final String token = Online.token();
                        Online.async(new Online.Work<String>() {
                            @Override public String run() throws ApiException {
                                Map<String, Object> mine = Online.api.call("GET", "/characters", null, token);
                                long active = Json.number(mine, "activeCharacterId");
                                String found = "";
                                for (Object item : Json.list(mine, "characters")) {
                                    @SuppressWarnings("unchecked") Map<String, Object> character = (Map<String, Object>) item;
                                    if (Json.number(character, "id") == active) found = Json.string(character, "name");
                                }
                                Online.setCharacter(found);
                                return found;
                            }
                        }, new Online.Done<String>() {
                            @Override public void ok(String character) {
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                if (character.isEmpty()) ui.showCharacters();
                            }
                            @Override public void failed(String message) {
                                // Signed out by a rejected token: ask again. A network failure keeps the stored sign-in.
                                if (!Online.signedIn()) { greeted = false; Gate.show(activity); }
                            }
                        });
                    } catch (Throwable ignored) {
                        // The launcher must open even if the dialog cannot.
                    }
                }
            }, 600);
        } catch (Throwable ignored) {
        }
    }

    /** A card-less instance, for dialogs shown outside the LAN page. */
    private OnlineUi(Activity activity) {
        this.activity = activity;
        this.name = null;
        this.ip = null;
        this.code = null;
        this.status = new TextView(activity);
        this.message = new TextView(activity);
        this.buttons = new LinearLayout(activity);
        this.modes = new LinearLayout(activity);
    }

    private OnlineUi(Activity activity, LinearLayout page, EditText name, EditText ip, EditText code) {
        this.activity = activity;
        this.name = name;
        this.ip = ip;
        this.code = code;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag("online-card");
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(box(0xFF1B2230, 10));
        TextView title = new TextView(activity);
        title.setText("星战GTA5Online · 联机中心");
        title.setTextSize(15);
        title.setTextColor(0xFFECF3F8);
        card.addView(title);
        status = new TextView(activity);
        status.setTextSize(13);
        status.setTextColor(0xFFB6C2D0);
        status.setPadding(0, dp(4), 0, dp(8));
        card.addView(status);
        modes = new LinearLayout(activity);
        modes.setOrientation(LinearLayout.VERTICAL);
        card.addView(modes);
        buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        // The row can be wider than the card on a narrow screen: let it scroll sideways.
        android.widget.HorizontalScrollView row = new android.widget.HorizontalScrollView(activity);
        row.setHorizontalScrollBarEnabled(false);
        row.addView(buttons);
        card.addView(row);
        message = new TextView(activity);
        message.setTextSize(13);
        message.setTextColor(0xFFFFC857);
        message.setPadding(0, dp(8), 0, 0);
        card.addView(message);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(10));
        page.addView(card, Math.min(1, page.getChildCount()), params);
        if (name != null) {
            // The room name is the character name; it is not typed here any more.
            name.setFocusable(false);
            name.setFocusableInTouchMode(false);
            name.setCursorVisible(false);
            name.setLongClickable(false);
            name.setHint("登录联机中心后自动填写");
        }
    }

    private void render() {
        String character = Online.characterName();
        buttons.removeAllViews();
        modes.removeAllViews();
        if (!Online.signedIn()) {
            status.setText("未登录。登录并选择角色后，才能创建或加入房间。");
            addButton("登录 / 注册", true, new Runnable() { @Override public void run() { showLogin(); } });
        } else {
            String line = "账号：" + Online.username() + (character.isEmpty() ? "\n还没有选择角色。" : "　角色：" + character);
            if (!syncStatus.isEmpty()) line += "\n" + syncStatus;
            if (!Online.relayStatus.isEmpty() && !"已就绪".equals(Online.relayStatus)) line += "\n服务器联机：" + Online.relayStatus;
            status.setText(line);
            if (!character.isEmpty()) {
                // What can be played, each with its way in.
                Runnable race = new Runnable() { @Override public void run() { Online.createRoom(activity, "race"); } };
                Runnable team = new Runnable() { @Override public void run() { Online.createRoom(activity, "team"); } };
                Runnable raceRooms = new Runnable() { @Override public void run() { showRooms("race"); } };
                Runnable teamRooms = new Runnable() { @Override public void run() { showRooms("team"); } };
                Runnable open = new Runnable() { @Override public void run() { Online.enterPublic(activity); } };
                addHeading("支持的玩法");
                addMode("赛车", "街头赛车，最多 10 人。进房后房主在“房主专属”里开赛，成绩计入排行榜。",
                        new String[] { "创建赛车房", "赛车房列表" }, new Runnable[] { race, raceRooms });
                addMode("团队竞技", "红蓝两队，最多 5 对 5，先达到目标击杀数的队伍获胜。进房后房主在悬浮窗里开赛。",
                        new String[] { "创建团队竞技房", "团队竞技房列表" }, new Runnable[] { team, teamRooms });
                addMode("个人对战", "公共对战房，随时进入。每局先到 20 分获胜，10 分钟到点按得分结算；连杀有悬赏，战绩计入赛季段位。",
                        new String[] { "进入公共对战房" }, new Runnable[] { open });
            }
            addButton(character.isEmpty() ? "选择角色" : "切换角色", character.isEmpty(), new Runnable() { @Override public void run() { showCharacters(); } });
            addButton("退出登录", false, new Runnable() { @Override public void run() { signOut(); } });
        }
        if (name != null && !character.contentEquals(name.getText())) name.setText(character);
        String lines = notice;
        if (!lanStatus.isEmpty()) lines += (lines.isEmpty() ? "" : "\n") + "联机状态：" + lanStatus;
        message.setText(lines);
        message.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void addHeading(String text) {
        TextView heading = new TextView(activity);
        heading.setText(text);
        heading.setTextSize(13);
        heading.setTextColor(0xFFB6C2D0);
        modes.addView(heading);
    }

    /** One way to play: its name, a line about it, and the buttons that lead into it. */
    private void addMode(String title, String about, String[] labels, Runnable[] actions) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(8), dp(12), dp(10));
        block.setBackground(box(0xFF242D3D, 8));
        TextView head = new TextView(activity);
        head.setText(title);
        head.setTextSize(15);
        head.setTextColor(0xFFECF3F8);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        block.addView(head);
        TextView line = new TextView(activity);
        line.setText(about);
        line.setTextSize(12);
        line.setTextColor(0xFFB6C2D0);
        line.setPadding(0, dp(2), 0, dp(8));
        block.addView(line);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) row.addView(button(labels[i], i == 0, actions[i]), spaced());
        block.addView(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(2));
        modes.addView(block, params);
    }

    /** Create/join was pressed without an account or a character: say so in a dialog and offer the next step. */
    static void askToSignIn() {
        notice = Online.NEED_LOGIN;
        renderAll();
        OnlineUi card = null;
        for (WeakReference<OnlineUi> reference : CARDS) {
            OnlineUi candidate = reference.get();
            if (candidate != null && !candidate.activity.isDestroyed() && candidate.status.isShown()) card = candidate;
        }
        if (card == null) return;
        final OnlineUi shown = card;
        final boolean signedIn = Online.signedIn();
        try {
            new AlertDialog.Builder(shown.activity).setTitle("联机中心")
                    .setMessage(signedIn ? "还没有选择角色。选择或创建一个角色后，才能创建或加入房间。" : "还没有登录。登录并选择角色后，才能创建或加入房间。")
                    .setPositiveButton(signedIn ? "选择角色" : "登录 / 注册", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            if (signedIn) shown.showCharacters(); else shown.showLogin();
                        }
                    }).setNegativeButton("取消", null).show();
        } catch (Throwable ignored) {
            // The message is already in the card.
        }
    }

    // ---- sign in ---------------------------------------------------------------------------------

    private void showLogin() { showLogin(false); }

    private void showLogin(boolean startup) {
        LinearLayout box = form();
        if (startup) {
            TextView hint = new TextView(activity);
            hint.setText("登录后可以创建和加入联机房间。只玩单机可以点“暂不登录”。");
            hint.setTextSize(13);
            hint.setPadding(0, 0, 0, dp(8));
            box.addView(hint);
        }
        final EditText user = input(box, "用户名", InputType.TYPE_CLASS_TEXT, 20);
        final EditText password = input(box, "密码（至少 8 位）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 72);
        final EditText invite = input(box, "邀请码（仅注册时需要，没有可留空）", InputType.TYPE_CLASS_TEXT, 64);
        final TextView error = errorLine(box);
        user.setText(Online.username());
        final AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("登录联机中心").setView(box)
                .setPositiveButton("登录", null).setNeutralButton("注册", null).setNegativeButton(startup ? "暂不登录" : "取消", null).create();
        dialog.show();
        // Set after show() so a failed attempt keeps the dialog and what was typed.
        View.OnClickListener submit = new View.OnClickListener() {
            @Override public void onClick(View view) {
                final boolean register = view == dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
                final String u = user.getText().toString().trim(), p = password.getText().toString();
                if (u.isEmpty() || p.isEmpty()) {
                    error.setText("请输入用户名和密码");
                    return;
                }
                final Map<String, Object> body = Json.of("username", u, "password", p);
                String inviteCode = invite.getText().toString().trim();
                if (register && !inviteCode.isEmpty()) body.put("inviteCode", inviteCode);
                error.setText(register ? "正在注册…" : "正在登录…");
                enable(dialog, false);
                Online.async(new Online.Work<String>() {
                    @Override public String run() throws ApiException {
                        return Online.authenticate(register, body);
                    }
                }, new Online.Done<String>() {
                    @Override public void ok(String character) {
                        dialog.dismiss();
                        notice = "";
                        if (character.isEmpty()) showCharacters();
                    }
                    @Override public void failed(String message) {
                        // A failure after the token was stored (character list) still counts as signed in.
                        if (Online.signedIn()) { dialog.dismiss(); Online.toast(message); return; }
                        error.setText(message);
                        enable(dialog, true);
                    }
                });
            }
        };
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(submit);
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(submit);
    }

    private static void enable(AlertDialog dialog, boolean enabled) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(enabled);
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(enabled);
    }

    private void signOut() {
        final String token = Online.token();
        Online.signOut();
        renderAll();
        greeted = false;
        Gate.show(activity);
        Online.async(new Online.Work<Object>() {
            @Override public Object run() throws ApiException { return Online.api.call("POST", "/auth/logout", Api.empty(), token); }
        }, new Online.Done<Object>() {
            @Override public void ok(Object value) {}
            @Override public void failed(String message) {}   // already signed out on this phone
        });
    }

    // ---- characters ------------------------------------------------------------------------------

    /** The character list, for a caller without a card. */
    static void chooseCharacter(Activity activity) { new OnlineUi(activity).showCharacters(); }

    private void showCharacters() {
        final String token = Online.token();
        Online.async(new Online.Work<Map<String, Object>>() {
            @Override public Map<String, Object> run() throws ApiException { return Online.api.call("GET", "/characters", null, token); }
        }, new Online.Done<Map<String, Object>>() {
            @Override public void ok(Map<String, Object> data) {
                if (activity.isDestroyed()) return;
                final List<Object> characters = Json.list(data, "characters");
                final String[] labels = new String[characters.size() + 1];
                for (int i = 0; i < characters.size(); i++) {
                    @SuppressWarnings("unchecked") Map<String, Object> character = (Map<String, Object>) characters.get(i);
                    labels[i] = Json.string(character, "name") + (Json.number(character, "id") == Json.number(data, "activeCharacterId") ? "（当前）" : "");
                }
                labels[characters.size()] = "＋ 创建新角色";
                new AlertDialog.Builder(activity).setTitle("选择角色").setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which >= characters.size()) { showCreateCharacter(); return; }
                        @SuppressWarnings("unchecked") Map<String, Object> character = (Map<String, Object>) characters.get(which);
                        select(Json.number(character, "id"), Json.string(character, "name"));
                    }
                }).setNegativeButton("取消", null).show();
            }
            @Override public void failed(String message) { Online.toast(message); }
        });
    }

    private void select(final long id, final String characterName) {
        final String token = Online.token();
        Online.async(new Online.Work<Object>() {
            @Override public Object run() throws ApiException {
                Online.api.call("POST", "/characters/" + id + "/select", Api.empty(), token);
                Online.setCharacter(characterName);
                return null;
            }
        }, new Online.Done<Object>() {
            @Override public void ok(Object value) { Online.toast("当前角色：" + characterName); }
            @Override public void failed(String message) { Online.toast(message); }
        });
    }

    private void showCreateCharacter() {
        LinearLayout box = form();
        final EditText field = input(box, "角色名（2～16 位，全服唯一，不能修改）", InputType.TYPE_CLASS_TEXT, 16);
        final TextView error = errorLine(box);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("创建角色").setView(box)
                .setPositiveButton("创建", null).setNegativeButton("取消", null).create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View button) {
                final String wanted = field.getText().toString().trim();
                if (wanted.isEmpty()) { error.setText("请输入角色名"); return; }
                final String token = Online.token();
                button.setEnabled(false);
                error.setText("正在创建…");
                Online.async(new Online.Work<String>() {
                    @Override public String run() throws ApiException {
                        Map<String, Object> made = Json.map(Online.api.call("POST", "/characters", Json.of("name", wanted), token), "character");
                        String created = Json.string(made, "name");
                        Online.api.call("POST", "/characters/" + Json.number(made, "id") + "/select", Api.empty(), token);
                        Online.setCharacter(created);
                        return created;
                    }
                }, new Online.Done<String>() {
                    @Override public void ok(String created) { dialog.dismiss(); Online.toast("当前角色：" + created); }
                    @Override public void failed(String message) { error.setText(message); button.setEnabled(true); }
                });
            }
        });
    }

    // ---- rooms -----------------------------------------------------------------------------------

    /** The rooms that are open. Picking one joins it at once, through the server. */
    /** The rooms of one mode ("race" or "team"); rooms that do not say what they are for are listed in both. */
    private void showRooms(final String mode) {
        final String token = Online.token();
        final String what = "race".equals(mode) ? "赛车房" : "团队竞技房";
        Online.async(new Online.Work<Map<String, Object>>() {
            @Override public Map<String, Object> run() throws ApiException { return Online.api.call("GET", "/rooms", null, token); }
        }, new Online.Done<Map<String, Object>>() {
            @Override public void ok(Map<String, Object> data) {
                if (activity.isDestroyed()) return;
                // Rooms made by old versions over Wi-Fi or a VPN cannot be joined any more.
                final List<Map<String, Object>> rooms = new ArrayList<Map<String, Object>>();
                for (Object item : Json.list(data, "rooms")) {
                    @SuppressWarnings("unchecked") Map<String, Object> room = (Map<String, Object>) item;
                    if (Json.number(room, "relayNetwork") <= 0 || Json.number(room, "public") > 0) continue;
                    String made = Online.modeOf(Json.string(room, "title"));
                    if (made.isEmpty() || made.equals(mode)) rooms.add(room);
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(what + "列表").setNegativeButton("关闭", null)
                        .setNeutralButton("刷新", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { showRooms(mode); }
                        });
                if (rooms.isEmpty()) {
                    builder.setMessage("现在没有" + what + "。可以自己创建一个。");
                } else {
                    String[] labels = new String[rooms.size()];
                    for (int i = 0; i < labels.length; i++) {
                        Map<String, Object> room = rooms.get(i);
                        labels[i] = Json.string(room, "title") + (Json.number(room, "public") > 0 ? "（个人对战）" : "") + "\n房主 " + Json.string(room, "hostName") + " · "
                                + Json.number(room, "players") + "/" + Json.number(room, "maxPlayers") + " 人";
                    }
                    builder.setItems(labels, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            Map<String, Object> room = rooms.get(which);
                            if (Json.number(room, "public") > 0) Online.enterPublic(activity);
                            else Online.joinRoom(activity, room);
                        }
                    });
                }
                builder.show();
            }
            @Override public void failed(String message) { Online.toast(message); }
        });
    }

    // ---- widgets ---------------------------------------------------------------------------------

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private GradientDrawable box(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private void addButton(String label, boolean primary, Runnable action) {
        buttons.addView(button(label, primary, action), spaced());
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        return params;
    }

    private Button button(String label, boolean primary, final Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(primary ? 0xFF12091F : 0xFFECF3F8);
        button.setBackground(box(primary ? 0xFFA06BFF : 0xFF2E3848, 8));
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                try { action.run(); } catch (Throwable t) { Online.toast("出错了：" + t.getClass().getSimpleName()); }
            }
        });
        return button;
    }

    private LinearLayout form() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        return box;
    }

    private EditText input(LinearLayout box, String hint, int type, int maxLength) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setFilters(new InputFilter[] { new InputFilter.LengthFilter(maxLength) });
        box.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private TextView errorLine(LinearLayout box) {
        TextView line = new TextView(activity);
        line.setTextSize(13);
        line.setTextColor(0xFFE5484D);
        line.setPadding(dp(4), dp(6), dp(4), 0);
        box.addView(line);
        return line;
    }

    // The launcher's side bar is laid out from parallel arrays, one entry per group, ours last.
    // These put ours first and the group that was first in its place.

    public static String[] groups(String[] names) {
        String first = names[0];
        names[0] = names[names.length - 1];
        names[names.length - 1] = first;
        return names;
    }

    public static int[][] children(int[][] pages) {
        int[] first = pages[0];
        pages[0] = pages[pages.length - 1];
        pages[pages.length - 1] = first;
        return pages;
    }

    /** The page each group opens on. */
    public static int[] pages(int[] opening) {
        int first = opening[0];
        opening[0] = opening[opening.length - 1];
        opening[opening.length - 1] = first;
        return opening;
    }

    /** The launcher draws icon 7 for the group in sixth place and icon n for place n; follow the swap. */
    public static int icon(int type) {
        return type == 7 ? 0 : type == 0 ? 7 : type;
    }
}
