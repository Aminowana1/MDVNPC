package com.mdvcraft.mdvnpc.storage;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.function.Consumer;

public final class NpcRepository {
    private final Path directory;
    public NpcRepository(Path directory) { this.directory = directory; }
    public record Snapshot(Settings settings, Map<String, NpcDefinition> npcs) {}
    public Snapshot load() throws Exception {
        Settings settings = Settings.parse(read(directory.resolve("config.yml")));
        return new Snapshot(settings, NpcParser.parse(read(directory.resolve("npcs.yml"))));
    }
    public void edit(Consumer<YamlConfiguration> edit) throws Exception {
        Path path = directory.resolve("npcs.yml");
        YamlConfiguration yaml = read(path);
        edit.accept(yaml);
        NpcParser.parse(yaml); // Never overwrite a valid file with invalid definitions.
        Path temp = Files.createTempFile(directory, "npcs-", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.copy(path, directory.resolve("npcs.yml.bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static YamlConfiguration read(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        return yaml;
    }
}
