package com.mdvcraft.mdvnpc.util;

import com.mdvcraft.mdvnpc.config.Settings;
import org.bukkit.command.CommandSender;
import java.util.function.Supplier;

public final class Messages {
    private final Supplier<Settings> settings;
    public Messages(Supplier<Settings> settings) { this.settings = settings; }
    public void send(CommandSender to, String key, String... replacements) {
        String value = settings.get().messages().getString("messages." + key, "");
        if (value.isEmpty()) return;
        for (int i = 0; i + 1 < replacements.length; i += 2) value = value.replace("{" + replacements[i] + "}", replacements[i + 1]);
        to.sendMessage(Text.color(settings.get().messages().getString("messages.prefix", "") + value));
    }
    public void help(CommandSender to) {
        settings.get().messages().getStringList("messages.help").forEach(line -> to.sendMessage(Text.color(line)));
    }
}
