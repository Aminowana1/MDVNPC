package com.mdvcraft.mdvnpc.skin;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import java.util.function.Supplier;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/** One bounded sampler only while names are resolving; disk writes use one coalescing worker. */
public final class SkinCacheService {
    private final MdvNpcPlugin plugin;
    private final SkinStore store;
    private final Map<String, Pending> pending = new HashMap<>();
    private record Pending(NpcDefinition definition, Supplier<UserProfile> profile, int attempts) {}
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MDVNPC-skin-writer"); thread.setDaemon(true); return thread;
    });
    private final Object writeLock = new Object();
    private String queued;
    private boolean writing;
    private BukkitTask task;
    public SkinCacheService(MdvNpcPlugin plugin) throws Exception {
        this.plugin = plugin;
        store = new SkinStore(plugin.getDataFolder().toPath()); store.load();
    }
    public NpcDefinition.Skin resolve(NpcDefinition definition) { return store.resolve(definition.id(), definition.skin()); }
    public void watch(NpcDefinition definition, Supplier<UserProfile> profile) {
        if (!resolve(definition).texture().isBlank()) return;
        pending.put(definition.id(), new Pending(definition, profile, 0));
        if (task == null) task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sample, 20, 20);
    }
    public void forget(String npc) { pending.remove(npc); stopIfEmpty(); }
    public void invalidate(String npc) { forget(npc); if (store.remove(npc)) persist(); }
    private void sample() {
        boolean changed = false;
        for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next(); var p = entry.getValue();
            var current = plugin.definitions().get(entry.getKey());
            if (current == null || !current.skin().equals(p.definition.skin())) { iterator.remove(); continue; }
            var profile = p.profile.get();
            NpcDefinition.Skin found = null;
            if (profile != null) for (var property : profile.getTextureProperties()) {
                if ("textures".equals(property.getName()) && property.getSignature() != null) {
                    var candidate = new NpcDefinition.Skin(p.definition.skin().name(), property.getValue(), property.getSignature(), profile.getUUID());
                    if (SkinStore.valid(candidate)) { found = candidate; break; }
                }
            }
            if (found != null) { changed |= store.put(entry.getKey(), found); iterator.remove(); }
            else if (p.attempts >= 59) {
                plugin.getLogger().warning("Skin de " + entry.getKey() + " no resuelta en 60 s; no se guardó una skin vacía. Reintenta con /mdvnpc reload.");
                iterator.remove();
            } else entry.setValue(new Pending(p.definition, p.profile, p.attempts + 1));
        }
        if (changed) persist();
        stopIfEmpty();
    }
    private void stopIfEmpty() { if (pending.isEmpty() && task != null) { task.cancel(); task = null; } }
    private void persist() {
        String text = store.serialize(); // Bukkit YAML is only accessed on the server thread.
        synchronized (writeLock) {
            queued = text;
            if (writing) return;
            writing = true;
        }
        writer.execute(() -> {
            while (true) {
                String next;
                synchronized (writeLock) {
                    next = queued; queued = null;
                    if (next == null) { writing = false; return; }
                }
                try { store.write(next); }
                catch (Exception ex) { plugin.getLogger().log(Level.SEVERE, "No se pudo persistir el cache de skins por NPC", ex); }
            }
        });
    }
    public void capturePending() { sample(); }
    public void close() {
        sample(); pending.clear(); stopIfEmpty(); persist(); writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) plugin.getLogger().severe("Guardado de skins pendiente después de 5 s; comprueba el disco."); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
