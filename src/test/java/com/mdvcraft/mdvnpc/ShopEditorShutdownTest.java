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

class ShopEditorShutdownTest {
    @TempDir Path folder;
    ServerMock server;
    MdvNpcPlugin plugin;
    ShopService shops;
    PlayerMock admin;
    org.bukkit.plugin.java.JavaPlugin owner;
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
        owner = MockBukkit.createMockPlugin(); server.getPluginManager().registerEvents(shops, owner);
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
    private int stored(boolean enabled) throws Exception {
        shops.openEditor(admin,"shop"); deposit(0,Material.DIAMOND,2); deposit(9,Material.EMERALD,3);
        var f=org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("isEnabled"); f.setAccessible(true); f.set(owner,enabled);
        shops.closeAll();
        var r=new ShopRepository(folder); r.load(); return r.offers("shop").size();
    }
    @Test void enabledPluginSavesTheOpenEditor() throws Exception { assertEquals(1, stored(true)); }
    @Test void disablingPluginSavesTheOpenEditorWithoutItsCloseListener() throws Exception { assertEquals(1, stored(false)); }
}
