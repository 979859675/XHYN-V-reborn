// Compile-time stub of the game's class; never packaged.
package com.gtavsource.android.lan;

final class LanRace {
    static final class Course {
        String name;
        int count() { throw new UnsupportedOperationException(); }
    }
    static final class Racer {
        int finishMs, place, reason, status;
        String name;
    }
    static final class Snapshot {
        long id;
        int laps, phase;
        Course course;
        Racer[] standings() { throw new UnsupportedOperationException(); }
    }
    static String reason(int code) { throw new UnsupportedOperationException(); }
}
