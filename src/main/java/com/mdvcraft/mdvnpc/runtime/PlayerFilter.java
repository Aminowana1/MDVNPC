package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.config.Settings;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

public final class PlayerFilter {
    private PlayerFilter() {}
    public static boolean accepts(Player player, Settings settings) {
        return player.isOnline() && !player.isDead()
                && !(settings.ignoreSpectators() && player.getGameMode() == GameMode.SPECTATOR)
                && !(settings.ignoreInvisible() && (player.isInvisible()
                    || player.getMetadata("vanished").stream().anyMatch(value -> value.asBoolean())));
    }
}
