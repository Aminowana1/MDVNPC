package com.mdvcraft.mdvnpc.storage;

import com.mdvcraft.mdvnpc.config.NpcParser;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Reads NPCs/&lt;id&gt;/ one NPC at a time: a damaged file or stray folder only affects itself.
 * While editing, damaged NPC are held by a placeholder so no edit can overwrite or delete their files.
 */
public final class NpcFolders {
    private static final String DAMAGED = "mdvnpc-damaged-file";
    @FunctionalInterface public interface Reader { void read(String id, Path file) throws Exception; }
    private NpcFolders() {}

    /** @return what failed, as folder name to a one-line description */
    public static Map<String, String> readEach(Path root, String fileName, Reader reader) throws IOException {
        Map<String, String> failures = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) return failures;
        try (var folders = Files.list(root)) {
            for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
                Path file = folder.resolve(fileName);
                if (!Files.exists(file)) continue; // Backups and other unrelated folders.
                String id = folder.getFileName().toString();
                try {
                    NpcParser.validateId(id);
                    reader.read(id, file);
                } catch (Exception ex) {
                    String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().strip();
                    failures.put(id, "NPCs/" + id + "/" + fileName + ": " + reason.lines().findFirst().orElse(""));
                }
            }
        }
        return failures;
    }

    public static void hold(YamlConfiguration yaml, Map<String, String> failures) {
        for (String id : failures.keySet())
            if (validId(id)) yaml.createSection("npcs." + id).set(DAMAGED, true);
    }

    /** Removes the placeholders, refusing the edit if it touched any of them. */
    public static void release(YamlConfiguration yaml, Map<String, String> failures) {
        for (var failure : failures.entrySet()) {
            if (!validId(failure.getKey())) continue;
            var section = yaml.getConfigurationSection("npcs." + failure.getKey());
            if (section == null || !section.getKeys(true).equals(Set.of(DAMAGED)))
                throw new IllegalArgumentException("Corrige el archivo antes de editar este NPC. " + failure.getValue());
            yaml.set("npcs." + failure.getKey(), null);
        }
    }

    private static boolean validId(String id) {
        try { NpcParser.validateId(id); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
