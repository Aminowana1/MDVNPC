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
    private InventoryClickEvent click(int slot, ItemStack cursor, ClickType type) {
        admin.setItemOnCursor(cursor);
        InventoryClickEvent event = new InventoryClickEvent(admin.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot, type, InventoryAction.PLACE_ALL);
        shops.click(event); return event;
    }
    @Test void templatesCopyWithoutTakingItemsAndPersistOnClose() throws Exception {
        shops.openEditor(admin, "shop");
        ItemStack result = new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.DIAMOND, 2);
        assertTrue(click(0, result, ClickType.LEFT).isCancelled());
        assertEquals(result, admin.getItemOnCursor());
        click(9, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 3), ClickType.LEFT);
        admin.closeInventory();
        var repo = new ShopRepository(folder); repo.load();
        assertEquals(2, repo.offers("shop").get(0).result().amount());
        assertEquals(3, repo.offers("shop").get(0).cost1().amount());
    }
    @Test void anotherAdminCannotEditLockedShop() {
        PlayerMock other = server.addPlayer(); other.setOp(true);
        shops.openEditor(admin, "shop"); shops.openEditor(other, "shop");
        assertNull(other.getOpenInventory().getTopInventory());
        verify(plugin.messages()).send(other, "shop-busy");
    }
    @Test void incompletePageBlocksReloadAndWritesRecoveryOnClose() throws Exception {
        shops.openEditor(admin, "shop"); click(0, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.DIAMOND), ClickType.LEFT);
        assertThrows(IllegalStateException.class, shops::prepareReload);
        admin.closeInventory();
        var repo = new ShopRepository(folder); repo.load(); assertTrue(repo.offers("shop").isEmpty());
        try (var files = Files.list(folder.resolve("shop-recovery"))) { assertEquals(1, files.count()); }
    }
    @Test void cancelledClicksAndBottomShiftNeverModifyTemplates() {
        shops.openEditor(admin, "shop");
        var cancelled = new InventoryClickEvent(admin.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        admin.setItemOnCursor(new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.DIAMOND)); cancelled.setCancelled(true); shops.click(cancelled);
        assertNull(admin.getOpenInventory().getTopInventory().getItem(0));
        assertTrue(click(36, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.DIAMOND), ClickType.SHIFT_LEFT).isCancelled());
        assertTrue(click(0, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.DIAMOND), ClickType.DROP).isCancelled());
        assertNull(admin.getOpenInventory().getTopInventory().getItem(0));
    }
    @Test void closingBeforeDeferredPageSwitchDoesNotReopenInventory() {
        shops.openEditor(admin, "shop"); click(33, null, ClickType.LEFT);
        admin.closeInventory(); server.getScheduler().performOneTick();
        assertNull(admin.getOpenInventory().getTopInventory());
    }
}

