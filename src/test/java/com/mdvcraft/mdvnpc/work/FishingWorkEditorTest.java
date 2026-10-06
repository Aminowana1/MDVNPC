package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.editor.NpcEditor;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.*;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.storage.NpcRepository;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

class FishingWorkEditorTest {
    @TempDir Path folder;
    ServerMock server;PlayerMock admin;World world;MdvNpcPlugin plugin;NpcRepository repository;RoutineRepository routines;ShopWorkEditor editor;
    AtomicReference<Map<String,NpcDefinition>> definitions=new AtomicReference<>();
    @BeforeEach void start()throws Exception{
        server=MockBukkit.mock();world=server.addSimpleWorld("world");admin=server.addPlayer();admin.setOp(true);admin.teleport(new Location(world,0,65,0,90,0));
        Files.writeString(folder.resolve("config.yml"),"{}\n");repository=new NpcRepository(folder);repository.edit(y->{String p="npcs.fisher";y.set(p+".location.world","world");y.set(p+".location.world-uuid",world.getUID().toString());y.set(p+".mode","shop");y.set(p+".shop.category","fisherman");y.set(p+".shop.title","Preserved shop");y.set(p+".custom.keep","yes");});
        definitions.set(repository.load().npcs());routines=new RoutineRepository(folder);
        var point=new RoutineGoal.Point(world.getUID(),0,65,0,0);var goal=new RoutineGoal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.TARGET,0,0,1,5,List.of(point));routines.install(new RoutineRepository.Snapshot(Map.of("fisher",new RoutineRepository.Plan(true,List.of(goal))),Map.of()));
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("MDVNPC-test");when(plugin.isEnabled()).thenReturn(true);when(plugin.getServer()).thenReturn(server);
        when(plugin.definitions()).thenAnswer(i->definitions.get());when(plugin.repository()).thenReturn(repository);when(plugin.shops()).thenReturn(mock(ShopService.class));
        when(plugin.npcEditor()).thenReturn(mock(NpcEditor.class));var commands=mock(RoutineCommands.class);when(plugin.routineCommands()).thenReturn(commands);
        var service=mock(RoutineService.class);when(service.repository()).thenReturn(routines);when(plugin.routines()).thenReturn(service);
        editor=new ShopWorkEditor(plugin);when(plugin.workEditor()).thenReturn(editor);
        doAnswer(i->{editor.closeAll();definitions.set(repository.load().npcs());return null;}).when(plugin).reloadNpcs();
    }
    @AfterEach void stop(){try{if(editor!=null)editor.closeAll();}finally{MockBukkit.unmock();}}
    private FishingDefinition fishing(){return definitions.get().get("fisher").shopWork().fishing();}
    private void button(int slot){button(slot,ClickType.LEFT);}
    private void button(int slot,ClickType click){var e=new InventoryClickEvent(admin.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,click,InventoryAction.PICKUP_ALL);editor.inventory(e);assertTrue(e.isCancelled());server.getScheduler().performOneTick();}
    private PlayerInteractEvent select(Block block){var e=new PlayerInteractEvent(admin,Action.RIGHT_CLICK_BLOCK,null,block,BlockFace.UP,EquipmentSlot.HAND);e.setCancelled(false);editor.select(e);return e;}
    private Block floor(int x,int y,int z){Block block=world.getBlockAt(x,y,z);block.setType(Material.STONE);return block;}
    private PlayerInteractEvent selectWater(Block water,boolean initiallyCancelled){
        Player player=mock(Player.class,delegatesTo(admin));doReturn(new RayTraceResult(new Vector(water.getX()+.5,water.getY()+1,water.getZ()+.5),water,BlockFace.UP)).when(player).rayTraceBlocks(16,FluidCollisionMode.ALWAYS);
        var event=new PlayerInteractEvent(player,Action.RIGHT_CLICK_AIR,null,null,BlockFace.SELF,EquipmentSlot.HAND);event.setCancelled(initiallyCancelled);editor.select(event);return event;
    }
    @Test void fishermanCategoryPreservesShopAndPreviousBlacksmithStations()throws Exception{
        repository.edit(y->{String path="npcs.fisher.shop.blacksmith.stations.smeltery";y.set(path+".world","world");y.set(path+".x",1);y.set(path+".y",64);y.set(path+".z",1);});definitions.set(repository.load().npcs());
        editor.open(admin,"fisher");button(11);assertEquals(ShopWorkDefinition.Category.VENDOR,definitions.get().get("fisher").shopWork().category());button(13);
        var npc=definitions.get().get("fisher");assertEquals(NpcDefinition.Mode.SHOP,npc.mode());assertEquals(ShopWorkDefinition.Category.FISHERMAN,npc.shopWork().category());assertNotNull(npc.shopWork().smeltery());
        var yaml=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(folder.resolve("NPCs/fisher/npc.yml").toFile());assertEquals("Preserved shop",yaml.getString("npcs.fisher.shop.title"));assertEquals("yes",yaml.getString("npcs.fisher.custom.keep"));
    }
    @Test void guidedSetupSavesFeetAndWaterSurfaceWithDirectionAndSurvivesReload(){
        editor.openStations(admin,"fisher");button(19);select(floor(-2,64,3));assertEquals(new FishingDefinition.Point(world.getUID(),"world",-1.5,65,3.5,90),fishing().shorePoints().getFirst());
        admin.teleport(new Location(world,0,65,0,-90,0));select(floor(2,64,3));assertEquals(-90,fishing().dock().yaw());assertEquals(65,fishing().dock().y());
        admin.teleport(new Location(world,0,65,0,180,0));Block water=world.getBlockAt(8,64,3);water.setType(Material.WATER);assertTrue(selectWater(water,true).isCancelled());
        assertTrue(fishing().complete());assertEquals(new FishingDefinition.Point(world.getUID(),"world",8.5,65,3.5,-180),fishing().boatPoints().getFirst());assertEquals(Material.WATER,water.getType());assertEquals(Material.STONE,world.getBlockAt(2,64,3).getType());
    }
    @Test void shoreListAddsMultiplePointsAndRemovesOnlyChosenPoint(){
        editor.openStations(admin,"fisher");button(10);assertEquals(54,admin.getOpenInventory().getTopInventory().getSize());button(45);select(floor(1,64,1));server.getScheduler().performOneTick();
        button(45);select(floor(4,64,1));server.getScheduler().performOneTick();assertEquals(2,fishing().shorePoints().size());
        button(9,ClickType.RIGHT);assertEquals(1,fishing().shorePoints().size());assertEquals(4.5,fishing().shorePoints().getFirst().x());assertNull(fishing().dock());
    }
    @Test void invalidAndSubmergedBoatSelectionsRemainPending(){
        editor.openStations(admin,"fisher");button(16);button(45);Block block=floor(8,64,3);selectWater(block,false);assertTrue(fishing().boatPoints().isEmpty());
        block.setType(Material.WATER);world.getBlockAt(8,65,3).setType(Material.WATER);selectWater(block,false);assertTrue(fishing().boatPoints().isEmpty());
        world.getBlockAt(8,65,3).setType(Material.AIR);selectWater(block,false);assertEquals(1,fishing().boatPoints().size());
    }
    @Test void protectedGroundEventAndRemovedPermissionNeverSave(){
        editor.openStations(admin,"fisher");button(10);button(45);Block block=floor(1,64,1);
        var event=new PlayerInteractEvent(admin,Action.RIGHT_CLICK_BLOCK,null,block,BlockFace.UP,EquipmentSlot.HAND);event.setCancelled(true);editor.select(event);assertTrue(fishing().shorePoints().isEmpty());
        admin.setOp(false);select(block);assertTrue(fishing().shorePoints().isEmpty());admin.setOp(true);select(block);assertTrue(fishing().shorePoints().isEmpty());
    }
    @Test void setupRequiresWorkPostAndCancelDropsPendingSelection(){
        routines.install(new RoutineRepository.Snapshot(Map.of(),Map.of()));editor.openStations(admin,"fisher");button(19);select(floor(1,64,1));assertTrue(fishing().shorePoints().isEmpty());
        editor.cancel(admin);assertNull(fishing().dock());
    }
    @Test void existingListLimitPreventsStartingAnotherPoint()throws Exception{
        var point=Map.of("world","world","x",.5,"y",65d,"z",.5,"yaw",0f);repository.edit(y->y.set("npcs.fisher.shop.fisherman.shore-points",Collections.nCopies(32,point)));definitions.set(repository.load().npcs());
        editor.openStations(admin,"fisher");button(10);button(45);assertFalse(select(floor(1,64,1)).isCancelled());assertEquals(32,fishing().shorePoints().size());
    }
    @Test void concurrentListChangeCannotBeOverwrittenByOldSelection()throws Exception{
        editor.openStations(admin,"fisher");button(10);button(45);
        var point=Map.of("world","world","x",8.5,"y",65d,"z",.5,"yaw",0f);repository.edit(y->y.set("npcs.fisher.shop.fisherman.shore-points",List.of(point)));
        select(floor(1,64,1));var loaded=repository.load().npcs().get("fisher").shopWork().fishing();assertEquals(1,loaded.shorePoints().size());assertEquals(8.5,loaded.shorePoints().getFirst().x());
    }
}
