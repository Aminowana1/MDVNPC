package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.model.NpcDefinition.Click;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Executor;
import com.mdvcraft.mdvnpc.util.Messages;
import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.logging.Logger;

public final class InteractionService {
    private record Key(String npc, UUID player) {}
    private final Map<Key, Long> cooldowns = new HashMap<>();
    private final Map<Key, Long> lastEvents = new HashMap<>();
    private final Messages messages;
    private final Logger logger;
    public InteractionService(Messages messages, Logger logger) { this.messages = messages; this.logger = logger; }

    public void click(ActiveNpc npc, Player player, Click click, long now) {
        var interaction = npc.definition().interaction();
        if (!npc.entity().isValid() || !player.getWorld().equals(npc.anchor().getWorld())) return;
        if (player.getLocation().distanceSquared(npc.anchor()) > interaction.range() * interaction.range()) return;
        if (interaction.lineOfSight() && !player.hasLineOfSight(npc.entity())) return;
        var actions = interaction.actions().stream().filter(a -> a.click() == click || a.click() == Click.BOTH).toList();
        if (actions.isEmpty()) return;
        Key key = new Key(npc.definition().id(), player.getUniqueId());
        // Bukkit/LibsDisguises may report InteractAtEntity and InteractEntity for one click.
        Long previous = lastEvents.put(key, now);
        if (previous != null && now - previous < DialogueService.nanos(0.15)) return;
        if (!interaction.permission().isBlank() && !player.hasPermission(interaction.permission())) {
            messages.send(player, "action-denied");
            return;
        }
        Long due = cooldowns.get(key);
        if (due != null && now < due) {
            messages.send(player, "cooldown", "seconds", Long.toString((long) Math.ceil((due - now) / 1_000_000_000.0)));
            return;
        }
        cooldowns.put(key, now + DialogueService.nanos(interaction.cooldownSeconds()));
        for (var action : actions) {
            String command = Text.placeholders(action.command(), player, npc.definition());
            try {
                boolean executed = action.executor() == Executor.CONSOLE
                        ? Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) : player.performCommand(command);
                if (!executed) logger.warning("NPC " + npc.definition().id() + ": comando no reconocido: " + action.command());
            } catch (RuntimeException ex) {
                logger.log(java.util.logging.Level.WARNING, "Error en comando del NPC " + npc.definition().id(), ex);
            }
        }
    }
    public void prune(long now) {
        cooldowns.values().removeIf(due -> now >= due);
        lastEvents.values().removeIf(last -> now - last > DialogueService.nanos(1));
    }
    public void forget(UUID player) {
        cooldowns.keySet().removeIf(key -> key.player().equals(player));
        lastEvents.keySet().removeIf(key -> key.player().equals(player));
    }
    public void clear() { cooldowns.clear(); lastEvents.clear(); }
}
