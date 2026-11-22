package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.*;

import static org.mockito.Mockito.*;

class NpcSpawnRetryTest {
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test @SuppressWarnings("unchecked")
    void staticNpcRetriesAndLogsTheSameFailureOnce() throws Exception {
        var server = MockBukkit.mock(); server.addPlayer();
        var plugin = mock(MdvNpcPlugin.class); var logger = mock(Logger.class);
        when(plugin.getName()).thenReturn("spawn-retry-test"); when(plugin.getLogger()).thenReturn(logger);
        var settings = Settings.parse(new YamlConfiguration()); when(plugin.settings()).thenReturn(settings);
        when(plugin.shops()).thenReturn(mock(ShopService.class));
        var world = mock(World.class); when(world.getName()).thenReturn("world");
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        var routines = mock(RoutineService.class); when(plugin.routines()).thenReturn(routines);
        when(routines.spawnLocation(any(), eq(world))).thenReturn(new Location(world, 0, 64, 0));
        var yaml = new YamlConfiguration(); yaml.set("npcs.still.location.world", "world"); yaml.set("npcs.still.location.y", 64);
        var manager = new NpcManager(plugin);
        field(manager, "definitions").set(manager, NpcParser.parse(yaml));
        var tick = NpcManager.class.getDeclaredMethod("tick", int.class); tick.setAccessible(true);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            for (int attempt = 0; attempt < 2; attempt++) {
                ((Map<String, Long>) field(manager, "spawnRetry").get(manager)).clear(); // The 30 seconds passed.
                tick.invoke(manager, 40 * settings.lookIntervalTicks());
            }
        }
        verify(world, times(2)).spawn(any(Location.class), eq(Villager.class), any(), any(Consumer.class));
        verify(logger, times(1)).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }
    private java.lang.reflect.Field field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
}
