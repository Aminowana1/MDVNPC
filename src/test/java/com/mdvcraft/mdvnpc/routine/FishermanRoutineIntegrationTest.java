package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.trait.HitReactionService;
import com.mdvcraft.mdvnpc.work.FishermanController;
import com.mdvcraft.mdvnpc.work.ShopWorkDefinition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The fishing cycle borrows WORK movement while preserving the ordinary shop and base post. */
class FishermanRoutineIntegrationTest {
    @TempDir Path folder;
    RoutineTransitionTest f;
    MdvNpcPlugin plugin;
    FishermanController fisher;
    final AtomicBoolean active=new AtomicBoolean(),walking=new AtomicBoolean(true);

    @BeforeEach void setup() throws Exception {
        f=new RoutineTransitionTest();f.folder=folder;f.setup();
        when(f.entity.isInsideVehicle()).thenReturn(false);
        plugin=(MdvNpcPlugin)field(f.service,"plugin");
        plugin.settings().messages().set("routines.occasional-looking",false);
        fisher=mock(FishermanController.class);
        when(fisher.active("thurg")).thenAnswer(call->active.get());
        when(fisher.walking("thurg")).thenAnswer(call->active.get() && walking.get());
        when(fisher.status("thurg")).thenReturn("pescador: caminando a punto de pesca");
        when(fisher.pause("thurg")).thenReturn(true);
        when(fisher.suspend(eq("thurg"),anyLong())).thenReturn(true);
        when(fisher.resume(eq("thurg"),anyLong())).thenReturn(true);
        doAnswer(call->{active.set(false);return null;}).when(fisher).stop("thurg");
        when(fisher.tick(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{active.set(true);return FishermanController.Result.RUNNING;});
    }
    @AfterEach void cleanup(){if(f!=null)f.cleanup();}
    private static Object field(Object object,String name) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private Object state() throws Exception{return ((Map<?,?>)field(f.service,"states")).get("thurg");}
    private void category(NpcDefinition.Mode mode,ShopWorkDefinition.Category category) {
        var d=f.npc.definition();
        var work=new ShopWorkDefinition(category,null,null,null);
        var definition=new NpcDefinition(d.id(),d.enabled(),d.name(),d.nameVisible(),d.position(),d.skin(),d.look(),d.dialogue(),
                d.interaction(),mode,d.tradeDialogue(),d.traits(),d.speech(),work);
        f.npc=new ActiveNpc(definition,f.npc.anchor(),f.entity,null);
        when(plugin.manager().activeNpcs()).thenReturn(List.of(f.npc));
    }
    private void start(RoutineGoal goal) throws Exception {
        f.service.repository().put("thurg",goal);f.service.start();set(f.service,"fishermen",fisher);
    }
    private void startFisherAtPost() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.FISHERMAN);
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));f.advance(12);
        assertTrue(active.get());assertTrue(f.service.canInteract(f.npc));
    }

    @Test void choreographyBeginsOnlyAfterWalkingToTheNormalWorkPost() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.FISHERMAN);
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,8));
        f.advance(2);verify(fisher,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        assertFalse(f.service.canInteract(f.npc));
        f.advance(180);verify(fisher,atLeastOnce()).tick(eq(f.npc),any(),eq(2.4),anyLong(),eq(2));
    }

    @Test void stationTravelRetainsWorkAndShopAccessWithoutChangingTheBasePost() throws Exception {
        startFisherAtPost();Location post=((Location)field(state(),"approach")).clone();Location anchor=f.npc.anchor().clone();
        f.position.setX(4.5);f.advance(2);
        assertTrue((Boolean)field(state(),"working"));assertTrue(f.service.canInteract(f.npc));assertTrue(f.service.canUseJob(f.npc));
        assertEquals(post,field(state(),"approach"));assertEquals(anchor,f.npc.anchor());assertFalse(f.service.canLook(f.npc));
        verify(plugin.shops(),never()).invalidateNpc("thurg");
    }

    @Test void failedStationReturnsByWalkingAndWaitsBeforeTryingAnimationAgain() throws Exception {
        startFisherAtPost();f.position.setX(4.5);Location from=f.position.clone();f.moves.clear();
        when(fisher.tick(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{active.set(false);return FishermanController.Result.FALLBACK;});
        clearInvocations(fisher);f.advance(2);
        assertFalse(f.service.canInteract(f.npc));assertTrue(f.position.distance(from)<=.240001);
        f.advance(180);assertTrue(f.service.canInteract(f.npc));assertFalse(active.get());
        f.assertWalkingSteps(from);verify(fisher,times(1)).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        f.advance(300);verify(fisher,times(1)).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        f.advance(140);verify(fisher,atLeast(2)).tick(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void missingStationsLeaveTheVendorAtItsPostWithABoundedRetry() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.FISHERMAN);
        when(fisher.tick(any(),any(),anyDouble(),anyLong(),anyInt())).thenReturn(FishermanController.Result.FALLBACK);
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));f.advance(300);
        assertTrue(f.service.canInteract(f.npc));assertTrue(f.moves.stream().allMatch(at->at.distanceSquared(new Location(f.world,.5,64,.5))<.001));
        verify(fisher,times(1)).tick(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void ordinaryVendorsKeepTheirExistingStationaryWorkBehaviour() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.VENDOR);
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));f.advance(200);
        assertTrue(f.service.canInteract(f.npc));verify(fisher,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void theCategoryDoesNotAnimateNonWorkGoalsOrOtherNpcModes() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.FISHERMAN);
        start(f.goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.CYCLE,0,0,8));f.advance(80);
        verify(fisher,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        category(NpcDefinition.Mode.NORMAL,ShopWorkDefinition.Category.FISHERMAN);
        f.service.repository().put("thurg",f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));
        f.service.remove("thurg");f.advance(160);verify(fisher,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void aStationWalkerStillRequestsTheForcedHopAfterThreeQuietSeconds() throws Exception {
        startFisherAtPost();RoutineNavigator navigator=mock(RoutineNavigator.class);set(f.service,"navigator",navigator);
        f.advance(62);verify(navigator).startRecoveryHop(eq(f.npc),eq(.6),eq(1.0),anyLong());
        assertTrue(f.service.canInteract(f.npc));
    }

    @Test void stationFloorRecoveryPreservesTheBasePostAndTradingOwnership() throws Exception {
        startFisherAtPost();Location post=((Location)field(state(),"approach")).clone();
        RoutineGravity floor=mock(RoutineGravity.class);
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(RoutineGravity.Result.BLOCKED);
        set(f.service,"gravity",floor);f.position.setX(4.5);f.advance(2);
        assertTrue((Boolean)field(state(),"working"));assertTrue(f.service.canUseJob(f.npc));
        assertEquals(post,field(state(),"approach"));verify(plugin.shops(),never()).invalidateNpc("thurg");
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(RoutineGravity.Result.STABLE);
        f.advance(22);assertEquals(post,field(state(),"approach"));assertTrue(active.get());
    }

    @Test void anOpenTradePausesAnimationAndKeepsTheMerchantAvailableUntilItCloses() throws Exception {
        startFisherAtPost();f.position.setX(4.5);Location at=f.position.clone();
        clearInvocations(fisher);
        when(plugin.shops().hasOpenSession("thurg")).thenReturn(true);f.advance(2);
        assertTrue(active.get());assertTrue(f.service.canUseJob(f.npc));f.advance(100);assertEquals(at,f.position);
        verify(fisher,atLeastOnce()).pause("thurg");verify(fisher,never()).stop("thurg");
        when(plugin.shops().hasOpenSession("thurg")).thenReturn(false);f.moves.clear();f.advance(10);
        assertFalse(f.service.canInteract(f.npc));assertFalse(f.moves.isEmpty());f.assertWalkingSteps(at);
    }

    @Test void tradingInTheBoatPausesInPlaceSoTheBuyersDistanceRemainsValid() throws Exception {
        startFisherAtPost();walking.set(false);when(fisher.boating("thurg")).thenReturn(true);
        Boat boat=mock(Boat.class);when(f.entity.getVehicle()).thenReturn(boat);when(f.entity.isInsideVehicle()).thenReturn(true);
        f.position.setX(10.5);Location before=f.position.clone();clearInvocations(fisher);
        when(plugin.shops().hasOpenSession("thurg")).thenReturn(true);f.advance(100);
        assertEquals(before,f.position);assertSame(boat,f.entity.getVehicle());assertTrue(active.get());assertTrue(f.service.canUseJob(f.npc));
        verify(fisher,atLeastOnce()).pause("thurg");verify(fisher,never()).stop("thurg");
        verify(fisher,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        when(plugin.shops().hasOpenSession("thurg")).thenReturn(false);f.advance(10);
        assertFalse(active.get());assertFalse(f.service.canInteract(f.npc));verify(fisher,atLeastOnce()).stop("thurg");
    }

    @Test void anUnsafeTradePauseCleansUpAndInvalidatesTheMerchant() throws Exception {
        startFisherAtPost();f.position.setX(10.5);clearInvocations(fisher,plugin.shops());
        when(fisher.pause("thurg")).thenReturn(false);when(plugin.shops().hasOpenSession("thurg")).thenReturn(true);
        f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));assertTrue((Boolean)field(state(),"fishermanReturning"));
        verify(plugin.shops()).invalidateNpc("thurg");verify(fisher,atLeastOnce()).stop("thurg");
    }

    @Test void changingToSleepStopsAnimationAndRevokesRemoteTradingImmediately() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.FISHERMAN);
        f.service.repository().put("thurg",f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,720,960,0));
        f.service.repository().put("thurg",f.goal(2,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE,960,720,0));
        f.service.start();set(f.service,"fishermen",fisher);f.advance(12);assertTrue(active.get());
        f.position.setX(4.5);when(f.world.getFullTime()).thenReturn(10000L);f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));
    }

    @Test void observersLeavingStopAndRestoreTheWorkshop() throws Exception {
        startFisherAtPost();f.following=false;f.observerPosition=null;f.advance(24);
        assertFalse(active.get());assertFalse(f.service.canInteract(f.npc));assertTrue(f.service.status("thurg").contains("suspendido"));
    }

    @Test void disablingOrRemovingTheRoutineCleansUpTheWorkshop() throws Exception {
        startFisherAtPost();f.service.repository().edit(y->y.set("npcs.thurg.enabled",false));f.advance(2);assertFalse(active.get());
        active.set(true);f.service.remove("thurg");assertFalse(active.get());
    }

    @Test void aHitReactionStopsAnimationBeforeBorrowingEquipment() throws Exception {
        startFisherAtPost();HitReactionService reactions=mock(HitReactionService.class);
        when(plugin.reactions()).thenReturn(reactions);when(reactions.busy("thurg")).thenReturn(true);f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canLook(f.npc));
        when(reactions.busy("thurg")).thenReturn(false);f.position.setX(4.5);f.advance(2);
        assertFalse(f.service.canInteract(f.npc));assertFalse(active.get());
    }

    @Test void aHitInTheBoatStaysMountedWithoutTakingRotationDuringTheReaction() throws Exception {
        startFisherAtPost();walking.set(false);when(fisher.boating("thurg")).thenReturn(true);
        Boat boat=mock(Boat.class);when(f.entity.getVehicle()).thenReturn(boat);when(f.entity.isInsideVehicle()).thenReturn(true);
        f.position.setX(10.5);Location at=f.position.clone();
        HitReactionService reactions=mock(HitReactionService.class);when(plugin.reactions()).thenReturn(reactions);
        RoutineGravity floor=mock(RoutineGravity.class);set(f.service,"gravity",floor);
        clearInvocations(fisher);f.service.prepareReaction(f.npc);
        assertTrue(active.get());assertTrue((Boolean)field(state(),"fishermanReactionPaused"));
        assertTrue((Boolean)field(state(),"working"));assertSame(boat,f.entity.getVehicle());assertEquals(at,f.position);
        verify(fisher).suspend(eq("thurg"),anyLong());verify(fisher,never()).stop("thurg");
        clearInvocations(f.entity,fisher);when(reactions.busy("thurg")).thenReturn(true);f.advance(80);
        assertSame(boat,f.entity.getVehicle());assertEquals(at,f.position);assertTrue(active.get());
        verify(fisher,atLeastOnce()).suspend(eq("thurg"),anyLong());verify(fisher,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        verify(fisher,never()).stop("thurg");verify(fisher,never()).resume(anyString(),anyLong());
        verify(f.entity,never()).setRotation(anyFloat(),anyFloat());
        verify(floor,never()).tick(any(),any(),anyLong(),anyInt(),anyDouble());
    }

    @Test void afterAHitTheSameBoatWorkPhaseResumesWithoutReturningToLand() throws Exception {
        startFisherAtPost();walking.set(false);when(fisher.boating("thurg")).thenReturn(true);
        Boat boat=mock(Boat.class);when(f.entity.getVehicle()).thenReturn(boat);when(f.entity.isInsideVehicle()).thenReturn(true);
        f.position.setX(10.5);Location at=f.position.clone();
        HitReactionService reactions=mock(HitReactionService.class);when(plugin.reactions()).thenReturn(reactions);
        RoutineGravity floor=mock(RoutineGravity.class);set(f.service,"gravity",floor);
        RoutineNavigator navigator=mock(RoutineNavigator.class);set(f.service,"navigator",navigator);
        f.service.prepareReaction(f.npc);when(reactions.busy("thurg")).thenReturn(true);f.advance(80);
        clearInvocations(fisher,navigator);when(reactions.busy("thurg")).thenReturn(false);f.advance(2);
        var order=inOrder(fisher);order.verify(fisher).resume(eq("thurg"),anyLong());
        order.verify(fisher).tick(eq(f.npc),any(),anyDouble(),anyLong(),eq(2));
        assertSame(boat,f.entity.getVehicle());assertEquals(at,f.position);assertTrue(active.get());assertTrue(f.service.canUseJob(f.npc));
        assertFalse((Boolean)field(state(),"fishermanReactionPaused"));assertFalse((Boolean)field(state(),"fishermanReturning"));
        verify(fisher,never()).stop("thurg");verify(navigator,never()).startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong());
        verify(floor,never()).tick(any(),any(),anyLong(),anyInt(),anyDouble());
    }

    @Test void theScheduleStillCleansUpABoatWhileItsHitReactionIsPaused() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.FISHERMAN);
        f.service.repository().put("thurg",f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,720,960,0));
        f.service.repository().put("thurg",f.goal(2,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE,960,720,0));
        f.service.start();set(f.service,"fishermen",fisher);f.advance(12);
        walking.set(false);when(fisher.boating("thurg")).thenReturn(true);
        Boat boat=mock(Boat.class);when(f.entity.getVehicle()).thenReturn(boat);when(f.entity.isInsideVehicle()).thenReturn(true);
        HitReactionService reactions=mock(HitReactionService.class);when(plugin.reactions()).thenReturn(reactions);
        f.service.prepareReaction(f.npc);when(reactions.busy("thurg")).thenReturn(true);
        assertTrue((Boolean)field(state(),"fishermanReactionPaused"));clearInvocations(fisher);
        when(f.world.getFullTime()).thenReturn(10000L);f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));assertFalse((Boolean)field(state(),"fishermanReactionPaused"));
        verify(fisher,atLeastOnce()).stop("thurg");verify(fisher,never()).resume(anyString(),anyLong());
    }

    @Test void aBoatThatCannotResumeUsesTheOrdinaryWorkPostFallback() throws Exception {
        startFisherAtPost();walking.set(false);when(fisher.boating("thurg")).thenReturn(true);
        f.position.setX(10.5);f.service.prepareReaction(f.npc);
        when(fisher.resume(eq("thurg"),anyLong())).thenReturn(false);f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));assertTrue((Boolean)field(state(),"fishermanReturning"));
        assertFalse((Boolean)field(state(),"fishermanReactionPaused"));
    }

    @Test void theWorkshopDeadlineStillExpiresWhenGravityKeepsReturningEarly() throws Exception {
        startFisherAtPost();f.position.setX(4.5);
        RoutineGravity floor=mock(RoutineGravity.class);
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(RoutineGravity.Result.BLOCKED);
        set(f.service,"gravity",floor);f.advance(2);assertTrue(active.get());
        when(fisher.travelExpired(eq("thurg"),anyLong())).thenReturn(true);f.advance(22);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));assertTrue((Boolean)field(state(),"fishermanReturning"));
    }

    @Test void sailingDoesNotApplyLandGravityOrTryToHopAndKeepsItsShopAvailable() throws Exception {
        startFisherAtPost();walking.set(false);when(fisher.boating("thurg")).thenReturn(true);
        Boat boat=mock(Boat.class);when(f.entity.getVehicle()).thenReturn(boat);when(f.entity.isInsideVehicle()).thenReturn(true);
        RoutineGravity floor=mock(RoutineGravity.class);set(f.service,"gravity",floor);
        RoutineNavigator navigator=mock(RoutineNavigator.class);set(f.service,"navigator",navigator);
        f.position.setX(10.5);clearInvocations(fisher);f.advance(100);
        verify(floor,never()).tick(any(),any(),anyLong(),anyInt(),anyDouble());
        verify(navigator,never()).startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong());
        verify(fisher,atLeast(40)).tick(eq(f.npc),any(),anyDouble(),anyLong(),eq(2));
        assertTrue(f.service.canInteract(f.npc));assertFalse(f.service.canLook(f.npc));
    }

    @Test void aBoatRouteExpiresWithoutWaitingForLandGravity() throws Exception {
        startFisherAtPost();walking.set(false);when(fisher.boating("thurg")).thenReturn(true);
        when(f.entity.isInsideVehicle()).thenReturn(true);f.position.setX(10.5);
        when(fisher.travelExpired(eq("thurg"),anyLong())).thenReturn(true);f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));
        assertTrue((Boolean)field(state(),"fishermanReturning"));
    }

    @Test void onlyTheAssignedNpcReceivesTheFishingBoatMovementException() throws Exception {
        startFisherAtPost();Boat boat=mock(Boat.class);Entity stranger=mock(Entity.class);
        when(fisher.ownsBoat(boat)).thenReturn(true);when(fisher.mountedNpc(boat)).thenReturn(f.npc);
        when(f.entity.getVehicle()).thenReturn(boat);when(stranger.getVehicle()).thenReturn(boat);
        assertTrue(f.service.isBoat(boat));assertTrue(f.service.inFishingBoat(f.entity));assertFalse(f.service.inFishingBoat(stranger));
        when(f.entity.getVehicle()).thenReturn(mock(Boat.class));assertFalse(f.service.inFishingBoat(f.entity));
        assertFalse(f.service.isBoat(null));assertFalse(f.service.inFishingBoat(null));
    }

    @Test void mountingAndLeavingBorrowTheGuardOnlyForTheAssignedNpcAndRestoreItAfterward() throws Exception {
        startFisherAtPost();Boat boat=mock(Boat.class);Entity stranger=mock(Entity.class);
        when(fisher.ownsBoat(boat)).thenReturn(true);when(fisher.mountedNpc(boat)).thenReturn(f.npc);
        when(stranger.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        assertFalse(f.service.canMountFishingBoat(boat,f.entity));
        when(boat.addPassenger(f.entity)).thenAnswer(call->{
            assertTrue(f.service.mounting(f.entity));assertTrue(f.service.canMountFishingBoat(boat,f.entity));
            assertFalse(f.service.mounting(stranger));assertFalse(f.service.canMountFishingBoat(boat,stranger));return true;
        });
        assertTrue(f.service.mount(f.npc,boat));assertFalse(f.service.mounting(f.entity));assertFalse(f.service.internal(f.entity));
        when(f.entity.leaveVehicle()).thenAnswer(call->{assertTrue(f.service.mounting(f.entity));return true;});
        assertTrue(f.service.mount(f.npc,null));assertFalse(f.service.mounting(f.entity));assertFalse(f.service.internal(f.entity));
    }

    @Test void aFailedMountRestoresTheExistingInternalTeleportGuard() throws Exception {
        startFisherAtPost();Boat boat=mock(Boat.class);java.util.UUID previous=java.util.UUID.randomUUID();
        set(f.service,"internalEntity",previous);set(f.service,"mounting",true);
        when(boat.addPassenger(f.entity)).thenThrow(new IllegalStateException("cancelled boat"));
        assertThrows(IllegalStateException.class,()->f.service.mount(f.npc,boat));
        assertEquals(previous,field(f.service,"internalEntity"));assertEquals(true,field(f.service,"mounting"));
        set(f.service,"internalEntity",null);set(f.service,"mounting",false);
    }

    @Test void drinkingStopsFishingBeforeTheDrinkBorrowsEquipment() throws Exception {
        startFisherAtPost();ItemStack beer=new ItemStack(Material.POTION);clearInvocations(fisher,f.visuals);
        f.service.beginDrink(f.npc,beer,20);
        var order=inOrder(fisher,f.visuals);order.verify(fisher).stop("thurg");
        order.verify(f.visuals).beginDrink(eq(f.npc),isNull(),eq(beer),eq(20L));
        assertFalse(active.get());assertTrue((Boolean)field(state(),"fishermanReturning"));
    }

    @Test void anInvalidNpcAlsoStopsItsBoatController() throws Exception {
        startFisherAtPost();when(f.entity.isValid()).thenReturn(false);f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));
    }
}


