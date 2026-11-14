// Compile-time stub of the game's class; never packaged.
package com.gtavsource.android.lan;

import android.content.Context;
import java.util.List;

public final class LanSession {
    public static final class State {
        public boolean active, busy, cleanup, host, virtualNetwork, ready, gameReady;
        public String code, status, game;
        public int players, playerLimit, slot;
        public boolean friendlyFire;
    }
    public static boolean setFriendlyFire(Context context, boolean enabled) { throw new UnsupportedOperationException(); }
    static String publishAnnouncement(String text) { throw new UnsupportedOperationException(); }
    public static State state() { throw new UnsupportedOperationException(); }
    public static List<String> addresses(Context context) { throw new UnsupportedOperationException(); }
    public static final class StateExtra { }
    /** Synthetic accessor of the private stats array in the real class. */
    static int[] access$600() { throw new UnsupportedOperationException(); }
    static LanRace.Snapshot raceSnapshot() { throw new UnsupportedOperationException(); }
}
