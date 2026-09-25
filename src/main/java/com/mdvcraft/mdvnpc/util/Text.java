package com.mdvcraft.mdvnpc.util;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import com.mdvcraft.mdvnpc.model.NpcDefinition;

public final class Text {
    private Text() {}
    public static String color(String value) { return ChatColor.translateAlternateColorCodes('&', value); }
    public static String placeholders(String text, Player player, NpcDefinition npc) {
        return text.replace("<p>", player.getName()).replace("<player>", player.getName())
                .replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString())
                .replace("{npc}", npc.name()).replace("{npc_id}", npc.id());
    }
}
