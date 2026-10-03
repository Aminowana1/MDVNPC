package com.mdvcraft.mdvnpc.routine;

import com.github.retrooper.packetevents.util.Vector3i;
import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import me.libraryaddict.disguise.DisguiseConfig;
import me.libraryaddict.disguise.disguisetypes.DisguiseInternals;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Legacy name offsets no longer replace or reposition LibsDisguises names; seat protection remains. */
class RoutineNameOffsetTest {
    MdvNpcPlugin plugin;World world;Block furniture;ArmorStand seat;Villager entity;PlayerDisguise disguise;PlayerWatcher watcher;
    RoutineVisuals visuals;ActiveNpc npc;YamlConfiguration config,npcConfig;
    List<Location> teleports=new ArrayList<>();
    AtomicReference<Location> position=new AtomicReference<>(),seatPosition=new AtomicReference<>();
    AtomicReference<Vector3i> bedPosition=new AtomicReference<>();AtomicBoolean sleeping=new AtomicBoolean();
    AtomicReference<Entity> vehicle=new AtomicReference<>();
    AtomicBoolean seatRemoved=new AtomicBoolean(),seatVisible=new AtomicBoolean(true),seatInvisible=new AtomicBoolean(),seatGlowing=new AtomicBoolean();
    AtomicBoolean seatMarker=new AtomicBoolean(),seatSmall=new AtomicBoolean(),seatGravity=new AtomicBoolean(true),seatBasePlate=new AtomicBoolean(true),seatArms=new AtomicBoolean(),seatNameVisible=new AtomicBoolean();
    UUID worldId=UUID.randomUUID();

    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("MDVNPC-test");when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        config=new YamlConfiguration();when(plugin.settings()).thenAnswer(i->Settings.parse(config));
        world=mock(World.class);when(world.getUID()).thenReturn(worldId);when(world.getName()).thenReturn("world");when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        furniture=mock(Block.class);when(furniture.getLocation()).thenAnswer(i->new Location(world,0,64,0));when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(furniture);when(world.getBlockAt(any(Location.class))).thenReturn(furniture);
        entity=mock(Villager.class);when(entity.getWorld()).thenReturn(world);position.set(new Location(world,.5,64,1.5));when(entity.getLocation()).thenAnswer(i->position.get().clone());
        disguise=mock(PlayerDisguise.class);watcher=mock(PlayerWatcher.class);when(disguise.getWatcher()).thenReturn(watcher);
        when(disguise.getName()).thenReturn("§6Lorenzo");when(disguise.isNameVisible()).thenReturn(true);when(watcher.getNameYModifier()).thenReturn(.35f);
        when(watcher.isSleeping()).thenAnswer(i->sleeping.get());doAnswer(i->{sleeping.set(i.getArgument(0));return null;}).when(watcher).setSleeping(anyBoolean());
        when(watcher.getBedPosition()).thenAnswer(i->bedPosition.get());doAnswer(i->{bedPosition.set(i.getArgument(0));return null;}).when(watcher).setBedPosition(any(Vector3i.class));
        seat=mock(ArmorStand.class);when(seat.isValid()).thenAnswer(i->!seatRemoved.get());when(seat.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(entity.getVehicle()).thenAnswer(i->vehicle.get());when(seat.addPassenger(entity)).thenAnswer(i->{vehicle.set(seat);return true;});when(entity.leaveVehicle()).thenAnswer(i->{vehicle.set(null);return true;});
        doAnswer(i->{seatRemoved.set(true);if(vehicle.get()==seat)vehicle.set(null);return null;}).when(seat).remove();
        when(seat.isVisible()).thenAnswer(i->seatVisible.get());doAnswer(i->{seatVisible.set(i.getArgument(0));return null;}).when(seat).setVisible(anyBoolean());
        when(seat.isInvisible()).thenAnswer(i->seatInvisible.get());doAnswer(i->{boolean value=i.getArgument(0);seatInvisible.set(value);seatVisible.set(!value);return null;}).when(seat).setInvisible(anyBoolean());
        when(seat.isGlowing()).thenAnswer(i->seatGlowing.get());doAnswer(i->{seatGlowing.set(i.getArgument(0));return null;}).when(seat).setGlowing(anyBoolean());
        when(seat.isMarker()).thenAnswer(i->seatMarker.get());doAnswer(i->{seatMarker.set(i.getArgument(0));return null;}).when(seat).setMarker(anyBoolean());
        when(seat.isSmall()).thenAnswer(i->seatSmall.get());doAnswer(i->{seatSmall.set(i.getArgument(0));return null;}).when(seat).setSmall(anyBoolean());
        when(seat.hasGravity()).thenAnswer(i->seatGravity.get());doAnswer(i->{seatGravity.set(i.getArgument(0));return null;}).when(seat).setGravity(anyBoolean());
        when(seat.hasBasePlate()).thenAnswer(i->seatBasePlate.get());doAnswer(i->{seatBasePlate.set(i.getArgument(0));return null;}).when(seat).setBasePlate(anyBoolean());
        when(seat.hasArms()).thenAnswer(i->seatArms.get());doAnswer(i->{seatArms.set(i.getArgument(0));return null;}).when(seat).setArms(anyBoolean());
        when(seat.isCustomNameVisible()).thenAnswer(i->seatNameVisible.get());doAnswer(i->{seatNameVisible.set(i.getArgument(0));return null;}).when(seat).setCustomNameVisible(anyBoolean());
        when(world.spawn(any(Location.class),eq(ArmorStand.class),any(Consumer.class))).thenAnswer(i->{seatRemoved.set(false);seatPosition.set(((Location)i.getArgument(0)).clone());((Consumer<ArmorStand>)i.getArgument(2)).accept(seat);return seat;});
        npcConfig=new YamlConfiguration();npcConfig.set("npcs.actor.location.world","world");npcConfig.set("npcs.actor.name","&6Lorenzo");npcConfig.set("npcs.actor.speech.prefix","&8[Taberna] {npc} »");
        npc=new ActiveNpc(NpcParser.parse(npcConfig).get("actor"),position.get().clone(),entity,disguise);
        visuals=new RoutineVisuals(plugin,(n,target)->{teleports.add(target.clone());position.set(target.clone());return true;});
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    private RoutineVisuals.Pose enterRaw(RoutineGoal.Type type){
        return enterRaw(type,new Location(world,.5,64,1.5));
    }
    private RoutineVisuals.Pose enterRaw(RoutineGoal.Type type,Location exit){
        if(type==RoutineGoal.Type.SLEEP){Bed bed=mock(Bed.class);when(bed.getFacing()).thenReturn(BlockFace.NORTH);when(furniture.getBlockData()).thenReturn(bed);}
        else{Stairs stairs=mock(Stairs.class);when(stairs.getHalf()).thenReturn(Bisected.Half.BOTTOM);when(stairs.getFacing()).thenReturn(BlockFace.NORTH);when(furniture.getBlockData()).thenReturn(stairs);}
        var point=new RoutineGoal.Point(worldId,0,64,0,0);var goal=new RoutineGoal(1,type,RoutineGoal.WalkMode.CYCLE,0,0,2.4,20,List.of(point));
        return visuals.enter(npc,goal,point,exit,0);
    }
    private RoutineVisuals.Pose enter(RoutineGoal.Type type){var pose=enterRaw(type);assertNotNull(pose);pose.nextMeal=Long.MAX_VALUE;return pose;}

    @ParameterizedTest @EnumSource(DisguiseConfig.PlayerNameType.class) @SuppressWarnings("unchecked")
    void legacyNameOffsetsNeverAlterTheOriginalNameForAnyLibsDisguisesRenderer(DisguiseConfig.PlayerNameType mode){
        var internals=mock(DisguiseInternals.class);when(disguise.getInternals()).thenReturn(internals);when(internals.getNameDisplayType()).thenReturn(mode);
        config.set("routines.name-offset-seated-y",1.25);config.set("routines.name-offset-sleeping-y",-.75);
        for(boolean visible:List.of(false,true)){
            npcConfig.set("npcs.actor.name-visible",visible);when(disguise.isNameVisible()).thenReturn(visible);
            npc=new ActiveNpc(NpcParser.parse(npcConfig).get("actor"),position.get().clone(),entity,disguise);
            var seated=enter(RoutineGoal.Type.SIT);for(int tick=1;tick<=40;tick++)visuals.tick(seated,tick);visuals.suspend(seated,50);visuals.leave(seated,true);
            var asleep=enter(RoutineGoal.Type.SLEEP);visuals.tick(asleep,60);visuals.suspend(asleep,70);assertTrue(visuals.restoreSleep(asleep,true));visuals.leave(asleep,true);
            assertEquals("§6Lorenzo",disguise.getName());assertEquals(visible,disguise.isNameVisible());assertEquals(.35f,watcher.getNameYModifier());
            assertEquals("&8[Taberna] {npc} »",npc.definition().speech().prefix());assertEquals(visible,npc.definition().nameVisible());
        }
        verify(disguise,never()).setNameVisible(anyBoolean());verify(disguise,never()).setName(anyString());verify(watcher,never()).setNameYModifier(anyFloat());
        verify(watcher,never()).setName(anyString());verify(watcher,never()).setNameVisible(anyBoolean());verify(world,never()).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));
    }
    @Test void defaultAndExplicitLegacySeatHeightsRemainSupported(){
        var normal=enter(RoutineGoal.Type.SIT);assertEquals(64.5,seatPosition.get().getY());visuals.leave(normal,false);
        config.set("routines.seat-offset-y",0);var legacy=enter(RoutineGoal.Type.SIT);assertEquals(64,seatPosition.get().getY());visuals.leave(legacy,false);
    }
    @Test void sleepingMetadataIsRepairedAndClearedWhileTheNameRemainsUntouched(){
        var pose=enter(RoutineGoal.Type.SLEEP);assertTrue(sleeping.get());visuals.suspend(pose,20);sleeping.set(false);bedPosition.set(null);
        assertTrue(visuals.restoreSleep(pose,false));assertTrue(sleeping.get());assertNotNull(bedPosition.get());visuals.leave(pose,true);assertFalse(sleeping.get());
        verify(disguise,never()).setNameVisible(anyBoolean());verify(watcher,never()).setNameYModifier(anyFloat());
    }
    @Test @SuppressWarnings("unchecked") void spawnListenerVisibilityChangesAreRepairedBeforeMounting(){
        when(world.spawn(any(Location.class),eq(ArmorStand.class),any(Consumer.class))).thenAnswer(i->{((Consumer<ArmorStand>)i.getArgument(2)).accept(seat);seatVisible.set(true);seatInvisible.set(false);seatGlowing.set(true);seatMarker.set(false);return seat;});
        doAnswer(i->{assertFalse(seatVisible.get());assertTrue(seatInvisible.get());assertFalse(seatGlowing.get());assertTrue(seatMarker.get());vehicle.set(seat);return true;}).when(seat).addPassenger(entity);
        var pose=enter(RoutineGoal.Type.SIT);assertTrue(seatInvisible.get());visuals.leave(pose,false);assertTrue(seatRemoved.get());assertNull(vehicle.get());
    }
    @Test void hiddenSeatRepairsChangedFlagsAndSendsNoUpdatesWhenStable(){
        var pose=enter(RoutineGoal.Type.SIT);clearInvocations(seat);
        for(int tick=1;tick<=40;tick++)visuals.tick(pose,tick);verify(seat,never()).setInvisible(anyBoolean());verify(seat,never()).setVisible(anyBoolean());verify(seat,never()).setMarker(anyBoolean());
        seatVisible.set(true);seatInvisible.set(false);seatGlowing.set(true);seatMarker.set(false);seatSmall.set(false);seatGravity.set(true);seatBasePlate.set(true);seatArms.set(true);seatNameVisible.set(true);
        visuals.tick(pose,41);assertFalse(seatVisible.get());assertTrue(seatInvisible.get());assertFalse(seatGlowing.get());assertTrue(seatMarker.get());assertTrue(seatSmall.get());assertFalse(seatGravity.get());assertFalse(seatBasePlate.get());assertFalse(seatArms.get());assertFalse(seatNameVisible.get());
        visuals.tick(pose,42);verify(seat).setInvisible(true);verify(seat).setVisible(false);verify(seat).setGlowing(false);verify(seat).setMarker(true);visuals.leave(pose,false);
    }
    @Test void dormantSeatRepairsVisibilityDuringSuspensionWithoutRemovingItsMount(){
        var pose=enter(RoutineGoal.Type.SIT);seatVisible.set(true);seatInvisible.set(false);visuals.suspend(pose,40);
        assertTrue(seatInvisible.get());assertFalse(seatVisible.get());assertSame(seat,vehicle.get());verify(seat,never()).remove();visuals.leave(pose,false);
    }
    @Test void failedMountAndRotationCannotLeaveAnUntrackedSupport(){
        doAnswer(i->{vehicle.set(seat);throw new IllegalStateException("Mount callback");}).when(seat).addPassenger(entity);
        assertThrows(IllegalStateException.class,()->enterRaw(RoutineGoal.Type.SIT));assertTrue(seatRemoved.get());assertNull(vehicle.get());
        doAnswer(i->{vehicle.set(seat);return true;}).when(seat).addPassenger(entity);doThrow(new IllegalStateException("Rotation callback")).when(entity).setRotation(anyFloat(),anyFloat());
        assertThrows(IllegalStateException.class,()->enterRaw(RoutineGoal.Type.SIT));assertTrue(seatRemoved.get());assertNull(vehicle.get());verify(seat,times(2)).remove();
    }
    @Test void failedSeatSpawnConfigurationRemovesSupportBeforeItCanBecomeAnOrphan(){
        doThrow(new IllegalStateException("Spawn callback")).when(seat).setMarker(true);assertThrows(IllegalStateException.class,()->enterRaw(RoutineGoal.Type.SIT));
        assertTrue(seatRemoved.get());verify(seat).remove();verify(seat,never()).addPassenger(any());
    }
    @Test void refusedMountRemovesOnlyTheUnusedSupport(){
        doReturn(false).when(seat).addPassenger(entity);assertNull(enterRaw(RoutineGoal.Type.SIT));assertTrue(seatRemoved.get());verify(entity,never()).leaveVehicle();
    }
    @Test void failedMealRestorationStillRemovesTheSeatWithoutChangingNameVisibility(){
        var pose=enter(RoutineGoal.Type.SIT);pose.mealUntil=10;doThrow(new IllegalStateException("Item metadata")).when(watcher).setMainHandRaised(false);
        assertThrows(IllegalStateException.class,()->visuals.leave(pose,false));assertTrue(seatRemoved.get());assertNull(vehicle.get());assertNull(pose.seat);verify(disguise,never()).setNameVisible(anyBoolean());
    }
    @Test void failedDismountStillRemovesTheSeatAndNormalLeavingIsIdempotent(){
        var pose=enter(RoutineGoal.Type.SIT);doThrow(new IllegalStateException("Dismount callback")).when(entity).leaveVehicle();
        assertThrows(IllegalStateException.class,()->visuals.leave(pose,false));assertTrue(seatRemoved.get());assertNull(pose.seat);visuals.leave(pose,false);verify(seat).remove();
    }
    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"SIT","SLEEP"}) @SuppressWarnings("unchecked")
    void staleArrivalCannotMountOrSleepFromADistantPosition(RoutineGoal.Type type){
        position.set(new Location(world,20.5,64,1.5));assertNull(enterRaw(type));assertTrue(teleports.isEmpty());
        verify(world,never()).spawn(any(Location.class),eq(ArmorStand.class),any(Consumer.class));verify(watcher,never()).setSleeping(anyBoolean());
    }
    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"SIT","SLEEP"})
    void checkedDoorwayMustActuallyBeReachedBeforeEnteringFurniture(RoutineGoal.Type type){
        position.set(new Location(world,.5,64,2.09));var pose=enter(type);visuals.leave(pose,true);
        position.set(new Location(world,.5,64,2.11));teleports.clear();assertNull(enterRaw(type));assertTrue(teleports.isEmpty());
    }
    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"SIT","SLEEP"}) @SuppressWarnings("unchecked")
    void remoteOrForeignDoorwayCannotAuthorizeAFurnitureTeleport(RoutineGoal.Type type){
        position.set(new Location(world,20.5,64,20.5));assertNull(enterRaw(type,position.get().clone()));
        position.set(new Location(world,.5,64,1.5));assertNull(enterRaw(type,null));
        assertNull(enterRaw(type,new Location(mock(World.class),.5,64,1.5)));assertTrue(teleports.isEmpty());
        verify(world,never()).spawn(any(Location.class),eq(ArmorStand.class),any(Consumer.class));verify(watcher,never()).setSleeping(anyBoolean());
    }
    @ParameterizedTest @ValueSource(doubles={-4,0,.5,4})
    void configuredSeatHeightsStillReturnLocallyWhenMounted(double height){
        config.set("routines.seat-offset-y",height);var pose=enter(RoutineGoal.Type.SIT);
        position.set(seatPosition.get().clone().add(0,.4,0));visuals.leave(pose,true);
        assertEquals(pose.exit,position.get());assertEquals(1,teleports.size());assertTrue(seatRemoved.get());assertNull(vehicle.get());assertTrue(seatInvisible.get());
    }
    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"SIT","SLEEP"})
    void leavingADisplacedPoseCleansItWithoutWarpingToItsOldDoorway(RoutineGoal.Type type){
        var pose=enter(type);teleports.clear();vehicle.set(null);Location displaced=new Location(world,25.5,64,.5);position.set(displaced.clone());
        visuals.leave(pose,true);assertEquals(displaced,position.get());assertTrue(teleports.isEmpty());
        if(type==RoutineGoal.Type.SIT){assertTrue(seatRemoved.get());assertNull(pose.seat);}else assertFalse(sleeping.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void seatCleanupCannotWarpAVerticallyDisplacedNpcEvenIfItsVehicleReferenceRemains(boolean mounted){
        var pose=enter(RoutineGoal.Type.SIT);if(!mounted)vehicle.set(null);Location displaced=new Location(world,.5,68.5,.5);position.set(displaced.clone());
        visuals.leave(pose,true);assertEquals(displaced,position.get());assertTrue(teleports.isEmpty());assertTrue(seatRemoved.get());assertNull(vehicle.get());
    }
    @Test void aDismountedNpcDoesNotInheritTheConfiguredChairHeightTolerance(){
        config.set("routines.seat-offset-y",4);var pose=enter(RoutineGoal.Type.SIT);vehicle.set(null);Location displaced=new Location(world,.5,68.4,.5);position.set(displaced.clone());
        visuals.leave(pose,true);assertEquals(displaced,position.get());assertTrue(teleports.isEmpty());assertTrue(seatRemoved.get());
    }
    @ParameterizedTest @CsvSource({"20.5,64.5625,.5",".5,68,.5"})
    void distantSleeperIsNotTeleportedBackByPeriodicOrForcedPoseRestoration(double x,double y,double z){
        var pose=enter(RoutineGoal.Type.SLEEP);teleports.clear();clearInvocations(watcher);Location displaced=new Location(world,x,y,z);position.set(displaced.clone());
        assertFalse(visuals.restoreSleep(pose,false));assertFalse(visuals.restoreSleep(pose,true));assertEquals(displaced,position.get());assertTrue(teleports.isEmpty());
        verify(watcher,never()).setSleeping(anyBoolean());visuals.leave(pose,true);assertEquals(displaced,position.get());assertTrue(teleports.isEmpty());assertFalse(sleeping.get());
    }
    @Test void smallSleepPoseDriftStillRepairsInPlace(){
        var pose=enter(RoutineGoal.Type.SLEEP);teleports.clear();position.set(pose.sleepingLocation.clone().add(.4,.3,0));sleeping.set(false);
        assertTrue(visuals.restoreSleep(pose,false));assertEquals(pose.sleepingLocation,position.get());assertEquals(1,teleports.size());assertTrue(sleeping.get());visuals.leave(pose,true);
    }
    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"SIT","SLEEP"})
    void poseCleanupAfterAWorldChangeNeverTeleportsBackIntoTheOldWorld(RoutineGoal.Type type){
        var pose=enter(type);teleports.clear();vehicle.set(null);Location displaced=new Location(mock(World.class),.5,64,.5);position.set(displaced.clone());
        if(type==RoutineGoal.Type.SLEEP)assertFalse(visuals.restoreSleep(pose,true));visuals.leave(pose,true);
        assertEquals(displaced,position.get());assertTrue(teleports.isEmpty());if(type==RoutineGoal.Type.SIT)assertTrue(seatRemoved.get());else assertFalse(sleeping.get());
    }
}
