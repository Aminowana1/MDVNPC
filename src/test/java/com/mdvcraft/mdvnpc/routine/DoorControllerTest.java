package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DoorControllerTest {
    World world;
    MdvNpcPlugin plugin;
    DoorController controller;
    YamlConfiguration settings;
    final Map<String,Block> blocks = new HashMap<>();
    final Map<String,DoorState> doors = new HashMap<>();
    final Map<UUID,Entity> entities = new HashMap<>();
    final Map<UUID,Location> positions = new HashMap<>();
    final List<EntityInteractEvent> events = new ArrayList<>();

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        MockBukkit.mock();
        settings = new YamlConfiguration();
        plugin = mock(MdvNpcPlugin.class);
        when(plugin.settings()).thenReturn(Settings.parse(settings));
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call -> block(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
        when(world.getEntity(any(UUID.class))).thenAnswer(call -> entities.get(call.getArgument(0)));
        when(world.getNearbyEntities(any(BoundingBox.class),any(Predicate.class))).thenAnswer(call -> nearby(call.getArgument(0),call.getArgument(1)));
        // Exercise the former broad clearance check too, so the side-by-side case is a regression.
        when(world.getNearbyEntities(any(Location.class),anyDouble(),anyDouble(),anyDouble(),any(Predicate.class))).thenAnswer(call -> {
            Location center=call.getArgument(0);
            double x=call.getArgument(1),y=call.getArgument(2),z=call.getArgument(3);
            return nearby(BoundingBox.of(center,x,y,z),call.getArgument(4));
        });
        listen(events::add);
        controller = new DoorController(plugin);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private Collection<Entity> nearby(BoundingBox box,Predicate<Entity> filter) {
        return entities.values().stream().filter(Entity::isValid).filter(filter)
                .filter(entity -> entity.getBoundingBox().overlaps(box)).toList();
    }
    private void listen(Consumer<EntityInteractEvent> handler) {
        Bukkit.getPluginManager().registerEvent(EntityInteractEvent.class,new Listener(){},EventPriority.NORMAL,
                (listener,event) -> handler.accept((EntityInteractEvent)event),MockBukkit.createMockPlugin());
    }
    private Block block(int x,int y,int z) {
        String key=x+":"+y+":"+z;
        return blocks.computeIfAbsent(key,ignored -> {
            Block block=mock(Block.class);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(y); when(block.getZ()).thenReturn(z);
            when(block.getLocation()).thenAnswer(call -> new Location(world,x,y,z));
            when(block.getType()).thenAnswer(call -> doors.containsKey(key) ? doors.get(key).type : y==63 ? Material.STONE : Material.AIR);
            when(block.getBlockData()).thenAnswer(call -> doors.containsKey(key) ? doors.get(key).data() : mock(BlockData.class));
            when(block.isPassable()).thenAnswer(call -> !doors.containsKey(key) && y!=63);
            when(block.getBoundingBox()).thenReturn(y==63 ? new BoundingBox(x,y,z,x+1,y+1,z+1)
                    : new BoundingBox(x,y,z,x,y,z));
            when(block.getRelative(anyInt(),anyInt(),anyInt())).thenAnswer(call -> block(x+(int)call.getArgument(0),y+(int)call.getArgument(1),z+(int)call.getArgument(2)));
            doAnswer(call -> {
                Door data=call.getArgument(0); DoorState state=doors.get(key);
                state.open=data.isOpen(); state.powered=data.isPowered(); return null;
            }).when(block).setBlockData(any(BlockData.class),eq(false));
            return block;
        });
    }
    private static final class DoorState {
        final Bisected.Half half;
        Material type=Material.OAK_DOOR;
        boolean open,powered;
        Door.Hinge hinge=Door.Hinge.LEFT;
        DoorState(Bisected.Half half,boolean open) { this.half=half; this.open=open; }
        Door data() {
            Door data=mock(Door.class);
            boolean[] value={open}; boolean poweredValue=powered; Material material=type; Door.Hinge hingeValue=hinge;
            when(data.getHalf()).thenReturn(half); when(data.isOpen()).thenAnswer(call -> value[0]);
            when(data.isPowered()).thenReturn(poweredValue);
            when(data.getAsString()).thenAnswer(call -> material+"[half="+half+",open="+value[0]+",powered="+poweredValue+",hinge="+hingeValue+"]");
            doAnswer(call -> { value[0]=call.getArgument(0); return null; }).when(data).setOpen(anyBoolean());
            return data;
        }
    }
    private DoorState lower() { return doors.get("0:64:0"); }
    private DoorState upper() { return doors.get("0:65:0"); }
    private void door(boolean open) {
        doors.put("0:64:0",new DoorState(Bisected.Half.BOTTOM,open));
        doors.put("0:65:0",new DoorState(Bisected.Half.TOP,open));
    }
    private Villager npc(double x,double z) {
        Villager npc=mock(Villager.class); UUID id=UUID.randomUUID();
        entities.put(id,npc); positions.put(id,new Location(world,x,64,z));
        when(npc.getUniqueId()).thenReturn(id); when(npc.getWorld()).thenReturn(world); when(npc.isValid()).thenReturn(true);
        when(npc.getLocation()).thenAnswer(call -> positions.get(id).clone());
        when(npc.getBoundingBox()).thenAnswer(call -> {
            Location at=positions.get(id);
            return new BoundingBox(at.getX()-.3,at.getY(),at.getZ()-.3,at.getX()+.3,at.getY()+1.95,at.getZ()+.3);
        });
        return npc;
    }
    private Location passage() { return new Location(world,.5,64,.5); }
    private void assertOpen(boolean open) { assertEquals(open,lower().open); assertEquals(open,upper().open); }

    @Test void opensBothHalvesAndClosesAfterTheTravellerClearsTheDoorway() {
        door(false); Villager npc=npc(1.5,.5);
        assertTrue(controller.openNear(npc,passage(),0)); assertOpen(true);
        controller.tick(19,false); assertOpen(true);
        controller.tick(20,false); assertOpen(false);
        assertEquals(2,events.size());
        verify(block(0,64,0),times(2)).setBlockData(any(BlockData.class),eq(false));
        verify(block(0,65,0),times(2)).setBlockData(any(BlockData.class),eq(false));
    }
    @Test void preopenedDoorClosesEvenWithAnIdleNpcInTheAdjacentBlock() {
        door(true); Villager traveller=npc(2.5,.5); npc(1.5,.5);
        assertTrue(controller.openNear(traveller,passage(),0));
        controller.tick(20,false); assertOpen(false);
        assertEquals(1,events.size());
    }
    @Test void doorwayOccupancyDefersClosingUntilEveryTravellerHasPassed() {
        door(true); Villager first=npc(2.5,.5),second=npc(.5,.5);
        controller.openNear(first,passage(),0); controller.openNear(second,passage(),10);
        controller.tick(20,false); assertOpen(true);
        controller.tick(30,false); assertOpen(true);
        positions.put(second.getUniqueId(),new Location(world,1.5,64,.5));
        controller.tick(32,false); assertOpen(false);
    }
    @Test void latestTravellerRefreshesDelayAndEarlierValidNpcCanCloseIfItDisappears() {
        door(true); Villager first=npc(2.5,.5),second=npc(1.5,.5);
        controller.openNear(first,passage(),0); controller.openNear(second,passage(),18);
        controller.tick(20,false); assertOpen(true);
        when(second.isValid()).thenReturn(false);
        controller.tick(38,false); assertOpen(false);
        assertEquals(first,events.getFirst().getEntity());
    }
    @Test void disabledPreopenedOptionStillClosesDoorsOpenedByAnNpc() {
        settings.set("routines.close-preopened-doors",false);
        Villager npc=npc(1.5,.5); door(true);
        controller.openNear(npc,passage(),0); controller.tick(20,false); assertOpen(true);
        door(false);
        controller.openNear(npc,passage(),40); controller.tick(60,false); assertOpen(false);
    }
    @Test void poweredDoorHalvesRemainUnderRedstoneControl() {
        door(false); upper().powered=true;
        assertFalse(controller.openNear(npc(1.5,.5),passage(),0)); assertOpen(false);
        lower().open=true; upper().open=true;
        controller.openNear(npc(1.5,.5),passage(),20); controller.tick(40,false); assertOpen(true);
        assertTrue(events.isEmpty());
    }
    @Test void ironDoorsAreNotOpenedManually() {
        door(false); lower().type=Material.IRON_DOOR; upper().type=Material.IRON_DOOR;
        assertFalse(controller.openNear(npc(1.5,.5),passage(),0)); assertOpen(false);
        assertTrue(events.isEmpty());
    }
    @Test void cancelledOpeningAndClosingEventsRespectProtection() {
        listen(event -> event.setCancelled(true));
        door(false); Villager npc=npc(1.5,.5);
        assertFalse(controller.openNear(npc,passage(),0)); assertOpen(false);
        door(true); controller.openNear(npc,passage(),20); controller.tick(40,false); assertOpen(true);
        verify(block(0,64,0),never()).setBlockData(any(BlockData.class),anyBoolean());
    }
    @Test void playerChangingADoorAfterPassageIsNotOverwritten() {
        door(true); controller.openNear(npc(1.5,.5),passage(),0);
        upper().hinge=Door.Hinge.RIGHT;
        controller.tick(20,false); assertOpen(true); assertTrue(events.isEmpty());
    }
    @Test void openingListenerChangingTheDoorIsNotOverwritten() {
        door(false); listen(event -> upper().hinge=Door.Hinge.RIGHT);
        assertFalse(controller.openNear(npc(1.5,.5),passage(),0)); assertOpen(false);
        verify(block(0,64,0),never()).setBlockData(any(BlockData.class),anyBoolean());
    }
    @Test void enteringTheDoorwayDuringTheClosingEventPreventsClosure() {
        door(true); Villager npc=npc(1.5,.5);
        controller.openNear(npc,passage(),0);
        listen(event -> { if (events.size()==1) positions.put(npc.getUniqueId(),passage()); });
        controller.tick(20,false); assertOpen(true);
        positions.put(npc.getUniqueId(),new Location(world,1.5,64,.5));
        controller.tick(22,false); assertOpen(false);
    }
    @Test void unloadedDoorChunkIsNeverReadOrLoaded() {
        when(world.isChunkLoaded(0,0)).thenReturn(false);
        assertFalse(controller.openNear(npc(1.5,.5),passage(),0));
        verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }

    private ActiveNpc walkingNpc(Villager entity,Pathfinder pathfinder) {
        when(entity.isOnGround()).thenReturn(true); when(entity.getPathfinder()).thenReturn(pathfinder);
        Pathfinder.PathResult path=mock(Pathfinder.PathResult.class);
        when(path.getPoints()).thenReturn(List.of(new Location(world,-2,64,0),new Location(world,-1,64,0),
                new Location(world,0,64,0),new Location(world,1,64,0),new Location(world,2,64,0)));
        when(path.canReachFinalPoint()).thenReturn(true);
        when(pathfinder.findPath(any(Location.class))).thenReturn(path);
        YamlConfiguration yaml=new YamlConfiguration();
        yaml.set("npcs.walker.location.world","world"); yaml.set("npcs.walker.location.y",64);
        return new ActiveNpc(NpcParser.parse(yaml).get("walker"),entity.getLocation(),entity,null);
    }
    @Test void navigatorOpensAClosedWoodenDoorBeforeEnteringAndClosesItAfterPassing() {
        door(false); Villager entity=npc(-1.5,.5); Pathfinder pathfinder=mock(Pathfinder.class);
        ActiveNpc npc=walkingNpc(entity,pathfinder);
        List<Double> openingPositions=new ArrayList<>();
        listen(event -> { if (!lower().open) openingPositions.add(entity.getLocation().getX()); });
        RoutineNavigator navigator=new RoutineNavigator(controller,(active,to) -> {
            if (to.getX()>-.30 && to.getX()<1.30) assertOpen(true);
            positions.put(entity.getUniqueId(),to.clone()); return true;
        });
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for (int tick=0;tick<120 && result!=RoutineNavigator.Result.ARRIVED;tick+=2) {
            result=navigator.move(npc,new Location(world,2.5,64,.5),2.4,tick,2);
            assertNotEquals(RoutineNavigator.Result.WAITING,result,"a usable closed door must not discard the Paper route");
        }
        assertEquals(RoutineNavigator.Result.ARRIVED,result); assertOpen(true);
        assertEquals(1,openingPositions.size());
        assertTrue(openingPositions.getFirst()<=-.30,"open while the traveller is still outside the doorway");
        assertTrue(entity.getLocation().getX()>1.30);
        controller.tick(120,false); assertOpen(false);
        verify(pathfinder).setCanOpenDoors(true); verify(pathfinder).setCanPassDoors(true);
        verify(pathfinder,times(1)).findPath(any(Location.class));
        navigator.clear();
    }
    @Test void navigatorCannotPassAClosedDoorWhenItsOpeningEventIsCancelled() {
        door(false); Villager entity=npc(-1.5,.5); Pathfinder pathfinder=mock(Pathfinder.class);
        ActiveNpc npc=walkingNpc(entity,pathfinder); listen(event -> event.setCancelled(true));
        RoutineNavigator navigator=new RoutineNavigator(controller,(active,to) -> {
            positions.put(entity.getUniqueId(),to.clone()); return true;
        });
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for (int tick=0;tick<20 && result!=RoutineNavigator.Result.WAITING;tick+=2)
            result=navigator.move(npc,new Location(world,2.5,64,.5),2.4,tick,2);
        assertEquals(RoutineNavigator.Result.WAITING,result); assertOpen(false);
        assertTrue(entity.getLocation().getX()<=-.30,"the traveller must stay outside a denied door");
        assertEquals(1,events.size()); navigator.clear();
    }
}
