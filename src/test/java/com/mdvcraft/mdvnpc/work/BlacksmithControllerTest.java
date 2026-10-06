package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.DoorController;
import com.mdvcraft.mdvnpc.routine.RoutineNavigator;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** State timings, Paper movement ownership and inventory-free visuals with live voxel geometry. */
class BlacksmithControllerTest {
    private record Key(int x,int y,int z) {}
    private MdvNpcPlugin plugin;
    private World world;
    private RoutineNavigator navigator;
    private BlacksmithController controller;
    private ActiveNpc npc;
    private PlayerWatcher watcher;
    private Villager entity;
    private EntityEquipment equipment;
    private YamlConfiguration config;
    private AtomicReference<Location> position;
    private AtomicReference<ItemStack> hand,entityHand;
    private AtomicReference<Float> dropChance;
    private AtomicReference<Float> bodyYaw;
    private AtomicBoolean raised;
    private final Map<Key,Block> blocks=new HashMap<>();
    private final List<ItemDisplay> displays=new ArrayList<>();
    private final List<Material> displayMaterials=new ArrayList<>();
    private boolean moving=true;
    private int waterLevel=3;
    private Location workPost;

    @BeforeEach void setup() {
        MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);world=mock(World.class);
        config=new YamlConfiguration();when(plugin.settings()).thenAnswer(call->Settings.parse(config));
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            Key key=new Key(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            return blocks.computeIfAbsent(key,ignored->block(key,key.y()==63?Material.STONE:Material.AIR));
        });
        blocks.put(new Key(10,64,0),block(new Key(10,64,0),Material.WATER_CAULDRON));
        blocks.put(new Key(15,64,0),block(new Key(15,64,0),Material.ANVIL));
        workPost=new Location(world,.5,64,.5,35,7);position=new AtomicReference<>(workPost.clone());
        entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(call->position.get().clone());bodyYaw=new AtomicReference<>(20f);
        when(entity.getBodyYaw()).thenAnswer(call->bodyYaw.get());
        doAnswer(call->{Location rotated=position.get().clone();rotated.setYaw(call.getArgument(0));rotated.setPitch(call.getArgument(1));position.set(rotated);return null;})
                .when(entity).setRotation(anyFloat(),anyFloat());
        doAnswer(call->{bodyYaw.set(call.getArgument(0));return null;}).when(entity).setBodyYaw(anyFloat());
        equipment=mock(EntityEquipment.class);when(entity.getEquipment()).thenReturn(equipment);
        entityHand=new AtomicReference<>(new ItemStack(Material.PAPER));
        when(equipment.getItemInMainHand()).thenAnswer(call->entityHand.get());
        doAnswer(call->{entityHand.set(((ItemStack)call.getArgument(0)).clone());return null;})
                .when(equipment).setItemInMainHand(any(ItemStack.class),eq(true));
        dropChance=new AtomicReference<>(.08f);
        when(equipment.getItemInMainHandDropChance()).thenAnswer(call->dropChance.get());
        doAnswer(call->{dropChance.set(call.getArgument(0));return null;}).when(equipment).setItemInMainHandDropChance(anyFloat());
        PlayerDisguise disguise=mock(PlayerDisguise.class);watcher=mock(PlayerWatcher.class);when(disguise.getWatcher()).thenReturn(watcher);
        hand=new AtomicReference<>(new ItemStack(Material.STICK));when(watcher.getItemInMainHand()).thenAnswer(call->hand.get());
        raised=new AtomicBoolean(true);when(watcher.isMainHandRaised()).thenAnswer(call->raised.get());
        doAnswer(call->{raised.set(call.getArgument(0));return null;}).when(watcher).setMainHandRaised(anyBoolean());
        doAnswer(call->{ItemStack item=call.getArgument(0);hand.set(item==null?null:item.clone());return null;})
                .when(watcher).setItemInMainHand(any());
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("npcs.smith.location.world","world");yaml.set("npcs.smith.location.y",64);
        yaml.set("npcs.smith.mode","shop");NpcDefinition base=NpcParser.parse(yaml).get("smith");
        npc=new ActiveNpc(definition(base,complete()),workPost.clone(),entity,disguise);
        navigator=mock(RoutineNavigator.class);
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            if(!moving)return RoutineNavigator.Result.WAITING;
            position.set(((Location)call.getArgument(1)).clone());return RoutineNavigator.Result.ARRIVED;
        });
        when(world.spawn(any(Location.class),eq(ItemDisplay.class),any(Consumer.class))).thenAnswer(call->{
            ItemDisplay display=mock(ItemDisplay.class);when(display.isValid()).thenReturn(true);when(display.teleport(any(Location.class))).thenReturn(true);
            displays.add(display);
            doAnswer(itemCall->{displayMaterials.add(((ItemStack)itemCall.getArgument(0)).getType());return null;})
                    .when(display).setItemStack(any());
            Consumer<ItemDisplay> configure=call.getArgument(2);configure.accept(display);return display;
        });
        controller=new BlacksmithController(plugin,navigator,mock(DoorController.class),ItemStack::new);
    }
    @AfterEach void cleanup() {try{controller.clear();}finally{MockBukkit.unmock();}}

    private ShopWorkDefinition complete() {
        return new ShopWorkDefinition(ShopWorkDefinition.Category.BLACKSMITH,
                new ShopWorkDefinition.Station(world.getUID(),"world",5,64,0),
                new ShopWorkDefinition.Station(world.getUID(),"world",10,64,0),
                new ShopWorkDefinition.Station(world.getUID(),"world",15,64,0));
    }
    private static NpcDefinition definition(NpcDefinition base,ShopWorkDefinition work) {
        return new NpcDefinition(base.id(),base.enabled(),base.name(),base.nameVisible(),base.position(),base.skin(),base.look(),
                base.dialogue(),base.interaction(),base.mode(),base.tradeDialogue(),base.traits(),base.speech(),work);
    }
    private Block block(Key key,Material material) {
        Block block=mock(Block.class);when(block.getType()).thenReturn(material);
        when(block.getWorld()).thenReturn(world);when(block.getX()).thenReturn(key.x());when(block.getY()).thenReturn(key.y());when(block.getZ()).thenReturn(key.z());
        VoxelShape shape=mock(VoxelShape.class);
        when(shape.getBoundingBoxes()).thenReturn(material.isAir()?List.of():List.of(new BoundingBox(0,0,0,1,1,1)));
        when(block.getCollisionShape()).thenReturn(shape);
        if(material==Material.WATER_CAULDRON) {
            Levelled level=mock(Levelled.class);when(level.getLevel()).thenAnswer(call->waterLevel);when(block.getBlockData()).thenReturn(level);
        }
        return block;
    }
    private BlacksmithController.Result tick(long time) {return controller.tick(npc,workPost,.2,time,2);}

    @Test void completeLoopUsesTwoFullTwoMinuteAnvilStagesAndVanillaMace() {
        assertEquals(BlacksmithController.Result.RUNNING,tick(0));assertEquals("trabajando en fundición",controller.status("smith"));
        assertEquals(Material.RAW_IRON,hand.get().getType());assertEquals(List.of(Material.RAW_IRON),displayMaterials);
        tick(160);assertEquals(Material.IRON_INGOT,hand.get().getType());assertTrue(controller.traveling("smith"));
        tick(161);assertEquals("trabajando en caldero",controller.status("smith"));assertEquals(Material.AIR,hand.get().getType());
        tick(261);tick(262);assertEquals("trabajando en yunque",controller.status("smith"));assertEquals(Material.MACE,hand.get().getType());
        tick(2661);assertFalse(controller.traveling("smith"));assertEquals("trabajando en yunque",controller.status("smith"));
        tick(2662);assertEquals("caminando a caldero",controller.status("smith"));assertEquals(Material.IRON_SWORD,hand.get().getType());
        tick(2663);assertEquals(Material.IRON_SWORD,displayMaterials.getLast());
        tick(2763);tick(2764);assertEquals(Material.MACE,hand.get().getType());
        tick(5163);assertEquals("trabajando en yunque",controller.status("smith"));
        tick(5164);assertEquals("caminando a fundición",controller.status("smith"));
        tick(5165);assertEquals("trabajando en fundición",controller.status("smith"));
        assertTrue(displayMaterials.containsAll(List.of(Material.RAW_IRON,Material.IRON_INGOT,Material.IRON_SWORD)));
        verify(entity,atLeast(2)).swingMainHand();
    }

    @Test void anvilPlaceSoundsOnlyAtHammerSwingsAndNeverWhenLeavingAnvil() {
        tick(0);tick(160);tick(161);tick(261);tick(262);
        verify(world).playSound(any(Location.class),eq(Sound.BLOCK_ANVIL_PLACE),eq(.6f),eq(1f));
        clearInvocations(entity,world);
        tick(270);tick(281);
        verify(entity,never()).swingMainHand();
        verify(world,never()).playSound(any(Location.class),eq(Sound.BLOCK_ANVIL_PLACE),anyFloat(),anyFloat());
        tick(282);
        verify(entity).swingMainHand();
        verify(world).playSound(any(Location.class),eq(Sound.BLOCK_ANVIL_PLACE),eq(.6f),eq(1f));
        clearInvocations(entity,world);
        tick(2662);
        assertEquals("caminando a caldero",controller.status("smith"));
        verify(entity,never()).swingMainHand();
        verify(world,never()).playSound(any(Location.class),any(Sound.class),anyFloat(),anyFloat());
        tick(2663);
        verify(world,never()).playSound(any(Location.class),eq(Sound.BLOCK_ANVIL_PLACE),anyFloat(),anyFloat());
        verify(world,never()).playSound(any(Location.class),eq(Sound.BLOCK_ANVIL_USE),anyFloat(),anyFloat());
    }


    @Test void anvilHitVolumeAndPitchAreConfigurable() {
        config.set("blacksmith.anvil-hit-volume",1.25);config.set("blacksmith.anvil-hit-pitch",.75);
        tick(0);tick(160);tick(161);tick(261);tick(262);
        verify(world).playSound(any(Location.class),eq(Sound.BLOCK_ANVIL_PLACE),eq(1.25f),eq(.75f));
    }

    @Test void smelteryAcceptsAirAndApproachKeepsOneWholeBlockGap() {
        tick(0);ArgumentCaptor<Location> approach=ArgumentCaptor.forClass(Location.class);
        verify(navigator).move(eq(npc),approach.capture(),eq(.2),eq(0L),eq(2));
        assertEquals(3.5,approach.getValue().getX());assertEquals(64,approach.getValue().getY());
        assertEquals(Material.AIR,world.getBlockAt(5,64,0).getType());
    }

    @Test void rawIronIsConsumedAtStationWithoutCreatingPickupEntities() {
        tick(0);ItemDisplay iron=displays.getFirst();tick(8);verify(iron).teleport(any(Location.class));
        tick(16);verify(iron).remove();tick(20);
        assertEquals(List.of(Material.RAW_IRON,Material.RAW_IRON),displayMaterials);
        verify(iron).setPersistent(false);verify(iron).setGravity(false);
        verify(world,never()).dropItem(any(Location.class),any(ItemStack.class));
        verify(world,never()).dropItemNaturally(any(Location.class),any(ItemStack.class));
    }

    @Test void stopRestoresBothHandsRaisedStateAndRemovesEveryDisplay() {
        tick(0);assertEquals(Material.RAW_IRON,entityHand.get().getType());controller.stop("smith");
        assertEquals(Material.STICK,hand.get().getType());assertEquals(Material.PAPER,entityHand.get().getType());
        assertEquals(.08f,dropChance.get());
        verify(watcher).setMainHandRaised(true);for(ItemDisplay display:displays)verify(display).remove();
        verify(entity).setRotation(35,7);verify(entity).setBodyYaw(20);
        assertFalse(controller.active("smith"));assertEquals("",controller.status("smith"));
    }

    @Test void missingStationFallsBackWithoutMovingOrReplacingHand() {
        npc=new ActiveNpc(definition(npc.definition(),new ShopWorkDefinition(ShopWorkDefinition.Category.BLACKSMITH,null,complete().cauldron(),complete().anvil())),
                npc.anchor(),entity,npc.disguise());
        assertEquals(BlacksmithController.Result.FALLBACK,tick(0));verifyNoInteractions(navigator);
        assertEquals(Material.STICK,hand.get().getType());
    }

    @Test void removedWaterOrAnvilDuringWorkStopsAndRestoresShopAppearance() {
        tick(0);waterLevel=0;assertEquals(BlacksmithController.Result.FALLBACK,tick(1));
        assertEquals(Material.STICK,hand.get().getType());assertFalse(controller.active("smith"));
        for(ItemDisplay display:displays)verify(display).remove();
    }

    @Test void unloadedStationNeverLoadsChunkOrReadsItsBlock() {
        when(world.isChunkLoaded(0,0)).thenReturn(false);clearInvocations(world);
        assertEquals(BlacksmithController.Result.FALLBACK,tick(0));
        verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());verifyNoInteractions(navigator);
    }

    @Test void wrongWorldStationFallsBack() {
        ShopWorkDefinition current=complete();ShopWorkDefinition other=new ShopWorkDefinition(current.category(),
                new ShopWorkDefinition.Station(UUID.randomUUID(),"world",5,64,0),current.cauldron(),current.anvil());
        npc=new ActiveNpc(definition(npc.definition(),other),npc.anchor(),entity,npc.disguise());
        assertEquals(BlacksmithController.Result.FALLBACK,tick(0));verifyNoInteractions(navigator);
    }

    @Test void allBlockedApproachesFallBackWithoutTeleportingThroughWalls() {
        for(int[] d:new int[][]{{0,2},{2,0},{0,-2},{-2,0}})
            for(int y=64;y<=66;y++)blocks.put(new Key(5+d[0],y,d[1]),block(new Key(5+d[0],y,d[1]),Material.STONE));
        assertEquals(BlacksmithController.Result.FALLBACK,tick(0));verify(navigator,never()).move(any(),any(),anyDouble(),anyLong(),anyInt());
        assertEquals(Material.STICK,hand.get().getType());
    }

    @Test void unreachableApproachesRetryOtherSidesAndStopWithinBoundedTime() {
        moving=false;tick(0);assertTrue(controller.traveling("smith"));
        tick(160);tick(320);tick(480);assertEquals(BlacksmithController.Result.FALLBACK,tick(600));
        ArgumentCaptor<Location> approach=ArgumentCaptor.forClass(Location.class);
        verify(navigator,times(4)).move(eq(npc),approach.capture(),anyDouble(),anyLong(),anyInt());
        assertEquals(4,approach.getAllValues().stream().map(Location::toVector).distinct().count());
        assertFalse(controller.active("smith"));assertEquals(Material.STICK,hand.get().getType());assertTrue(displays.isEmpty());
    }

    @Test void falseArrivedResultCannotStartRemoteEffects() {
        moving=false;when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenReturn(RoutineNavigator.Result.ARRIVED);
        tick(0);assertTrue(controller.traveling("smith"));assertTrue(displays.isEmpty());
        assertEquals(Material.STICK,hand.get().getType());
    }

    @Test void initialAnimationRequiresArrivalAtOrdinaryWorkPost() {
        position.set(new Location(world,100,64,100));assertEquals(BlacksmithController.Result.FALLBACK,tick(0));
        verifyNoInteractions(navigator);assertTrue(displays.isEmpty());
    }

    @Test void movedNpcUsesNavigatorAgainAndDoesNotTeleportBack() {
        tick(0);position.set(position.get().clone().add(0,0,2));tick(2);
        assertTrue(controller.traveling("smith"));assertEquals(2.5,position.get().getZ());
        tick(4);assertFalse(controller.traveling("smith"));assertEquals(.5,position.get().getZ());
        verify(entity,never()).teleport(any(Location.class));
    }

    @Test void partialSpawnFailureRemovesAlreadyCreatedDisplayAndRestoresHands() {
        when(world.spawn(any(Location.class),eq(ItemDisplay.class),any(Consumer.class))).thenAnswer(call->{
            ItemDisplay display=mock(ItemDisplay.class);displays.add(display);
            doThrow(new IllegalStateException("display failure")).when(display).setItemStack(any());
            Consumer<ItemDisplay> configure=call.getArgument(2);configure.accept(display);return display;
        });
        assertEquals(BlacksmithController.Result.FALLBACK,tick(0));verify(displays.getFirst()).remove();
        assertEquals(Material.STICK,hand.get().getType());assertEquals(Material.PAPER,entityHand.get().getType());assertFalse(controller.active("smith"));
    }

    @Test void configDurationsAreAppliedWithoutChangingDefaultStageOrder() {
        config.set("blacksmith.smelt-seconds",1);config.set("blacksmith.quench-seconds",1);config.set("blacksmith.anvil-seconds",1);
        tick(0);tick(20);tick(21);tick(41);tick(42);tick(62);tick(63);tick(83);tick(84);tick(104);tick(105);
        assertEquals("trabajando en fundición",controller.status("smith"));
        assertEquals(List.of(Material.RAW_IRON,Material.RAW_IRON,Material.IRON_INGOT,Material.IRON_SWORD,Material.RAW_IRON),displayMaterials);
    }

    @Test void clearStopsMultipleOwnersAndLeavesNoCosmeticEntities() {
        tick(0);controller.clear();controller.clear();assertFalse(controller.active("smith"));
        for(ItemDisplay display:displays)verify(display,times(1)).remove();
    }

    @Test void serviceCanDetectTravelTimeoutWhileFloorRecoverySuppressesControllerTicks() {
        moving=false;tick(0);
        assertFalse(controller.travelExpired("smith",599));assertTrue(controller.travelExpired("smith",600));
        assertFalse(controller.travelExpired("missing",600));controller.stop("smith");
        assertFalse(controller.travelExpired("smith",600));
    }

    @Test void removedFloorWhileHammeringFallsBackAndReleasesAllVisuals() {
        tick(0);tick(160);tick(161);tick(261);tick(262);
        assertEquals("trabajando en yunque",controller.status("smith"));
        Location feet=position.get();Key floor=new Key(feet.getBlockX(),63,feet.getBlockZ());
        blocks.put(floor,block(floor,Material.AIR));
        assertEquals(BlacksmithController.Result.FALLBACK,tick(264));
        assertEquals(Material.STICK,hand.get().getType());assertFalse(controller.active("smith"));
    }

    @Test void embeddedObstacleDuringStationaryAnimationFallsBack() {
        tick(0);Location feet=position.get();Key obstacle=new Key(feet.getBlockX(),64,feet.getBlockZ());
        blocks.put(obstacle,block(obstacle,Material.STONE));
        assertEquals(BlacksmithController.Result.FALLBACK,tick(2));assertEquals(Material.STICK,hand.get().getType());
    }

    @Test void stopPreservesExternallyReplacedHandsWhileStillRemovingOwnedVisuals() {
        tick(0);assertEquals(0f,dropChance.get());
        hand.set(new ItemStack(Material.BOOK));entityHand.set(new ItemStack(Material.APPLE));raised.set(true);dropChance.set(.2f);
        controller.stop("smith");
        assertEquals(Material.BOOK,hand.get().getType());assertEquals(Material.APPLE,entityHand.get().getType());
        assertTrue(raised.get());assertEquals(.2f,dropChance.get());
        for(ItemDisplay display:displays)verify(display).remove();
    }

    @Test void stopPreservesExternallyChangedHeadAndBodyRotation() {
        tick(0);Location external=position.get().clone();external.setYaw(15);external.setPitch(25);position.set(external);bodyYaw.set(30f);
        controller.stop("smith");assertEquals(15,position.get().getYaw());assertEquals(25,position.get().getPitch());assertEquals(30f,bodyYaw.get());
        assertEquals(Material.STICK,hand.get().getType());
    }
}
