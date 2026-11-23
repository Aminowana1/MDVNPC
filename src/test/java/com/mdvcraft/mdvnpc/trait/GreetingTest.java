package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.music.MusicService;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GreetingTest {
    MdvNpcPlugin plugin;HitReactionService reactions;ActiveNpc npc;org.mockbukkit.mockbukkit.entity.PlayerMock player;Villager entity;World world;RoutineService routines;

    @BeforeEach void setup(){
        var server=MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);var cfg=new YamlConfiguration();
        cfg.set("ignore-invisible-players",false);when(plugin.settings()).thenReturn(Settings.parse(cfg));
        when(plugin.traits()).thenReturn(mock(TraitService.class));when(plugin.sounds()).thenReturn(mock(NpcSounds.class));
        routines=mock(RoutineService.class);when(plugin.routines()).thenReturn(routines);
        when(plugin.music()).thenReturn(mock(MusicService.class));
        world=server.addSimpleWorld("world");entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(i->new Location(world,0,64,0));when(entity.getEyeLocation()).thenAnswer(i->new Location(world,0,65.6,0));
        var yaml=new YamlConfiguration();yaml.set("npcs.manolito.location.world","world");
        npc=new ActiveNpc(NpcParser.parse(yaml).get("manolito"),new Location(world,0,64,0),entity,null);
        when(routines.canGreet(npc)).thenReturn(true);
        NpcManager manager=mock(NpcManager.class);when(manager.activeNpcs()).thenReturn(List.of(npc));
        when(plugin.manager()).thenReturn(manager);
        // Reactions find their player again on every tick, so it must exist on the server.
        player=server.addPlayer();player.teleport(new Location(world,3,64,0));when(entity.hasLineOfSight(player)).thenReturn(true);
        reactions=new HitReactionService(plugin);
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}

    @Test void crouchingTwiceNearAnNpcIsAGreeting(){
        reactions.sneaked(player);
        assertFalse(reactions.busy("manolito"),"a single crouch is not a greeting");
        reactions.sneaked(player);
        assertTrue(reactions.greeting("manolito"));
        verify(routines).prepareReaction(npc);
        verify(entity,atLeastOnce()).setRotation(anyFloat(),anyFloat());
        verifyNoInteractions(plugin.sounds());
    }

    @Test void theSameNpcDoesNotGreetTheSamePlayerAgainAtOnce(){
        reactions.sneaked(player);reactions.sneaked(player);
        reactions.cancel("manolito");
        reactions.sneaked(player);reactions.sneaked(player);
        assertFalse(reactions.busy("manolito"));
        verify(routines,times(1)).prepareReaction(npc);
    }

    @Test void farAwaySleepingOrUnseenNpcsDoNotGreet(){
        player.teleport(new Location(world,6,64,0));
        reactions.sneaked(player);reactions.sneaked(player);
        assertFalse(reactions.busy("manolito"),"beyond five blocks");

        player.teleport(new Location(world,3,64,0));
        when(routines.canGreet(npc)).thenReturn(false);
        reactions.sneaked(player);reactions.sneaked(player);
        assertFalse(reactions.busy("manolito"),"a sleeper stays in bed");

        when(routines.canGreet(npc)).thenReturn(true);
        when(entity.hasLineOfSight(player)).thenReturn(false);
        reactions.sneaked(player);reactions.sneaked(player);
        assertFalse(reactions.busy("manolito"),"behind a wall");
        verify(routines,never()).prepareReaction(any());
    }

    @Test void theGreetingEndsAndTheNpcResumes(){
        var cfg=new YamlConfiguration();cfg.set("ignore-invisible-players",false);cfg.set("npc-greetings.pause-seconds",.5);
        when(plugin.settings()).thenReturn(Settings.parse(cfg));
        reactions.sneaked(player);reactions.sneaked(player);
        assertTrue(reactions.busy("manolito"));
        long deadline=System.nanoTime()+2_000_000_000L;
        for(long tick=0;reactions.busy("manolito") && System.nanoTime()<deadline;tick+=2)reactions.tick(tick);
        assertFalse(reactions.busy("manolito"));
    }

    @Test void aHitTakesOverAGreeting(){
        // The test world draws no particles.
        var cfg=new YamlConfiguration();cfg.set("ignore-invisible-players",false);
        cfg.set("npc-reactions.angry-particles",0);cfg.set("npc-reactions.impact-particles",0);
        when(plugin.settings()).thenReturn(Settings.parse(cfg));
        reactions.sneaked(player);reactions.sneaked(player);
        player.teleport(new Location(world,1,64,0));
        reactions.hit(npc,player);
        assertTrue(reactions.busy("manolito"));
        assertFalse(reactions.greeting("manolito"));
    }

    @Test void disabledGreetingsIgnoreCrouching(){
        var cfg=new YamlConfiguration();cfg.set("ignore-invisible-players",false);cfg.set("npc-greetings.enabled",false);
        when(plugin.settings()).thenReturn(Settings.parse(cfg));
        reactions.sneaked(player);reactions.sneaked(player);
        assertFalse(reactions.busy("manolito"));
    }

    @Test void onlyPressesCloseTogetherCount(){
        DoubleSneak sneaks=new DoubleSneak();UUID id=UUID.randomUUID();long window=600_000_000L;
        assertFalse(sneaks.press(id,0,window));
        assertFalse(sneaks.press(id,window+1,window),"too slow: this press starts again");
        assertTrue(sneaks.press(id,window+2,window));
        assertFalse(sneaks.press(id,window+3,window),"a third press starts a new greeting");
        sneaks.prune(10*window,window);
        assertEquals(0,sneaks.size());
    }

    @Test void aDisguisedNpcWavesBack(){
        var disguise=mock(me.libraryaddict.disguise.disguisetypes.PlayerDisguise.class);
        var watcher=mock(me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher.class);
        when(disguise.getWatcher()).thenReturn(watcher);
        npc=new ActiveNpc(npc.definition(),npc.anchor(),entity,disguise);
        when(routines.canGreet(npc)).thenReturn(true);when(plugin.manager().activeNpcs()).thenReturn(List.of(npc));
        reactions.sneaked(player);reactions.sneaked(player);
        for(long tick=0;tick<=20;tick+=2)reactions.tick(tick);
        verify(watcher,times(2)).setSneaking(true);
        verify(entity,times(2)).swingMainHand();
        verify(watcher,atLeast(2)).setSneaking(false);
        assertTrue(reactions.greeting("manolito"),"still facing the player after the wave");
    }
}
