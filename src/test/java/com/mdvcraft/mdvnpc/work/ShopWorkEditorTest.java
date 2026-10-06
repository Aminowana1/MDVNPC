package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.editor.NpcEditor;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.*;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.storage.NpcRepository;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopWorkEditorTest {
    @TempDir Path folder;
    ServerMock server;PlayerMock admin;World world;MdvNpcPlugin plugin;NpcRepository repository;RoutineRepository routines;ShopWorkEditor editor;
    AtomicReference<Map<String,NpcDefinition>> definitions=new AtomicReference<>();
    @BeforeEach void start()throws Exception{
        server=MockBukkit.mock();world=server.addSimpleWorld("world");admin=server.addPlayer();admin.setOp(true);admin.teleport(new Location(world,0,65,0));
        Files.writeString(folder.resolve("config.yml"),"{}\n");repository=new NpcRepository(folder);repository.edit(y->{String p="npcs.smith";y.set(p+".location.world","world");y.set(p+".location.world-uuid",world.getUID().toString());y.set(p+".mode","shop");y.set(p+".shop.category","blacksmith");y.set(p+".shop.title","Preserved shop");y.set(p+".custom.keep","yes");});
        definitions.set(repository.load().npcs());routines=new RoutineRepository(folder);installPoint();
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("MDVNPC-test");when(plugin.isEnabled()).thenReturn(true);when(plugin.getServer()).thenReturn(server);
        when(plugin.definitions()).thenAnswer(i->definitions.get());when(plugin.repository()).thenReturn(repository);when(plugin.shops()).thenReturn(mock(ShopService.class));
        when(plugin.npcEditor()).thenReturn(mock(NpcEditor.class));var commands=mock(RoutineCommands.class);when(plugin.routineCommands()).thenReturn(commands);
        var service=mock(RoutineService.class);when(service.repository()).thenReturn(routines);when(plugin.routines()).thenReturn(service);
        editor=new ShopWorkEditor(plugin);when(plugin.workEditor()).thenReturn(editor);
        doAnswer(i->{editor.closeAll();definitions.set(repository.load().npcs());return null;}).when(plugin).reloadNpcs();
    }
    private void installPoint(){var point=new RoutineGoal.Point(world.getUID(),0,65,0,0);var goal=new RoutineGoal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.TARGET,0,0,1,5,List.of(point));routines.install(new RoutineRepository.Snapshot(Map.of("smith",new RoutineRepository.Plan(true,List.of(goal))),Map.of()));}
    @AfterEach void stop(){try{if(editor!=null)editor.closeAll();}finally{MockBukkit.unmock();}}
    private NpcDefinition npc(){return definitions.get().get("smith");}
    private void button(int slot){button(slot,ClickType.LEFT);}
    private void button(int slot,ClickType click){var e=new InventoryClickEvent(admin.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,click,InventoryAction.PICKUP_ALL);editor.inventory(e);assertTrue(e.isCancelled());server.getScheduler().performOneTick();}
    private PlayerInteractEvent select(Block block,EquipmentSlot hand,boolean cancelled){var event=new PlayerInteractEvent(admin,Action.RIGHT_CLICK_BLOCK,null,block,org.bukkit.block.BlockFace.UP,hand);event.setCancelled(cancelled);editor.select(event);return event;}
    @Test void categoriesKeepShopModeAndExistingTradeData()throws Exception{
        editor.open(admin,"smith");button(11);assertEquals(ShopWorkDefinition.Category.VENDOR,npc().shopWork().category());assertEquals(NpcDefinition.Mode.SHOP,npc().mode());button(15);assertEquals(ShopWorkDefinition.Category.BLACKSMITH,npc().shopWork().category());
        var yaml=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(folder.resolve("NPCs/smith/npc.yml").toFile());assertEquals("Preserved shop",yaml.getString("npcs.smith.shop.title"));assertEquals("yes",yaml.getString("npcs.smith.custom.keep"));
    }
    @Test void guidedSelectionSurvivesReloadAndStoresBlockRatherThanBlockAbove(){
        editor.openStations(admin,"smith");button(19);Block forge=world.getBlockAt(-2,64,3);forge.setType(Material.STONE);assertTrue(select(forge,EquipmentSlot.HAND,false).isCancelled());
        assertEquals(new ShopWorkDefinition.Station(world.getUID(),"world",-2,64,3),npc().shopWork().smeltery());forge.setType(Material.AIR);
        Block cauldron=world.getBlockAt(2,64,3);cauldron.setType(Material.WATER_CAULDRON);select(cauldron,EquipmentSlot.HAND,false);
        Block anvil=world.getBlockAt(5,64,3);anvil.setType(Material.CHIPPED_ANVIL);select(anvil,EquipmentSlot.HAND,false);server.getScheduler().performOneTick();
        assertTrue(npc().shopWork().complete());assertEquals(Material.AIR,forge.getType());assertEquals(Material.WATER_CAULDRON,cauldron.getType());assertEquals(Material.CHIPPED_ANVIL,anvil.getType());
    }
    @Test void cauldronAndAnvilRejectWrongBlocksWithoutDiscardingSelection(){
        editor.openStations(admin,"smith");button(13);Block block=world.getBlockAt(1,64,1);block.setType(Material.CAULDRON);select(block,EquipmentSlot.HAND,false);assertNull(npc().shopWork().cauldron());
        block.setType(Material.WATER_CAULDRON);select(block,EquipmentSlot.HAND,false);assertNotNull(npc().shopWork().cauldron());server.getScheduler().performOneTick();
        button(16);block.setType(Material.STONE);select(block,EquipmentSlot.HAND,false);assertNull(npc().shopWork().anvil());block.setType(Material.DAMAGED_ANVIL);select(block,EquipmentSlot.HAND,false);assertNotNull(npc().shopWork().anvil());
    }
    @Test void offHandAndProtectedEventsDoNotSaveStation(){
        editor.openStations(admin,"smith");button(10);Block block=world.getBlockAt(1,64,1);block.setType(Material.FURNACE);
        select(block,EquipmentSlot.OFF_HAND,false);assertNull(npc().shopWork().smeltery());select(block,EquipmentSlot.HAND,true);assertNull(npc().shopWork().smeltery());
        select(block,EquipmentSlot.HAND,false);assertNotNull(npc().shopWork().smeltery());
    }
    @Test void removedPermissionAndCancelDiscardPendingSelection(){
        editor.openStations(admin,"smith");button(10);Block block=world.getBlockAt(1,64,1);admin.setOp(false);select(block,EquipmentSlot.HAND,false);assertNull(npc().shopWork().smeltery());
        admin.setOp(true);editor.openStations(admin,"smith");button(10);editor.cancel(admin);select(block,EquipmentSlot.HAND,false);assertNull(npc().shopWork().smeltery());
    }
    @Test void stationSelectionNeedsExistingNormalWorkPost(){
        routines.install(new RoutineRepository.Snapshot(Map.of(),Map.of()));editor.openStations(admin,"smith");button(19);select(world.getBlockAt(1,64,1),EquipmentSlot.HAND,false);assertNull(npc().shopWork().smeltery());
    }
    @Test void rightClickStationButtonClearsOnlyThatStation()throws Exception{
        repository.edit(y->{String p="npcs.smith.shop.blacksmith.stations.smeltery";y.set(p+".world","world");y.set(p+".x",1);y.set(p+".y",64);y.set(p+".z",1);});definitions.set(repository.load().npcs());
        editor.openStations(admin,"smith");button(10,ClickType.RIGHT);assertNull(npc().shopWork().smeltery());assertEquals(ShopWorkDefinition.Category.BLACKSMITH,npc().shopWork().category());assertEquals(NpcDefinition.Mode.SHOP,npc().mode());
    }
    @Test void queuedInventoryEditsRecheckPermissionBeforeSaving(){
        editor.open(admin,"smith");var e=new InventoryClickEvent(admin.getOpenInventory(),InventoryType.SlotType.CONTAINER,11,ClickType.LEFT,InventoryAction.PICKUP_ALL);editor.inventory(e);admin.setOp(false);server.getScheduler().performOneTick();assertEquals(ShopWorkDefinition.Category.BLACKSMITH,npc().shopWork().category());
    }
}
