// Compile-time stub of the game's class; never packaged.
package com.gtavsource.android.lan;

import android.content.Context;
import java.util.List;

public final class LanSession {
    public static final class State {
        public boolean active, busy, cleanup, host, virtualNetwork, ready, gameReady;
        public String code, status;
        public int players, playerLimit;
    }
    public static State state() { throw new UnsupportedOperationException(); }
    public static List<String> addresses(Context context) { throw new UnsupportedOperationException(); }
    static LanRace.Snapshot raceSnapshot() { throw new UnsupportedOperationException(); }
}
