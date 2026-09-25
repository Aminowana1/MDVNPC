package com.mdvcraft.mdvnpc.command;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.util.*;

public final class NpcCommand implements CommandExecutor, TabCompleter {
    private static final List<String> COMMANDS = List.of("help", "list", "status", "create", "movehere", "delete", "rename", "skin", "enable", "reload");
    private final MdvNpcPlugin plugin;
    public NpcCommand(MdvNpcPlugin plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var msg = plugin.messages();
        if (!sender.hasPermission("mdvnpc.admin")) { msg.send(sender, "no-permission"); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "list" -> msg.send(sender, "list", "npcs", String.join(", ", plugin.definitions().keySet()));
                case "status" -> msg.send(sender, "status", "count", "" + plugin.definitions().size(), "active", "" + plugin.manager().activeCount(), "ticks", "" + plugin.settings().intervalTicks());
                case "reload" -> { plugin.reloadNpcs(); msg.send(sender, "reloaded", "count", "" + plugin.definitions().size()); }
                case "create", "movehere", "delete", "rename", "skin", "enable" -> {
                    int needed = Set.of("create", "rename", "skin", "enable").contains(sub) ? 3 : 2;
                    if (args.length < needed) { msg.help(sender); return true; }
                    String id = args[1];
                    NpcParser.validateId(id);
                    if (!sub.equals("create") && !plugin.definitions().containsKey(id)) { msg.send(sender, "not-found"); return true; }
                    if (sub.equals("create") && plugin.definitions().containsKey(id)) throw new IllegalArgumentException("Ya existe " + id);
                    Location location = null;
                    if (sub.equals("create") || sub.equals("movehere")) {
                        if (!(sender instanceof Player player)) { msg.send(sender, "player-only"); return true; }
                        location = player.getLocation();
                    }
                    if (sub.equals("enable") && !args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false"))
                        throw new IllegalArgumentException("Usa true o false");
                    Location destination = location;
                    plugin.repository().edit(yaml -> {
                        String p = "npcs." + id;
                        switch (sub) {
                            case "create" -> {
                                yaml.set(p + ".enabled", true);
                                yaml.set(p + ".name", args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : id);
                                yaml.set(p + ".name-visible", true);
                                yaml.set(p + ".skin.name", args[2]);
                                yaml.set(p + ".look.enabled", true); yaml.set(p + ".look.range", 6);
                                yaml.set(p + ".dialogue.enabled", true); yaml.set(p + ".dialogue.range", 6);
                                yaml.set(p + ".dialogue.interval-seconds", 40); yaml.set(p + ".dialogue.initial-delay-seconds", 2);
                                yaml.set(p + ".dialogue.random", true); yaml.set(p + ".dialogue.lines", List.of("&7{npc} &f» &7Hola, &e{player}&7."));
                                yaml.set(p + ".interaction.cooldown-seconds", 2); yaml.set(p + ".interaction.range", 6);
                                yaml.set(p + ".interaction.commands", List.of());
                                setLocation(yaml, p, destination);
                            }
                            case "movehere" -> setLocation(yaml, p, destination);
                            case "delete" -> yaml.set(p, null);
                            case "rename" -> yaml.set(p + ".name", String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                            case "skin" -> {
                                yaml.set(p + ".skin.name", args[2]);
                                yaml.set(p + ".skin.texture", ""); yaml.set(p + ".skin.signature", ""); yaml.set(p + ".skin.uuid", "");
                            }
                            case "enable" -> yaml.set(p + ".enabled", Boolean.parseBoolean(args[2]));
                        }
                    });
                    plugin.reloadNpcs();
                    msg.send(sender, sub.equals("delete") ? "deleted" : "saved", "npc", id);
                }
                default -> msg.help(sender);
            }
        } catch (Exception ex) { msg.send(sender, "error", "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); }
        return true;
    }
    private static void setLocation(YamlConfiguration yaml, String path, Location loc) {
        String p = path + ".location.";
        yaml.set(p + "world", loc.getWorld().getName()); yaml.set(p + "world-uuid", loc.getWorld().getUID().toString());
        yaml.set(p + "x", loc.getX()); yaml.set(p + "y", loc.getY()); yaml.set(p + "z", loc.getZ());
        yaml.set(p + "yaw", loc.getYaw()); yaml.set(p + "pitch", loc.getPitch());
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mdvnpc.admin")) return List.of();
        Collection<String> choices = args.length == 1 ? COMMANDS : args.length == 2 && Set.of("movehere", "delete", "rename", "skin", "enable").contains(args[0].toLowerCase(Locale.ROOT))
                ? plugin.definitions().keySet() : args.length == 3 && args[0].equalsIgnoreCase("enable") ? List.of("true", "false") : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
