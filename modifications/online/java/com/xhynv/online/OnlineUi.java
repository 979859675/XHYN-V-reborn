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

    private final Activity activity;
    private final TextView status;
    private final LinearLayout buttons;
    private final EditText name, ip, code;

    /** Called from the LanPage constructor. Fields a page does not have are null. */
    public static void attach(LinearLayout page, EditText name, EditText ip, EditText code) {
        try {
            Context context = page.getContext();
            if (!(context instanceof Activity)) return;
            Online.init(context);
            Online.startReporter();
            OnlineUi card = new OnlineUi((Activity) context, page, name, ip, code);
            CARDS.add(new WeakReference<OnlineUi>(card));
            card.render();
        } catch (Throwable ignored) {
            // The LAN page must still open if the card cannot be built.
        }
    }

    static void renderAll() {
        for (Iterator<WeakReference<OnlineUi>> it = CARDS.iterator(); it.hasNext(); ) {
            OnlineUi card = it.next().get();
            if (card == null || card.activity.isDestroyed()) it.remove();
            else card.render();
        }
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
        title.setText("联机中心");
        title.setTextSize(15);
        title.setTextColor(0xFFECF3F8);
        card.addView(title);
        status = new TextView(activity);
        status.setTextSize(13);
        status.setTextColor(0xFFB6C2D0);
        status.setPadding(0, dp(4), 0, dp(8));
        card.addView(status);
        buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(buttons);
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
        if (!Online.signedIn()) {
            status.setText("未登录。登录并选择角色后，才能创建或加入房间。");
            addButton("登录 / 注册", true, new Runnable() { @Override public void run() { showLogin(); } });
        } else {
            String line = "账号：" + Online.username() + (character.isEmpty() ? "\n还没有选择角色。" : "　角色：" + character);
            if (!syncStatus.isEmpty()) line += "\n" + syncStatus;
            status.setText(line);
            addButton(character.isEmpty() ? "选择角色" : "切换角色", character.isEmpty(), new Runnable() { @Override public void run() { showCharacters(); } });
            if (ip != null && code != null) addButton("在线房间", !character.isEmpty(), new Runnable() { @Override public void run() { showRooms(); } });
            addButton("退出登录", false, new Runnable() { @Override public void run() { signOut(); } });
        }
        if (name != null && !character.contentEquals(name.getText())) name.setText(character);
    }

    // ---- sign in ---------------------------------------------------------------------------------

    private void showLogin() {
        LinearLayout box = form();
        final EditText user = input(box, "用户名", InputType.TYPE_CLASS_TEXT, 20);
        final EditText password = input(box, "密码（至少 8 位）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 72);
        final EditText invite = input(box, "邀请码（仅注册时需要，没有可留空）", InputType.TYPE_CLASS_TEXT, 64);
        final TextView error = errorLine(box);
        user.setText(Online.username());
        final AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("登录联机中心").setView(box)
                .setPositiveButton("登录", null).setNeutralButton("注册", null).setNegativeButton("取消", null).create();
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
                        Map<String, Object> session = Online.api.call("POST", register ? "/auth/register" : "/auth/login", body, null);
                        String token = Json.string(session, "token");
                        if (token.isEmpty()) throw new ApiException(502, "联机中心没有返回登录信息");
                        Online.signIn(token, Json.string(Json.map(session, "user"), "username"));
                        // Pick up the character already chosen on the website or another phone.
                        Map<String, Object> mine = Online.api.call("GET", "/characters", null, token);
                        long active = Json.number(mine, "activeCharacterId");
                        for (Object item : Json.list(mine, "characters")) {
                            @SuppressWarnings("unchecked") Map<String, Object> character = (Map<String, Object>) item;
                            if (Json.number(character, "id") == active) Online.setCharacter(Json.string(character, "name"));
                        }
                        return Online.characterName();
                    }
                }, new Online.Done<String>() {
                    @Override public void ok(String character) {
                        dialog.dismiss();
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
        Online.async(new Online.Work<Object>() {
            @Override public Object run() throws ApiException { return Online.api.call("POST", "/auth/logout", Api.empty(), token); }
        }, new Online.Done<Object>() {
            @Override public void ok(Object value) {}
            @Override public void failed(String message) {}   // already signed out on this phone
        });
    }

    // ---- characters ------------------------------------------------------------------------------

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

    private void showRooms() {
        final String token = Online.token();
        Online.async(new Online.Work<Map<String, Object>>() {
            @Override public Map<String, Object> run() throws ApiException { return Online.api.call("GET", "/rooms", null, token); }
        }, new Online.Done<Map<String, Object>>() {
            @Override public void ok(Map<String, Object> data) {
                if (activity.isDestroyed()) return;
                final List<Object> rooms = Json.list(data, "rooms");
                AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("在线房间").setNegativeButton("关闭", null)
                        .setNeutralButton("刷新", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { showRooms(); }
                        });
                if (rooms.isEmpty()) {
                    builder.setMessage("现在没有在线房间。房主创建房间后会显示在这里。");
                } else {
                    String[] labels = new String[rooms.size()];
                    for (int i = 0; i < labels.length; i++) {
                        @SuppressWarnings("unchecked") Map<String, Object> room = (Map<String, Object>) rooms.get(i);
                        labels[i] = Json.string(room, "title") + "\n房主 " + Json.string(room, "hostName") + " · "
                                + Json.number(room, "players") + "/" + Json.number(room, "maxPlayers") + " 人"
                                + (Json.flag(room, "virtualLan") ? " · 虚拟局域网" : " · 同一 Wi-Fi / 热点");
                    }
                    builder.setItems(labels, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            @SuppressWarnings("unchecked") Map<String, Object> room = (Map<String, Object>) rooms.get(which);
                            ip.setText(Json.string(room, "hostAddress"));
                            code.setText(Json.string(room, "roomCode"));
                            Online.toast(Json.flag(room, "virtualLan")
                                    ? "已填入。这个房间用虚拟局域网：先连上和房主相同的组网并打开“虚拟局域网”开关，再点“加入房间”。"
                                    : "已填入。连上和房主相同的 Wi-Fi 或热点后，点“加入房间”。");
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

    private void addButton(String label, boolean primary, final Runnable action) {
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        buttons.addView(button, params);
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
}
