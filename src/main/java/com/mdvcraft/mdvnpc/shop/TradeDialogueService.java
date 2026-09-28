package com.mdvcraft.mdvnpc.shop;

import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.runtime.DialogueService;
import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** The purchase message has a distinct per-player/per-NPC cooldown from proximity messages. */
public final class TradeDialogueService {
    private record Key(UUID player, String npc) {}
    private final Map<Key, Long> due = new HashMap<>();
    private final Map<Key, Integer> sequence = new HashMap<>();
    public void traded(Player player, NpcDefinition npc) {
        var dialog = npc.tradeDialogue();
        if (!dialog.enabled() || dialog.lines().isEmpty()) return;
        var key = new Key(player.getUniqueId(), npc.id());
        long now = System.nanoTime();
        Long next = due.get(key);
        if (next != null && now - next < 0) return;
        int index = dialog.random() ? ThreadLocalRandom.current().nextInt(dialog.lines().size()) : sequence.getOrDefault(key, 0) % dialog.lines().size();
        sequence.put(key, (index + 1) % dialog.lines().size());
        due.put(key, now + DialogueService.nanos(dialog.cooldownSeconds()));
        player.sendMessage(Text.color(Text.placeholders(dialog.lines().get(index), player, npc)));
    }
    public void forget(UUID player) {
        due.keySet().removeIf(k -> k.player.equals(player));
        sequence.keySet().removeIf(k -> k.player.equals(player));
    }
    public void clear() { due.clear(); sequence.clear(); }
    public void prune(long now) {
        due.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < DialogueService.nanos(60)) return false;
            sequence.remove(entry.getKey()); return true;
        });
    }
}
