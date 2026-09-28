package com.mdvcraft.mdvnpc.storage;

import com.mdvcraft.mdvnpc.config.NpcParser;
import java.io.IOException;
import java.nio.file.*;

/** Centralizes the per-NPC layout introduced in 1.3.0. */
public final class NpcPaths {
    private NpcPaths() {}
    public static Path root(Path dataFolder) { return dataFolder.resolve("NPCs"); }
    public static Path npc(Path dataFolder, String id) {
        NpcParser.validateId(id);
        return root(dataFolder).resolve(id);
    }
    public static Path definition(Path dataFolder, String id) { return npc(dataFolder, id).resolve("npc.yml"); }
    public static Path routines(Path dataFolder, String id) { return npc(dataFolder, id).resolve("routines.yml"); }
    public static Path shop(Path dataFolder, String id) { return npc(dataFolder, id).resolve("shop.yml"); }
    public static Path skin(Path dataFolder, String id) { return npc(dataFolder, id).resolve("skin-cache.yml"); }
    public static void ensure(Path dataFolder, String id) throws IOException { Files.createDirectories(npc(dataFolder, id)); }
    public static void cleanupIfEmpty(Path dataFolder, String id) throws IOException {
        Path folder = npc(dataFolder, id);
        if (!Files.isDirectory(folder)) return;
        try (var entries = Files.list(folder)) {
            if (entries.findAny().isEmpty()) Files.deleteIfExists(folder);
        }
    }
}
