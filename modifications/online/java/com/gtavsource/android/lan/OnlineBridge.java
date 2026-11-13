// SPDX-License-Identifier: MIT
package com.gtavsource.android.lan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of the lobby client that need the game's package-private classes: race results,
 * diagnostics and test bots. Everything else is in com.xhynv.online.
 */
public final class OnlineBridge {
    private static final int RESULTS = 4, FINISHED = 2;

    private OnlineBridge() {}

    /**
     * The finished race currently on the results screen, as the body of POST /races plus an "id"
     * that identifies the race; null when no race has just finished.
     */
    public static Map<String, Object> finishedRace() {
        LanRace.Snapshot snapshot = LanSession.raceSnapshot();
        if (snapshot == null || snapshot.phase != RESULTS || snapshot.course == null) return null;
        List<Object> results = new ArrayList<Object>();
        for (LanRace.Racer racer : snapshot.standings()) {
            if (racer == null || racer.name == null) continue;
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", racer.name);
            boolean finished = racer.status == FINISHED && racer.finishMs > 0 && racer.place > 0;
            entry.put("finished", finished);
            if (finished) {
                entry.put("position", racer.place);
                entry.put("timeMs", racer.finishMs);
            } else {
                entry.put("reason", LanRace.reason(racer.reason));
            }
            results.add(entry);
        }
        if (results.isEmpty()) return null;
        Map<String, Object> race = new LinkedHashMap<String, Object>();
        race.put("id", snapshot.id);
        String course = snapshot.course.name == null ? "" : snapshot.course.name.trim();
        race.put("courseName", course.isEmpty() ? "街头赛道" : course);
        race.put("mode", snapshot.laps > 0 ? "circuit" : "sprint");
        race.put("laps", Math.max(1, Math.min(5, snapshot.laps)));
        race.put("checkpoints", Math.max(0, Math.min(32, snapshot.course.count())));
        race.put("results", results);
        return race;
    }

    // ---- diagnostics -----------------------------------------------------------------------------

    /** One line saying why the game considers itself ready or not; shown to the host and the administrator. */
    public static String diagnostics() {
        StringBuilder line = new StringBuilder();
        try {
            LanSession.State state = LanSession.state();
            line.append(state.game == null ? "" : state.game).append(" | 房间就绪=").append(state.ready ? "是" : "否")
                    .append(" 游戏就绪=").append(state.gameReady ? "是" : "否");
            int[] stats = LanSession.access$600();
            if (stats != null) line.append(" stats=").append(Arrays.toString(stats).replace(" ", ""));
            StringBuilder bots = new StringBuilder();
            synchronized (BOTS) {
                for (int i = 0; i < BOTS.length; i++) {
                    if (BOTS[i] != null) bots.append(' ').append(i + 1).append(':').append(BOTS[i].describe());
                }
            }
            if (bots.length() > 0) line.append(" | 人机").append(bots);
        } catch (Throwable t) {
            line.append(" 诊断失败:").append(t.getClass().getSimpleName());
        }
        return line.toString();
    }

    // ---- test bots -------------------------------------------------------------------------------

    private static final int FRAME_BYTES = 0x12a0, READY_FLAG = 0x1, IN_VEHICLE_FLAGS = 0x2 | 0x40, CAR_INITIAL_FLAG = 0x8, WORLD_FLAG = 0x20;
    private static final int FLAGS = 0, VEHICLE_COUNT = 0x1c, X = 0x20, Y = 0x24, VEHICLES = 0xe8, RULES = 0x11e8, HEALTH_EPOCH = 0x129c;
    private static final Bot[] BOTS = new Bot[3];
    /** The host's own latest game frame, for mirror bots. */
    private static volatile byte[] hostFrame;

    /** Called by the game with each frame the local player sends. */
    public static void observe(byte[] frame) { hostFrame = frame; }

    /**
     * A room member that runs on the host's phone, using the game's own networking code. It joins like
     * a real phone would; in the game world it has no body unless it mirrors the host.
     */
    private static final class Bot implements LanTransport.Source {
        final int index;
        final String code;
        volatile boolean mirror, dead;
        volatile String note = "", reason = "";
        volatile LanTransport transport;
        private byte[] last;

        Bot(int index, String code) { this.index = index; this.code = code; }

        @Override public void disconnected() {
            // Keep the game's own reason ("连接超时…", "房间已满…"): it is the only clue to why a bot cannot join.
            try {
                LanTransport t = transport;
                if (t != null) reason = String.valueOf(t.status());
            } catch (Throwable ignored) {
            }
            dead = true;
        }
        @Override public void receive(byte[] frame) { }

