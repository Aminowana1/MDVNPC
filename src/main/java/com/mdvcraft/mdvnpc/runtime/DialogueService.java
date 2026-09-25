package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class DialogueService {
    private record Key(String npc, UUID player) {}
    private static final class State {
        long due, lastSeen;
        int nextLine;
        State(long due, long now) { this.due = due; this.lastSeen = now; }
    }
    private final Map<Key, State> states = new HashMap<>();

    public void update(ActiveNpc npc, Collection<Player> players, long now) {
        var dialogue = npc.definition().dialogue();
        if (!dialogue.enabled() || dialogue.lines().isEmpty()) return;
        for (Player player : players) {
            if (player.getLocation().distanceSquared(npc.anchor()) > dialogue.range() * dialogue.range()) continue;
            if (dialogue.lineOfSight() && !npc.entity().hasLineOfSight(player)) continue;
            Key key = new Key(npc.definition().id(), player.getUniqueId());
            State state = states.computeIfAbsent(key, ignored -> new State(now + nanos(dialogue.initialDelaySeconds()), now));
            state.lastSeen = now;
            if (now < state.due) continue;
            int index = dialogue.random() ? ThreadLocalRandom.current().nextInt(dialogue.lines().size())
                    : state.nextLine;
            state.nextLine = (index + 1) % dialogue.lines().size();
            player.sendMessage(Text.color(Text.placeholders(dialogue.lines().get(index), player, npc.definition())));
            state.due = now + nanos(dialogue.intervalSeconds());
        }
    }
    public void prune(long now) {
        // Keep a departing player's cooldown until it expires, but release old entries.
        states.values().removeIf(state -> now >= state.due && now - state.lastSeen > nanos(60));
    }
    public void forget(UUID player) { states.keySet().removeIf(key -> key.player().equals(player)); }
    public void clear() { states.clear(); }
    public static long nanos(double seconds) { return (long) (seconds * 1_000_000_000L); }
}
