package com.mdvcraft.mdvnpc.config;

import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.model.NpcDefinition.*;
import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

public final class NpcParser {
    private NpcParser() {}
    public static Map<String, NpcDefinition> parse(ConfigurationSection yaml) {
        Map<String, NpcDefinition> result = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) throw new IllegalArgumentException("Falta la sección npcs; para vaciarla usa npcs: {}");
        for (String id : root.getKeys(false)) {
            try {
                validateId(id);
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) throw new IllegalArgumentException("Se esperaba una sección");
                String world = s.getString("location.world", "");
                UUID worldId = uuid(s.getString("location.world-uuid", ""));
                if (worldId == null && world.isBlank()) throw new IllegalArgumentException("Falta mundo o UUID de mundo");
                Position pos = new Position(worldId, world,
                        number(s, "location.x", 0, -29999984, 29999984), number(s, "location.y", 0, -2048, 2048),
                        number(s, "location.z", 0, -29999984, 29999984), (float) number(s, "location.yaw", 0, -360, 360),
                        (float) number(s, "location.pitch", 0, -90, 90));
                String texture = s.getString("skin.texture", ""), signature = s.getString("skin.signature", "");
                if (texture.isBlank() != signature.isBlank()) throw new IllegalArgumentException("La textura y firma deben estar juntas");
                if (!texture.isBlank()) { Base64.getDecoder().decode(texture); Base64.getDecoder().decode(signature); }
                String skinName = s.getString("skin.name", "Steve");
                if (!skinName.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Nombre de skin inválido");
                Skin skin = new Skin(skinName, texture, signature, uuid(s.getString("skin.uuid", "")));
                Look look = new Look(s.getBoolean("look.enabled", true), number(s, "look.range", 6, 0, 64),
                        s.getBoolean("look.require-line-of-sight", false), s.getBoolean("look.reset-when-alone", true));
                Dialogue text = new Dialogue(s.getBoolean("dialogue.enabled", true), number(s, "dialogue.range", 6, 0, 64),
                        number(s, "dialogue.interval-seconds", 40, 0.5, 86400), number(s, "dialogue.initial-delay-seconds", 2, 0, 86400),
                        s.getBoolean("dialogue.random", true), s.getBoolean("dialogue.require-line-of-sight", false), s.getStringList("dialogue.lines"));
                List<Action> actions = new ArrayList<>();
                for (Map<?, ?> row : s.getMapList("interaction.commands")) {
                    String command = Objects.toString(row.get("command"), "").trim();
                    if (command.startsWith("/")) command = command.substring(1);
                    if (command.isBlank() || command.contains("\n") || command.contains("\r")) throw new IllegalArgumentException("Comando vacío o multilínea");
                    actions.add(new Action(Click.valueOf(Objects.toString(row.get("click"), "RIGHT").toUpperCase(Locale.ROOT)),
                            Executor.valueOf(Objects.toString(row.get("executor"), "CONSOLE").toUpperCase(Locale.ROOT)), command));
                }
                Interaction interaction = new Interaction(number(s, "interaction.range", 6, 0.1, 16),
                        number(s, "interaction.cooldown-seconds", 2, 0, 86400), s.getBoolean("interaction.require-line-of-sight", true),
                        s.getString("interaction.permission", ""), actions);
                result.put(id, new NpcDefinition(id, s.getBoolean("enabled", true), s.getString("name", id),
                        s.getBoolean("name-visible", true), pos, skin, look, text, interaction));
            } catch (RuntimeException ex) { throw new IllegalArgumentException("NPC " + id + ": " + ex.getMessage(), ex); }
        }
        return Collections.unmodifiableMap(result);
    }
    public static void validateId(String id) {
        if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("ID: 1..48 letras minúsculas, números, _ o -");
    }
    private static UUID uuid(String value) { return value.isBlank() ? null : UUID.fromString(value); }
    private static double number(ConfigurationSection s, String key, double fallback, double min, double max) {
        if (s.contains(key) && !(s.get(key) instanceof Number)) throw new IllegalArgumentException(key + " debe ser numérico");
        double n = s.getDouble(key, fallback);
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException(key + ": usar " + min + ".." + max);
        return n;
    }
}
