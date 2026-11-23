package com.mdvcraft.mdvnpc.storage;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Stores each NPC in NPCs/<id>/npc.yml while keeping the old edit API atomic. */
public final class NpcRepository {
    private final Path directory;
    private final Path npcRoot;
    public NpcRepository(Path directory) {
        this.directory = directory;
        this.npcRoot = NpcPaths.root(directory);
    }
    public record Snapshot(Settings settings, Map<String, NpcDefinition> npcs) {}
    private Map<String, NpcDefinition> lastGood = Map.of();
    private List<String> problems = List.of();
    /** NPC files skipped by the last load; those NPC keep their last valid definition. */
    public List<String> problems() { return problems; }

    public Snapshot load() throws Exception {
        Files.createDirectories(npcRoot);
        migrateLegacy();
        Settings settings = Settings.parse(readRequired(directory.resolve("config.yml")));
        return new Snapshot(settings, Collections.unmodifiableMap(loadDefinitions()));
    }

    public void edit(Consumer<YamlConfiguration> edit) throws Exception {
        Files.createDirectories(npcRoot);
        migrateLegacy();
        YamlConfiguration aggregate = new YamlConfiguration();
        aggregate.createSection("npcs");
        Map<String, String> damaged = NpcFolders.readEach(npcRoot, "npc.yml", (id, file) -> {
            YamlConfiguration local = readRequired(file);
            parseOne(local, id, file);
            copy(local.getConfigurationSection("npcs." + id), aggregate, "npcs." + id);
        });
        Map<String, NpcDefinition> before = NpcParser.parse(aggregate);
        NpcFolders.hold(aggregate, damaged);
        edit.accept(aggregate);
        NpcFolders.release(aggregate, damaged);
        Map<String, NpcDefinition> parsed = NpcParser.parse(aggregate); // validate before touching disk
        for (var entry : parsed.entrySet()) {
            if (!Objects.equals(before.get(entry.getKey()), entry.getValue())) writeDefinition(aggregate, entry.getKey());
        }
        for (String id : before.keySet()) if (!parsed.containsKey(id)) {
            Files.deleteIfExists(NpcPaths.definition(directory, id));
            NpcPaths.cleanupIfEmpty(directory, id);
        }
    }

    private Map<String, NpcDefinition> loadDefinitions() throws Exception {
        Map<String, NpcDefinition> all = new LinkedHashMap<>();
        Map<String, String> damaged = NpcFolders.readEach(npcRoot, "npc.yml",
                (id, file) -> all.put(id, parseOne(readRequired(file), id, file)));
        for (String id : damaged.keySet()) if (lastGood.containsKey(id)) all.put(id, lastGood.get(id));
        problems = List.copyOf(damaged.values());
        lastGood = new LinkedHashMap<>(all);
        return all;
    }

    private static NpcDefinition parseOne(YamlConfiguration yaml, String id, Path file) {
        Map<String, NpcDefinition> parsed = NpcParser.parse(yaml);
        if (parsed.size() != 1 || !parsed.containsKey(id))
            throw new IllegalArgumentException(file + " debe contener únicamente npcs." + id);
        return parsed.get(id);
    }

    private void writeDefinition(YamlConfiguration aggregate, String id) throws Exception {
        ConfigurationSection section = aggregate.getConfigurationSection("npcs." + id);
        if (section == null) throw new IllegalArgumentException("NPC sin sección: " + id);
        YamlConfiguration local = new YamlConfiguration();
        local.createSection("npcs");
        copy(section, local, "npcs." + id);
        // Validate the individual file too, so a malformed cross-section copy can never be persisted.
        NpcParser.parse(local);
        NpcPaths.ensure(directory, id);
        AtomicFile.write(NpcPaths.definition(directory, id), local.saveToString());
    }

    private void migrateLegacy() throws Exception {
        Path legacy = directory.resolve("npcs.yml");
        if (!Files.exists(legacy)) return;
        YamlConfiguration yaml = readRequired(legacy);
        Map<String, NpcDefinition> parsed = NpcParser.parse(yaml);
        for (String id : parsed.keySet()) {
            Path target = NpcPaths.definition(directory, id);
            if (Files.exists(target)) continue;
            ConfigurationSection section = yaml.getConfigurationSection("npcs." + id);
            if (section == null) continue;
            YamlConfiguration local = new YamlConfiguration(); local.createSection("npcs");
            copy(section, local, "npcs." + id);
            NpcPaths.ensure(directory, id);
            AtomicFile.write(target, local.saveToString());
        }
        Files.move(legacy, directory.resolve("npcs.yml.legacy-backup"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copy(ConfigurationSection from, YamlConfiguration to, String target) {
        to.set(target, null);
        to.createSection(target);
        copyInto(from, to.getConfigurationSection(target));
    }
    private static void copyInto(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            ConfigurationSection child = from.getConfigurationSection(key);
            if (child != null) {
                ConfigurationSection next = to.createSection(key);
                copyInto(child, next);
            } else to.set(key, from.get(key));
        }
    }
    private static YamlConfiguration readRequired(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        return yaml;
    }
}
