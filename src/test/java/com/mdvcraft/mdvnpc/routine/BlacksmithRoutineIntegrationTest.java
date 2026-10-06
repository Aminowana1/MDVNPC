package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.trait.HitReactionService;
import com.mdvcraft.mdvnpc.work.BlacksmithController;
import com.mdvcraft.mdvnpc.work.ShopWorkDefinition;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The normal WORK post owns schedule/trading; the optional workshop only borrows travel. */
class BlacksmithRoutineIntegrationTest {
    @TempDir Path folder;
    RoutineTransitionTest f;
    MdvNpcPlugin plugin;
    BlacksmithController smith;
    final AtomicBoolean active=new AtomicBoolean(),traveling=new AtomicBoolean(true);

    @BeforeEach void setup() throws Exception {
        f=new RoutineTransitionTest();f.folder=folder;f.setup();
        when(f.entity.isInsideVehicle()).thenReturn(false);
        plugin=(MdvNpcPlugin)field(f.service,"plugin");
        plugin.settings().messages().set("routines.occasional-looking",false);
        smith=mock(BlacksmithController.class);
        when(smith.active("thurg")).thenAnswer(call->active.get());
        when(smith.traveling("thurg")).thenAnswer(call->active.get() && traveling.get());
        when(smith.status("thurg")).thenReturn("herrero: caminando a fundición");
        doAnswer(call->{active.set(false);return null;}).when(smith).stop("thurg");
        when(smith.tick(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{active.set(true);return BlacksmithController.Result.RUNNING;});
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
        f.service.repository().put("thurg",goal);f.service.start();set(f.service,"blacksmiths",smith);
    }
    private void startSmithAtPost() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.BLACKSMITH);
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));f.advance(12);
        assertTrue(active.get());assertTrue(f.service.canInteract(f.npc));
    }

    @Test void choreographyBeginsOnlyAfterWalkingToTheNormalWorkPost() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.BLACKSMITH);
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,8));
        f.advance(2);verify(smith,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        assertFalse(f.service.canInteract(f.npc));
        f.advance(180);verify(smith,atLeastOnce()).tick(eq(f.npc),any(),eq(2.4),anyLong(),eq(2));
    }

    @Test void stationTravelRetainsWorkAndShopAccessWithoutChangingTheBasePost() throws Exception {
        startSmithAtPost();Location post=((Location)field(state(),"approach")).clone();Location anchor=f.npc.anchor().clone();
        f.position.setX(4.5);f.advance(2);
        assertTrue((Boolean)field(state(),"working"));assertTrue(f.service.canInteract(f.npc));assertTrue(f.service.canUseJob(f.npc));
        assertEquals(post,field(state(),"approach"));assertEquals(anchor,f.npc.anchor());assertFalse(f.service.canLook(f.npc));
        verify(plugin.shops(),never()).invalidateNpc("thurg");
    }

    @Test void failedStationReturnsByWalkingAndWaitsBeforeTryingAnimationAgain() throws Exception {
        startSmithAtPost();f.position.setX(4.5);Location from=f.position.clone();f.moves.clear();
        when(smith.tick(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{active.set(false);return BlacksmithController.Result.FALLBACK;});
        clearInvocations(smith);f.advance(2);
        assertFalse(f.service.canInteract(f.npc));assertTrue(f.position.distance(from)<=.240001);
        f.advance(180);assertTrue(f.service.canInteract(f.npc));assertFalse(active.get());
        f.assertWalkingSteps(from);verify(smith,times(1)).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        f.advance(300);verify(smith,times(1)).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        f.advance(140);verify(smith,atLeast(2)).tick(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void missingStationsLeaveTheVendorAtItsPostWithABoundedRetry() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.BLACKSMITH);
        when(smith.tick(any(),any(),anyDouble(),anyLong(),anyInt())).thenReturn(BlacksmithController.Result.FALLBACK);
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));f.advance(300);
        assertTrue(f.service.canInteract(f.npc));assertTrue(f.moves.stream().allMatch(at->at.distanceSquared(new Location(f.world,.5,64,.5))<.001));
        verify(smith,times(1)).tick(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void ordinaryVendorsKeepTheirExistingStationaryWorkBehaviour() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.VENDOR);
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));f.advance(200);
        assertTrue(f.service.canInteract(f.npc));verify(smith,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void theCategoryDoesNotAnimateNonWorkGoalsOrOtherNpcModes() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.BLACKSMITH);
        start(f.goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.CYCLE,0,0,8));f.advance(80);
        verify(smith,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
        category(NpcDefinition.Mode.NORMAL,ShopWorkDefinition.Category.BLACKSMITH);
        f.service.repository().put("thurg",f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0));
        f.service.remove("thurg");f.advance(160);verify(smith,never()).tick(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void aStationWalkerStillRequestsTheForcedHopAfterThreeQuietSeconds() throws Exception {
        startSmithAtPost();RoutineNavigator navigator=mock(RoutineNavigator.class);set(f.service,"navigator",navigator);
        f.advance(62);verify(navigator).startRecoveryHop(eq(f.npc),eq(.6),eq(1.0),anyLong());
        assertTrue(f.service.canInteract(f.npc));
    }

    @Test void stationFloorRecoveryPreservesTheBasePostAndTradingOwnership() throws Exception {
        startSmithAtPost();Location post=((Location)field(state(),"approach")).clone();
        RoutineGravity floor=mock(RoutineGravity.class);
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(RoutineGravity.Result.BLOCKED);
        set(f.service,"gravity",floor);f.position.setX(4.5);f.advance(2);
        assertTrue((Boolean)field(state(),"working"));assertTrue(f.service.canUseJob(f.npc));
        assertEquals(post,field(state(),"approach"));verify(plugin.shops(),never()).invalidateNpc("thurg");
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(RoutineGravity.Result.STABLE);
        f.advance(22);assertEquals(post,field(state(),"approach"));assertTrue(active.get());
    }

    @Test void anOpenTradeStopsAnimationAndKeepsTheMerchantAvailableUntilItCloses() throws Exception {
        startSmithAtPost();f.position.setX(4.5);Location at=f.position.clone();
        when(plugin.shops().hasOpenSession("thurg")).thenReturn(true);f.advance(2);
        assertFalse(active.get());assertTrue(f.service.canUseJob(f.npc));f.advance(100);assertEquals(at,f.position);
        when(plugin.shops().hasOpenSession("thurg")).thenReturn(false);f.moves.clear();f.advance(10);
        assertFalse(f.service.canInteract(f.npc));assertFalse(f.moves.isEmpty());f.assertWalkingSteps(at);
    }

    @Test void changingToSleepStopsAnimationAndRevokesRemoteTradingImmediately() throws Exception {
        category(NpcDefinition.Mode.SHOP,ShopWorkDefinition.Category.BLACKSMITH);
        f.service.repository().put("thurg",f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,720,960,0));
        f.service.repository().put("thurg",f.goal(2,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE,960,720,0));
        f.service.start();set(f.service,"blacksmiths",smith);f.advance(12);assertTrue(active.get());
        f.position.setX(4.5);when(f.world.getFullTime()).thenReturn(10000L);f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));
    }

    @Test void observersLeavingStopAndRestoreTheWorkshop() throws Exception {
        startSmithAtPost();f.following=false;f.observerPosition=null;f.advance(24);
        assertFalse(active.get());assertFalse(f.service.canInteract(f.npc));assertTrue(f.service.status("thurg").contains("suspendido"));
    }

    @Test void disablingOrRemovingTheRoutineCleansUpTheWorkshop() throws Exception {
        startSmithAtPost();f.service.repository().edit(y->y.set("npcs.thurg.enabled",false));f.advance(2);assertFalse(active.get());
        active.set(true);f.service.remove("thurg");assertFalse(active.get());
    }

    @Test void aHitReactionStopsAnimationBeforeBorrowingEquipment() throws Exception {
        startSmithAtPost();HitReactionService reactions=mock(HitReactionService.class);
        when(plugin.reactions()).thenReturn(reactions);when(reactions.busy("thurg")).thenReturn(true);f.advance(2);
        assertFalse(active.get());assertFalse(f.service.canLook(f.npc));
        when(reactions.busy("thurg")).thenReturn(false);f.position.setX(4.5);f.advance(2);
        assertFalse(f.service.canInteract(f.npc));assertFalse(active.get());
    }

    @Test void theWorkshopDeadlineStillExpiresWhenGravityKeepsReturningEarly() throws Exception {
        startSmithAtPost();f.position.setX(4.5);
        RoutineGravity floor=mock(RoutineGravity.class);
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(RoutineGravity.Result.BLOCKED);
        set(f.service,"gravity",floor);f.advance(2);assertTrue(active.get());
        when(smith.travelExpired(eq("thurg"),anyLong())).thenReturn(true);f.advance(22);
        assertFalse(active.get());assertFalse(f.service.canUseJob(f.npc));assertTrue((Boolean)field(state(),"blacksmithReturning"));
    }
}
