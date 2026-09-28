package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.routine.*;
import org.bukkit.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldClockTest {
    @TempDir Path folder;ServerMock server;MdvNpcPlugin plugin;World world;
    @BeforeEach void setup() {
        server=MockBukkit.mock(new TestServer());world=server.addSimpleWorld("world5");
        plugin=mock(MdvNpcPlugin.class);when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void longerDayAndNightUseIndependentRatesAndRestoreRule() throws Exception {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,true);var manager=new WorldClockManager(plugin);
        manager.configure(Map.of("world5",new RoutineRepository.Clock(20,10)));
        assertFalse(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE));world.setFullTime(0);manager.tick(20);assertEquals(10,world.getFullTime());
        world.setFullTime(12000);manager.tick(20);assertEquals(12020,world.getFullTime());
        manager.close();assertTrue(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE));
    }
    @Test void crashOwnershipRestoresOriginalFalseRuleAndUnmanagedWorldUntouched() throws Exception {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);var manager=new WorldClockManager(plugin);
        manager.configure(Map.of("world5",new RoutineRepository.Clock(20,20)));
        var restarted=new WorldClockManager(plugin);restarted.configure(Map.of());assertFalse(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE));
        world.setFullTime(333);restarted.tick(20);assertEquals(333,world.getFullTime());
    }
    @Test void fractionalRateSurvivesUpdatesAndExternalTimeChangeResetsFraction() throws Exception {
        var manager=new WorldClockManager(plugin);manager.configure(Map.of("world5",new RoutineRepository.Clock(30,30)));
        world.setFullTime(0);for(int i=0;i<30;i++)manager.tick(1);assertEquals(10,world.getFullTime());
        world.setFullTime(18000);manager.tick(3);assertEquals(18001,world.getFullTime());manager.close();
    }
}
