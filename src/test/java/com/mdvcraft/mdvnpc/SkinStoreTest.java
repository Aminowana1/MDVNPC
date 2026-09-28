package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.model.NpcDefinition.Skin;
import com.mdvcraft.mdvnpc.skin.SkinStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SkinStoreTest {
    @TempDir Path folder;
    private Skin resolved() { return new Skin("Steve", "dGV4dHVyZQ==", "c2lnbmF0dXJl", UUID.randomUUID()); }
    private Skin named(String name) { return new Skin(name, "", "", null); }
    @Test void signedTextureSurvivesRestartAndExplicitConfigWins() throws Exception {
        var store = new SkinStore(folder); var skin = resolved();
        assertTrue(store.put("npc", skin)); assertFalse(store.put("npc", skin));
        store.write(store.serialize());
        var reboot = new SkinStore(folder); reboot.load();
        assertEquals(skin, reboot.resolve("npc", named("steve")));
        assertEquals(named("Alex"), reboot.resolve("npc", named("Alex")));
        Skin explicit = new Skin("Steve", "b3RoZXI=", "c2ln", UUID.randomUUID());
        assertEquals(explicit, reboot.resolve("npc", explicit));
    }
    @Test void refreshAndDeletionPersistAndLeaveBackup() throws Exception {
        var store = new SkinStore(folder); store.put("npc", resolved()); store.write(store.serialize());
        store.remove("npc"); store.write(store.serialize());
        var reboot = new SkinStore(folder); reboot.load();
        assertEquals(named("Steve"), reboot.resolve("npc", named("Steve")));
        assertFalse(Files.exists(folder.resolve("NPCs/npc/skin-cache.yml")));
    }
    @Test void incompleteProfilesNeverReplaceCachedSkin() {
        var store = new SkinStore(folder); var skin = resolved(); store.put("npc", skin);
        assertFalse(store.put("npc", named("Steve")));
        assertFalse(store.put("npc", new Skin("Steve", "?", "?", UUID.randomUUID())));
        assertEquals(skin, store.resolve("npc", named("Steve")));
    }
    @Test void corruptCacheIsNotSilentlyOverwritten() throws Exception {
        Files.writeString(folder.resolve("skins.yml"), "skins:\n  npc:\n    uuid: broken\n");
        assertThrows(Exception.class, () -> new SkinStore(folder).load());
        assertTrue(Files.readString(folder.resolve("skins.yml")).contains("broken"));
    }
}
