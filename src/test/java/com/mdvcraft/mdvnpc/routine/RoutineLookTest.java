package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutineLookTest {
    ServerMock server;MdvNpcPlugin plugin;Villager entity;World world;RoutineLook looks;ActiveNpc npc;
    @BeforeEach void setup() {
        server=MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);when(plugin.getServer()).thenReturn(server);when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        world=mock(World.class);entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(i -> new Location(world,0,64,0));when(entity.getEyeLocation()).thenAnswer(i -> new Location(world,0,65.6,0));
        npc=new ActiveNpc(null,new Location(world,0,64,0),entity,null);looks=new RoutineLook(plugin);
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void idleGlancesDoNotStartImmediatelyOrScanPlayersEveryTick() {
        var state=new RoutineLook.State();for(int t=0;t<120;t+=2)looks.tick(npc,state,t,0,true,false);
        verify(world,never()).getNearbyPlayers(any(Location.class),anyDouble(),any());verify(entity,never()).setRotation(anyFloat(),anyFloat());
    }
    @Test void readingLooksDownAndRestoresOnRelease() {
        var state=new RoutineLook.State();for(int t=0;t<24;t+=4)looks.tick(npc,state,t,90,true,true);
        assertEquals(28,state.headPitch,.001);verify(entity,atLeastOnce()).setBodyYaw(90);
        looks.clear(npc,state);verify(entity).setRotation(90,0);assertFalse(state.initialized);
    }
    @Test void playerBeyondThreeBlocksIsNeverSelectedEvenIfBroadPhaseReturnsIt() {
        Player far=mock(Player.class);when(far.getLocation()).thenReturn(new Location(world,3.01,64,0));
        when(world.getNearbyPlayers(any(Location.class),eq(3d),any())).thenReturn(List.of(far));
        var state=new RoutineLook.State();state.initialized=true;looks.tick(npc,state,1,0,true,false);
        assertNull(state.player);verify(entity,never()).hasLineOfSight(far);
    }
    @Test void yawInterpolationTakesShortestTurnAcrossWrap() {
        assertEquals(2,RoutineLook.angle(179,-179),.001);assertEquals(181,RoutineLook.approach(179,-179,12),.001);
    }
}
