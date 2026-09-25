package com.mdvcraft.mdvnpc.runtime;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.Collection;

public final class LookService {
    public void update(ActiveNpc npc, Collection<Player> players, double threshold) {
        var look = npc.definition().look();
        if (!look.enabled()) return;
        Player nearest = null;
        double best = look.range() * look.range();
        for (Player player : players) {
            double distance = player.getLocation().distanceSquared(npc.anchor());
            if (distance <= best && (!look.lineOfSight() || npc.entity().hasLineOfSight(player))) {
                nearest = player;
                best = distance;
            }
        }
        float yaw = npc.anchor().getYaw(), pitch = npc.anchor().getPitch();
        if (nearest != null) {
            var direction = nearest.getEyeLocation().toVector().subtract(npc.entity().getEyeLocation().toVector());
            if (direction.lengthSquared() < 0.000001) return;
            Location facing = npc.anchor().clone().setDirection(direction);
            yaw = facing.getYaw();
            pitch = facing.getPitch();
        } else if (!look.resetWhenAlone()) return;
        Location current = npc.entity().getLocation();
        if (angularDifference(current.getYaw(), yaw) > threshold || Math.abs(current.getPitch() - pitch) > threshold)
            npc.entity().setRotation(yaw, pitch);
    }
    public static double angularDifference(float a, float b) {
        return Math.abs(((a - b) % 360 + 540) % 360 - 180);
    }
}
