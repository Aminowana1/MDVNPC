package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.routine.*;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutineCommandTest {
    @TempDir Path folder;ServerMock server;PlayerMock player;MdvNpcPlugin plugin;RoutineService service;RoutineCommands commands;World world;
    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock(new TestServer());world=server.addSimpleWorld("world");player=server.addPlayer();player.teleport(new Location(world,0,65,0));player.setOp(true);
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("MDVNPC-test");when(plugin.isEnabled()).thenReturn(true);when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        var y=new YamlConfiguration();y.set("npcs.shop.location.world","world");var definitions=NpcParser.parse(y);when(plugin.definitions()).thenReturn(definitions);
        var manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.resolveWorld(any())).thenReturn(world);when(plugin.shops()).thenReturn(mock(ShopService.class));
        service=new RoutineService(plugin);when(plugin.routines()).thenReturn(service);commands=new RoutineCommands(plugin);
    }
    @AfterEach void stop(){commands.clear();service.close();MockBukkit.unmock();}
    void command(String input)throws Exception {commands.command(player,input.split(" "));}
    void chat(String input) {
        var event=new AsyncPlayerChatEvent(false,player,input,new HashSet<>());commands.chat(event);assertTrue(event.isCancelled());server.getScheduler().performOneTick();
    }
    PlayerInteractEvent click(Action action,int x,EquipmentSlot hand) {
        var b=world.getBlockAt(x,63,0);b.setType(Material.STONE);
        var event=new PlayerInteractEvent(player,action,null,b,org.bukkit.block.BlockFace.UP,hand);commands.click(event);return event;
    }
    @Test void chatHoursThenFloorClickStoresWorkAndCancelsVanillaInteraction()throws Exception {
        command("routine shop goal 1 trabajo");chat("07:00");chat("18:00");
        assertTrue(click(Action.RIGHT_CLICK_BLOCK,0,EquipmentSlot.HAND).isCancelled());
        var goal=service.repository().read().plans().get("shop").goals().getFirst();assertEquals(420,goal.start());assertEquals(1080,goal.end());assertEquals(64,goal.points().getFirst().y());verify(plugin).reloadNpcs();
    }
    @Test void metaLeftClicksAddPointsRightClickConfirmsWithoutExtraPoint()throws Exception {
        command("routine shop goal 2 caminar meta 2.0");click(Action.LEFT_CLICK_BLOCK,0,EquipmentSlot.HAND);click(Action.LEFT_CLICK_BLOCK,3,EquipmentSlot.HAND);
        assertTrue(service.repository().read().plans().isEmpty());click(Action.RIGHT_CLICK_BLOCK,7,EquipmentSlot.HAND);
        var goal=service.repository().read().plans().get("shop").goals().getFirst();assertTrue(goal.target());assertEquals(2,goal.points().size());assertEquals(2,goal.speed());
    }
    @Test void offhandDoesNotDuplicateAndCancelDoesNotPersist()throws Exception {
        command("routine shop goal 2 caminar meta");click(Action.LEFT_CLICK_BLOCK,0,EquipmentSlot.OFF_HAND);chat("cancelar");
        assertTrue(service.repository().read().plans().isEmpty());verify(plugin,never()).reloadNpcs();
    }
    @Test void concurrentEditIsNotOverwrittenBySelection()throws Exception {
        command("routine shop goal 1 trabajo 7 18");
        var other=new RoutineGoal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,420,1080,3,20,List.of(new RoutineGoal.Point(world.getUID(),9,64,0,0)));
        service.repository().put("shop",other);click(Action.RIGHT_CLICK_BLOCK,0,EquipmentSlot.HAND);
        assertEquals(other,service.repository().read().plans().get("shop").goals().getFirst());verify(plugin,never()).reloadNpcs();
    }
    @Test void revokedPermissionAndChangedWorldCannotCommit()throws Exception {
        command("routine shop goal 1 trabajo 7 18");player.setOp(false);click(Action.RIGHT_CLICK_BLOCK,0,EquipmentSlot.HAND);assertTrue(service.repository().read().plans().isEmpty());
        player.setOp(true);command("routine shop goal 1 trabajo 7 18");player.teleport(new Location(server.addSimpleWorld("other"),0,64,0));click(Action.RIGHT_CLICK_BLOCK,0,EquipmentSlot.HAND);assertTrue(service.repository().read().plans().isEmpty());
    }
    @Test void emptyConfirmationDoesNotCreateAnInvalidGoal()throws Exception {
        command("routine shop goal 1 caminar ciclo 7 18");click(Action.RIGHT_CLICK_BLOCK,0,EquipmentSlot.HAND);assertTrue(service.repository().read().plans().isEmpty());
    }
    private RoutineGoal rootWithBarOption() throws Exception {
        var work=new RoutineGoal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,420,1080,2.4,20,List.of(new RoutineGoal.Point(world.getUID(),0,64,0,0)));
        var bar=new RoutineGoal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,420,1080,2.4,20,List.of(new RoutineGoal.Point(world.getUID(),8,63,0,0)));
        var root=work.withAlternatives(List.of(bar));service.repository().put("shop",root);return root;
    }
    private void menuClick(int slot) {
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        commands.editor().inventory(event);assertTrue(event.isCancelled());
    }
    @Test void optionsMenuExplainsModeAndKeepsAlternativesWhenSwitchingToFixed()throws Exception {
        rootWithBarOption();commands.editor().openGoal(player,"shop",1);menuClick(18);
        assertEquals(Material.IRON_PICKAXE,player.getOpenInventory().getTopInventory().getItem(0).getType());
        assertEquals(Material.OAK_STAIRS,player.getOpenInventory().getTopInventory().getItem(1).getType());
        menuClick(19);var root=service.repository().read().plans().get("shop").goals().getFirst();
        assertFalse(root.randomChoice());assertEquals(2,root.choiceCount());assertTrue(player.getOpenInventory().getTopInventory().getItem(19).getItemMeta().getLore().stream().anyMatch(line->line.contains("principal")));
    }
    @Test void editingAlternativeSpeedChangesOnlyThatAction()throws Exception {
        rootWithBarOption();commands.editor().openOptions(player,"shop",1);menuClick(1);menuClick(12);
        var event=new AsyncPlayerChatEvent(false,player,"1.2",new HashSet<>());commands.editor().chat(event);assertTrue(event.isCancelled());server.getScheduler().performOneTick();
        var root=service.repository().read().plans().get("shop").goals().getFirst();assertEquals(2.4,root.speed());assertEquals(1.2,root.choice(1).speed());assertEquals(420,root.choice(1).start());
    }
    @Test void addingWalkOptionReusesWorldSelectionAndInheritsHours()throws Exception {
        var original=rootWithBarOption();commands.editor().openOptions(player,"shop",1);menuClick(21);menuClick(13);
        click(Action.LEFT_CLICK_BLOCK,20,EquipmentSlot.HAND);click(Action.RIGHT_CLICK_BLOCK,20,EquipmentSlot.HAND);
        var root=service.repository().read().plans().get("shop").goals().getFirst();assertEquals(3,root.choiceCount());assertEquals(original.choice(1),root.choice(1));
        assertEquals(RoutineGoal.Type.WALK,root.choice(2).type());assertEquals(420,root.choice(2).start());assertEquals(1080,root.choice(2).end());assertEquals(20,root.choice(2).points().getFirst().x());
    }
    @Test void deletingAlternativeKeepsPrincipalAndOtherSchedule()throws Exception {
        var original=rootWithBarOption();commands.editor().openOptions(player,"shop",1);menuClick(1);menuClick(16);
        var root=service.repository().read().plans().get("shop").goals().getFirst();assertEquals(original.choice(0),root);assertFalse(root.randomChoice());
    }
    @Test void reselectingPrincipalDoesNotEraseRandomOptions()throws Exception {
        var original=rootWithBarOption();commands.beginReselect(player,"shop",original);click(Action.RIGHT_CLICK_BLOCK,4,EquipmentSlot.HAND);
        var root=service.repository().read().plans().get("shop").goals().getFirst();assertEquals(4,root.points().getFirst().x());assertEquals(original.choice(1),root.choice(1));assertTrue(root.randomChoice());
    }
}
