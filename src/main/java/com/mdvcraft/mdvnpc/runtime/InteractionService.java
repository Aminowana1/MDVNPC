package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.model.NpcDefinition.Click;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Executor;
import com.mdvcraft.mdvnpc.util.Messages;
import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import com.mdvcraft.mdvnpc.storage.AtomicFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Lives as long as the plugin: neither a relog nor a reload may reset a cooldown. */
public final class InteractionService {
    /** Shorter cooldowns do not need to survive a restart, nor a disk write per click. */
    private static final long PERSIST_FROM = DialogueService.nanos(60);
    private record Key(String npc, UUID player) {}
    private final Map<Key, Long> cooldowns = new HashMap<>();
    private final Map<Key, Long> lastEvents = new HashMap<>();
    private final Messages messages;
    private final Logger logger;
    private final Path file;
    private final ExecutorService writer;
    private final AtomicReference<String> queued = new AtomicReference<>();
    public InteractionService(Messages messages, Logger logger) { this(messages, logger, null); }
    public InteractionService(Messages messages, Logger logger, Path file) {
        this.messages = messages; this.logger = logger; this.file = file;
        writer = file == null ? null : Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "MDVNPC-cooldown-writer"); thread.setDaemon(true); return thread;
        });
        if (file != null) load();
    }

    public void click(ActiveNpc npc, Player player, Click click, long now) {
        var interaction = npc.definition().interaction();
        if (!npc.entity().isValid() || !player.getWorld().equals(npc.position().getWorld())) return;
        if (player.getLocation().distanceSquared(npc.position()) > interaction.range() * interaction.range()) return;
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
        long cooldown = DialogueService.nanos(interaction.cooldownSeconds());
        Long due = cooldowns.get(key);
        // A cooldown lowered by an administrator also applies to the players already waiting.
        if (due != null && due - now > cooldown) {
            due = now + cooldown; cooldowns.put(key, due); persist();
        }
        if (due != null && now < due) {
            messages.send(player, "cooldown", "seconds", Long.toString((long) Math.ceil((due - now) / 1_000_000_000.0)));
            return;
        }
        cooldowns.put(key, now + cooldown);
        if (cooldown >= PERSIST_FROM) persist();
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
    /** Drops only the double-event debounce; cooldowns outlive the connection. */
    public void forget(UUID player) { lastEvents.keySet().removeIf(key -> key.player().equals(player)); }
    public void resetEvents() { lastEvents.clear(); }

    // One line per cooldown: expiry in epoch milliseconds, player and NPC id.
    private void load() {
        if (!Files.exists(file)) return;
        try {
            long wall = System.currentTimeMillis(), nano = System.nanoTime();
            for (String line : Files.readAllLines(file)) {
                String[] part = line.split(" ");
                try {
                    if (part.length != 3) throw new IllegalArgumentException(line);
                    long remaining = Long.parseLong(part[0]) - wall;
                    if (remaining > 0) cooldowns.put(new Key(part[2], UUID.fromString(part[1])),
                            nano + TimeUnit.MILLISECONDS.toNanos(Math.min(remaining, TimeUnit.DAYS.toMillis(1))));
                } catch (IllegalArgumentException invalid) {
                    logger.warning("Línea inválida ignorada en " + file.getFileName() + ": " + line);
                }
            }
        } catch (Exception ex) { logger.log(Level.WARNING, "No se pudieron leer los cooldowns guardados", ex); }
    }
    private void persist() {
        if (writer == null || writer.isShutdown()) return;
        long wall = System.currentTimeMillis(), nano = System.nanoTime();
        StringBuilder text = new StringBuilder();
        cooldowns.forEach((key, due) -> {
            long remaining = due - nano;
            if (remaining > 0) text.append(wall + TimeUnit.NANOSECONDS.toMillis(remaining))
                    .append(' ').append(key.player()).append(' ').append(key.npc()).append('\n');
        });
        if (queued.getAndSet(text.toString()) != null) return; // The queued write will take the newer text.
        writer.execute(() -> {
            String next = queued.getAndSet(null);
            try {
                Files.createDirectories(file.getParent());
                AtomicFile.write(file, next);
            } catch (Exception ex) { logger.log(Level.SEVERE, "No se pudieron guardar los cooldowns", ex); }
        });
    }
    public void close() {
        if (writer == null || writer.isShutdown()) return;
        persist(); writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) logger.severe("Guardado de cooldowns pendiente después de 5 s; comprueba el disco."); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
