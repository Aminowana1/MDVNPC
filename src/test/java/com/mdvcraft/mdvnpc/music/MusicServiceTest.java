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
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicServiceTest {
    ServerMock server; MdvNpcPlugin plugin; RoutineService routines; World world; Player listener;
    MusicService music;
    Logger logger;
    @BeforeEach void setup() {
        server=MockBukkit.mock();
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("music-test");when(plugin.isEnabled()).thenReturn(true);
        logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        routines=mock(RoutineService.class);when(plugin.routines()).thenReturn(routines);
        world=mock(World.class);when(world.getUID()).thenReturn(UUID.randomUUID());
        listener=mock(Player.class);when(listener.isOnline()).thenReturn(true);when(listener.getWorld()).thenReturn(world);
        when(listener.getLocation()).thenReturn(new Location(world,0,64,0));
        when(world.getNearbyPlayers(any(Location.class),anyDouble())).thenReturn(List.of(listener));
        music=new MusicService(plugin);
    }
    @AfterEach void cleanup(){try{if(music!=null)music.stop();}finally{MockBukkit.unmock();}}
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
        long first=start.getLong(session);server.getScheduler().performTicks(650);
        assertTrue(start.getLong(session)>first);music.stop();assertTrue(sessions().isEmpty());
    }
    @Test void respawnWithSameIdReplacesOldEntityInSession() throws Exception {
        music.working(npc("a","musician_flute",0));music.working(npc("b","musician_guitar",3));
        server.getScheduler().performTicks(23);Object previous=sessions().get(Set.of("a","b"));
        music.remove("a");music.working(npc("a","musician_flute",0));
        server.getScheduler().performTicks(20);assertNotSame(previous,sessions().get(Set.of("a","b")));
    }
    private MusicianVisuals injectedVisuals() {
        music.stop();var visuals=mock(MusicianVisuals.class);music=new MusicService(plugin,visuals);return visuals;
    }
    @Test void visualsUseTheSameSessionClockAndLocalAudienceDuringWarmupNotesAndRests() {
        var visuals=injectedVisuals();var flute=npc("a","musician_flute",0);var guitar=npc("b","musician_guitar",3);
        music.working(flute);music.working(guitar);server.getScheduler().performTicks(25);
        for(var musician:List.of(flute,guitar)) {
            verify(visuals).tick(same(musician),eq(List.of(listener)),eq(14d),eq(-2L),eq(0L),eq(false));
            verify(visuals).tick(same(musician),eq(List.of(listener)),eq(14d),eq(0L),eq(2L),eq(true));
            verify(visuals).tick(same(musician),eq(List.of(listener)),eq(14d),eq(1L),eq(3L),eq(false));
        }
        verify(world,atLeastOnce()).getNearbyPlayers(any(Location.class),eq(14d));verify(world,never()).getPlayers();verify(plugin,never()).getServer();
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_NOTE_BLOCK_FLUTE),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_NOTE_BLOCK_GUITAR),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
    }
    @Test void visualSuspensionRestoresPoseWithoutRemovingTheWorkerOrRestartingItsSong() throws Exception {
        var visuals=injectedVisuals();var n=npc("a","musician_flute",0);music.working(n);server.getScheduler().performTicks(23);
        var session=sessions().get(Set.of("a"));when(visuals.isAnimating(n)).thenReturn(true);assertTrue(music.isAnimating(n));
        clearInvocations(visuals,listener);music.suspendVisuals("a");verify(visuals).remove("a");assertTrue(music.isPerforming(n));
        server.getScheduler().performTicks(20);assertSame(session,sessions().get(Set.of("a")));
        verify(visuals,atLeastOnce()).tick(same(n),eq(List.of(listener)),eq(14d),anyLong(),anyLong(),anyBoolean());
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_NOTE_BLOCK_FLUTE),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        assertFalse(music.isAnimating(npc("a","musician_flute",0)),"El mismo ID no debe atribuir la pose al NPC reemplazado");
    }
    @Test void offDutyAndBusyWorkersLoseVisualsImmediatelyAndCannotAnimate() {
        var visuals=injectedVisuals();var n=npc("a","musician_guitar",0);music.working(n);server.getScheduler().performTicks(23);
        when(visuals.isAnimating(n)).thenReturn(true);assertTrue(music.isAnimating(n));clearInvocations(visuals,listener);
        when(routines.canInteract(n)).thenReturn(false);server.getScheduler().performTicks(5);
        verify(visuals,atLeastOnce()).remove("a");verify(visuals,never()).tick(any(),anyList(),anyDouble(),anyLong(),anyLong(),anyBoolean());assertFalse(music.isAnimating(n));
        verify(listener,never()).playSound(any(Location.class),any(Sound.class),any(SoundCategory.class),anyFloat(),anyFloat());
        when(routines.canInteract(n)).thenReturn(true);var reactions=mock(com.mdvcraft.mdvnpc.trait.HitReactionService.class);when(plugin.reactions()).thenReturn(reactions);when(reactions.busy("a")).thenReturn(true);
        clearInvocations(visuals);server.getScheduler().performTicks(5);verify(visuals,atLeastOnce()).remove("a");assertFalse(music.isAnimating(n));
    }
    @Test void replacementAndRemovalRestoreOldVisualsAndStopCancelsFurtherAnimation() {
        var visuals=injectedVisuals();var old=npc("a","musician_flute",0);music.working(old);server.getScheduler().performTicks(23);clearInvocations(visuals);
        var replacement=npc("a","musician_guitar",0);music.working(replacement);verify(visuals).remove("a");clearInvocations(visuals);
        server.getScheduler().performTicks(20);verify(visuals,never()).tick(same(old),anyList(),anyDouble(),anyLong(),anyLong(),anyBoolean());
        verify(visuals,atLeastOnce()).tick(same(replacement),eq(List.of(listener)),eq(14d),anyLong(),anyLong(),anyBoolean());
        music.remove("a");verify(visuals,atLeastOnce()).remove("a");verify(visuals).stop();clearInvocations(visuals,listener);
        server.getScheduler().performTicks(50);verifyNoInteractions(visuals,listener);assertFalse(music.isAnimating(replacement));
    }
    @Test void oneBrokenAnimationDoesNotInterruptEitherInstrumentTheOtherNpcOrTheMusicClock() throws Exception {
        var visuals=injectedVisuals();var flute=npc("a","musician_flute",0);var guitar=npc("b","musician_guitar",3);
        var failure=new IllegalStateException("Componente cosmético no disponible");
        doThrow(failure).when(visuals).tick(same(flute),anyList(),anyDouble(),anyLong(),anyLong(),anyBoolean());
        when(visuals.isAnimating(flute)).thenReturn(true);music.working(flute);music.working(guitar);server.getScheduler().performTicks(25);
        var clock=MusicService.class.getDeclaredField("tick");clock.setAccessible(true);long initialClock=clock.getLong(music);
        verify(visuals).tick(same(flute),eq(List.of(listener)),eq(14d),eq(-2L),eq(0L),eq(false));verify(visuals).remove("a");
        assertFalse(music.isAnimating(flute));assertTrue(music.isPerforming(flute));
        verify(logger).log(eq(Level.WARNING),contains("Animación musical pausada para a"),same(failure));
        clearInvocations(listener);server.getScheduler().performTicks(30);assertTrue(clock.getLong(music)>initialClock);
        verify(visuals,times(1)).tick(same(flute),anyList(),anyDouble(),anyLong(),anyLong(),anyBoolean());
        verify(visuals,atLeast(30)).tick(same(guitar),eq(List.of(listener)),eq(14d),anyLong(),anyLong(),anyBoolean());
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_NOTE_BLOCK_FLUTE),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        verify(listener,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_NOTE_BLOCK_GUITAR),eq(SoundCategory.RECORDS),anyFloat(),anyFloat());
        verify(logger,times(1)).log(eq(Level.WARNING),anyString(),same(failure));
        var replacement=npc("a","musician_flute",0);music.working(replacement);server.getScheduler().performTicks(20);
        verify(visuals,atLeastOnce()).tick(same(replacement),eq(List.of(listener)),eq(14d),anyLong(),anyLong(),anyBoolean());
        verify(logger,times(1)).log(eq(Level.WARNING),anyString(),same(failure));
    }
}
