package com.mdvcraft.mdvnpc.routine;

import com.github.retrooper.packetevents.util.Vector3i;
import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.runtime.NpcNameService;
import me.libraryaddict.disguise.DisguiseConfig;
import me.libraryaddict.disguise.disguisetypes.DisguiseInternals;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Name offsets retain LibsDisguises state, with a temporary display for native player nametags. */
class RoutineNameOffsetTest {
    MdvNpcPlugin plugin;World world;Block furniture;ArmorStand seat;Villager entity;PlayerDisguise disguise;PlayerWatcher watcher;
    DisguiseInternals<?> internals;TextDisplay display;YamlConfiguration npcConfig;
    RoutineVisuals visuals;ActiveNpc npc;YamlConfiguration config;
    AtomicReference<Float> nameOffset=new AtomicReference<>(.35f);
    AtomicReference<Location> position=new AtomicReference<>(),seatPosition=new AtomicReference<>();
    AtomicReference<Vector3i> bedPosition=new AtomicReference<>();AtomicBoolean sleeping=new AtomicBoolean();
    AtomicReference<Location> displayLocation=new AtomicReference<>();AtomicReference<Component> displayText=new AtomicReference<>();
    AtomicReference<String> actualName=new AtomicReference<>("&6Lorenzo");AtomicBoolean nameVisible=new AtomicBoolean(true);
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
        internals=mock(DisguiseInternals.class);when(disguise.getInternals()).thenReturn(internals);when(internals.getNameDisplayType()).thenReturn(DisguiseConfig.PlayerNameType.TEXT_DISPLAY);
        when(disguise.getName()).thenAnswer(i->actualName.get());when(disguise.getHeight()).thenReturn(1.8);when(disguise.getDisguiseScale()).thenReturn(1d);
        when(disguise.isNameVisible()).thenAnswer(i->nameVisible.get());when(disguise.setNameVisible(anyBoolean())).thenAnswer(i->{nameVisible.set(i.getArgument(0));return disguise;});
        when(watcher.getNameYModifier()).thenAnswer(i->nameOffset.get());doAnswer(i->{nameOffset.set(i.getArgument(0));return null;}).when(watcher).setNameYModifier(anyFloat());
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
        display=mock(TextDisplay.class);when(display.isValid()).thenReturn(true);
        when(world.spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class))).thenAnswer(i->{displayLocation.set(((Location)i.getArgument(0)).clone());((Consumer<TextDisplay>)i.getArgument(2)).accept(display);return display;});
        when(display.teleport(any(Location.class))).thenAnswer(i->{displayLocation.set(((Location)i.getArgument(0)).clone());return true;});
        doAnswer(i->{displayText.set(i.getArgument(0));return null;}).when(display).text(any(Component.class));
        npcConfig=new YamlConfiguration();npcConfig.set("npcs.actor.location.world","world");npcConfig.set("npcs.actor.name","&6Lorenzo");npcConfig.set("npcs.actor.name-visible",false);npcConfig.set("npcs.actor.speech.prefix","&8[Taberna] {npc} »");
        npc=new ActiveNpc(NpcParser.parse(npcConfig).get("actor"),position.get().clone(),entity,disguise);
        visuals=new RoutineVisuals(plugin,(n,target)->{position.set(target.clone());return true;});
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    private RoutineVisuals.Pose enterRaw(RoutineGoal.Type type){
        if(type==RoutineGoal.Type.SLEEP){Bed bed=mock(Bed.class);when(bed.getFacing()).thenReturn(BlockFace.NORTH);when(furniture.getBlockData()).thenReturn(bed);}
        else{Stairs stairs=mock(Stairs.class);when(stairs.getHalf()).thenReturn(Bisected.Half.BOTTOM);when(stairs.getFacing()).thenReturn(BlockFace.NORTH);when(furniture.getBlockData()).thenReturn(stairs);}
        var point=new RoutineGoal.Point(worldId,0,64,0,0);
        var goal=new RoutineGoal(1,type,RoutineGoal.WalkMode.CYCLE,0,0,2.4,20,List.of(point));
        return visuals.enter(npc,goal,point,new Location(world,.5,64,1.5),0);
    }
    private RoutineVisuals.Pose enter(RoutineGoal.Type type){var pose=enterRaw(type);assertNotNull(pose);pose.nextMeal=Long.MAX_VALUE;return pose;}
    @Test void seatedOffsetIsAdditionalToNameBaselineAndRestoresOnLeavingEvenAfterConfigChanges(){
        config.set("routines.name-offset-seated-y",-.75);var pose=enter(RoutineGoal.Type.SIT);assertEquals(64.5,seatPosition.get().getY());assertEquals(-.4f,nameOffset.get(),.00001);
        config.set("routines.name-offset-seated-y",1.25);visuals.leave(pose,true);assertEquals(.35f,nameOffset.get(),.00001);verify(entity).leaveVehicle();verify(seat).remove();
        assertEquals("&6Lorenzo",npc.definition().name());assertEquals("&8[Taberna] {npc} »",npc.definition().speech().prefix());assertFalse(npc.definition().nameVisible());
        verify(disguise,never()).setNameVisible(anyBoolean());verify(watcher,never()).setName(anyString());verify(watcher,never()).setNameVisible(anyBoolean());
    }
    @Test void explicitLegacySeatOffsetIsPreservedAndZeroNameOffsetsSendNoMetadata(){
        config.set("routines.seat-offset-y",0);var pose=enter(RoutineGoal.Type.SIT);assertEquals(64,seatPosition.get().getY());visuals.tick(pose,1);visuals.leave(pose,false);
        assertEquals(.35f,nameOffset.get(),.00001);verify(watcher,never()).getNameYModifier();verify(watcher,never()).setNameYModifier(anyFloat());
    }
    @Test void sleepingOffsetSurvivesSuspensionRepairsDriftAndRestoresWhenWaking(){
        config.set("routines.name-offset-sleeping-y",.3);var pose=enter(RoutineGoal.Type.SLEEP);assertEquals(.65f,nameOffset.get(),.00001);assertTrue(sleeping.get());
        visuals.suspend(pose,50);assertEquals(.65f,nameOffset.get(),.00001);nameOffset.set(2f);assertTrue(visuals.restoreSleep(pose,true));assertEquals(.65f,nameOffset.get(),.00001);
        visuals.leave(pose,true);assertEquals(.35f,nameOffset.get(),.00001);assertFalse(sleeping.get());
    }
    @Test void seatedNameIsNotResentEveryUpdateAndRecoversOnlyWhenItDrifts(){
        config.set("routines.name-offset-seated-y",.5);var pose=enter(RoutineGoal.Type.SIT);clearInvocations(watcher);
        for(int tick=1;tick<=40;tick++)visuals.tick(pose,tick);verify(watcher,never()).setNameYModifier(anyFloat());
        nameOffset.set(-2f);visuals.tick(pose,41);verify(watcher).setNameYModifier(.85f);visuals.leave(pose,false);assertEquals(.35f,nameOffset.get(),.00001);
    }
    @Test void failedNameAdjustmentDoesNotLeaveAnOccupiedInvisibleSeatBehind(){
        config.set("routines.name-offset-seated-y",.5);doThrow(new IllegalStateException("Packet failure")).when(watcher).setNameYModifier(.85f);
        assertThrows(IllegalStateException.class,()->enter(RoutineGoal.Type.SIT));verify(entity).leaveVehicle();verify(seat).remove();assertEquals(.35f,nameOffset.get(),.00001);
    }
    private void nativeVisibleName(){
        when(internals.getNameDisplayType()).thenReturn(DisguiseConfig.PlayerNameType.TEAMS);
        npcConfig.set("npcs.actor.name-visible",true);npc=new ActiveNpc(NpcParser.parse(npcConfig).get("actor"),position.get().clone(),entity,disguise);
    }
    @Test void nativeNameUsesOneTemporaryDisplayAndRestoresVisibilityWithoutChangingItsModifier(){
        nativeVisibleName();config.set("routines.name-offset-seated-y",-.75);var pose=enter(RoutineGoal.Type.SIT);
        assertSame(display,pose.nameDisplay);assertFalse(nameVisible.get());assertEquals(65.67,displayLocation.get().getY(),.00001);assertEquals("§6Lorenzo",LegacyComponentSerializer.legacySection().serialize(displayText.get()));
        verify(display).setPersistent(false);verify(display).setGravity(false);verify(display).setViewRange(1);verify(watcher,never()).setNameYModifier(anyFloat());
        config.set("routines.name-offset-seated-y",1);visuals.leave(pose,false);assertTrue(nameVisible.get());verify(disguise).setNameVisible(true);verify(display).remove();assertNull(pose.nameDisplay);assertEquals(.35f,nameOffset.get(),.00001);
        assertTrue(npc.definition().nameVisible());assertEquals("&8[Taberna] {npc} »",npc.definition().speech().prefix());
    }
    @Test void nativeDisplayMovesAndUpdatesItsColoredTextOnlyWhenNeeded(){
        nativeVisibleName();config.set("routines.name-offset-seated-y",.5);var pose=enter(RoutineGoal.Type.SIT);clearInvocations(display);
        for(int tick=1;tick<=40;tick++)visuals.tick(pose,tick);verify(display,never()).teleport(any(Location.class));verify(display,never()).text(any(Component.class));
        position.set(position.get().clone().add(.25,.1,0));visuals.tick(pose,41);verify(display).teleport(any(Location.class));assertEquals(position.get().getX(),displayLocation.get().getX());
        actualName.set("&bMaestro");visuals.tick(pose,42);assertEquals("§bMaestro",LegacyComponentSerializer.legacySection().serialize(displayText.get()));verify(display).text(any(Component.class));
        visuals.leave(pose,false);
    }
    @Test void nativeModeRespectsBothConfiguredAndCurrentlyHiddenNamesAndZeroOffset(){
        when(internals.getNameDisplayType()).thenReturn(DisguiseConfig.PlayerNameType.VANILLA);config.set("routines.name-offset-seated-y",.5);var hidden=enter(RoutineGoal.Type.SIT);visuals.leave(hidden,false);
        nativeVisibleName();nameVisible.set(false);var temporarilyHidden=enter(RoutineGoal.Type.SIT);visuals.leave(temporarilyHidden,false);
        nameVisible.set(true);config.set("routines.name-offset-seated-y",0);var unchanged=enter(RoutineGoal.Type.SIT);visuals.leave(unchanged,false);
        verify(world,never()).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));verify(disguise,never()).setNameVisible(anyBoolean());verify(watcher,never()).setNameYModifier(anyFloat());
    }
    @Test void nativeSleepingNameFollowsTheRestoredBedAndDisappearsOnWake(){
        nativeVisibleName();config.set("routines.name-offset-sleeping-y",-.5);var pose=enter(RoutineGoal.Type.SLEEP);Location atBed=displayLocation.get().clone();
        visuals.suspend(pose,20);assertFalse(nameVisible.get());verify(display,never()).remove();
        position.set(position.get().clone().add(1,0,0));visuals.tick(pose,21);assertNotEquals(atBed,displayLocation.get());
        assertTrue(visuals.restoreSleep(pose,true));assertEquals(atBed,displayLocation.get());visuals.leave(pose,true);verify(display).remove();assertTrue(nameVisible.get());assertFalse(sleeping.get());
    }
    @Test void nativeDisplayIsRemovedIfRestoringNameVisibilityFails(){
        nativeVisibleName();config.set("routines.name-offset-seated-y",.5);var pose=enter(RoutineGoal.Type.SIT);
        doThrow(new IllegalStateException("Metadata failure")).when(disguise).setNameVisible(true);assertThrows(IllegalStateException.class,()->visuals.leave(pose,false));
        verify(display).remove();verify(seat).remove();assertNull(pose.nameDisplay);
    }
    @Test void failedNativeNameHidingRestoresVisibilityAndRemovesTheNewDisplayAndSeat(){
        nativeVisibleName();config.set("routines.name-offset-seated-y",.5);
        doAnswer(i->{nameVisible.set(false);throw new IllegalStateException("Metadata failure");}).when(disguise).setNameVisible(false);
        assertThrows(IllegalStateException.class,()->enter(RoutineGoal.Type.SIT));assertTrue(nameVisible.get());verify(display).remove();verify(seat).remove();verify(entity).leaveVehicle();
    }
    @Test void failedDisplayConfigurationDoesNotHideTheNameOrLeaveAnOrphan(){
        nativeVisibleName();config.set("routines.name-offset-seated-y",.5);doThrow(new IllegalStateException("Text failure")).when(display).text(any(Component.class));
        assertThrows(IllegalStateException.class,()->enter(RoutineGoal.Type.SIT));assertTrue(nameVisible.get());verify(disguise,never()).setNameVisible(anyBoolean());verify(display).remove();verify(seat).remove();
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
        doThrow(new IllegalStateException("Spawn callback")).when(seat).setMarker(true);
        assertThrows(IllegalStateException.class,()->enterRaw(RoutineGoal.Type.SIT));assertTrue(seatRemoved.get());verify(seat).remove();verify(seat,never()).addPassenger(any());
    }
    @Test void refusedMountRemovesOnlyTheUnusedSupport(){
        doReturn(false).when(seat).addPassenger(entity);assertNull(enterRaw(RoutineGoal.Type.SIT));assertTrue(seatRemoved.get());verify(entity,never()).leaveVehicle();
    }
    @Test void failedMealRestorationStillRemovesTheSeatAndRestoresTheNameOnLeaving(){
        nativeVisibleName();config.set("routines.name-offset-seated-y",.5);var pose=enter(RoutineGoal.Type.SIT);pose.mealUntil=10;
        doThrow(new IllegalStateException("Item metadata")).when(watcher).setMainHandRaised(false);
        assertThrows(IllegalStateException.class,()->visuals.leave(pose,false));assertTrue(seatRemoved.get());assertNull(vehicle.get());assertNull(pose.seat);assertTrue(nameVisible.get());verify(display).remove();
    }
    @Test void failedDismountStillRemovesTheSeatAndNormalLeavingIsIdempotent(){
        var pose=enter(RoutineGoal.Type.SIT);doThrow(new IllegalStateException("Dismount callback")).when(entity).leaveVehicle();
        assertThrows(IllegalStateException.class,()->visuals.leave(pose,false));assertTrue(seatRemoved.get());assertNull(pose.seat);visuals.leave(pose,false);verify(seat).remove();
    }
    private NpcNameService ownedNames(AtomicReference<Double> offset){
        nativeVisibleName();when(internals.getNameDisplayType()).thenReturn(DisguiseConfig.PlayerNameType.ARMORSTANDS);nameVisible.set(false);
        var names=mock(NpcNameService.class);var manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.names()).thenReturn(names);when(names.manages(npc)).thenReturn(true);
        when(names.offset(npc)).thenAnswer(i->offset.get());doAnswer(i->{offset.set(i.getArgument(1));return null;}).when(names).offset(eq(npc),anyDouble());return names;
    }
    @Test void ownedNameOffsetUsesTheExistingDisplayAndRestoresItsOriginalServiceAfterManagerReplacement(){
        var offset=new AtomicReference<>(.2);var names=ownedNames(offset);config.set("routines.name-offset-seated-y",-.6);var pose=enter(RoutineGoal.Type.SIT);
        assertSame(names,pose.ownedNames);assertEquals(-.4,offset.get(),.00001);assertNull(pose.nameDisplay);assertFalse(nameVisible.get());
        verify(watcher,never()).getNameYModifier();verify(watcher,never()).setNameYModifier(anyFloat());verify(disguise,never()).setNameVisible(anyBoolean());verify(world,never()).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));
        config.set("routines.name-offset-seated-y",1);offset.set(2d);visuals.tick(pose,1);assertEquals(-.4,offset.get(),.00001);
        var replacement=mock(NpcManager.class);var replacementNames=mock(NpcNameService.class);when(plugin.manager()).thenReturn(replacement);when(replacement.names()).thenReturn(replacementNames);
        visuals.leave(pose,false);assertEquals(.2,offset.get(),.00001);verify(names).offset(npc,.2);verify(replacementNames,never()).offset(any(),anyDouble());assertNull(pose.ownedNames);
    }
    @Test void ownedSleepingNameDoesNotRecreateOrResendTheDisplayDuringStablePoseUpdates(){
        var offset=new AtomicReference<>(.25);var names=ownedNames(offset);config.set("routines.name-offset-sleeping-y",-.5);var pose=enter(RoutineGoal.Type.SLEEP);assertEquals(-.25,offset.get(),.00001);clearInvocations(names);
        visuals.suspend(pose,20);assertTrue(visuals.restoreSleep(pose,true));for(int tick=21;tick<=40;tick++)visuals.tick(pose,tick);verify(names,never()).offset(any(),anyDouble());
        visuals.leave(pose,true);assertEquals(.25,offset.get(),.00001);assertFalse(nameVisible.get());verify(names).offset(npc,.25);verify(disguise,never()).setNameVisible(anyBoolean());
    }
    @Test void failedOwnedNameAdjustmentRestoresItsBaselineAndCleansUpTheSeat(){
        var offset=new AtomicReference<>(.25);var names=ownedNames(offset);config.set("routines.name-offset-seated-y",-.5);
        doAnswer(i->{offset.set(-.25);throw new IllegalStateException("Display metadata");}).when(names).offset(npc,-.25);
        assertThrows(IllegalStateException.class,()->enterRaw(RoutineGoal.Type.SIT));assertEquals(.25,offset.get(),.00001);assertTrue(seatRemoved.get());assertNull(vehicle.get());verify(names).offset(npc,.25);verify(disguise,never()).setNameVisible(anyBoolean());
    }
}
