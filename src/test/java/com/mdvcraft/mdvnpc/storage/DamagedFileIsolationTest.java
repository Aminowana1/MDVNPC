package com.mdvcraft.mdvnpc.storage;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.command.NpcCommand;
import com.mdvcraft.mdvnpc.routine.*;
import com.mdvcraft.mdvnpc.shop.*;
import com.mdvcraft.mdvnpc.skin.SkinStore;
import com.mdvcraft.mdvnpc.util.Messages;
import org.bukkit.command.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DamagedFileIsolationTest {
    private static final String BROKEN = "npcs: [broken";
    @TempDir Path data;
    @BeforeEach void setup() throws Exception {
        MockBukkit.mock();
        Files.copy(Path.of("src/main/resources/config.yml"), data.resolve("config.yml"));
        npc("guide"); npc("other");
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    private Path file(String id, String name) throws Exception {
        return Files.createDirectories(data.resolve("NPCs/" + id)).resolve(name);
    }
    private void npc(String id) throws Exception {
        Files.writeString(file(id, "npc.yml"), "npcs:\n  " + id + ":\n    location: {world: world}\n");
    }

    @Test void unrelatedFolderDoesNotStopLoading() throws Exception {
        Files.createDirectories(data.resolve("NPCs/Backup"));
        assertEquals(2, new NpcRepository(data).load().npcs().size());
    }
    @Test void damagedNpcIsSkippedOnAColdStart() throws Exception {
        Files.writeString(file("other", "npc.yml"), BROKEN);
        assertEquals(java.util.Set.of("guide"), new NpcRepository(data).load().npcs().keySet());
    }
    @Test void damagedNpcKeepsItsLastGoodDefinitionOnReload() throws Exception {
        var repository = new NpcRepository(data); var before = repository.load().npcs().get("other");
        Files.writeString(file("other", "npc.yml"), BROKEN);
        assertEquals(before, repository.load().npcs().get("other"));
    }
    @Test void editingAnotherNpcNeverTouchesTheDamagedFile() throws Exception {
        Path broken = file("other", "npc.yml"); Files.writeString(broken, BROKEN);
        new NpcRepository(data).edit(yaml -> yaml.set("npcs.guide.name", "Guía"));
        assertEquals(BROKEN, Files.readString(broken));
        assertTrue(Files.readString(file("guide", "npc.yml")).contains("Guía"));
    }
    @Test void damagedRoutineDoesNotStopTheOtherRoutines() throws Exception {
        Files.writeString(file("guide", "routines.yml"), "npcs:\n  guide:\n    enabled: false\n");
        Files.writeString(file("other", "routines.yml"), BROKEN);
        assertEquals(java.util.Set.of("guide"), new RoutineRepository(data).read().plans().keySet());
    }
    @Test void damagedShopDoesNotStopTheOtherShops() throws Exception {
        Files.writeString(file("other", "shop.yml"), "shops: [broken");
        assertDoesNotThrow(() -> new ShopRepository(data).load());
    }
    @Test void damagedSkinCacheIsNotFatal() throws Exception {
        Files.writeString(file("other", "skin-cache.yml"), "skins: [broken");
        assertDoesNotThrow(() -> new SkinStore(data).load());
    }
    @Test void deletingAnNpcWithADamagedRoutineDeletesNothing() throws Exception {
        Path routine = file("other", "routines.yml"); Files.writeString(routine, BROKEN);
        var repository = new NpcRepository(data); var definitions = repository.load().npcs();
        var plugin = mock(MdvNpcPlugin.class); var shops = mock(ShopService.class); var routines = mock(RoutineService.class);
        var routineFiles = new RoutineRepository(data);
        when(plugin.repository()).thenReturn(repository); when(plugin.definitions()).thenReturn(definitions);
        when(plugin.shops()).thenReturn(shops); when(plugin.routines()).thenReturn(routines);
        when(routines.repository()).thenReturn(routineFiles); when(plugin.messages()).thenReturn(mock(Messages.class));
        when(plugin.skins()).thenReturn(mock(com.mdvcraft.mdvnpc.skin.SkinCacheService.class));
        var sender = mock(CommandSender.class); when(sender.hasPermission("mdvnpc.admin")).thenReturn(true);
        new NpcCommand(plugin).onCommand(sender, mock(Command.class), "mdvnpc", new String[]{"delete", "other"});
        assertTrue(Files.exists(file("other", "npc.yml")));
        assertEquals(BROKEN, Files.readString(routine));
        verify(shops, never()).deleteShop(anyString());
    }
}
