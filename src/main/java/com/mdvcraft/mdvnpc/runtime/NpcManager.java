package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.skin.DisguiseService;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.logging.Level;

public final class NpcManager {
    private record ChunkKey(UUID world, int x, int z) {}
    private final MdvNpcPlugin plugin;
    private final NamespacedKey marker;
    private final DisguiseService skins = new DisguiseService();
    private final Map<String, ActiveNpc> active = new LinkedHashMap<>();
    private final Map<UUID, ActiveNpc> byEntity = new HashMap<>();
    private final Map<ChunkKey, List<NpcDefinition>> byChunk = new HashMap<>();
    private final Set<ChunkKey> pending = new HashSet<>();
    private Map<String, NpcDefinition> definitions = Map.of();
    private BukkitTask ticker;
    private long generation;
    private long nextMaintenance;
    private final LookService look = new LookService();
    private final DialogueService dialogue = new DialogueService();
    private final InteractionService interactions;

    public NpcManager(MdvNpcPlugin plugin) {
        this.plugin = plugin;
        marker = new NamespacedKey(plugin, "npc-id");
        interactions = new InteractionService(plugin.messages(), plugin.getLogger());
    }
    public void start(Map<String, NpcDefinition> definitions) {
        this.definitions = definitions;
        indexWorlds();
        // Startup/reload-only cleanup of entities marked by MDVNPC; never touch other plugins.
        for (World world : Bukkit.getWorlds())
            for (Entity entity : world.getEntities()) if (owned(entity)) entity.remove();
        for (ChunkKey key : byChunk.keySet()) queue(key);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, plugin.settings().intervalTicks(), plugin.settings().intervalTicks());
    }
    public void stop() {
        generation++;
        if (ticker != null) { ticker.cancel(); ticker = null; }
        for (ActiveNpc npc : List.copyOf(active.values())) remove(npc);
        active.clear(); byEntity.clear(); byChunk.clear(); pending.clear();
        dialogue.clear(); interactions.clear();
    }
    private void indexWorlds() {
        byChunk.clear();
        for (NpcDefinition definition : definitions.values()) {
            if (!definition.enabled()) continue;
            World world = resolveWorld(definition);
            if (world == null) {
                plugin.getLogger().warning("NPC " + definition.id() + ": mundo no cargado; queda pendiente. Usa /mdvnpc movehere " + definition.id() + " para cambiarlo.");
                continue;
            }
            var p = definition.position();
            byChunk.computeIfAbsent(new ChunkKey(world.getUID(), p.chunkX(), p.chunkZ()), ignored -> new ArrayList<>()).add(definition);
        }
    }
    public World resolveWorld(NpcDefinition definition) {
        var position = definition.position();
        // An explicit UUID wins: never silently spawn in a different world with the same name.
        return position.worldId() != null ? Bukkit.getWorld(position.worldId()) : Bukkit.getWorld(position.worldName());
    }
    public void worldLoaded() {
        indexWorlds();
        for (ChunkKey key : byChunk.keySet()) queue(key);
    }
    public void chunkLoaded(Chunk chunk) { queue(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ())); }
    private void queue(ChunkKey key) {
        World world = Bukkit.getWorld(key.world());
        if (!byChunk.containsKey(key) || world == null || !world.isChunkLoaded(key.x(), key.z()) || !pending.add(key)) return;
        long expectedGeneration = generation;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (expectedGeneration != generation) return;
            pending.remove(key);
            World current = Bukkit.getWorld(key.world());
            if (current == null || !current.isChunkLoaded(key.x(), key.z())) return;
            for (NpcDefinition definition : byChunk.getOrDefault(key, List.of())) spawn(definition, current);
        });
    }
    private void spawn(NpcDefinition definition, World world) {
        ActiveNpc existing = active.get(definition.id());
        if (existing != null && existing.entity().isValid()) return;
        if (existing != null) remove(existing);
        var p = definition.position();
        Location anchor = new Location(world, p.x(), p.y(), p.z(), p.yaw(), p.pitch());
        if (p.y() < world.getMinHeight() || p.y() >= world.getMaxHeight()) {
            plugin.getLogger().warning("NPC " + definition.id() + ": altura fuera de los límites del mundo.");
            return;
        }
        Villager entity = null;
        try {
            entity = world.spawn(anchor, Villager.class, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, villager -> {
                villager.getPersistentDataContainer().set(marker, PersistentDataType.STRING, definition.id());
                villager.addScoreboardTag("MDVNPC");
                villager.setAI(false);
                villager.setAware(false);
                villager.setAdult();
                villager.setAgeLock(true);
                villager.setGravity(false);
                villager.setInvulnerable(true);
                villager.setSilent(true);
                villager.setCollidable(false);
                villager.setCanPickupItems(false);
                villager.setRemoveWhenFarAway(false);
                villager.setPersistent(false);
            });
            if (!entity.isValid()) throw new IllegalStateException("Otro plugin canceló la aparición de la entidad");
            var disguise = skins.apply(entity, definition);
            ActiveNpc npc = new ActiveNpc(definition, anchor, entity, disguise);
            active.put(definition.id(), npc);
            byEntity.put(entity.getUniqueId(), npc);
        } catch (RuntimeException | LinkageError ex) {
            if (entity != null) entity.remove();
            plugin.getLogger().log(Level.SEVERE, "No se pudo crear NPC " + definition.id() + ". Revisa LibsDisguises y usa /mdvnpc reload.", ex);
        }
    }
    private void remove(ActiveNpc npc) {
        active.remove(npc.definition().id(), npc);
        byEntity.remove(npc.entity().getUniqueId(), npc);
        try { npc.disguise().removeDisguise(); }
        catch (RuntimeException ex) { plugin.getLogger().log(Level.WARNING, "No se pudo retirar un disfraz", ex); }
        finally { npc.entity().remove(); }
    }
    public void chunkUnloaded(Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        for (NpcDefinition definition : byChunk.getOrDefault(key, List.of())) {
            ActiveNpc npc = active.get(definition.id());
            if (npc != null) remove(npc);
        }
    }
    public void cleanupLoadedEntities(List<Entity> entities) {
        for (Entity entity : entities) if (owned(entity) && !byEntity.containsKey(entity.getUniqueId())) entity.remove();
    }
    public void entityUnloaded(Entity entity) {
        ActiveNpc npc = byEntity.get(entity.getUniqueId());
        if (npc != null) remove(npc);
    }
    private void tick() {
        long now = System.nanoTime();
        if (now >= nextMaintenance) {
            dialogue.prune(now); interactions.prune(now);
            nextMaintenance = now + DialogueService.nanos(60);
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        for (ActiveNpc npc : List.copyOf(active.values())) {
            if (!npc.entity().isValid()) {
                remove(npc);
                var p = npc.definition().position();
                queue(new ChunkKey(npc.anchor().getWorld().getUID(), p.chunkX(), p.chunkZ()));
                continue;
            }
            var definition = npc.definition();
            double range = Math.max(definition.look().enabled() ? definition.look().range() : 0,
                    definition.dialogue().enabled() ? definition.dialogue().range() : 0);
            if (range <= 0) continue;
            Collection<Player> players = npc.anchor().getWorld().getNearbyPlayers(npc.anchor(), range,
                    player -> PlayerFilter.accepts(player, plugin.settings()));
            look.update(npc, players, plugin.settings().rotationThreshold());
            dialogue.update(npc, players, now);
        }
    }
    public boolean owned(Entity entity) { return entity.getPersistentDataContainer().has(marker, PersistentDataType.STRING); }
    public ActiveNpc find(Entity entity) { return byEntity.get(entity.getUniqueId()); }
    public InteractionService interactions() { return interactions; }
    public void forget(UUID player) { dialogue.forget(player); interactions.forget(player); }
    public int activeCount() { return active.size(); }
}
