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
import org.mockito.ArgumentCaptor;
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
        when(entity.isOnGround()).thenReturn(true);
        var pathfinder=mock(com.destroystokyo.paper.entity.Pathfinder.class);when(entity.getPathfinder()).thenReturn(pathfinder);
        when(pathfinder.findPath(any(Location.class))).thenAnswer(call->{
            Location target=call.getArgument(0);var path=mock(com.destroystokyo.paper.entity.Pathfinder.PathResult.class);
            List<Location> points=new ArrayList<>();int step=position.getBlockX()<=target.getBlockX()?1:-1;
            for(int x=position.getBlockX();x!=target.getBlockX();x+=step)points.add(new Location(world,x,64,0));
            points.add(new Location(world,target.getBlockX(),64,target.getBlockZ()));
            when(path.getPoints()).thenReturn(points);when(path.canReachFinalPoint()).thenReturn(true);return path;
        });
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
    @Test void unloadedDestinationAllowsLocalRecoveryWithoutReadingOrEnteringItsChunk() throws Exception {
        routines.repository().put("shop",goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,"0","0",80));
        when(world.isChunkLoaded(eq(5),anyInt())).thenReturn(false);
        routines.start();advance(120);
        verify(world,never()).getBlockAt(eq(80),anyInt(),anyInt());
        ArgumentCaptor<Location> recovery=ArgumentCaptor.forClass(Location.class);
        verify(entity,atLeastOnce()).teleport(recovery.capture());
        assertTrue(recovery.getAllValues().stream().anyMatch(at->at.getY()>64.5),"the waiting walker still tries its hop");
        for(Location frame:recovery.getAllValues()) {
            assertSame(world,frame.getWorld());assertEquals(.5,frame.getX(),1e-6);
            assertTrue(frame.getZ()>=.5 && frame.getZ()<=1.500001,"recovery stays local and follows the facing");
            assertTrue(frame.getY()>=64 && frame.getY()<=64.600001);
            assertTrue(world.isChunkLoaded(frame.getBlockX()>>4,frame.getBlockZ()>>4));
        }
        verify(entity.getPathfinder(),never()).findPath(any(Location.class));
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
