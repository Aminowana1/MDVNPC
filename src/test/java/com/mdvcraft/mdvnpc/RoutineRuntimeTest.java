package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.routine.*;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutineRuntimeTest {
    @TempDir Path folder;
    ServerMock server; MdvNpcPlugin plugin; RoutineService routines; World world; ActiveNpc npc; Villager entity;
    Location position; ShopService shops;
    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock(new TestServer());
        world=mock(World.class);when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(mock(Player.class)));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(inv -> {
            int x=inv.getArgument(0),y=inv.getArgument(1),z=inv.getArgument(2);
            var block=mock(org.bukkit.block.Block.class);
            when(block.getType()).thenReturn(y==63?Material.STONE:Material.AIR);
            when(block.getBoundingBox()).thenReturn(y==63?new org.bukkit.util.BoundingBox(x,y,z,x+1,y+1,z+1):new org.bukkit.util.BoundingBox(x,y,z,x,y,z));
            when(block.isPassable()).thenReturn(y!=63);return block;
        });
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("MDVNPC-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        var yaml=new YamlConfiguration();yaml.set("npcs.shop.location.world","world");yaml.set("npcs.shop.location.y",64);
        var definitions=NpcParser.parse(yaml);when(plugin.definitions()).thenReturn(definitions);
        entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.getWorld()).thenReturn(world);
        position=new Location(world,.5,64,.5);when(entity.getLocation()).thenAnswer(i -> position.clone());
        when(entity.teleport(any(Location.class))).thenAnswer(i -> {position=((Location)i.getArgument(0)).clone();return true;});
        npc=new ActiveNpc(definitions.get("shop"),position.clone(),entity,null);
        var manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.activeNpcs()).thenReturn(List.of(npc));
        shops=mock(ShopService.class);when(plugin.shops()).thenReturn(shops);
        routines=new RoutineService(plugin);when(plugin.routines()).thenReturn(routines);
    }
    @AfterEach void cleanup() {if(routines!=null)routines.close();MockBukkit.unmock();}
    RoutineGoal goal(int order,RoutineGoal.Type type,RoutineGoal.WalkMode mode,String from,String until,int x) {
        return new RoutineGoal(order,type,mode,RoutineSchedule.parseHour(from),RoutineSchedule.parseHour(until),2.4,20,List.of(new RoutineGoal.Point(world.getUID(),x,64,0,90)));
    }
    void advance(int count) {server.getScheduler().performTicks(count);}
    @Test void workOnlyOpensAfterArrivalAndClosesImmediatelyAtBoundary() throws Exception {
        routines.repository().put("shop",goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,"7","18",0));
        when(world.getFullTime()).thenReturn(1000L);routines.start();assertFalse(routines.canInteract(npc));
        advance(2);assertTrue(routines.canInteract(npc));
        when(world.getFullTime()).thenReturn(12000L);assertFalse(routines.canInteract(npc));
        advance(2);verify(shops).invalidateNpc("shop");assertFalse(routines.canInteract(npc));
    }
    @Test void noPlayersSuspendsAndRevokesShopWithoutKeepingSearch() throws Exception {
        routines.repository().put("shop",goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,"0","0",0));
        routines.start();advance(2);assertTrue(routines.canInteract(npc));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of());
        advance(22);assertFalse(routines.canInteract(npc));assertTrue(routines.status("shop").contains("suspendido"));
    }
    @Test void metaCannotOpenShopUntilNextGoalIsReached() throws Exception {
        routines.repository().put("shop",goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.TARGET,"0","0",0));
        routines.repository().put("shop",goal(2,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,"0","0",0));
        routines.start();advance(2);assertFalse(routines.canInteract(npc));advance(2);assertTrue(routines.canInteract(npc));
    }
    @Test void unloadedDestinationNeverRequestsItsBlocksOrTeleports() throws Exception {
        routines.repository().put("shop",goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,"0","0",80));
        when(world.isChunkLoaded(eq(5),anyInt())).thenReturn(false);
        routines.start();advance(120);
        verify(world,never()).getBlockAt(eq(80),anyInt(),anyInt());verify(entity,never()).teleport(any(Location.class));
        assertFalse(routines.canInteract(npc));
    }
    @Test void staticNpcKeepsOriginalInteractionBehaviorAndInternalScopeClears() {
        assertTrue(routines.canInteract(npc));assertFalse(routines.internal(entity));
        assertTrue(routines.teleport(npc,new Location(world,1,64,0)));assertFalse(routines.internal(entity));
    }
    @Test void walkingReachesWorkWithLimitedPathJobs() throws Exception {
        routines.repository().put("shop",goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,"0","0",4));
        routines.start();advance(120);
        assertTrue(routines.canInteract(npc));assertEquals(4.5,position.getX(),.1);
        assertTrue(routines.metrics().contains("búsquedas pendientes=0"));
    }
}
