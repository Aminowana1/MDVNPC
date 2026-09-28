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

    public Snapshot load() throws Exception {
        Files.createDirectories(npcRoot);
        migrateLegacy();
        Settings settings = Settings.parse(readRequired(directory.resolve("config.yml")));
        return new Snapshot(settings, Collections.unmodifiableMap(loadDefinitions()));
    }

    public void edit(Consumer<YamlConfiguration> edit) throws Exception {
        Files.createDirectories(npcRoot);
        migrateLegacy();
        YamlConfiguration aggregate = aggregate();
        Map<String, NpcDefinition> before = NpcParser.parse(aggregate);
        edit.accept(aggregate);
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
        if (!Files.isDirectory(npcRoot)) return all;
        try (var dirs = Files.list(npcRoot)) {
            for (Path folder : dirs.filter(Files::isDirectory).sorted().toList()) {
                String id = folder.getFileName().toString();
                NpcParser.validateId(id);
                Path file = folder.resolve("npc.yml");
                if (!Files.exists(file)) continue;
                YamlConfiguration yaml = readRequired(file);
                Map<String, NpcDefinition> parsed = NpcParser.parse(yaml);
                if (parsed.size() != 1 || !parsed.containsKey(id))
                    throw new IllegalArgumentException(file + " debe contener únicamente npcs." + id);
                if (all.put(id, parsed.get(id)) != null) throw new IllegalArgumentException("NPC duplicado: " + id);
            }
        }
        return all;
    }

    private YamlConfiguration aggregate() throws Exception {
        YamlConfiguration aggregate = new YamlConfiguration();
        aggregate.createSection("npcs");
        if (!Files.isDirectory(npcRoot)) return aggregate;
        try (var dirs = Files.list(npcRoot)) {
            for (Path folder : dirs.filter(Files::isDirectory).sorted().toList()) {
                String id = folder.getFileName().toString();
                Path file = folder.resolve("npc.yml");
                if (!Files.exists(file)) continue;
                YamlConfiguration local = readRequired(file);
                ConfigurationSection section = local.getConfigurationSection("npcs." + id);
                if (section == null) throw new IllegalArgumentException("Falta npcs." + id + " en " + file);
                copy(section, aggregate, "npcs." + id);
            }
        }
        return aggregate;
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
