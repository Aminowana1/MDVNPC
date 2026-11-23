package com.mdvcraft.mdvnpc.trait;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Detects a player crouching twice in quick succession. Only presses count, not releases. */
public final class DoubleSneak {
    private final Map<UUID, Long> lastPress = new HashMap<>();

    /** @return true on the second press within {@code windowNanos} of the first */
    public boolean press(UUID player, long now, long windowNanos) {
        Long previous = lastPress.remove(player);
        if (previous != null && now - previous <= windowNanos) return true;
        lastPress.put(player, now);
        return false;
    }

    public void forget(UUID player) { lastPress.remove(player); }

    /** Drops presses older than the window; a burst of players cannot grow this map forever. */
    public void prune(long now, long windowNanos) {
        lastPress.values().removeIf(at -> now - at > windowNanos);
    }

    int size() { return lastPress.size(); }
}
