package com.mdvcraft.mdvnpc.editor;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.command.NpcCommand;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.RoutineCommands;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.storage.NpcRepository;
import com.mdvcraft.mdvnpc.trait.PrefixEditor;
import com.mdvcraft.mdvnpc.trait.TraitEditor;
import com.mdvcraft.mdvnpc.util.Messages;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcEditorTest {
    @TempDir Path folder;
    ServerMock server;PlayerMock admin;MdvNpcPlugin plugin;NpcRepository repository;NpcEditor editor;
    AnvilView nameView;AnvilInventory nameInventory;Player renamePlayer;AtomicReference<String> input=new AtomicReference<>("Bardo");
    AtomicReference<Map<String,NpcDefinition>> definitions=new AtomicReference<>();
    @BeforeEach void start()throws Exception{
        server=MockBukkit.mock();server.addSimpleWorld("world");admin=server.addPlayer();admin.setOp(true);
        Files.writeString(folder.resolve("config.yml"),"{}\n");repository=new NpcRepository(folder);
        repository.edit(y->{String p="npcs.bard";y.set(p+".location.world","world");y.set(p+".name","Bardo");y.set(p+".speech.prefix","&6[Taberna] {npc} »");y.set(p+".skin.name","Alex");y.set(p+".custom.keep-me","preservado");});
        definitions.set(repository.load().npcs());plugin=mock(MdvNpcPlugin.class);
        when(plugin.getName()).thenReturn("MDVNPC-test");when(plugin.isEnabled()).thenReturn(true);when(plugin.getServer()).thenReturn(server);
        when(plugin.definitions()).thenAnswer(i->definitions.get());when(plugin.repository()).thenReturn(repository);
        when(plugin.shops()).thenReturn(mock(ShopService.class));when(plugin.routineCommands()).thenReturn(mock(RoutineCommands.class));
        when(plugin.prefixEditor()).thenReturn(mock(PrefixEditor.class));when(plugin.traitEditor()).thenReturn(mock(TraitEditor.class));when(plugin.messages()).thenReturn(mock(Messages.class));
        doAnswer(i->{definitions.set(repository.load().npcs());return null;}).when(plugin).reloadNpcs();
        nameView=mock(AnvilView.class);nameInventory=(AnvilInventory)Bukkit.createInventory(null,InventoryType.ANVIL);
        renamePlayer=mock(Player.class);when(renamePlayer.getUniqueId()).thenReturn(admin.getUniqueId());when(renamePlayer.isOnline()).thenReturn(true);when(renamePlayer.hasPermission("mdvnpc.admin")).thenReturn(true);
        when(renamePlayer.getOpenInventory()).thenReturn(nameView);when(nameView.getPlayer()).thenReturn(renamePlayer);when(nameView.getTopInventory()).thenReturn(nameInventory);when(nameView.getRenameText()).thenAnswer(i->input.get());when(nameView.getType()).thenReturn(InventoryType.ANVIL);
        editor=new NpcEditor(plugin,(p,id)->nameView);when(plugin.npcEditor()).thenReturn(editor);
    }
    @AfterEach void stop(){try{if(editor!=null)editor.closeAll();}finally{MockBukkit.unmock();}}
    private void button(int slot){var e=new InventoryClickEvent(admin.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);editor.click(e);assertTrue(e.isCancelled());server.getScheduler().performOneTick();}
    private InventoryClickEvent nameClick(int slot,ClickType type){var e=mock(InventoryClickEvent.class);when(e.getWhoClicked()).thenReturn(renamePlayer);when(e.getView()).thenReturn(nameView);when(e.getClickedInventory()).thenReturn(slot<3?nameInventory:admin.getInventory());when(e.getRawSlot()).thenReturn(slot);when(e.getClick()).thenReturn(type);editor.click(e);verify(e).setCancelled(true);return e;}
    private NpcDefinition def(){return definitions.get().get("bard");}
    @Test void musicianInstrumentAndShopCanBeConfiguredEntirelyByButtons()throws Exception{
        editor.open(admin,"bard");button(11);button(16);button(11);assertEquals(NpcDefinition.Mode.MUSICIAN_FLUTE,def().mode());
        button(11);button(16);button(15);assertEquals(NpcDefinition.Mode.MUSICIAN_GUITAR,def().mode());
        button(11);button(13);assertEquals(NpcDefinition.Mode.SHOP,def().mode());button(13);verify(plugin.shops()).openEditor(admin,"bard");
        assertEquals("Alex",def().skin().name());assertEquals("&6[Taberna] {npc} »",def().speech().prefix());
        var yaml=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(folder.resolve("NPCs/bard/npc.yml").toFile());assertEquals("preservado",yaml.getString("npcs.bard.custom.keep-me"));
    }
    @Test void shopButtonGuidesNormalNpcToChooseShopWithoutOpeningTradeEditor(){
        editor.open(admin,"bard");button(13);verify(plugin.shops(),never()).openEditor(any(),anyString());assertEquals(Material.CHEST,admin.getOpenInventory().getTopInventory().getItem(13).getType());
    }
    @Test void queuedEditsRecheckPermissionAndOpenInventoryBeforeSaving()throws Exception{
        editor.open(admin,"bard");button(11);
        var click=new InventoryClickEvent(admin.getOpenInventory(),InventoryType.SlotType.CONTAINER,13,ClickType.LEFT,InventoryAction.PICKUP_ALL);editor.click(click);admin.setOp(false);server.getScheduler().performOneTick();assertEquals(NpcDefinition.Mode.NORMAL,repository.load().npcs().get("bard").mode());
        admin.setOp(true);editor.open(admin,"bard");button(11);click=new InventoryClickEvent(admin.getOpenInventory(),InventoryType.SlotType.CONTAINER,13,ClickType.LEFT,InventoryAction.PICKUP_ALL);editor.click(click);admin.closeInventory();server.getScheduler().performOneTick();assertEquals(NpcDefinition.Mode.NORMAL,def().mode());
    }
    @Test void anvilPreviewsAndSavesNameWithoutAllowingAnyVanillaTransfer()throws Exception{
        editor.openName(renamePlayer,"bard");input.set("&6Lorenzo el Bardo");var prepare=new PrepareAnvilEvent(nameView,null);editor.prepare(prepare);assertNotNull(prepare.getResult());
        nameClick(0,ClickType.LEFT);nameClick(5,ClickType.SHIFT_LEFT);assertEquals("Bardo",def().name());
        var drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(nameView);when(drag.getWhoClicked()).thenReturn(renamePlayer);editor.drag(drag);verify(drag).setCancelled(true);
        nameClick(2,ClickType.LEFT);assertEquals("Bardo",def().name(),"Persistencia diferida fuera del evento de clic");server.getScheduler().performOneTick();
        assertEquals("&6Lorenzo el Bardo",def().name());assertEquals("Alex",def().skin().name());assertEquals(NpcDefinition.Mode.NORMAL,def().mode());assertTrue(nameInventory.isEmpty());verify(renamePlayer).closeInventory();
        verify(nameView,atLeastOnce()).setRepairCost(0);verify(nameView,atLeastOnce()).setRepairItemCountCost(0);
    }
    @Test void renamedByOtherAdminIsNotOverwrittenByAnvil()throws Exception{
        editor.openName(renamePlayer,"bard");repository.edit(y->y.set("npcs.bard.name","Otro nombre"));input.set("El que pierde");nameClick(2,ClickType.LEFT);server.getScheduler().performOneTick();assertEquals("Otro nombre",repository.load().npcs().get("bard").name());
    }
    @Test void anvilRejectsInvalidInputAndClearsVirtualTokensWhenClosed(){
        editor.openName(renamePlayer,"bard");input.set(" ");var prepare=new PrepareAnvilEvent(nameView,null);editor.prepare(prepare);assertNull(prepare.getResult());nameClick(2,ClickType.LEFT);assertEquals("Bardo",def().name());
        var close=new InventoryCloseEvent(nameView);editor.close(close);assertTrue(nameInventory.isEmpty());
    }
    @Test void nameLimitMatchesFiftyCharactersAvailableInVanillaAnvil(){
        editor.openName(renamePlayer,"bard");
        for(int length:List.of(49,50)){input.set("a".repeat(length));var prepare=new PrepareAnvilEvent(nameView,null);editor.prepare(prepare);assertNotNull(prepare.getResult());}
        input.set("a".repeat(51));var prepare=new PrepareAnvilEvent(nameView,null);editor.prepare(prepare);assertNull(prepare.getResult());nameClick(2,ClickType.LEFT);server.getScheduler().performOneTick();assertEquals("Bardo",def().name());
        input.set("a".repeat(50));nameClick(2,ClickType.LEFT);server.getScheduler().performOneTick();assertEquals(50,def().name().length());
    }
    @Test void commandAliasesAndFiesteroCompleteWithoutTypingConfiguration() {
        var command=new NpcCommand(plugin);assertTrue(command.onTabComplete(admin,null,"mdvnpc",new String[]{"edit","b"}).contains("bard"));assertTrue(command.onTabComplete(admin,null,"mdvnpc",new String[]{"trait","bard","f"}).contains("fiestero"));
        command.onCommand(admin,null,"mdvnpc",new String[]{"menu","bard"});assertEquals(Material.NAME_TAG,admin.getOpenInventory().getTopInventory().getItem(10).getType());
    }
}
