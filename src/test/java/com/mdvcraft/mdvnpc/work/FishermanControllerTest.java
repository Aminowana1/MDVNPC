package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.DoorController;
import com.mdvcraft.mdvnpc.routine.RoutineNavigator;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import io.papermc.paper.entity.TeleportFlag;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.boat.OakBoat;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

/** Real land/water voxel checks, native passenger ownership and cosmetic cleanup. */
class FishermanControllerTest {
    private record Key(int x,int y,int z) {}
    private World world;
    private MdvNpcPlugin plugin;
    private RoutineNavigator navigator;
    private FishermanController controller;
    private ActiveNpc npc;
    private Villager entity;
    private PlayerWatcher watcher;
    private EntityEquipment equipment;
    private YamlConfiguration config;
    private AtomicReference<Location> position;
    private AtomicReference<Entity> vehicle;
    private AtomicReference<ItemStack> hand,entityHand;
    private AtomicReference<Float> chance,bodyYaw;
    private AtomicBoolean raised;
    private Location workPost;
    private boolean moving=true,allWaterBlocked,boatTeleport=true;
    private final Map<Key,Block> blocks=new HashMap<>();
    private final List<OakBoat> boats=new ArrayList<>();
    private final List<AtomicReference<Location>> boatLocations=new ArrayList<>();
    private final List<FishermanController.BobberVisual> bobbers=new ArrayList<>();
    private final List<Location> bobberPositions=new ArrayList<>();

