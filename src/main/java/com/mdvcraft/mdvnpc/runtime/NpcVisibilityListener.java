package com.mdvcraft.mdvnpc.runtime;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/** Forgets hide requests when the viewer leaves or the plugin that made them disables. */
public final class NpcVisibilityListener implements Listener {
    private final Plugin plugin;
    private final NpcVisibilityService visibility;

    public NpcVisibilityListener(Plugin plugin, NpcVisibilityService visibility) {
        this.plugin = plugin;
        this.visibility = visibility;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        visibility.playerQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void pluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != plugin) visibility.ownerDisabled(event.getPlugin());
    }
}
