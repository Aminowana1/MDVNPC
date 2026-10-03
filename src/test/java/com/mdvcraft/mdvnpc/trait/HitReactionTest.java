package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
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

class HitReactionTest {
    MdvNpcPlugin plugin;HitReactionService reactions;ActiveNpc npc;Player player;Villager entity;World world;MusicService music;
    @BeforeEach void setup(){
        MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);var cfg=new YamlConfiguration();
        cfg.set("ignore-invisible-players",false);when(plugin.settings()).thenReturn(Settings.parse(cfg));
        when(plugin.traits()).thenReturn(mock(TraitService.class));when(plugin.sounds()).thenReturn(mock(NpcSounds.class));
        when(plugin.routines()).thenReturn(mock(RoutineService.class));
        music=mock(MusicService.class);when(plugin.music()).thenReturn(music);
        world=mock(World.class);entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(i->new Location(world,0,64,0));when(entity.getEyeLocation()).thenAnswer(i->new Location(world,0,65.6,0));
        var yaml=new YamlConfiguration();yaml.set("npcs.manolito.location.world","world");
        npc=new ActiveNpc(NpcParser.parse(yaml).get("manolito"),new Location(world,0,64,0),entity,null);
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(new Location(world,1,64,0));
        when(player.getEyeLocation()).thenReturn(new Location(world,1,65.6,0));when(entity.hasLineOfSight(player)).thenReturn(true);
        reactions=new HitReactionService(plugin);
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void spamDoesNotRepeatDialogueAndCooldownSurvivesCancellation(){
        reactions.hit(npc,player);assertTrue(reactions.busy("manolito"));reactions.hit(npc,player);
        verify(plugin.sounds(),times(1)).say(eq(npc),eq(player),anyString());
        verify(plugin.routines(),times(1)).prepareReaction(npc);verify(entity,atLeastOnce()).setRotation(anyFloat(),anyFloat());
        reactions.cancel("manolito");assertFalse(reactions.busy("manolito"));reactions.hit(npc,player);
        verify(plugin.sounds(),times(1)).say(eq(npc),eq(player),anyString());
    }
    @Test void remoteOrHiddenAttackerCannotStartReaction(){
        when(player.getLocation()).thenReturn(new Location(world,20,64,0));reactions.hit(npc,player);assertFalse(reactions.busy("manolito"));
        when(player.getLocation()).thenReturn(new Location(world,1,64,0));when(entity.hasLineOfSight(player)).thenReturn(false);
        reactions.hit(npc,player);assertFalse(reactions.busy("manolito"));verifyNoInteractions(plugin.sounds(),music);
    }
    @Test void reactionClaimsHeadOnlyAfterMusicVisualsHaveBeenRestored(){
        reactions.hit(npc,player);
        var order=inOrder(music,plugin.traits(),plugin.routines(),entity);
        order.verify(music).suspendVisuals("manolito");
        order.verify(plugin.traits()).cancel("manolito");
        order.verify(plugin.routines()).prepareReaction(npc);
        order.verify(entity).setRotation(anyFloat(),anyFloat());
        reactions.hit(npc,player);
        verify(music,times(1)).suspendVisuals("manolito");
    }
}
