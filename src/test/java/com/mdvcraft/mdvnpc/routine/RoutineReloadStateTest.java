package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutineReloadStateTest {
    @TempDir Path folder;
    RoutineService service;
    RandomGenerator random;
    @BeforeEach void setup() throws Exception {
        var server = MockBukkit.mock();
        var plugin = mock(MdvNpcPlugin.class);
        when(plugin.getName()).thenReturn("reload-test"); when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        service = new RoutineService(plugin);
        random = mock(RandomGenerator.class); when(random.nextInt(2)).thenReturn(1, 0);
    }
    @AfterEach void cleanup() { service.stop(); MockBukkit.unmock(); }
    private Object field(String name) throws Exception {
        var field = RoutineService.class.getDeclaredField(name); field.setAccessible(true); return field.get(service);
    }
    private RoutineGoal goal(int x) {
        var world = new UUID(0, 1);
        var main = new RoutineGoal(1, RoutineGoal.Type.SLEEP, RoutineGoal.WalkMode.CYCLE, 1320, 420, 2.4, 20,
                List.of(new RoutineGoal.Point(world, x, 64, 0, 0)));
        return main.withAlternatives(List.of(new RoutineGoal(1, RoutineGoal.Type.SIT, RoutineGoal.WalkMode.CYCLE, 1320, 420, 2.4, 20,
                List.of(new RoutineGoal.Point(world, 9, 64, 0, 0)))));
    }
    private RoutineGoal draw(RoutineGoal goal) throws Exception {
        return ((RoutineChoices) field("choices")).select("npc", new RoutineSchedule.Window(18000, 1, List.of(goal)), goal, random);
    }

    @Test void reloadKeepsTheDrawOfAnUntouchedGoal() throws Exception {
        assertEquals(RoutineGoal.Type.SIT, draw(goal(0)).type());
        service.stop();
        assertEquals(RoutineGoal.Type.SIT, draw(goal(0)).type());
        verify(random, times(1)).nextInt(2);
    }
    @Test void editedGoalIsDrawnAgain() throws Exception {
        draw(goal(0));
        assertEquals(RoutineGoal.Type.SLEEP, draw(goal(5)).type());
    }
    @Test void clockFailureDoesNotPreventStarting() throws Exception {
        var clocks = mock(WorldClockManager.class);
        doThrow(new IllegalStateException("disk")).when(clocks).configure(any());
        var field = RoutineService.class.getDeclaredField("clocks"); field.setAccessible(true); field.set(service, clocks);
        assertDoesNotThrow(service::start);
    }
}
