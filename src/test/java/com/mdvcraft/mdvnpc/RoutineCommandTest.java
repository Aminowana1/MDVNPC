package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.routine.*;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
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
}
