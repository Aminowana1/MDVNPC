package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.config.Settings;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Predicate;

public final class PlayerFilter {
    private static final Predicate<UUID> NONE = id -> false;
    /** Players from whom normal NPC are hidden (see {@link NpcVisibilityService}). */
    private static volatile Predicate<UUID> hiddenViewers = NONE;

    private PlayerFilter() {}

    public static boolean accepts(Player player, Settings settings) {
        return player.isOnline() && !player.isDead()
                && !hiddenViewers.test(player.getUniqueId())
                && !(settings.ignoreSpectators() && player.getGameMode() == GameMode.SPECTATOR)
                && !(settings.ignoreInvisible() && (player.isInvisible()
                    || player.getMetadata("vanished").stream().anyMatch(value -> value.asBoolean())));
    }

    /** True if normal NPC are hidden from this player; such players are ignored by NPC. */
    public static boolean hidden(Player player) {
        return player != null && hiddenViewers.test(player.getUniqueId());
    }

    /** Installed by MDVNPC on enable; {@code null} restores "nobody is hidden". */
    public static void hiddenViewers(Predicate<UUID> predicate) {
        hiddenViewers = predicate == null ? NONE : predicate;
    }
}
