package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.music.MusicService;
import com.mdvcraft.mdvnpc.routine.*;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.storage.NpcRepository;
import com.mdvcraft.mdvnpc.trait.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReloadRollbackTest {
    private MdvNpcPlugin plugin;
    private void set(String name, Object value) throws Exception {
        var field = MdvNpcPlugin.class.getDeclaredField(name); field.setAccessible(true); field.set(plugin, value);
    }

    @Test void failedReloadRestartsThePreviousConfiguration() throws Exception {
        plugin = mock(MdvNpcPlugin.class, CALLS_REAL_METHODS);
        doReturn(null).when(plugin).getResource(anyString());
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        var oldSettings = Settings.parse(new YamlConfiguration());
        var oldYaml = new YamlConfiguration(); oldYaml.set("npcs.old.location.world", "world");
        Map<String, NpcDefinition> oldNpcs = NpcParser.parse(oldYaml);
        var repository = mock(NpcRepository.class);
        when(repository.load()).thenReturn(new NpcRepository.Snapshot(Settings.parse(new YamlConfiguration()), Map.of()));
        var routines = mock(RoutineService.class); var files = mock(RoutineRepository.class);
        when(routines.repository()).thenReturn(files);
        var oldRoutines = new RoutineRepository.Snapshot(Map.of(), Map.of()); when(files.snapshot()).thenReturn(oldRoutines);
        when(files.read()).thenReturn(new RoutineRepository.Snapshot(Map.of(), Map.of()));
        doThrow(new IllegalStateException("start failed")).doNothing().when(routines).start();
        set("settings", oldSettings); set("definitions", oldNpcs); set("repository", repository); set("routines", routines);
        set("shops", mock(ShopService.class)); set("music", mock(MusicService.class));
        set("traits", mock(TraitService.class)); set("reactions", mock(HitReactionService.class));
        set("manager", mock(NpcManager.class));
        try (var managers = mockConstruction(NpcManager.class)) {
            assertThrows(IllegalStateException.class, plugin::reloadNpcs);
            assertSame(oldSettings, plugin.settings());
            assertEquals(oldNpcs.keySet(), plugin.definitions().keySet());
            verify(managers.constructed().getLast()).start(oldNpcs);
        }
    }
}