        @Override public byte[] snapshot() {
            if (!mirror) {
                last = null;
                note = "";
                return LanFrame.empty();
            }
            try {
                byte[] frame = hostFrame;
                String skip = skipReason(frame);
                if (skip != null) {
                    note = last != null ? "镜像,停留:" + skip : "镜像未开始:" + skip;
                } else {
                    // The game decides what a legal frame is; try the plain copy first, then without the "initial car" mark.
                    byte[] made = mirrored(frame, index, false);
                    if (!LanFrame.valid(made)) made = mirrored(frame, index, true);
                    if (LanFrame.valid(made)) {
                        last = made;
                        note = "镜像中";
                    } else {
                        note = "镜像帧不合法 flags=0x" + Integer.toHexString(flags(frame));
                    }
                }
                if (last != null) return last;
            } catch (Throwable t) {
                note = "镜像失败:" + t.getClass().getSimpleName();
            }
            return LanFrame.empty();
        }

        String describe() {
            LanTransport t = transport;
            String state;
            if (dead || t == null || !t.running()) {
                String why = reason;
                if (why.isEmpty() && t != null) {
                    try { why = String.valueOf(t.status()); } catch (Throwable ignored) { }
                }
                state = why.isEmpty() ? "已断开" : "已断开:" + why;
            } else {
                state = t.connected() ? "已加入" : "连接中";
            }
            return note.isEmpty() ? state : state + "(" + note + ")";
        }
    }

    private static int flags(byte[] frame) { return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(FLAGS); }

    /** Why the host's current frame cannot be mirrored, or null if it can. */
    static String skipReason(byte[] frame) {
        if (frame == null) return "还没有拿到房主的状态";
        if (frame.length != FRAME_BYTES) return "状态长度=" + frame.length;
        int flags = flags(frame);
        if ((flags & READY_FLAG) == 0) return "房主未就绪 flags=0x" + Integer.toHexString(flags);
        if ((flags & IN_VEHICLE_FLAGS) != 0) return "房主在车上 flags=0x" + Integer.toHexString(flags);
        return null;
    }

    /** The host's frame moved a few metres aside, without the parts only a host or a driver sends. */
    static byte[] mirrored(byte[] frame, int index, boolean dropInitialCar) {
        byte[] copy = frame.clone();
        ByteBuffer out = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
        int flags = out.getInt(FLAGS) & ~WORLD_FLAG;
        if (dropInitialCar) flags &= ~CAR_INITIAL_FLAG;
        out.putInt(FLAGS, flags);
        out.putInt(VEHICLE_COUNT, 0);
        Arrays.fill(copy, VEHICLES, RULES, (byte) 0);          // world vehicles
        Arrays.fill(copy, RULES, HEALTH_EPOCH, (byte) 0);      // room rules, damage dealt, melee
        out.putFloat(X, out.getFloat(X) + 2.5f * index);
        out.putFloat(Y, out.getFloat(Y) + 2.5f);
        return copy;
    }

    /** Whether bot {@code index} (1-based) is running for this room code. */
    public static boolean botAlive(int index, String code) {
        synchronized (BOTS) {
            Bot bot = BOTS[index - 1];
            return bot != null && !bot.dead && bot.code.equals(code) && bot.transport != null && bot.transport.running();
        }
    }

    /**
     * Starts bot {@code index}, replacing a previous one. The caller has arranged for the socket this
     * creates to be the bot's own relay socket.
     */
    public static void startBot(int index, String name, String hostAddress, String code, boolean mirror) {
        stopBot(index);
        Bot bot = new Bot(index, code);
        bot.mirror = mirror;
        bot.transport = LanTransport.join(hostAddress, name, code, bot, false);
        bot.transport.start();
        synchronized (BOTS) { BOTS[index - 1] = bot; }
    }

    public static void setMirror(boolean mirror) {
        synchronized (BOTS) {
            for (Bot bot : BOTS) if (bot != null) bot.mirror = mirror;
        }
    }

    public static void stopBot(int index) {
        Bot bot;
        synchronized (BOTS) {
            bot = BOTS[index - 1];
            BOTS[index - 1] = null;
        }
        if (bot != null && bot.transport != null) {
            try { bot.transport.close(); } catch (Throwable ignored) { }
        }
    }
}