    @BeforeEach void setup() {
        MockBukkit.mock();world=mock(World.class);plugin=mock(MdvNpcPlugin.class);config=new YamlConfiguration();
        when(plugin.settings()).thenAnswer(call->Settings.parse(config));
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            Key key=new Key(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            return blocks.computeIfAbsent(key,ignored->block(key,key.y()==63?
                    key.x()>=1?(allWaterBlocked?Material.STONE:Material.WATER):Material.STONE:Material.AIR));
        });
        workPost=new Location(world,-8.5,64,.5,20,5);position=new AtomicReference<>(workPost.clone());
        entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(call->position.get().clone());vehicle=new AtomicReference<>();
        when(entity.getVehicle()).thenAnswer(call->vehicle.get());when(entity.isInsideVehicle()).thenAnswer(call->vehicle.get()!=null);
        bodyYaw=new AtomicReference<>(12f);when(entity.getBodyYaw()).thenAnswer(call->bodyYaw.get());
        doAnswer(call->{Location facing=position.get().clone();facing.setYaw(call.getArgument(0));facing.setPitch(call.getArgument(1));position.set(facing);return null;})
                .when(entity).setRotation(anyFloat(),anyFloat());
        doAnswer(call->{bodyYaw.set(call.getArgument(0));return null;}).when(entity).setBodyYaw(anyFloat());
        equipment=mock(EntityEquipment.class);when(entity.getEquipment()).thenReturn(equipment);
        entityHand=new AtomicReference<>(new ItemStack(Material.PAPER));when(equipment.getItemInMainHand()).thenAnswer(call->entityHand.get());
        doAnswer(call->{entityHand.set(((ItemStack)call.getArgument(0)).clone());return null;}).when(equipment).setItemInMainHand(any(ItemStack.class),eq(true));
        chance=new AtomicReference<>(.08f);when(equipment.getItemInMainHandDropChance()).thenAnswer(call->chance.get());
        doAnswer(call->{chance.set(call.getArgument(0));return null;}).when(equipment).setItemInMainHandDropChance(anyFloat());
        var disguise=mock(PlayerDisguise.class);watcher=mock(PlayerWatcher.class);when(disguise.getWatcher()).thenReturn(watcher);
        hand=new AtomicReference<>(new ItemStack(Material.STICK));when(watcher.getItemInMainHand()).thenAnswer(call->hand.get());
        doAnswer(call->{ItemStack item=call.getArgument(0);hand.set(item==null?null:item.clone());return null;}).when(watcher).setItemInMainHand(any());
        raised=new AtomicBoolean(true);when(watcher.isMainHandRaised()).thenAnswer(call->raised.get());
        doAnswer(call->{raised.set(call.getArgument(0));return null;}).when(watcher).setMainHandRaised(anyBoolean());
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("npcs.fisher.location.world","world");
        yaml.set("npcs.fisher.location.y",64);yaml.set("npcs.fisher.mode","shop");
        NpcDefinition base=NpcParser.parse(yaml).get("fisher");
        var work=new ShopWorkDefinition(ShopWorkDefinition.Category.FISHERMAN,null,null,null,
                new FishingDefinition(List.of(point(-1.5,.5,-90)),point(-.5,.5,-90),List.of(point(8.5,.5,0))));
        npc=new ActiveNpc(definition(base,work),workPost.clone(),entity,disguise);
        navigator=mock(RoutineNavigator.class);
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            if(!moving)return RoutineNavigator.Result.WAITING;
            position.set(((Location)call.getArgument(1)).clone());return RoutineNavigator.Result.ARRIVED;
        });
        when(world.spawn(any(Location.class),eq(OakBoat.class),any(Consumer.class))).thenAnswer(call->{
            OakBoat boat=mock(OakBoat.class);boats.add(boat);
            AtomicReference<Location> at=new AtomicReference<>(((Location)call.getArgument(0)).clone());boatLocations.add(at);
            when(boat.isValid()).thenReturn(true);when(boat.getWorld()).thenReturn(world);when(boat.getLocation()).thenAnswer(read->at.get().clone());
            when(boat.getPassengers()).thenAnswer(read->vehicle.get()==boat?List.of(entity):List.of());
            when(boat.teleport(any(Location.class),any(TeleportFlag[].class))).thenAnswer(move->{
                if(!boatTeleport)return false;
                Location next=((Location)move.getArgument(0)).clone();at.set(next);position.set(next.clone().add(0,.45,0));return true;
            });
            ((Consumer<OakBoat>)call.getArgument(2)).accept(boat);return boat;
        });
        FishermanController.BobberFactory bobberFactory=(active,start)->{
            FishermanController.BobberVisual visual=mock(FishermanController.BobberVisual.class);bobbers.add(visual);
            when(visual.move(any(Location.class))).thenAnswer(move->{bobberPositions.add(((Location)move.getArgument(0)).clone());return true;});
            return visual;
        };
        controller=new FishermanController(plugin,navigator,mock(DoorController.class),(active,to)->{position.set(to.clone());return true;},
                (active,to)->{vehicle.set(to);if(to!=null)position.set(to.getLocation().clone().add(0,.45,0));return true;},ItemStack::new,new Random(1),bobberFactory);
    }
    @AfterEach void cleanup() {try{controller.clear();}finally{MockBukkit.unmock();}}
    private FishingDefinition.Point point(double x,double z,float yaw) {return new FishingDefinition.Point(world.getUID(),"world",x,64,z,yaw);}
    private static NpcDefinition definition(NpcDefinition base,ShopWorkDefinition work) {
        return new NpcDefinition(base.id(),base.enabled(),base.name(),base.nameVisible(),base.position(),base.skin(),base.look(),base.dialogue(),
                base.interaction(),base.mode(),base.tradeDialogue(),base.traits(),base.speech(),work);
    }
    private Block block(Key key,Material material) {
        Block block=mock(Block.class);when(block.getType()).thenReturn(material);when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(key.x());when(block.getY()).thenReturn(key.y());when(block.getZ()).thenReturn(key.z());
        VoxelShape shape=mock(VoxelShape.class);when(shape.getBoundingBoxes()).thenReturn(material==Material.STONE?List.of(new BoundingBox(0,0,0,1,1,1)):List.of());
        when(block.getCollisionShape()).thenReturn(shape);
        if(material==Material.WATER) {Levelled level=mock(Levelled.class);when(block.getBlockData()).thenReturn(level);}
        return block;
    }
    private FishermanController.Result tick(long time) {return controller.tick(npc,workPost,.2,time,2);}
    private long launch() {
        config.set("fisherman.shore-seconds",1);config.set("fisherman.boat-seconds",1);
        assertEquals(FishermanController.Result.RUNNING,tick(0));tick(1);tick(20);tick(21);
        assertEquals("navegando al punto de pesca",controller.status("fisher"));return 21;
    }
    private long reachBoatPoint(long time) {
        while(!controller.status("fisher").equals("pescando en bote") && time<200)tick(++time);
        assertEquals("pescando en bote",controller.status("fisher"));return time;
    }

    @Test void publicConstructorWorksWhenTheDefaultAlgorithmProviderIsUnavailable() {
        try(var provider=mockStatic(RandomGenerator.class)) {
            provider.when(RandomGenerator::getDefault).thenThrow(new IllegalArgumentException(
                    "No implementation of the random number generator algorithm L32X64MixRandom is available"));
            controller=assertDoesNotThrow(()->new FishermanController(plugin,navigator,
                    (active,to)->{position.set(to.clone());return true;},
                    (active,to)->{vehicle.set(to);return true;}));
            assertEquals(FishermanController.Result.RUNNING,tick(0));
            assertEquals("pescando en la orilla",controller.status("fisher"));
            provider.verifyNoInteractions();
        }
    }

    @Test void startsOnlyAfterReachingOrdinaryWorkPost() {
        position.set(workPost.clone().add(1,0,0));assertEquals(FishermanController.Result.FALLBACK,tick(0));
        assertFalse(controller.active("fisher"));verifyNoInteractions(navigator);
    }
    @Test void missingStationConfigurationLeavesShopUntouched() {
        npc=new ActiveNpc(definition(npc.definition(),new ShopWorkDefinition(ShopWorkDefinition.Category.FISHERMAN,null,null,null)),workPost,entity,npc.disguise());
        assertEquals(FishermanController.Result.FALLBACK,tick(0));assertEquals(Material.STICK,hand.get().getType());
        assertTrue(bobbers.isEmpty());assertTrue(boats.isEmpty());
    }
    @Test void shoreUsesPaperAndSavedYawCastsFourAndHalfBlocks() {
        assertEquals(FishermanController.Result.RUNNING,tick(0));tick(1);tick(17);
        assertEquals("pescando en la orilla",controller.status("fisher"));assertEquals(-90,position.get().getYaw());
        assertEquals(Material.FISHING_ROD,hand.get().getType());
        verify(navigator).move(eq(npc),argThat(location->location.getX()==-1.5 && location.getZ()==.5),eq(.2),eq(0L),eq(2));
        Location bobber=bobberPositions.getLast();assertEquals(3,bobber.getX(),1e-6);assertEquals(.5,bobber.getZ(),1e-6);
        assertEquals(0,chance.get());assertEquals(1,bobbers.size());
    }
    @Test void completeCycleUsesRealBoatAndReturnsToDockBeforeDeletingIt() {
        long time=reachBoatPoint(launch());assertTrue(controller.boating("fisher"));
        assertSame(boats.getFirst(),vehicle.get());assertTrue(controller.ownsBoat(boats.getFirst()));assertSame(npc,controller.mountedNpc(boats.getFirst()));
        assertEquals(0,position.get().getYaw());assertEquals(0,boatLocations.getFirst().get().getYaw());
        tick(++time);assertEquals(Material.FISHING_ROD,hand.get().getType());
        tick(time+=20);assertEquals("regresando al muelle",controller.status("fisher"));
        while(controller.boating("fisher") && time<400)tick(++time);
        assertNull(vehicle.get());verify(boats.getFirst()).remove();assertFalse(controller.ownsBoat(boats.getFirst()));
        assertEquals(-.5,position.get().getX());assertEquals(64,position.get().getY());
        assertEquals("caminando al punto de pesca",controller.status("fisher"));tick(++time);
        assertEquals("pescando en la orilla",controller.status("fisher"));
    }
    @Test void boatTeleportAlwaysRetainsNativePassenger() {
        long time=launch();tick(++time);
        verify(boats.getFirst()).teleport(any(Location.class),aryEq(new TeleportFlag[]{TeleportFlag.EntityState.RETAIN_PASSENGERS}));
        assertSame(boats.getFirst(),vehicle.get());
    }
    @Test void noLandProgressFallbackDoesNotWaitForGlobalDeadline() {
        moving=false;assertEquals(FishermanController.Result.RUNNING,tick(0));assertTrue(controller.walking("fisher"));
        assertEquals(FishermanController.Result.FALLBACK,tick(160));assertFalse(controller.active("fisher"));
    }
    @Test void serviceCanEnforceTravelDeadlineEvenDuringGravityRecovery() {
        moving=false;config.set("fisherman.travel-timeout-seconds",3);tick(0);
        assertFalse(controller.travelExpired("fisher",59));assertTrue(controller.travelExpired("fisher",60));
        assertEquals(FishermanController.Result.FALLBACK,tick(60));
    }
    @Test void destroyedShoreFloorCleansBobberAndFallsBack() {
        tick(0);tick(1);FishermanController.BobberVisual bobber=bobbers.getFirst();
        blocks.put(new Key(-2,63,0),block(new Key(-2,63,0),Material.AIR));
        assertEquals(FishermanController.Result.FALLBACK,tick(2));verify(bobber).remove();
        assertEquals(Material.STICK,hand.get().getType());
    }
    @Test void invalidDockWaterDoesNotStartEvenShoreAnimation() {
        allWaterBlocked=true;assertEquals(FishermanController.Result.FALLBACK,tick(0));
        assertTrue(boats.isEmpty());assertTrue(bobbers.isEmpty());
    }
    @Test void unreachableBoatPointDoesNotSpawnBoat() {
        var fishing=new FishingDefinition(List.of(point(-1.5,.5,-90)),point(-.5,.5,-90),List.of(point(200.5,.5,0)));
        npc=new ActiveNpc(definition(npc.definition(),new ShopWorkDefinition(ShopWorkDefinition.Category.FISHERMAN,null,null,null,fishing)),workPost,entity,npc.disguise());
        config.set("fisherman.shore-seconds",1);tick(0);tick(20);
        assertEquals(FishermanController.Result.FALLBACK,tick(21));assertTrue(boats.isEmpty());
    }
    @Test void stopWhileMountedExitsAtSafeDockAndCleansAllCosmetics() {
        long time=reachBoatPoint(launch());tick(++time);FishermanController.BobberVisual bobber=bobbers.getLast();
        controller.stop("fisher");assertNull(vehicle.get());verify(boats.getFirst()).remove();verify(bobber).remove();
        assertEquals(-.5,position.get().getX());assertEquals(64,position.get().getY());assertFalse(controller.active("fisher"));
        assertEquals(Material.STICK,hand.get().getType());assertEquals(Material.PAPER,entityHand.get().getType());
        assertEquals(.08f,chance.get());assertTrue(raised.get());
    }
    @Test void lostBoatPassengerFallsBackAndRemovesOwnedBoat() {
        launch();vehicle.set(null);assertEquals(FishermanController.Result.FALLBACK,tick(22));
        verify(boats.getFirst()).remove();assertFalse(controller.active("fisher"));
    }
    @Test void boatMovementFailureFallsBackAtSafeDock() {
        launch();boatTeleport=false;assertEquals(FishermanController.Result.FALLBACK,tick(22));
        assertNull(vehicle.get());assertEquals(-.5,position.get().getX());verify(boats.getFirst()).remove();
    }
    @Test void externalHandsAndRotationKeepTheirOwnershipOnStop() {
        tick(0);tick(1);hand.set(new ItemStack(Material.DIAMOND));entityHand.set(new ItemStack(Material.GOLD_INGOT));
        Location elsewhere=position.get().clone();elsewhere.setYaw(45);elsewhere.setPitch(10);position.set(elsewhere);bodyYaw.set(50f);
        controller.stop("fisher");assertEquals(Material.DIAMOND,hand.get().getType());assertEquals(Material.GOLD_INGOT,entityHand.get().getType());
        assertEquals(45,position.get().getYaw());assertEquals(50,bodyYaw.get());
    }
    @Test void unloadedBoatChunkFailsBeforeBlockReadsAndCleansPassenger() {
        launch();when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);
        assertEquals(FishermanController.Result.FALLBACK,tick(22));assertNull(vehicle.get());verify(boats.getFirst()).remove();
    }
    @Test void foreignMountIsPreservedRatherThanHijacked() {
        tick(0);Entity foreign=mock(Entity.class);vehicle.set(foreign);
        assertEquals(FishermanController.Result.FALLBACK,tick(1));assertSame(foreign,vehicle.get());
    }
    @Test void merchantPauseKeepsBoatAndNpcWherePlayerOpenedTrade() {
        long time=reachBoatPoint(launch());tick(++time);FishermanController.BobberVisual bobber=bobbers.getLast();
        Location before=position.get().clone();OakBoat boat=boats.getFirst();
        assertTrue(controller.pause("fisher"));assertEquals(before.getX(),position.get().getX());
        assertEquals(before.getZ(),position.get().getZ());assertSame(boat,vehicle.get());
        assertTrue(controller.ownsBoat(boat));assertTrue(controller.active("fisher"));verify(boat,never()).remove();
        verify(bobber).remove();assertEquals(Material.AIR,hand.get().getType());
        controller.stop("fisher");assertNull(vehicle.get());verify(boat).remove();assertEquals(-.5,position.get().getX());
    }
    @Test void newWallBlocksCastAndRemovesPartiallyCreatedVisuals() {
        tick(0);blocks.put(new Key(1,65,0),block(new Key(1,65,0),Material.STONE));
        assertEquals(FishermanController.Result.FALLBACK,tick(1));assertTrue(bobbers.isEmpty());
        assertFalse(controller.active("fisher"));assertEquals(Material.STICK,hand.get().getType());
    }
    @Test void smallDiagonalPushIntoBankFallsBackInsteadOfCorrectingThroughLand() {
        launch();OakBoat boat=boats.getFirst();
        boatLocations.getFirst().set(boatLocations.getFirst().get().clone().add(-.9,0,.3));
        clearInvocations(boat);
        assertEquals(FishermanController.Result.FALLBACK,tick(22));
        verify(boat,never()).teleport(any(Location.class),any(TeleportFlag[].class));
        verify(boat).remove();assertNull(vehicle.get());assertEquals(-.5,position.get().getX());
    }
    @Test void hitReactionKeepsBoatAndResumesFishingAtSamePoint() {
        long time=reachBoatPoint(launch());tick(++time);OakBoat boat=boats.getFirst();
        Location at=position.get().clone();FishermanController.BobberVisual bobber=bobbers.getLast();int before=bobbers.size();
        assertTrue(controller.suspend("fisher",time));verify(bobber).remove();
        Location reaction=position.get().clone();reaction.setYaw(80);reaction.setPitch(-10);position.set(reaction);
        hand.set(new ItemStack(Material.DIAMOND));entityHand.set(new ItemStack(Material.DIAMOND));
        assertTrue(controller.suspend("fisher",time+100));assertEquals(Material.DIAMOND,hand.get().getType());
        assertEquals(80,position.get().getYaw());assertEquals(-10,position.get().getPitch());
        assertSame(boat,vehicle.get());assertEquals(at.getX(),position.get().getX());assertEquals(at.getZ(),position.get().getZ());
        assertEquals("pescando en bote",controller.status("fisher"));verify(boat,never()).remove();
        assertTrue(controller.resume("fisher",time+200));tick(time+200);
        assertEquals("pescando en bote",controller.status("fisher"));assertSame(boat,vehicle.get());
        assertEquals(Material.FISHING_ROD,hand.get().getType());assertEquals(before+1,bobbers.size());
        assertEquals(at.getX(),position.get().getX());assertEquals(at.getZ(),position.get().getZ());
    }
    @Test void sailingDeadlineExcludesReactionPauseDuration() {
        config.set("fisherman.travel-timeout-seconds",3);long time=launch();
        assertTrue(controller.suspend("fisher",time));assertFalse(controller.travelExpired("fisher",time+1000));
        assertTrue(controller.resume("fisher",time+1000));assertFalse(controller.travelExpired("fisher",time+1059));
        assertTrue(controller.travelExpired("fisher",time+1060));
    }
    @Test void stopDuringReactionKeepsBorrowedHandAndCleansBoat() {
        long time=reachBoatPoint(launch());controller.suspend("fisher",time);
        hand.set(new ItemStack(Material.DIAMOND));entityHand.set(new ItemStack(Material.GOLD_INGOT));
        controller.stop("fisher");assertEquals(Material.DIAMOND,hand.get().getType());
        assertEquals(Material.GOLD_INGOT,entityHand.get().getType());assertNull(vehicle.get());verify(boats.getFirst()).remove();
    }
}
