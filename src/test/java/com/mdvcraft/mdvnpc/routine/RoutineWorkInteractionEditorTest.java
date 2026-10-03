package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoutineWorkInteractionEditorTest {
    @TempDir Path folder;
    ServerMock server;PlayerMock admin;MdvNpcPlugin plugin;RoutineRepository repository;
    RoutineEditor editor;RoutineCommands commands;RoutineGoal original;

    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock();var world=server.addSimpleWorld("world");admin=server.addPlayer();admin.setOp(true);
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("MDVNPC-goal-test");when(plugin.getServer()).thenReturn(server);when(plugin.isEnabled()).thenReturn(true);
        var npcs=new YamlConfiguration();npcs.set("npcs.bard.location.world","world");npcs.set("npcs.bard.name","Bardo");when(plugin.definitions()).thenReturn(NpcParser.parse(npcs));
        repository=new RoutineRepository(folder);var routines=mock(RoutineService.class);when(plugin.routines()).thenReturn(routines);when(routines.repository()).thenReturn(repository);
        when(plugin.shops()).thenReturn(mock(ShopService.class));commands=mock(RoutineCommands.class);editor=new RoutineEditor(plugin,commands);
        doAnswer(i->{repository.install(repository.read());return null;}).when(plugin).reloadNpcs();
        var chair=new RoutineGoal.Point(world.getUID(),3,64,4,90);
        var route=List.of(new RoutineGoal.Point(world.getUID(),5,64,7,0),new RoutineGoal.Point(world.getUID(),8,64,7,0));
        var dialogue=new RoutineGoal.Dialogue(true,6,25,3,false,true,List.of("Buenas noches"));
        var alternate=new RoutineGoal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.RANDOM,600,900,3.1,12,route,dialogue);
        original=new RoutineGoal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,600,900,2.4,20,List.of(chair),dialogue)
                .withAlternatives(List.of(alternate)).withRandomChoice(true);
        repository.put("bard",original);
    }
    @AfterEach void cleanup(){if(editor!=null)editor.clear();MockBukkit.unmock();}
    private RoutineGoal root(){return repository.snapshot().plans().get("bard").goals().getFirst();}
    private InventoryClickEvent click(int slot){
        var event=new InventoryClickEvent(admin.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        editor.inventory(event);assertTrue(event.isCancelled());return event;
    }
    private ItemStack item(int slot){return admin.getOpenInventory().getTopInventory().getItem(slot);}
    private List<String> lore(int slot){return item(slot).getItemMeta().getLore().stream().map(ChatColor::stripColor).toList();}
    private void assertPersisted(RoutineGoal expected) throws Exception {
        assertEquals(expected,root());assertEquals(expected,repository.read().plans().get("bard").goals().getFirst());
        for(int choice=0;choice<root().choiceCount();choice++)assertEquals(expected.workInteraction(),root().choice(choice).workInteraction());
    }
    @SuppressWarnings("deprecation") private void chat(String text){
        var event=new AsyncPlayerChatEvent(false,admin,text,new HashSet<>());editor.chat(event);assertTrue(event.isCancelled());server.getScheduler().performOneTick();
    }

    @Test void principalTogglePersistsOnAndOffWithoutChangingActivitiesOrOptions() throws Exception {
        editor.openGoal(admin,"bard",1);assertEquals(Material.GRAY_DYE,item(19).getType());
        assertEquals("Atender durante este goal",ChatColor.stripColor(item(19).getItemMeta().getDisplayName()));
        assertTrue(lore(19).contains("Atención extra desactivada"));click(19);assertPersisted(original.withWorkInteraction(true));
        assertEquals(Material.LIME_DYE,item(19).getType());assertTrue(lore(19).contains("Atención extra activada"));
        click(19);assertPersisted(original);assertEquals(Material.GRAY_DYE,item(19).getType());
    }
    @Test void alternateToggleChangesTheRootAndKeepsItsAlternateScreen() throws Exception {
        editor.openChoice(admin,"bard",1,1);click(19);assertPersisted(original.withWorkInteraction(true));
        assertEquals(Material.LEATHER_BOOTS,item(4).getType());assertTrue(lore(4).contains("Atención extra: activada en todas las opciones"));
        click(19);assertPersisted(original);assertEquals(Material.LEATHER_BOOTS,item(4).getType());
        verify(plugin,times(2)).reloadNpcs();
    }
    @Test void optionsMenuControlsTheSameRootSettingAndWritesItOnlyAtRoot() throws Exception {
        editor.openOptions(admin,"bard",1);assertTrue(lore(23).contains("Se aplica a todas las opciones del goal."));
        click(23);assertPersisted(original.withWorkInteraction(true));
        assertTrue(lore(0).contains("Atención extra: activada en todas las opciones"));assertTrue(lore(1).contains("Atención extra: activada en todas las opciones"));
        var yaml=YamlConfiguration.loadConfiguration(folder.resolve("NPCs/bard/routines.yml").toFile());
        assertTrue(yaml.getBoolean("npcs.bard.goals.1.work-interaction"));assertFalse(yaml.contains("npcs.bard.goals.1.alternatives.1.work-interaction"));
        click(23);assertPersisted(original);
    }
    @Test void mainAndAlternateSummariesShowSharedAttentionAndSelection() throws Exception {
        repository.put("bard",original.withWorkInteraction(true));editor.openMain(admin,"bard");
        assertTrue(lore(0).contains("Atención extra: activada en todas las opciones"));
        editor.openChoice(admin,"bard",1,1);assertTrue(lore(4).contains("Selección: Aleatoria (2 opciones)"));
        assertTrue(lore(19).contains("Permite comandos y compras del NPC"));assertTrue(lore(19).contains("mientras realiza esta actividad."));
        assertTrue(lore(19).contains("También al caminar hacia el destino."));assertTrue(lore(19).contains("Trabajo sigue atendiendo en su puesto."));
    }
    @Test void workOptionExplainsUsualServiceWithExtraAttentionOff() throws Exception {
        var work=new RoutineGoal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,600,900,2.4,20,List.of(original.points().getFirst()));
        repository.put("bard",original.withChoice(1,work));editor.openChoice(admin,"bard",1,1);
        assertTrue(lore(4).contains("Trabajo atiende en su puesto."));assertTrue(lore(19).contains("Atención extra desactivada"));
        editor.openOptions(admin,"bard",1);assertTrue(lore(1).contains("Trabajo atiende en su puesto."));
    }
    @Test void revokedPermissionCancelsTheClickWithoutSaving() throws Exception {
        editor.openGoal(admin,"bard",1);admin.setOp(false);click(19);assertPersisted(original);
        verify(plugin,never()).reloadNpcs();verify(plugin.shops(),never()).prepareReload();
    }
    @Test void bottomInventoryAndDragCannotToggleAttention() throws Exception {
        editor.openGoal(admin,"bard",1);click(27+19);assertPersisted(original);
        var drag=mock(InventoryDragEvent.class);when(drag.getInventory()).thenReturn(admin.getOpenInventory().getTopInventory());editor.drag(drag);verify(drag).setCancelled(true);
        verify(plugin,never()).reloadNpcs();
    }
    @Test void changingScheduleAndRandomSelectionRetainsAttention() throws Exception {
        repository.put("bard",original.withWorkInteraction(true));editor.openGoal(admin,"bard",1);click(10);chat("11:00 16:00");
        var expected=original.withWorkInteraction(true).withTimes(660,960);assertPersisted(expected);
        editor.openOptions(admin,"bard",1);click(19);assertPersisted(expected.withRandomChoice(false));
        assertTrue(lore(23).contains("Atención extra activada"));
    }
    @Test void changingAlternateWalkModeRetainsSharedAttention() throws Exception {
        repository.put("bard",original.withWorkInteraction(true));editor.openChoice(admin,"bard",1,1);click(13);click(15);
        var expected=original.withWorkInteraction(true).withChoice(1,original.choice(1).withMode(RoutineGoal.WalkMode.CYCLE));assertPersisted(expected);
        assertTrue(lore(19).contains("Atención extra activada"));
    }
    @Test void assigningScheduleToAMetaRouteRetainsAttention() throws Exception {
        var meta=new RoutineGoal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.TARGET,0,0,2.4,20,original.choice(1).points()).withWorkInteraction(true);
        repository.put("bard",meta);editor.openGoal(admin,"bard",1);click(13);click(13);chat("11:00 16:00");
        assertPersisted(meta.withMode(RoutineGoal.WalkMode.RANDOM).withTimes(660,960));
    }
    @Test void cancelingChatEditAndReselectingPointsRetainTheSharedSetting() throws Exception {
        repository.put("bard",original.withWorkInteraction(true));editor.openChoice(admin,"bard",1,1);click(12);chat("cancelar");
        assertPersisted(original.withWorkInteraction(true));click(11);
        verify(commands).beginReselect(eq(admin),eq("bard"),argThat(g->g.workInteraction() && g.points().equals(original.choice(1).points())),eq(1));
        assertPersisted(original.withWorkInteraction(true));
    }
}
