// SPDX-License-Identifier: MIT
package com.gtavsource.android.lan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads race results out of the game. It lives in the game's package because the race classes are
 * package-private; everything else of the lobby client is in com.xhynv.online.
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
}
