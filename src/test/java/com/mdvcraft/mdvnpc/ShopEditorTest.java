package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.shop.*;
import com.mdvcraft.mdvnpc.util.Messages;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.ItemStackMock;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopEditorTest {
    @TempDir Path folder;
    ServerMock server;
    MdvNpcPlugin plugin;
    ShopService shops;
    PlayerMock admin;
    @BeforeEach void start() throws Exception {
        server = MockBukkit.mock(new TestServer()); admin = server.addPlayer(); admin.setOp(true);
        plugin = mock(MdvNpcPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("MDVNPC-test");
        when(plugin.messages()).thenReturn(mock(Messages.class));
        var yaml = new YamlConfiguration(); yaml.createSection("npcs.shop");
        yaml.set("npcs.shop.mode", "shop"); yaml.set("npcs.shop.location.world", "world");
        when(plugin.definitions()).thenReturn(NpcParser.parse(yaml));
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        shops = new ShopService(plugin); shops.load();
        server.getPluginManager().registerEvents(shops, MockBukkit.createMockPlugin());
    }
    @AfterEach void stop() { shops.closeAll(); MockBukkit.unmock(); }
    private Inventory top() { return admin.getOpenInventory().getTopInventory(); }
    private ItemStack item(Material type, int count) { return new ItemStackMock(type, count); }
    private InventoryClickEvent click(int raw, ClickType type, InventoryAction action) {
        var e = new InventoryClickEvent(admin.getOpenInventory(), InventoryType.SlotType.CONTAINER, raw, type, action);
        shops.click(e); return e;
    }
    private void reopen() {
        admin.closeInventory(); shops.prune(System.nanoTime() + 2_000_000_000L); shops.openEditor(admin, "shop");
    }
    private void deposit(int slot, Material type, int count) {
        admin.setItemOnCursor(item(type, count));
        assertFalse(click(slot, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled());
        // MockBukkit does not execute NMS clicks. Simulate the native commit AFTER our handler.
        assertNull(top().getItem(slot));
        top().setItem(slot, admin.getItemOnCursor()); admin.setItemOnCursor(null);
    }
    @Test void normalPlacementIsNotClonedAndNativeCommitPersists() throws Exception {
        shops.openEditor(admin, "shop"); deposit(0, Material.DIAMOND, 2); deposit(9, Material.EMERALD, 3);
        admin.closeInventory();
        var repo = new ShopRepository(folder); repo.load();
        assertEquals(2, repo.offers("shop").get(0).result().amount());
        assertEquals(3, repo.offers("shop").get(0).cost1().amount());
    }
    @Test void shiftMovesInAndOutExactlyOnce() {
        shops.openEditor(admin, "shop");
        admin.getOpenInventory().setItem(36, item(Material.DIAMOND, 12));
        assertTrue(click(36, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled());
        assertTrue(TradeGuard.empty(admin.getOpenInventory().getItem(36)));
        assertEquals(12, top().getItem(0).getAmount());
        click(0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertTrue(TradeGuard.empty(top().getItem(0)));
        assertEquals(12, admin.getInventory().getItem(0).getAmount());
        reopen(); assertTrue(TradeGuard.empty(top().getItem(0)));
    }
    @Test void shiftMergesThenLeavesRemainderWhenEditorFull() {
        shops.openEditor(admin, "shop");
        for (int i = 0; i < 27; i++) top().setItem(i, item(Material.STONE, 64));
        top().setItem(0, item(Material.STONE, 60));
        admin.getOpenInventory().setItem(36, item(Material.STONE, 10));
        click(36, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertEquals(64, top().getItem(0).getAmount());
        assertEquals(6, admin.getOpenInventory().getItem(36).getAmount());
        assertEquals(Material.ARROW, top().getItem(29).getType());
    }
    @Test void shiftOutDoesNotLoseItemsWhenPlayerInventoryFull() {
        shops.openEditor(admin, "shop"); top().setItem(0, item(Material.DIAMOND, 8));
        for (int i = 0; i < 36; i++) admin.getInventory().setItem(i, item(Material.STONE, 64));
        click(0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertEquals(8, top().getItem(0).getAmount());
    }
    @Test void removingResultPersistsCostsAsDraftInsteadOfRestoringResult() throws Exception {
        shops.openEditor(admin, "shop"); deposit(0, Material.DIAMOND, 2); deposit(9, Material.EMERALD, 3); reopen();
        click(0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY); reopen();
        assertTrue(TradeGuard.empty(top().getItem(0)));
        assertEquals(3, top().getItem(9).getAmount());
        var repo = new ShopRepository(folder); repo.load(); assertTrue(repo.offers("shop").isEmpty());
        assertEquals(3, repo.editorPage("shop", 0)[9].amount());
        assertEquals(2, admin.getInventory().getItem(0).getAmount());
    }
    @Test void incompletePageIsSavedAcrossReload() throws Exception {
        shops.openEditor(admin, "shop"); deposit(0, Material.DIAMOND, 4);
        assertDoesNotThrow(shops::prepareReload); shops.load(); shops.openEditor(admin, "shop");
        assertEquals(4, top().getItem(0).getAmount());
        var repo = new ShopRepository(folder); repo.load(); assertTrue(repo.offers("shop").isEmpty());
    }
    @Test void dragAllowedInEditableRowsButCancelledIfItTouchesNavigation() {
        shops.openEditor(admin, "shop");
        var allowed = new InventoryDragEvent(admin.getOpenInventory(), item(Material.DIAMOND, 2), item(Material.DIAMOND, 4), false,
                Map.of(0, item(Material.DIAMOND, 1), 9, item(Material.DIAMOND, 1)));
        shops.drag(allowed); assertFalse(allowed.isCancelled()); assertNull(top().getItem(0));
        var blocked = new InventoryDragEvent(admin.getOpenInventory(), null, item(Material.DIAMOND, 2), false,
                Map.of(0, item(Material.DIAMOND, 1), 31, item(Material.DIAMOND, 1)));
        shops.drag(blocked); assertTrue(blocked.isCancelled()); assertNull(top().getItem(0));
    }
    @Test void doubleClickCollectsEditableItemsButNeverSaveButton() {
        shops.openEditor(admin, "shop"); top().setItem(0, item(Material.EMERALD, 4));
        admin.getOpenInventory().setItem(36, item(Material.EMERALD, 3)); admin.setItemOnCursor(item(Material.EMERALD, 1));
        click(36, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        assertEquals(8, admin.getItemOnCursor().getAmount());
        assertTrue(TradeGuard.empty(top().getItem(0))); assertEquals(Material.EMERALD, top().getItem(31).getType());
    }
    @Test void normalPickupSplitHotbarAndOffhandAreDelegated() {
        shops.openEditor(admin, "shop"); top().setItem(0, item(Material.DIAMOND, 8));
        assertFalse(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled());
        assertFalse(click(0, ClickType.RIGHT, InventoryAction.PICKUP_HALF).isCancelled());
        var number = new InventoryClickEvent(admin.getOpenInventory(), InventoryType.SlotType.CONTAINER, 0,
                ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 1);
        shops.click(number); assertFalse(number.isCancelled());
        assertFalse(click(0, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP).isCancelled());
        assertEquals(8, top().getItem(0).getAmount()); // Listener never duplicates native operations.
    }
    @Test void cancelledClicksAndControlsDoNotTransferItems() {
        shops.openEditor(admin, "shop"); admin.getOpenInventory().setItem(36, item(Material.DIAMOND, 4));
        var e = new InventoryClickEvent(admin.getOpenInventory(), InventoryType.SlotType.CONTAINER, 36,
                ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY); e.setCancelled(true); shops.click(e);
        assertNull(top().getItem(0)); assertEquals(4, admin.getOpenInventory().getItem(36).getAmount());
        assertTrue(click(31, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled());
    }
    @Test void anotherAdminCannotEditLockedShop() {
        PlayerMock other = server.addPlayer(); other.setOp(true);
        shops.openEditor(admin, "shop"); shops.openEditor(other, "shop");
        assertNull(other.getOpenInventory().getTopInventory()); verify(plugin.messages()).send(other, "shop-busy");
    }
    @Test void unresolvedReferencesCannotBeExtractedAsBarrierItems() throws Exception {
        ShopItem reference = new ShopItem(ShopItem.Kind.MMOITEMS, "SWORD", "MISSING", null, 1);
        var repo = new ShopRepository(folder); repo.load();
        repo.savePage("shop", 0, Map.of(0, new ShopOffer(reference, reference, null)));
        shops.load(); shops.openEditor(admin, "shop");
        assertTrue(click(0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled());
        assertEquals(Material.BARRIER, top().getItem(0).getType());
        assertTrue(TradeGuard.empty(admin.getInventory().getItem(0)));
        admin.setItemOnCursor(null); click(0, ClickType.RIGHT, InventoryAction.PICKUP_HALF); reopen();
        assertTrue(TradeGuard.empty(top().getItem(0))); assertEquals(Material.BARRIER, top().getItem(9).getType());
        repo.load(); assertTrue(repo.offers("shop").isEmpty());
        assertEquals(reference, repo.editorPage("shop", 0)[9]);
    }
    @Test void completingDraftPublishesTradeAndClearingItRemovesAllStoredItems() throws Exception {
        shops.openEditor(admin, "shop"); deposit(9, Material.EMERALD, 3); reopen();
        deposit(0, Material.DIAMOND, 1); reopen();
        var repo = new ShopRepository(folder); repo.load(); assertEquals(1, repo.offers("shop").size());
        click(0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        click(9, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY); reopen();
        repo.load(); assertTrue(repo.offers("shop").isEmpty());
        assertTrue(Arrays.stream(repo.editorPage("shop", 0)).allMatch(Objects::isNull));
    }
    @Test void closingBeforeDeferredPageSwitchDoesNotReopenInventory() {
        shops.openEditor(admin, "shop"); click(33, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        admin.closeInventory(); server.getScheduler().performOneTick(); assertNull(top());
    }
}


