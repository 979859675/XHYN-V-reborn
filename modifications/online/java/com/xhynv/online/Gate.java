// SPDX-License-Identifier: MIT
package com.xhynv.online;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Map;

/** The sign-in screen that covers the launcher until an account is signed in. */
final class Gate {
    static final String PICTURE = "online/login.jpg";

    private static Dialog shown;
    private static Activity owner;

    private Gate() {}

    /** Covers `activity` with the sign-in screen unless an account is signed in or the screen is already up. */
    static void show(final Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || Online.signedIn()) return;
            if (shown != null && shown.isShowing() && owner == activity) return;
            close();
            Dialog dialog = build(activity);
            dialog.show();
            shown = dialog;
            owner = activity;
        } catch (Throwable failure) {
            Online.toast("无法显示登录界面：" + failure);
        }
    }

    private static void close() {
        try {
            if (shown != null && shown.isShowing()) shown.dismiss();
        } catch (Throwable ignored) {
            // Its activity is gone, and the window with it.
        }
        shown = null;
        owner = null;
    }

    private static Dialog build(final Activity activity) {
        final float density = activity.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF0B0E13);

        ImageView picture = new ImageView(activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            InputStream in = activity.getAssets().open(PICTURE);
            try {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap != null) picture.setImageBitmap(bitmap);
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            // The plain background stays.
        }
        root.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(activity);
        shade.setBackgroundColor(0x55000000);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = px(density, 20);
        panel.setPadding(pad, px(density, 16), pad, px(density, 16));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xD0101620);
        glass.setCornerRadius(px(density, 18));
        glass.setStroke(px(density, 1), 0x40FFFFFF);
        panel.setBackground(glass);

        TextView title = text(activity, "星战GTA5Online", 22, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title);
        TextView hint = text(activity, "登录或注册后进入", 13, 0xFFB9C3D0);
        hint.setPadding(0, px(density, 2), 0, px(density, 10));
        panel.addView(hint);

        final EditText user = field(activity, density, "用户名", InputType.TYPE_CLASS_TEXT, 20);
        final EditText password = field(activity, density, "密码（至少 8 位）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 72);
        final EditText invite = field(activity, density, "邀请码（注册时填写，没有可留空）", InputType.TYPE_CLASS_TEXT, 64);
        user.setText(Online.username());
        panel.addView(user, spaced(density));
        panel.addView(password, spaced(density));
        panel.addView(invite, spaced(density));

        final TextView error = text(activity, "", 13, 0xFFFFB9AD);
        error.setPadding(0, px(density, 2), 0, px(density, 6));
        panel.addView(error);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Button login = button(activity, density, "登录", 0xFF2F7DF6);
        final Button register = button(activity, density, "注册", 0x40FFFFFF);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, px(density, 44), 1f);
        left.rightMargin = px(density, 10);
        row.addView(login, left);
        row.addView(register, new LinearLayout.LayoutParams(0, px(density, 44), 1f));
        panel.addView(row);

        TextView foot = text(activity, "官网 zy.xzcqgzs.cn  ·  QQ群 942876882", 12, 0xFF9AA6B5);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, px(density, 10), 0, 0);
        panel.addView(foot);

        // Scrolls when the keyboard leaves less room than the panel needs.
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(pad, px(density, 12), pad, px(density, 12));
        column.addView(panel, new LinearLayout.LayoutParams(px(density, 340), ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(root);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        // Back leaves the app; it does not let anyone past.
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface which, int code, KeyEvent event) {
                if (code != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    try {
                        activity.moveTaskToBack(true);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        View.OnClickListener submit = new View.OnClickListener() {
            @Override public void onClick(View view) {
                final boolean registering = view == register;
                final String u = user.getText().toString().trim(), p = password.getText().toString();
                if (u.isEmpty() || p.isEmpty()) {
                    error.setText("请输入用户名和密码");
                    return;
                }
                final Map<String, Object> body = Json.of("username", u, "password", p);
                String code = invite.getText().toString().trim();
                if (registering && !code.isEmpty()) body.put("inviteCode", code);
                error.setText(registering ? "正在注册…" : "正在登录…");
                login.setEnabled(false);
                register.setEnabled(false);
                Online.async(new Online.Work<String>() {
                    @Override public String run() throws ApiException { return Online.authenticate(registering, body); }
                }, new Online.Done<String>() {
                    @Override public void ok(String character) { entered(activity, character.isEmpty()); }
                    @Override public void failed(String message) {
                        // A failure after the session was stored (the character list) still counts as signed in.
                        if (Online.signedIn()) {
                            Online.toast(message);
                            entered(activity, false);
                            return;
                        }
                        error.setText(message);
                        login.setEnabled(true);
                        register.setEnabled(true);
                    }
                });
            }
        };
        login.setOnClickListener(submit);
        register.setOnClickListener(submit);
        return dialog;
    }

    private static void entered(Activity activity, boolean needsCharacter) {
        close();
        OnlineUi.notice = "";
        OnlineUi.renderAll();
        if (needsCharacter && !activity.isFinishing() && !activity.isDestroyed()) OnlineUi.chooseCharacter(activity);
    }

    private static int px(float density, int dp) { return Math.round(dp * density); }

    private static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static LinearLayout.LayoutParams spaced(float density) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(density, 42));
        params.bottomMargin = px(density, 8);
        return params;
    }

    private static EditText field(Activity activity, float density, String hint, int type, int limit) {
        EditText edit = new EditText(activity);
        edit.setHint(hint);
        edit.setInputType(type);
        edit.setSingleLine(true);
        edit.setTextSize(15);
        edit.setTextColor(0xFFFFFFFF);
        edit.setHintTextColor(0x99FFFFFF);
        edit.setFilters(new InputFilter[] { new InputFilter.LengthFilter(limit) });
        // Landscape keyboards otherwise replace the whole screen with their own text box.
        edit.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x26FFFFFF);
        shape.setCornerRadius(px(density, 10));
        edit.setBackground(shape);
        edit.setPadding(px(density, 12), 0, px(density, 12), 0);
        edit.setGravity(Gravity.CENTER_VERTICAL);
        return edit;
    }

    private static Button button(Activity activity, float density, String label, int color) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(px(density, 12));
        button.setBackground(shape);
        return button;
    }
}
