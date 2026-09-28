package com.mdvcraft.mdvnpc.skin;

import com.mdvcraft.mdvnpc.model.NpcDefinition.Skin;
import com.mdvcraft.mdvnpc.storage.AtomicFile;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;

/** A cache is keyed by NPC and requested name; explicit textures always take precedence. */
public final class SkinStore {
    private final Path file;
    private final Map<String, Skin> skins = new LinkedHashMap<>();
    public SkinStore(Path folder) { file = folder.resolve("skins.yml"); }
    public void load() throws Exception {
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));
        var root = yaml.getConfigurationSection("skins");
        if (root == null) throw new IllegalArgumentException("Falta skins: {} en skins.yml");
        Map<String, Skin> parsed = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            var s = Objects.requireNonNull(root.getConfigurationSection(id), "Skin inválida: " + id);
            Skin skin = new Skin(s.getString("name", ""), s.getString("texture", ""), s.getString("signature", ""),
                    UUID.fromString(s.getString("uuid", "")));
            if (!valid(skin)) throw new IllegalArgumentException("Skin inválida: " + id);
            parsed.put(id, skin);
        }
        skins.clear(); skins.putAll(parsed);
    }
    public static boolean valid(Skin skin) {
        if (skin == null || skin.profileId() == null || !skin.name().matches("[A-Za-z0-9_]{1,16}") || skin.texture().isBlank() || skin.signature().isBlank()) return false;
        try { return Base64.getDecoder().decode(skin.texture()).length > 0 && Base64.getDecoder().decode(skin.signature()).length > 0; }
        catch (IllegalArgumentException ex) { return false; }
    }
    public Skin resolve(String npc, Skin requested) {
        if (!requested.texture().isBlank()) return requested;
        Skin cached = skins.get(npc);
        return cached != null && cached.name().equalsIgnoreCase(requested.name()) ? cached : requested;
    }
    public boolean put(String npc, Skin skin) {
        if (!valid(skin)) return false;
        return !skin.equals(skins.put(npc, skin));
    }
    public boolean remove(String npc) { return skins.remove(npc) != null; }
    public String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.createSection("skins");
        skins.forEach((id, skin) -> {
            String p = "skins." + id + ".";
            yaml.set(p + "name", skin.name()); yaml.set(p + "texture", skin.texture());
            yaml.set(p + "signature", skin.signature()); yaml.set(p + "uuid", skin.profileId().toString());
        });
        return yaml.saveToString();
    }
    public void write(String serialized) throws Exception { AtomicFile.write(file, serialized); }
}
