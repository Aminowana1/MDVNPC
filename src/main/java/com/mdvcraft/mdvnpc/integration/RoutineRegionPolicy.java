package com.mdvcraft.mdvnpc.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import org.bukkit.Location;

/** Loaded only with WorldGuard enabled. NPC doors use the non-member USE policy. */
public final class RoutineRegionPolicy {
    private RoutineRegionPolicy() {}
    public static boolean canUse(Location location) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .testState(BukkitAdapter.adapt(location), (RegionAssociable)null, Flags.USE);
    }
}
