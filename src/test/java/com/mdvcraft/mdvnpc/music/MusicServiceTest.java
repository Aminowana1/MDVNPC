package com.mdvcraft.mdvnpc.music;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicServiceTest {
    ServerMock server; MdvNpcPlugin plugin; RoutineService routines; World world; Player listener;
    MusicService music;
    @BeforeEach void setup() {
        server=MockBukkit.mock();
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("music-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        routines=mock(RoutineService.class);when(plugin.routines()).thenReturn(routines);
        world=mock(World.class);when(world.getUID()).thenReturn(UUID.randomUUID());
        listener=mock(Player.class);when(listener.isOnline()).thenReturn(true);when(listener.getWorld()).thenReturn(world);
        when(listener.getLocation()).thenReturn(new Location(world,0,64,0));
        when(world.getNearbyPlayers(any(Location.class),anyDouble())).thenReturn(List.of(listener));
        music=new MusicService(plugin);
    }
    @AfterEach void cleanup(){music.stop();MockBukkit.unmock();}
    ActiveNpc npc(String id,String mode,double x) {
        var yaml=new YamlConfiguration();yaml.set("npcs."+id+".location.world","world");yaml.set("npcs."+id+".mode",mode);
        var entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        Location position=new Location(world,x,64,0);when(entity.getLocation()).thenReturn(position);
        var npc=new ActiveNpc(NpcParser.parse(yaml).get(id),position,entity,null);
        when(routines.canInteract(npc)).thenReturn(true);return npc;
    }
    @Test void scoresContainBothPartsAndExactTourdionOpening() {
        for(String name:List.of("tourdion","jabali","farol")) {
            Song s=Song.load(name);assertTrue(s.duration()>100);
            assertTrue(s.frames().get(0).stream().anyMatch(Song.Note::flute));
            assertTrue(s.frames().get(0).stream().anyMatch(n->!n.flute()));
        }
        Song t=Song.load("tourdion");assertEquals(288,t.duration());
        assertEquals(1.59f,t.frames().get(3).stream().filter(Song.Note::flute).findFirst().orElseThrow().pitch());
    }
    @Test void soloPlaysAndStopsImmediatelyWhenRemoved() {
        var n=npc("solo","musician_flute",0);music.working(n);server.getScheduler().performTicks(25);
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_NOTE_BLOCK_FLUTE),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        music.remove("solo");clearInvocations(listener);server.getScheduler().performTicks(50);verifyNoInteractions(listener);
    }
    @Test void pairStartsOnSameTickAndNeverQueriesOnlinePlayers() {
        music.working(npc("flute","musician_flute",0));music.working(npc("guitar","musician_guitar",4));
        server.getScheduler().performTicks(21);
        verify(listener,never()).playSound(any(Location.class),any(Sound.class),any(SoundCategory.class),anyFloat(),anyFloat());
        server.getScheduler().performTicks(2);
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_NOTE_BLOCK_FLUTE),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_NOTE_BLOCK_GUITAR),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        verify(plugin,never()).getServer();
    }
    @Test void cachedListenerLeavingRadiusOrWorldHearsNothing() {
        music.working(npc("solo","musician_flute",0));server.getScheduler().performTicks(23);clearInvocations(listener);
        when(listener.getLocation()).thenReturn(new Location(world,15,64,0));
        server.getScheduler().performTicks(50);
        verify(listener,never()).playSound(any(Location.class),any(Sound.class),any(SoundCategory.class),anyFloat(),anyFloat());
        when(listener.getWorld()).thenReturn(mock(World.class));assertFalse(MusicService.audible(new Location(world,0,64,0),listener,14));
    }
    @Test void offDutyMusicianIsSilentEvenBeforeRegroup() {
        var n=npc("solo","musician_guitar",0);music.working(n);server.getScheduler().performTicks(23);clearInvocations(listener);
        when(routines.canInteract(n)).thenReturn(false);server.getScheduler().performTicks(50);
        verify(listener,never()).playSound(any(Location.class),any(Sound.class),any(SoundCategory.class),anyFloat(),anyFloat());
    }
    @SuppressWarnings("unchecked")
    Map<Set<String>,Object> sessions() throws Exception {
        var field=MusicService.class.getDeclaredField("sessions");field.setAccessible(true);
        return (Map<Set<String>,Object>)field.get(music);
    }
    @Test void distantEnsembleKeepsSessionWhileNearbyGroupMergesAndSplits() throws Exception {
        var a=npc("a","musician_flute",0);var b=npc("b","musician_guitar",30);
        var c=npc("c","musician_flute",100);
        music.working(a);music.working(b);music.working(c);server.getScheduler().performTicks(23);
        assertEquals(3,sessions().size());Object stable=sessions().get(Set.of("c"));
        when(b.entity().getLocation()).thenReturn(new Location(world,4,64,0));
        server.getScheduler().performTicks(20);
        assertEquals(2,sessions().size());assertTrue(sessions().containsKey(Set.of("a","b")));
        assertSame(stable,sessions().get(Set.of("c")));
        music.remove("a");server.getScheduler().performTicks(20);
        assertTrue(sessions().containsKey(Set.of("b")));assertSame(stable,sessions().get(Set.of("c")));
    }
    @Test void worldsNeverMergeAndNormalNpcsAreIgnored() throws Exception {
        var a=npc("a","musician_flute",0);var b=npc("b","musician_guitar",0);
        World other=mock(World.class);when(other.getUID()).thenReturn(UUID.randomUUID());
        when(b.entity().getLocation()).thenReturn(new Location(other,0,64,0));
        music.working(a);music.working(b);music.working(npc("normal","normal",0));
        server.getScheduler().performTicks(23);
        assertEquals(Set.of(Set.of("a"),Set.of("b")),sessions().keySet());
    }
    @Test void completedSongAdvancesAndStopClearsAllSessions() throws Exception {
        music.working(npc("a","musician_flute",0));server.getScheduler().performTicks(23);
        Object session=sessions().get(Set.of("a"));var start=session.getClass().getDeclaredField("start");start.setAccessible(true);
        long first=start.getLong(session);server.getScheduler().performTicks(310);
        assertTrue(start.getLong(session)>first);music.stop();assertTrue(sessions().isEmpty());
    }
    @Test void respawnWithSameIdReplacesOldEntityInSession() throws Exception {
        music.working(npc("a","musician_flute",0));music.working(npc("b","musician_guitar",3));
        server.getScheduler().performTicks(23);Object previous=sessions().get(Set.of("a","b"));
        music.remove("a");music.working(npc("a","musician_flute",0));
        server.getScheduler().performTicks(20);assertNotSame(previous,sessions().get(Set.of("a","b")));
    }
}
