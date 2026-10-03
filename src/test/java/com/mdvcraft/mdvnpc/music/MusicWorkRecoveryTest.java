package com.mdvcraft.mdvnpc.music;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Mode;
import com.mdvcraft.mdvnpc.routine.RoutineGoal;
import com.mdvcraft.mdvnpc.routine.RoutineNavigator;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real scheduling and music eligibility; only the server-native path engine and rendering are doubles. */
class MusicWorkRecoveryTest {
    @TempDir Path folder;
    ServerMock server;MdvNpcPlugin plugin;World world;Player listener;Villager entity;
    RoutineService routines;RoutineNavigator navigator;MusicService music;MusicianVisuals visuals;
    ActiveNpc npc;Location position,post;RoutineGoal.Point point;
    boolean routeWaiting;

    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);
        when(plugin.getName()).thenReturn("music-work-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var options=new YamlConfiguration();options.set("routines.occasional-looking",false);
        when(plugin.settings()).thenReturn(Settings.parse(options));when(plugin.shops()).thenReturn(mock(ShopService.class));
        world=mock(World.class);when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getFullTime()).thenReturn(1000L);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);Block block=mock(Block.class);
            when(block.getType()).thenReturn(y==63?Material.STONE:Material.AIR);when(block.isPassable()).thenReturn(y!=63);
            when(block.getBoundingBox()).thenReturn(y==63?new BoundingBox(x,y,z,x+1,y+1,z+1):new BoundingBox(x,y,z,x,y,z));return block;
        });
        listener=mock(Player.class);when(listener.getUniqueId()).thenReturn(UUID.randomUUID());when(listener.getWorld()).thenReturn(world);
        when(listener.isOnline()).thenReturn(true);when(listener.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(listener.getLocation()).thenAnswer(call->new Location(world,2.5,64,.5));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(listener));
        when(world.getNearbyPlayers(any(Location.class),anyDouble())).thenReturn(List.of(listener));
        entity=mock(Villager.class);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        post=new Location(world,.5,64,.5);position=post.clone();when(entity.getLocation()).thenAnswer(call->position.clone());
        when(entity.getEyeLocation()).thenAnswer(call->position.clone().add(0,1.6,0));setMode(Mode.MUSICIAN_FLUTE);
        var manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.activeNpcs()).thenAnswer(call->List.of(npc));
        visuals=mock(MusicianVisuals.class);music=new MusicService(plugin,visuals);when(plugin.music()).thenReturn(music);
        routines=new RoutineService(plugin);when(plugin.routines()).thenReturn(routines);
        point=new RoutineGoal.Point(world.getUID(),0,64,0,0);
        routines.repository().put("performer",new RoutineGoal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,420,1080,2.4,20,List.of(point)));
        routines.start();navigator=mock(RoutineNavigator.class);
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            Location target=call.getArgument(1);assertEquals(post,target,"Recovery must keep the original work post");
            if(routeWaiting)return RoutineNavigator.Result.WAITING;
            if(position.distanceSquared(target)<.025)return RoutineNavigator.Result.ARRIVED;
            var delta=target.toVector().subtract(position.toVector());double step=.24;
            if(delta.length()>step)delta.multiply(step/delta.length());position.add(delta);return RoutineNavigator.Result.MOVING;
        });
        var navigation=RoutineService.class.getDeclaredField("navigator");navigation.setAccessible(true);navigation.set(routines,navigator);
    }
    private void setMode(Mode mode){
        var yaml=new YamlConfiguration();yaml.set("npcs.performer.location.world","world");yaml.set("npcs.performer.mode",mode.name().toLowerCase(Locale.ROOT));
        yaml.set("npcs.performer.dialogue.enabled",false);npc=new ActiveNpc(NpcParser.parse(yaml).get("performer"),post.clone(),entity,null);
    }
    @AfterEach void cleanup(){try{if(routines!=null)routines.close();}finally{try{if(music!=null)music.stop();}finally{MockBukkit.unmock();}}}
    private void startWorking(){server.getScheduler().performTicks(26);assertTrue(routines.canInteract(npc));assertTrue(music.isPerforming(npc));}

    @ParameterizedTest @EnumSource(value=Mode.class,names={"MUSICIAN_FLUTE","MUSICIAN_GUITAR"})
    void displacedLongWorkingMusicianWalksBackAndResumesBothMusicAndGestures(Mode mode){
        setMode(mode);Sound instrument=mode==Mode.MUSICIAN_FLUTE?Sound.BLOCK_NOTE_BLOCK_FLUTE:Sound.BLOCK_NOTE_BLOCK_GUITAR;
        startWorking();server.getScheduler().performTicks(1300);var chosenGoal=routines.activeGoal(npc);
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(instrument),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        position=new Location(world,4.5,64,.5);clearInvocations(listener,navigator,visuals);assertFalse(routines.canInteract(npc));
        server.getScheduler().performTicks(8);
        assertFalse(music.isPerforming(npc));assertTrue(music.status("performer").startsWith("sin sesión musical"));
        assertSame(chosenGoal,routines.activeGoal(npc));assertTrue(routines.status("performer").contains("caminando"));
        verify(navigator).cancel("performer");verify(navigator,atLeastOnce()).move(eq(npc),eq(post),anyDouble(),anyLong(),anyInt());
        verify(listener,never()).playSound(any(Location.class),any(Sound.class),any(SoundCategory.class),anyFloat(),anyFloat());
        assertTrue(position.getX()>post.getX()+1,"Recovery starts with path steps, not an immediate arrival");
        clearInvocations(listener,visuals);server.getScheduler().performTicks(80);
        assertTrue(routines.canInteract(npc));assertTrue(music.isPerforming(npc));assertSame(chosenGoal,routines.activeGoal(npc));
        assertEquals("trabajando; goal 1",routines.status("performer"));
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(instrument),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        verify(visuals,atLeastOnce()).tick(eq(npc),anyList(),anyDouble(),anyLong(),anyLong(),anyBoolean());verify(entity,never()).teleport(any(Location.class));
    }
    @Test void musicianStillWithinThePostToleranceKeepsWorkingWithoutRestartingHisRouteOrSession(){
        startWorking();clearInvocations(navigator,visuals);position=post.clone().add(.59,0,0);server.getScheduler().performTicks(32);
        assertTrue(routines.canInteract(npc));assertTrue(music.isPerforming(npc));assertEquals("trabajando; goal 1",routines.status("performer"));
        verify(navigator,never()).cancel(anyString());verify(navigator,never()).move(any(),any(),anyDouble(),anyLong(),anyInt());verify(visuals,never()).stop();
    }
    @Test void scheduleEndingWhileReturningPreventsAnyLateMusicRestart(){
        startWorking();position=new Location(world,4.5,64,.5);routeWaiting=true;server.getScheduler().performTicks(8);
        assertFalse(music.isPerforming(npc));assertTrue(routines.status("performer").contains("esperando ruta"));
        when(world.getFullTime()).thenReturn(12000L);clearInvocations(listener,navigator,visuals);server.getScheduler().performTicks(80);
        assertFalse(routines.canInteract(npc));assertFalse(music.isPerforming(npc));assertNull(routines.activeGoal(npc));
        assertTrue(routines.status("performer").contains("fuera de horario"));verify(navigator,never()).move(any(),any(),anyDouble(),anyLong(),anyInt());
        verify(listener,never()).playSound(any(Location.class),any(Sound.class),any(SoundCategory.class),anyFloat(),anyFloat());verify(visuals,never()).tick(any(),anyList(),anyDouble(),anyLong(),anyLong(),anyBoolean());
    }
    @ParameterizedTest @EnumSource(value=Mode.class,names={"NORMAL","SHOP"})
    void displacementDoesNotChangeTheExistingBehaviourOfOtherJobs(Mode mode){
        setMode(mode);server.getScheduler().performTicks(26);assertEquals("trabajando; goal 1",routines.status("performer"));
        position=new Location(world,4.5,64,.5);clearInvocations(navigator);server.getScheduler().performTicks(32);
        assertEquals("trabajando; goal 1",routines.status("performer"));verify(navigator,never()).cancel(anyString());verify(navigator,never()).move(any(),any(),anyDouble(),anyLong(),anyInt());
    }
}
