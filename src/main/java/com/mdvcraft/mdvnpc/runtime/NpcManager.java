package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.skin.DisguiseService;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.entity.CreatureSpawnEvent;
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
    // A spawn callback marks the entity before it has been inserted in the active map.
    // Keep it protected from our own stale-entity cleanup until spawn/disguise completes.
    private final Set<UUID> spawning = new HashSet<>();
    private final Map<UUID, String> spawnOutcomes = new HashMap<>();
    private final Map<ChunkKey, List<NpcDefinition>> byChunk = new HashMap<>();
    private final Set<ChunkKey> pending = new HashSet<>();
    private Map<String, NpcDefinition> definitions = Map.of();
    private BukkitTask ticker;
    private long generation;
    private long nextMaintenance;
    private long tickCounter;
    private long nextRespawn;
    private final Map<String, Long> spawnRetry = new HashMap<>();
    private final LookService look = new LookService();
    private final DialogueService dialogue;
    private final InteractionService interactions;

    public NpcManager(MdvNpcPlugin plugin) {
        this.plugin = plugin;
        dialogue = new DialogueService(plugin.sounds());
        marker = new NamespacedKey(plugin, "npc-id");
        interactions = new InteractionService(plugin.messages(), plugin.getLogger());
    }
    public void start(Map<String, NpcDefinition> definitions) {
        this.definitions = definitions;
        indexWorlds();
        // Startup/reload-only cleanup of entities marked by MDVNPC; never touch other plugins.
        for (World world : Bukkit.getWorlds())
            for (Entity entity : world.getEntities()) if (owned(entity) || plugin.routines().isSeat(entity)) entity.remove();
        for (ChunkKey key : byChunk.keySet()) queue(key);
        int cadence = gcd(plugin.settings().intervalTicks(), plugin.settings().lookIntervalTicks());
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(cadence), cadence, cadence);
    }
    public void stop() {
        plugin.skins().capturePending();
        generation++;
        if (ticker != null) { ticker.cancel(); ticker = null; }
        for (ActiveNpc npc : List.copyOf(active.values())) remove(npc);
        active.clear(); byEntity.clear(); spawning.clear(); spawnOutcomes.clear(); byChunk.clear(); pending.clear();
        dialogue.clear(); interactions.clear(); spawnRetry.clear();
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
            for (var point : plugin.routines().points(definition.id())) {
                if (!point.world().equals(world.getUID())) continue;
                var list = byChunk.computeIfAbsent(new ChunkKey(world.getUID(), point.x() >> 4, point.z() >> 4), ignored -> new ArrayList<>());
                if (!list.contains(definition)) list.add(definition);
            }
        }
    }
    public World resolveWorld(NpcDefinition definition) {
        var position = definition.position();
        // An explicit UUID wins: never silently spawn in a different world with the same name.
        return position.worldId() != null ? Bukkit.getWorld(position.worldId()) : Bukkit.getWorld(position.worldName());
    }
    public void worldLoaded() {
        plugin.routines().worldLoaded();
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
        Location anchor = plugin.routines().spawnLocation(definition, world);
        if (anchor == null || !world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) return;
        long now = System.nanoTime();
        if (now < spawnRetry.getOrDefault(definition.id(), 0L)) return;
        spawnRetry.put(definition.id(), now + 30_000_000_000L);
        if (p.y() < world.getMinHeight() || p.y() >= world.getMaxHeight()) {
            plugin.getLogger().warning("NPC " + definition.id() + ": altura fuera de los límites del mundo.");
            return;
        }
        Villager entity = null;
        UUID[] spawningId = new UUID[1];
        String stage = "generar la entidad base";
        try {
            entity = world.spawn(anchor, Villager.class, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, villager -> {
                spawningId[0] = villager.getUniqueId();
                spawning.add(spawningId[0]);
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
            if (entity == null || !entity.isValid()) {
                String outcome = spawningId[0] == null ? "no se ejecutó el callback de creación"
                        : spawnOutcomes.getOrDefault(spawningId[0], "no se observó el estado final de CreatureSpawnEvent");
                throw new IllegalStateException("El aldeano base no está en el mundo: " + outcome
                        + ". Comprueba las flags mob-spawning/deny-spawn de WorldGuard en "
                        + world.getName() + " y los plugins de control de entidades.");
            }
            stage = "aplicar el disfraz de LibsDisguises";
            var disguise = skins.apply(entity, definition, plugin.skins().resolve(definition));
            plugin.skins().watch(definition, disguise::getUserProfile);
            ActiveNpc npc = new ActiveNpc(definition, anchor, entity, disguise);
            active.put(definition.id(), npc);
            byEntity.put(entity.getUniqueId(), npc);
            spawnRetry.remove(definition.id());
        } catch (RuntimeException | LinkageError ex) {
            if (entity != null) entity.remove();
            plugin.getLogger().log(Level.SEVERE, "No se pudo crear NPC " + definition.id() + " en "
                    + world.getName() + " [" + p.x() + ", " + p.y() + ", " + p.z() + "] al "
                    + stage + ". /mdvnpc reload solo reintentará la aparición.", ex);
        } finally {
            if (spawningId[0] != null) {
                spawning.remove(spawningId[0]);
                spawnOutcomes.remove(spawningId[0]);
            }
        }
    }
    private void remove(ActiveNpc npc) {
        if(plugin.music()!=null)plugin.music().remove(npc.definition().id());
        if(plugin.reactions()!=null)plugin.reactions().cancel(npc.definition().id());
        if(plugin.traits()!=null)plugin.traits().cancel(npc.definition().id());
        if(plugin.sounds()!=null)plugin.sounds().forget(npc.definition().id());
        plugin.routines().remove(npc.definition().id());
        plugin.skins().forget(npc.definition().id());
        plugin.shops().invalidateNpc(npc.definition().id());
        active.remove(npc.definition().id(), npc);
        byEntity.remove(npc.entity().getUniqueId(), npc);
        try { npc.disguise().removeDisguise(); }
        catch (RuntimeException ex) { plugin.getLogger().log(Level.WARNING, "No se pudo retirar un disfraz", ex); }
        finally { npc.entity().remove(); }
    }
    public void chunkUnloaded(Chunk chunk) {
        for (ActiveNpc npc : List.copyOf(active.values())) {
            Location location = npc.position();
            if (location.getWorld() == chunk.getWorld() && location.getBlockX() >> 4 == chunk.getX() && location.getBlockZ() >> 4 == chunk.getZ()) remove(npc);
        }
    }
    public void cleanupLoadedEntities(List<Entity> entities) {
        for (Entity entity : entities) {
            if (plugin.routines().isSeat(entity) && !plugin.routines().liveSeat(entity)) entity.remove();
            if (owned(entity) && !spawning.contains(entity.getUniqueId())
                    && !byEntity.containsKey(entity.getUniqueId())) entity.remove();
        }
    }
    /** Solo se reconocen UUID creados por nuestro propio callback de World.spawn. */
    public boolean isSpawningNpc(Entity entity) {
        return spawning.contains(entity.getUniqueId()) && owned(entity);
    }
    public String pendingNpcId(Entity entity) {
        if (!isSpawningNpc(entity)) return "(desconocido)";
        return entity.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
    }
    /** Observe only our own in-flight entity, after WG's targeted region exception. */
    public void observeSpawn(CreatureSpawnEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (!spawning.contains(id)) return;
        spawnOutcomes.put(id, "CreatureSpawnEvent " + event.getSpawnReason() +
                (event.isCancelled() ? " CANCELADO al terminar los listeners" : " no cancelado al terminar los listeners"));
    }
    public void entityUnloaded(Entity entity) {
        ActiveNpc npc = byEntity.get(entity.getUniqueId());
        if (npc != null) remove(npc);
    }
    private static int gcd(int a, int b) { while (b != 0) { int rem = a % b; a = b; b = rem; } return a; }
    private void tick(int cadence) {
        tickCounter += cadence;
        boolean checkLook = tickCounter % plugin.settings().lookIntervalTicks() == 0;
        boolean checkDialogue = tickCounter % plugin.settings().intervalTicks() == 0;
        long now = System.nanoTime();
        if (now >= nextMaintenance) {
            dialogue.prune(now); interactions.prune(now); plugin.shops().prune(now);
            nextMaintenance = now + DialogueService.nanos(60);
        }
        if ((!checkLook && !checkDialogue) || Bukkit.getOnlinePlayers().isEmpty()) return;
        if (tickCounter >= nextRespawn) {
            nextRespawn = tickCounter + 40;
            for (var definition : definitions.values()) if (definition.enabled() && plugin.routines().enabled(definition.id()) && !active.containsKey(definition.id())) {
                World world = resolveWorld(definition); if (world != null) spawn(definition, world);
            }
        }
        for (ActiveNpc npc : List.copyOf(active.values())) {
            if (!npc.entity().isValid()) {
                remove(npc);
                var p = npc.definition().position();
                queue(new ChunkKey(npc.anchor().getWorld().getUID(), p.chunkX(), p.chunkZ()));
                continue;
            }
            var definition = npc.definition();
            if (!plugin.routines().canLook(npc)) continue;
            boolean allowLook = checkLook && (plugin.music() == null || !plugin.music().isAnimating(npc));
            double range = Math.max(allowLook && definition.look().enabled() ? definition.look().range() : 0,
                    checkDialogue && !plugin.routines().enabled(definition.id()) && definition.dialogue().enabled() && !definition.dialogue().lines().isEmpty() ? definition.dialogue().range() : 0);
            if (range <= 0) continue;
            Collection<Player> players = npc.position().getWorld().getNearbyPlayers(npc.position(), range,
                    player -> PlayerFilter.accepts(player, plugin.settings()));
            if (allowLook) look.update(npc, players, plugin.settings().rotationThreshold());
            // NPC con rutina usan exclusivamente los diálogos del goal (WORK antiguo hereda
            // el diálogo global desde RoutineService). Evita duplicar frases mientras trabaja.
            if (checkDialogue && !plugin.routines().enabled(definition.id())) dialogue.update(npc, players, now);
        }
    }
    public boolean owned(Entity entity) { return entity.getPersistentDataContainer().has(marker, PersistentDataType.STRING); }
    public ActiveNpc find(Entity entity) { return byEntity.get(entity.getUniqueId()); }
    public InteractionService interactions() { return interactions; }
    public void forget(UUID player) { dialogue.forget(player); interactions.forget(player); }
    public int activeCount() { return active.size(); }
    public Collection<ActiveNpc> activeNpcs() { return List.copyOf(active.values()); }
}

