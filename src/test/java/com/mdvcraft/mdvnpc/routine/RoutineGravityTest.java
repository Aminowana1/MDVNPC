package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real bounded physics/terrain over mutable collision fixtures; never enables vanilla AI. */
class RoutineGravityTest {
    @TempDir Path folder;
    World world;Villager entity;ActiveNpc npc;RoutineGravity gravity;
    RoutineGravity.State state;
    Location position;
    int floorY=63;
    boolean noOpTeleport;
    final Map<String,Block> blocks=new HashMap<>();
    final Map<Integer,Material> hazards=new HashMap<>();
    final List<Location> moves=new ArrayList<>();

    @BeforeEach void setup() {
        MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            return blocks.computeIfAbsent(x+","+y+","+z,ignored->{
                Block block=mock(Block.class);
                when(block.getType()).thenAnswer(i->hazards.getOrDefault(y,y==floorY?Material.STONE:Material.AIR));
                when(block.isPassable()).thenAnswer(i->y!=floorY);
                when(block.getBoundingBox()).thenAnswer(i->y==floorY?new BoundingBox(x,y,z,x+1,y+1,z+1)
                        :new BoundingBox(x,y,z,x,y,z));
                return block;
            });
        });
        position=new Location(world,.5,64,.5);entity=mock(Villager.class);
        when(entity.getLocation()).thenAnswer(i->position.clone());when(entity.getWorld()).thenReturn(world);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.isValid()).thenReturn(true);
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("npcs.test.location.world","world");
        npc=new ActiveNpc(NpcParser.parse(yaml).get("test"),position.clone(),entity,null);
        gravity=new RoutineGravity(new DoorController(mock(MdvNpcPlugin.class)),(active,target)->{
            if(!noOpTeleport){position=target.clone();moves.add(position.clone());}return true;
        });
        state=new RoutineGravity.State();
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    private long reads() {
        return mockingDetails(world).getInvocations().stream().filter(i->i.getMethod().getName().equals("getBlockAt")).count();
    }

    @Test void removingOneBlockOfSupportFallsGraduallyAndLandsExactly() {
        assertEquals(RoutineGravity.Result.STABLE,gravity.tick(npc,state,0,2));floorY=62;
        assertEquals(RoutineGravity.Result.FALLING,gravity.tick(npc,state,20,2));
        assertTrue(position.getY()<64 && position.getY()>63.5);
        RoutineGravity.Result result=RoutineGravity.Result.FALLING;
        for(long tick=22;tick<80 && result!=RoutineGravity.Result.STABLE;tick+=2)result=gravity.tick(npc,state,tick,2);
        assertEquals(RoutineGravity.Result.STABLE,result);assertEquals(63,position.getY(),1e-8);
        Location before=new Location(world,.5,64,.5);
        for(Location move:moves){assertEquals(before.getX(),move.getX());assertEquals(before.getZ(),move.getZ());
            assertTrue(before.getY()-move.getY()<=.4000001);assertTrue(move.getY()>=63);before=move;}
        verify(entity,never()).setAI(anyBoolean());verify(entity,never()).setAware(anyBoolean());
    }

    @Test void loadedClearAirWithoutFloorInTheEightBlockProbeStillFallsInBoundedSteps() {
        floorY=50;
        assertEquals(RoutineGravity.Result.FALLING,gravity.tick(npc,state,0,2));
        assertTrue(position.getY()<64 && position.getY()>63.5,"no long drop teleport");
        RoutineGravity.Result result=RoutineGravity.Result.FALLING;
        for(long tick=2;tick<120 && result!=RoutineGravity.Result.STABLE;tick+=2)result=gravity.tick(npc,state,tick,2);
        assertEquals(RoutineGravity.Result.STABLE,result);assertEquals(51,position.getY(),1e-8);
        assertTrue(moves.size()>20,"the descent must use visible bounded updates");
    }

    @Test void aHazardBelowTheMissingFloorStopsRecoveryBeforeMoving() {
        floorY=60;hazards.put(62,Material.LAVA);
        assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(npc,state,0,2));
        assertEquals(64,position.getY());assertTrue(moves.isEmpty());
    }

    @Test void anUnloadedPartOfTheBodyFootprintIsNeverReadOrEntered() {
        floorY=60;position=new Location(world,15.9,64,.5);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenAnswer(i->(int)i.getArgument(0)==0);
        assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(npc,state,0,2));
        assertTrue(moves.isEmpty());verify(world,never()).getBlockAt(intThat(x->x>=16),anyInt(),anyInt());
    }

    @Test void aSuccessfulButNoOpTeleportIsBlockedAndBackedOff() {
        floorY=62;noOpTeleport=true;
        assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(npc,state,0,2));
        long firstReads=reads();assertEquals(64,position.getY());assertFalse(state.falling());
        for(long tick=1;tick<20;tick++)assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(npc,state,tick,1));
        assertEquals(firstReads,reads());
    }

    @Test void aSupportedIdleNpcChecksItsColumnOnlyOncePerSecond() {
        assertEquals(RoutineGravity.Result.STABLE,gravity.tick(npc,state,0,2));long firstReads=reads();
        assertTrue(firstReads>0);
        for(long tick=1;tick<20;tick++)assertEquals(RoutineGravity.Result.STABLE,gravity.tick(npc,state,tick,1));
        assertEquals(firstReads,reads());assertTrue(moves.isEmpty());
        assertEquals(RoutineGravity.Result.STABLE,gravity.tick(npc,state,20,2));assertTrue(reads()>firstReads);
    }

    @Test void aMountedNpcKeepsItsPoseAndDoesNotProbeTheFloor() {
        floorY=60;when(entity.isInsideVehicle()).thenReturn(true);
        assertEquals(RoutineGravity.Result.STABLE,gravity.tick(npc,state,0,2));
        assertEquals(0,reads());assertTrue(moves.isEmpty());
    }

    @Test void aWorkerLosingItsFloorStopsWorkingThenRejoinsTheNewCheckedHeight() throws Exception {
        MockBukkit.unmock();
        RoutineTransitionTest fixture=new RoutineTransitionTest();fixture.folder=folder;fixture.setup();
        try {
            // The fixture's mounted flag was intended for cosmetic-seat tests.
            when(fixture.entity.isInsideVehicle()).thenReturn(false);
            fixture.service.repository().put("thurg",fixture.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));
            fixture.service.start();fixture.advance(2);assertTrue(fixture.service.canInteract(fixture.npc));
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) {
                Block missing=fixture.world.getBlockAt(x,63,z);
                when(missing.getType()).thenReturn(Material.AIR);when(missing.isPassable()).thenReturn(true);
                when(missing.getBoundingBox()).thenReturn(new BoundingBox(x,63,z,x,63,z));
                Block lower=fixture.world.getBlockAt(x,62,z);
                when(lower.getType()).thenReturn(Material.STONE);when(lower.isPassable()).thenReturn(false);
                when(lower.getBoundingBox()).thenReturn(new BoundingBox(x,62,z,x+1,63,z+1));
            }
            fixture.advance(2);
            assertFalse(fixture.service.canInteract(fixture.npc));assertTrue(fixture.position.getY()<64);
            fixture.advance(20);assertEquals(63,fixture.position.getY(),1e-8);
            assertTrue(fixture.service.canInteract(fixture.npc),fixture.service.status("thurg"));
            verify(fixture.entity,never()).setAI(anyBoolean());
        } finally {fixture.cleanup();}
    }

    private RoutineTransitionTest staticFixture(boolean observed,boolean mounted) throws Exception {
        MockBukkit.unmock();RoutineTransitionTest fixture=new RoutineTransitionTest();
        fixture.folder=folder;fixture.setup();fixture.following=observed;
        if(!observed)fixture.observerPosition=null;
        when(fixture.entity.isInsideVehicle()).thenReturn(mounted);
        fixture.service.start();fixture.advance(2);return fixture;
    }
    private void lowerFixtureFloor(RoutineTransitionTest fixture) {
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) {
            Block missing=fixture.world.getBlockAt(x,63,z);
            when(missing.getType()).thenReturn(Material.AIR);when(missing.isPassable()).thenReturn(true);
            when(missing.getBoundingBox()).thenReturn(new BoundingBox(x,63,z,x,63,z));
            Block lower=fixture.world.getBlockAt(x,62,z);
            when(lower.getType()).thenReturn(Material.STONE);when(lower.isPassable()).thenReturn(false);
            when(lower.getBoundingBox()).thenReturn(new BoundingBox(x,62,z,x+1,63,z+1));
        }
    }
    private MdvNpcPlugin fixturePlugin(RoutineTransitionTest fixture) throws Exception {
        var field=RoutineService.class.getDeclaredField("plugin");field.setAccessible(true);
        return (MdvNpcPlugin)field.get(fixture.service);
    }

    @Test void anObservedStaticNpcFallsWithoutCreatingARoutineAndReplacementResetsTheCachedProbe() throws Exception {
        RoutineTransitionTest fixture=staticFixture(true,false);
        try {
            lowerFixtureFloor(fixture);fixture.advance(20);
            assertTrue(fixture.position.getY()<64);fixture.advance(20);assertEquals(63,fixture.position.getY(),1e-8);
            assertFalse(fixture.service.enabled("thurg"));assertTrue(fixture.service.canUseJob(fixture.npc));
            assertTrue(fixture.service.canLook(fixture.npc));
            // A replacement must not inherit the old entity's cached stable-floor decision.
            fixture.position=new Location(fixture.world,.5,64,.5);
            Villager replacement=mock(Villager.class);when(replacement.getUniqueId()).thenReturn(UUID.randomUUID());
            when(replacement.getWorld()).thenReturn(fixture.world);when(replacement.isValid()).thenReturn(true);
            when(replacement.getLocation()).thenAnswer(i->fixture.position.clone());
            when(replacement.teleport(any(Location.class))).thenAnswer(i->{fixture.position=((Location)i.getArgument(0)).clone();return true;});
            ActiveNpc newNpc=new ActiveNpc(fixture.npc.definition(),fixture.position.clone(),replacement,null);
            when(fixturePlugin(fixture).manager().activeNpcs()).thenReturn(List.of(newNpc));fixture.advance(2);
            assertTrue(fixture.position.getY()<64,"entity identity must force a fresh floor probe");
            verify(replacement,never()).setAI(anyBoolean());
            when(fixturePlugin(fixture).manager().activeNpcs()).thenReturn(List.of());fixture.service.remove("thurg");
            clearInvocations(fixture.world);fixture.advance(40);
            verify(fixture.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        } finally {fixture.cleanup();}
    }

    @Test void anUnobservedStaticNpcDoesNotReadBlocksOrFall() throws Exception {
        RoutineTransitionTest fixture=staticFixture(false,false);
        try {
            lowerFixtureFloor(fixture);clearInvocations(fixture.world);fixture.advance(100);
            assertEquals(64,fixture.position.getY());assertTrue(fixture.moves.isEmpty());
            verify(fixture.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        } finally {fixture.cleanup();}
    }

    @Test void staticFloorRecoveryLeavesVehiclesAndBusyReactionsInControl() throws Exception {
        RoutineTransitionTest fixture=staticFixture(true,true);
        try {
            lowerFixtureFloor(fixture);clearInvocations(fixture.world);fixture.advance(24);
            assertEquals(64,fixture.position.getY());verify(fixture.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
            when(fixture.entity.isInsideVehicle()).thenReturn(false);
            var reactions=mock(com.mdvcraft.mdvnpc.trait.HitReactionService.class);
            when(reactions.busy("thurg")).thenReturn(true);when(fixturePlugin(fixture).reactions()).thenReturn(reactions);
            fixture.advance(24);assertEquals(64,fixture.position.getY());
            verify(fixture.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
            when(reactions.busy("thurg")).thenReturn(false);fixture.advance(24);
            assertTrue(fixture.position.getY()<64);verify(fixture.entity,never()).setAI(anyBoolean());
        } finally {fixture.cleanup();}
    }
}
