package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.listener.NpcListener;
import com.mdvcraft.mdvnpc.music.MusicService;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.InteractionService;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.util.Messages;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real routine state/scheduling and public interaction gates; visual entity rendering is supplied separately. */
class RoutineJobInteractionTest {
    @TempDir Path folder;
    ServerMock server; MdvNpcPlugin plugin; RoutineService routines; RoutineVisuals visuals;
    World world; NpcManager manager; ActiveNpc npc; Villager entity; Player player;
    Pathfinder pathfinder; Location position; MusicService music; ShopService actualShop;
    RoutineGoal.Type furniture = RoutineGoal.Type.SIT;
    final Map<String,Block> blocks = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.getFullTime()).thenReturn(1000L);when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("job-routine-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getServer()).thenReturn(server);
        Logger logger=mock(Logger.class);Settings settings=Settings.parse(new YamlConfiguration());
        Messages messages=mock(Messages.class);ShopService shop=mock(ShopService.class);
        when(plugin.getLogger()).thenReturn(logger);when(plugin.settings()).thenReturn(settings);
        when(plugin.messages()).thenReturn(messages);when(plugin.shops()).thenReturn(shop);
        music=mock(MusicService.class);when(plugin.music()).thenReturn(music);
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("Tester");when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getMetadata("vanished")).thenReturn(List.of());when(player.performCommand(anyString())).thenReturn(true);
        when(player.getLocation()).thenAnswer(ignored->new Location(world,.5,64,2.5));
        when(player.getEyeLocation()).thenAnswer(ignored->new Location(world,.5,65.6,2.5));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(player));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            return blocks.computeIfAbsent(x+","+y+","+z+","+furniture,ignored->{
                Block b=mock(Block.class);boolean solid=y==63;
                when(b.getType()).thenReturn(solid?Material.STONE:Material.AIR);when(b.isPassable()).thenReturn(!solid);
                when(b.getBoundingBox()).thenReturn(solid?new BoundingBox(x,y,z,x+1,y+1,z+1):new BoundingBox(x,y,z,x,y,z));
                if(x==0 && y==64 && z==0) {
                    var data=furniture==RoutineGoal.Type.SLEEP?mock(Bed.class):mock(Stairs.class);
                    when(b.getBlockData()).thenReturn(data);
                }
                return b;
            });
        });
        entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.isOnGround()).thenReturn(true);
        when(entity.getWorld()).thenReturn(world);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.isInsideVehicle()).thenReturn(true);
        position=new Location(world,.5,64,1.5);when(entity.getLocation()).thenAnswer(ignored->position.clone());
        when(entity.getEyeLocation()).thenAnswer(ignored->position.clone().add(0,1.6,0));
        when(entity.teleport(any(Location.class))).thenAnswer(call->{position=((Location)call.getArgument(0)).clone();return true;});
        doAnswer(call->{position.setYaw(call.getArgument(0));position.setPitch(call.getArgument(1));return null;}).when(entity).setRotation(anyFloat(),anyFloat());
        pathfinder=mock(Pathfinder.class);when(entity.getPathfinder()).thenReturn(pathfinder);
        when(pathfinder.findPath(any(Location.class))).thenAnswer(call->{
            Location target=call.getArgument(0);List<Location> path=new ArrayList<>();int x=position.getBlockX(),z=position.getBlockZ();
            path.add(new Location(world,x,64,z));
            while(x!=target.getBlockX() || z!=target.getBlockZ()) {
                x+=Integer.compare(target.getBlockX(),x);z+=Integer.compare(target.getBlockZ(),z);path.add(new Location(world,x,64,z));
            }
            var result=mock(Pathfinder.PathResult.class);when(result.getPoints()).thenReturn(path);return result;
        });
        manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);setMode("normal");
        when(manager.activeNpcs()).thenAnswer(ignored->List.of(npc));when(manager.find(entity)).thenAnswer(ignored->npc);when(manager.owned(entity)).thenReturn(true);
        // Build dependencies before opening a Mockito stubbing: mock getter calls inside
        // thenReturn(...) would interrupt when(manager.interactions()).
        InteractionService interactions=new InteractionService(messages,logger);
        when(manager.interactions()).thenReturn(interactions);
        visuals=mock(RoutineVisuals.class);when(visuals.restoreSleep(any(),anyBoolean())).thenReturn(true);
        when(visuals.enter(any(),any(),any(),any(),anyLong())).thenAnswer(call->{
            RoutineVisuals.Pose pose=new RoutineVisuals.Pose();pose.npc=call.getArgument(0);pose.sleeping=((RoutineGoal)call.getArgument(1)).type()==RoutineGoal.Type.SLEEP;
            if(!pose.sleeping){pose.seat=mock(ArmorStand.class);when(pose.seat.isValid()).thenReturn(true);}return pose;
        });
        routines=new RoutineService(plugin,visuals);when(plugin.routines()).thenReturn(routines);
        var field=MdvNpcPlugin.class.getDeclaredField("routines");field.setAccessible(true);field.set(plugin,routines);
        doCallRealMethod().when(plugin).canInteract(any());
    }

    void setMode(String mode) {
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("npcs.npc.location.world","world");yaml.set("npcs.npc.location.y",64);
        yaml.set("npcs.npc.mode",mode);yaml.set("npcs.npc.interaction.require-line-of-sight",false);
        yaml.set("npcs.npc.interaction.commands",List.of(Map.of("click","RIGHT","executor","PLAYER","command","example job")));
        var definitions=NpcParser.parse(yaml);when(plugin.definitions()).thenReturn(definitions);npc=new ActiveNpc(definitions.get("npc"),position.clone(),entity,null);
    }
    RoutineGoal goal(RoutineGoal.Type type,boolean permission,int x,int start,int end) {
        return new RoutineGoal(1,type,RoutineGoal.WalkMode.CYCLE,start,end,2.4,20,List.of(new RoutineGoal.Point(world.getUID(),x,64,0,0))).withWorkInteraction(permission);
    }
    void install(RoutineGoal goal) throws Exception { routines.repository().put("npc",goal);routines.start();server.getScheduler().performTicks(2); }
    @AfterEach void cleanup() {try{if(routines!=null)routines.close();if(actualShop!=null)actualShop.closeAll();}finally{MockBukkit.unmock();}}

    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"SIT","SLEEP","WALK"})
    void enabledGoalAllowsJobWhileKeepingMusicAndIdleLookingDisabled(RoutineGoal.Type type) throws Exception {
        furniture=type;setMode("musician_flute");install(goal(type,true,type==RoutineGoal.Type.WALK?8:0,420,1080));
        assertEquals(type,routines.activeGoal(npc).type());assertTrue(routines.canUseJob(npc));assertTrue(plugin.canInteract(npc));
        assertFalse(routines.canInteract(npc));assertFalse(routines.canLook(npc));verify(music,never()).working(any());
        if(type!=RoutineGoal.Type.WALK)verify(visuals).enter(eq(npc),any(),any(),any(),anyLong());
        else assertTrue(routines.status("npc").contains("caminando"));
        routines.unavailable(npc,player);verify(player,never()).sendMessage(anyString());
    }

    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"SIT","SLEEP","WALK"})
    void defaultDisabledGoalDoesNotGrantJobUse(RoutineGoal.Type type) throws Exception {
        furniture=type;install(goal(type,false,type==RoutineGoal.Type.WALK?8:0,420,1080));
        assertFalse(routines.canUseJob(npc));assertFalse(plugin.canInteract(npc));assertFalse(routines.canInteract(npc));assertFalse(routines.canLook(npc));
    }

    @Test void workKeepsItsOldArrivalGateAndCanOptIntoJobUseDuringTheJourney() throws Exception {
        RoutineGoal original=goal(RoutineGoal.Type.WORK,false,6,420,1080);install(original);
        assertFalse(routines.canInteract(npc));assertFalse(routines.canUseJob(npc));
        server.getScheduler().performTicks(120);assertTrue(routines.canInteract(npc));assertTrue(routines.canUseJob(npc));assertTrue(routines.canLook(npc));
        routines.stop();position=new Location(world,.5,64,1.5);routines.repository().put("npc",original.withWorkInteraction(true));routines.start();
        server.getScheduler().performTicks(2);assertTrue(routines.canUseJob(npc));assertFalse(routines.canInteract(npc));assertFalse(routines.canLook(npc));
    }

    @Test void scheduleBoundaryAndLiveFlagDisableRevokeThePermissionBeforeTheNextUpdate() throws Exception {
        RoutineGoal allowed=goal(RoutineGoal.Type.SIT,true,0,420,480);install(allowed);assertTrue(routines.canUseJob(npc));
        when(world.getFullTime()).thenReturn(2000L);assertFalse(routines.canUseJob(npc));assertFalse(plugin.canInteract(npc));
        when(world.getFullTime()).thenReturn(1000L);routines.repository().put("npc",allowed.withWorkInteraction(false));
        assertFalse(routines.canUseJob(npc));assertFalse(plugin.canInteract(npc));
    }

    @Test void pausedInvalidAndReplacedNpcsCannotReuseAnActiveGoalsPermission() throws Exception {
        install(goal(RoutineGoal.Type.SIT,true,0,420,1080));assertTrue(routines.canUseJob(npc));
        ActiveNpc stale=new ActiveNpc(npc.definition(),npc.anchor(),entity,null);assertFalse(routines.canUseJob(stale));
        when(entity.isValid()).thenReturn(false);assertFalse(routines.canUseJob(npc));when(entity.isValid()).thenReturn(true);
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of());server.getScheduler().performTicks(22);
        assertTrue(routines.status("npc").contains("suspendido"));assertFalse(routines.canUseJob(npc));
    }

    @Test void failedRoutineDoesNotKeepJobPermission() throws Exception {
        doThrow(new IllegalStateException("native route failure")).when(pathfinder).findPath(any(Location.class));
        install(goal(RoutineGoal.Type.WALK,true,8,420,1080));assertTrue(routines.status("npc").contains("error"));
        assertFalse(routines.canUseJob(npc));assertFalse(plugin.canInteract(npc));
    }

    @Test void selectedAlternativeInheritsTheParentPermissionInAnActualRunningRoutine() throws Exception {
        RoutineGoal root=goal(RoutineGoal.Type.WALK,true,6,420,1080)
                .withAlternatives(List.of(goal(RoutineGoal.Type.WALK,false,8,420,1080)));
        install(root);assertTrue(routines.activeGoal(npc).workInteraction());assertTrue(routines.canUseJob(npc));assertFalse(routines.canInteract(npc));
    }

    @Test void staticNpcAndDisabledRoutineKeepTheOriginalJobBehaviour() throws Exception {
        assertTrue(routines.canUseJob(npc));
        routines.repository().put("npc",goal(RoutineGoal.Type.WALK,false,8,420,1080));
        routines.repository().edit(y->y.set("npcs.npc.enabled",false));assertTrue(routines.canUseJob(npc));assertTrue(plugin.canInteract(npc));
    }

    @Test void rightClickExecutesTheNpcCommandDuringSittingAndRejectsItOutsideTheWindow() throws Exception {
        install(goal(RoutineGoal.Type.SIT,true,0,420,480));NpcListener listener=new NpcListener(plugin);
        var first=new PlayerInteractEntityEvent(player,entity,EquipmentSlot.HAND);listener.interact(first);
        assertTrue(first.isCancelled());verify(player).performCommand("example job");
        when(world.getFullTime()).thenReturn(2000L);listener.interact(new PlayerInteractEntityEvent(player,entity,EquipmentSlot.HAND));
        verify(player,times(1)).performCommand(anyString());verify(player).sendMessage(anyString());
    }

    @Test @SuppressWarnings("unchecked") void aShopOpenedDuringSittingCannotPurchaseAfterTheAllowedGoalExpires() throws Exception {
        setMode("shop");install(goal(RoutineGoal.Type.SIT,true,0,420,480));
        actualShop=new ShopService(plugin);actualShop.load();when(plugin.shops()).thenReturn(actualShop);
        MerchantRecipe recipe=new MerchantRecipe(new ItemStack(Material.DIAMOND),999);
        recipe.setIgnoreDiscounts(true);recipe.setIngredients(List.of(new ItemStack(Material.EMERALD,3)));
        Merchant merchant=mock(Merchant.class);MerchantInventory inventory=mock(MerchantInventory.class);
        when(inventory.getMerchant()).thenReturn(merchant);when(inventory.getSelectedRecipeIndex()).thenReturn(0);
        ItemStack ingredient=new ItemStack(Material.EMERALD,3);
        when(inventory.getSelectedRecipe()).thenReturn(recipe);when(inventory.getItem(0)).thenReturn(ingredient);
        InventoryView view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inventory);when(player.getOpenInventory()).thenReturn(view);
        var type=Class.forName("com.mdvcraft.mdvnpc.shop.ShopService$Session");var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object session=constructor.newInstance("npc",merchant,npc,0L,List.of(new MerchantRecipe(recipe)));
        var sessions=ShopService.class.getDeclaredField("merchants");sessions.setAccessible(true);
        ((Map<UUID,Object>)sessions.get(actualShop)).put(player.getUniqueId(),session);
        PlayerPurchaseEvent valid=new PlayerPurchaseEvent(player,recipe,false,true);actualShop.guardPurchase(valid);assertFalse(valid.isCancelled());
        when(world.getFullTime()).thenReturn(2000L);PlayerPurchaseEvent stale=new PlayerPurchaseEvent(player,recipe,false,true);
        actualShop.guardPurchase(stale);assertTrue(stale.isCancelled());verify(inventory,never()).setItem(anyInt(),any());
    }
}
