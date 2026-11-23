package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Hides every normal NPC (npcs.yml) from chosen players, on behalf of other plugins.
 *
 * <p>Every entity MDVNPC spawns for a normal NPC (the base villager plus seats, boats and
 * work displays) is registered with {@link #track(Entity)}. A viewer is hidden while at
 * least one owner plugin asks for it; the hidden state is applied to entities that already
 * exist and to every entity tracked later, so respawns, reloads and chunk loads keep it.
 * {@link PlayerFilter} also skips hidden viewers, so hidden NPC never talk to them, look at
 * them, react to them or open shops for them.</p>
 *
 * <p>Companions are never tracked here: their visibility belongs to their own viewer list.
 * State is memory only and is dropped when the viewer quits or the owner plugin disables.
 * Main thread only.</p>
 */
public final class NpcVisibilityService {
    /** Invalid entities are pruned once the registry grows past this size. */
    static final int PRUNE_THRESHOLD = 256;

    private final Plugin plugin;
    private final Map<UUID, Set<Plugin>> owners = new HashMap<>();
    private final Map<UUID, Entity> tracked = new LinkedHashMap<>();

    public NpcVisibilityService(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Null-safe registration for code that may run with a test/mocked plugin. */
    public static void track(MdvNpcPlugin plugin, Entity entity) {
        NpcVisibilityService service = plugin == null ? null : plugin.visibility();
        if (service != null) service.track(entity);
    }

    /** Registers an NPC-owned entity and hides it from every currently hidden viewer. */
    public void track(Entity entity) {
        if (entity == null || !entity.isValid()) return;
        if (tracked.size() >= PRUNE_THRESHOLD) prune();
        tracked.put(entity.getUniqueId(), entity);
        for (Player viewer : hiddenOnlineViewers()) viewer.hideEntity(plugin, entity);
    }

    /**
     * Starts hiding all normal NPC from {@code viewer} on behalf of {@code owner}.
     *
     * @return true if the viewer was not hidden before this call
     */
    public boolean hide(Plugin owner, Player viewer) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(viewer, "viewer");
        Set<Plugin> set = owners.computeIfAbsent(viewer.getUniqueId(), ignored -> new LinkedHashSet<>());
        boolean first = set.isEmpty();
        set.add(owner);
        if (first) {
            prune();
            for (Entity entity : tracked.values()) viewer.hideEntity(plugin, entity);
        }
        return first;
    }

    /**
     * Stops hiding for {@code owner}. NPC become visible again only when no other owner
     * still hides them from this viewer.
     *
     * @return true if the viewer can see NPC again after this call
     */
    public boolean show(Plugin owner, Player viewer) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(viewer, "viewer");
        Set<Plugin> set = owners.get(viewer.getUniqueId());
        if (set == null || !set.remove(owner)) return false;
        if (!set.isEmpty()) return false;
        owners.remove(viewer.getUniqueId());
        reveal(viewer);
        return true;
    }

    public boolean isHidden(UUID viewer) {
        Set<Plugin> set = viewer == null ? null : owners.get(viewer);
        return set != null && !set.isEmpty();
    }

    public boolean isHidden(Player viewer) {
        return viewer != null && isHidden(viewer.getUniqueId());
    }

    /** Paper forgets per-player entity visibility on quit, so we forget it too. */
    public void playerQuit(UUID viewer) {
        if (viewer != null) owners.remove(viewer);
    }

    /** Drops every hide request made by a plugin that is being disabled. */
    public void ownerDisabled(Plugin owner) {
        if (owner == null) return;
        for (UUID viewer : List.copyOf(owners.keySet())) {
            Set<Plugin> set = owners.get(viewer);
            if (set == null || !set.remove(owner) || !set.isEmpty()) continue;
            owners.remove(viewer);
            Player player = Bukkit.getPlayer(viewer);
            if (player != null && player.isOnline()) reveal(player);
        }
    }

    /** Shows everything to everyone and forgets all state (MDVNPC disabling). */
    public void close() {
        for (Player viewer : hiddenOnlineViewers()) reveal(viewer);
        owners.clear();
        tracked.clear();
    }

    int trackedCount() {
        return tracked.size();
    }

    private void reveal(Player viewer) {
        prune();
        for (Entity entity : tracked.values()) viewer.showEntity(plugin, entity);
    }

    private void prune() {
        tracked.values().removeIf(entity -> entity == null || !entity.isValid());
    }

    private List<Player> hiddenOnlineViewers() {
        List<Player> out = new ArrayList<>();
        for (Map.Entry<UUID, Set<Plugin>> entry : owners.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) out.add(player);
        }
        return out;
    }
}
