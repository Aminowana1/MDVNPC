package com.mdvcraft.mdvnpc.work;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side vanilla fishing bobber owned by the disguised NPC entity.
 *
 * The fishing-bobber spawn packet stores the owner's entity id in its data field. LibsDisguises
 * exposes this same server entity id as the NPC's player disguise, so vanilla clients draw the
 * normal hook and fishing line from the rod without any ItemDisplay/particle imitation.
 */
final class VanillaFishingBobber implements FishermanController.BobberVisual {
    // Server entity ids are small monotonically increasing values. Reserve a distant client-only range.
    private static final AtomicInteger IDS = new AtomicInteger(2_000_000_000);

    private final ActiveNpc npc;
    private final int entityId;
    private final UUID uuid = UUID.randomUUID();
    private final Set<UUID> viewers = new HashSet<>();
    private Location position;
    private boolean removed;

    VanillaFishingBobber(ActiveNpc npc, Location start) {
        this.npc = npc;
        this.position = start.clone();
        int next = IDS.getAndDecrement();
        if (next < 1_900_000_000) {
            IDS.compareAndSet(next - 1, 2_000_000_000);
            next = IDS.getAndDecrement();
        }
        this.entityId = next;
    }

    @Override public boolean move(Location target) {
        if (removed || target == null || target.getWorld() == null || !npc.entity().isValid()
                || npc.entity().isDead() || target.getWorld() != npc.entity().getWorld()) return false;
        position = target.clone();
        try {
            syncViewers();
            return true;
        } catch (RuntimeException | LinkageError error) {
            remove();
            return false;
        }
    }

    private void syncViewers() {
        Set<UUID> tracked = new HashSet<>();
        Set<Player> trackedBy = npc.entity().getTrackedBy();
        if (trackedBy == null) trackedBy = Set.of();
        for (Player player : trackedBy) {
            if (player == null || !player.isOnline() || player.getWorld() != position.getWorld()) continue;
            tracked.add(player.getUniqueId());
            if (viewers.add(player.getUniqueId())) sendSpawn(player);
            else sendMove(player);
        }
        for (UUID id : new HashSet<>(viewers)) {
            if (tracked.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) sendDestroy(player);
            viewers.remove(id);
        }
    }

    private void sendSpawn(Player player) {
        var packetLocation = new com.github.retrooper.packetevents.protocol.world.Location(
                position.getX(), position.getY(), position.getZ(), 0, 0);
        var packet = new WrapperPlayServerSpawnEntity(entityId, uuid, EntityTypes.FISHING_BOBBER,
                packetLocation, 0, npc.entity().getEntityId(), new Vector3d(0, 0, 0));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private void sendMove(Player player) {
        var packet = new WrapperPlayServerEntityTeleport(entityId,
                new Vector3d(position.getX(), position.getY(), position.getZ()), 0, 0, false);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private void sendDestroy(Player player) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerDestroyEntities(entityId));
    }

    @Override public void remove() {
        if (removed) return;
        removed = true;
        try {
            for (UUID id : new HashSet<>(viewers)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) sendDestroy(player);
            }
        } catch (RuntimeException | LinkageError ignored) {
        } finally {
            viewers.clear();
        }
    }
}
