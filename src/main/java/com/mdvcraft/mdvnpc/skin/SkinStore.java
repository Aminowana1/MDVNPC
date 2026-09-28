package com.mdvcraft.mdvnpc.skin;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Skin;
import com.mdvcraft.mdvnpc.storage.AtomicFile;
import com.mdvcraft.mdvnpc.storage.NpcPaths;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;

/** Cache stored per NPC in NPCs/<id>/skin-cache.yml. */
public final class SkinStore {
    private final Path folder;
    private final Path npcRoot;
    private final Map<String, Skin> skins = new LinkedHashMap<>();
    public SkinStore(Path folder) { this.folder=folder; this.npcRoot=NpcPaths.root(folder); }
    public void load() throws Exception {
        Files.createDirectories(npcRoot); migrateLegacy();
        Map<String,Skin> parsed=new LinkedHashMap<>();
        try(var dirs=Files.list(npcRoot)) {
            for(Path dir:dirs.filter(Files::isDirectory).sorted().toList()) {
                String id=dir.getFileName().toString(); NpcParser.validateId(id); Path file=dir.resolve("skin-cache.yml"); if(!Files.exists(file)) continue;
                YamlConfiguration yaml=new YamlConfiguration(); yaml.loadFromString(Files.readString(file));
                var root=yaml.getConfigurationSection("skins"); if(root==null) throw new IllegalArgumentException("Falta skins: {} en "+file);
                var s=root.getConfigurationSection(id); if(s==null || root.getKeys(false).size()!=1) throw new IllegalArgumentException(file+" debe contener solo skins."+id);
                Skin skin=parseSkin(s,id); parsed.put(id,skin);
            }
        }
        skins.clear(); skins.putAll(parsed);
    }
    private static Skin parseSkin(ConfigurationSection s,String id) {
        Skin skin=new Skin(s.getString("name",""),s.getString("texture",""),s.getString("signature",""),UUID.fromString(s.getString("uuid","")));
        if(!valid(skin)) throw new IllegalArgumentException("Skin inválida: "+id); return skin;
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
    public boolean put(String npc, Skin skin) { if (!valid(skin)) return false; return !skin.equals(skins.put(npc, skin)); }
    public boolean remove(String npc) { return skins.remove(npc) != null; }
    public String serialize() {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.createSection("skins");
        skins.forEach((id, skin) -> {
            String p = "skins." + id + "."; yaml.set(p + "name", skin.name()); yaml.set(p + "texture", skin.texture());
            yaml.set(p + "signature", skin.signature()); yaml.set(p + "uuid", skin.profileId().toString());
        });
        return yaml.saveToString();
    }
    public void write(String serialized) throws Exception {
        YamlConfiguration aggregate=new YamlConfiguration(); aggregate.loadFromString(serialized);
        var root=aggregate.getConfigurationSection("skins"); if(root==null) throw new IllegalArgumentException("Falta skins");
        Set<String> ids=new HashSet<>(root.getKeys(false));
        for(String id:ids) {
            NpcParser.validateId(id); var s=Objects.requireNonNull(root.getConfigurationSection(id)); parseSkin(s,id);
            YamlConfiguration local=new YamlConfiguration(); local.createSection("skins"); copy(s,local,"skins."+id);
            NpcPaths.ensure(folder,id); AtomicFile.write(NpcPaths.skin(folder,id),local.saveToString());
        }
        if(Files.isDirectory(npcRoot)) try(var dirs=Files.list(npcRoot)) {
            for(Path dir:dirs.filter(Files::isDirectory).toList()) {
                String id=dir.getFileName().toString(); if(ids.contains(id)) continue;
                Files.deleteIfExists(dir.resolve("skin-cache.yml")); NpcPaths.cleanupIfEmpty(folder,id);
            }
        }
    }
    private void migrateLegacy() throws Exception {
        Path legacy=folder.resolve("skins.yml"); if(!Files.exists(legacy)) return;
        YamlConfiguration yaml=new YamlConfiguration(); yaml.loadFromString(Files.readString(legacy));
        var root=yaml.getConfigurationSection("skins"); if(root==null) throw new IllegalArgumentException("Falta skins: {} en skins.yml");
        for(String id:root.getKeys(false)) {
            NpcParser.validateId(id); Path target=NpcPaths.skin(folder,id); if(Files.exists(target)) continue;
            var section=Objects.requireNonNull(root.getConfigurationSection(id)); parseSkin(section,id);
            YamlConfiguration local=new YamlConfiguration(); local.createSection("skins"); copy(section,local,"skins."+id);
            NpcPaths.ensure(folder,id); AtomicFile.write(target,local.saveToString());
        }
        Files.move(legacy,folder.resolve("skins.yml.legacy-backup"),StandardCopyOption.REPLACE_EXISTING);
    }
    private static void copy(ConfigurationSection from,YamlConfiguration to,String target){to.set(target,null);to.createSection(target);copyInto(from,Objects.requireNonNull(to.getConfigurationSection(target)));}
    private static void copyInto(ConfigurationSection from,ConfigurationSection to){for(String key:from.getKeys(false)){var child=from.getConfigurationSection(key);if(child!=null){var next=to.createSection(key);copyInto(child,next);}else to.set(key,from.get(key));}}
}
