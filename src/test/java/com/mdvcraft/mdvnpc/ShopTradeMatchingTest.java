package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.util.Messages;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopTradeMatchingTest {
    @TempDir Path folder;
    ShopService shops;
    Player player;
    Villager entity;
    MerchantRecipe recipe;
    MerchantInventory inventory;
    World world;
    MdvNpcPlugin plugin;
    @BeforeEach @SuppressWarnings("unchecked") void start() throws Exception {
        var server = MockBukkit.mock(new TestServer()); world = server.addSimpleWorld("world");
        plugin = mock(MdvNpcPlugin.class); var manager = mock(NpcManager.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true);
        when(plugin.canInteract(any())).thenReturn(true); when(plugin.manager()).thenReturn(manager); when(plugin.messages()).thenReturn(mock(Messages.class));
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        var yaml = new YamlConfiguration(); yaml.createSection("npcs.shop");
        yaml.set("npcs.shop.mode", "shop"); yaml.set("npcs.shop.location.world", "world");
        yaml.set("npcs.shop.interaction.permission", "shop.use");
        yaml.set("npcs.shop.interaction.require-line-of-sight", false);
        var definitions = NpcParser.parse(yaml); when(plugin.definitions()).thenReturn(definitions);
        entity = mock(Villager.class); when(entity.isValid()).thenReturn(true);
        var active = new ActiveNpc(definitions.get("shop"), new Location(world, 0, 64, 0), entity, null);
        when(manager.find(entity)).thenReturn(active);
        player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true); when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 1, 64, 0)); when(player.hasPermission("shop.use")).thenReturn(true);
        recipe = new MerchantRecipe(new ItemStack(Material.DIAMOND), 999); recipe.setIgnoreDiscounts(true);
        recipe.setIngredients(List.of(new ItemStack(Material.EMERALD, 3)));
        var merchant = mock(Merchant.class); inventory = mock(MerchantInventory.class);
        when(inventory.getMerchant()).thenReturn(merchant); when(inventory.getSelectedRecipeIndex()).thenReturn(0);
        when(inventory.getSelectedRecipe()).thenReturn(recipe); when(inventory.getItem(0)).thenReturn(new ItemStack(Material.EMERALD, 3));
        var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
        shops = new ShopService(plugin); shops.load();
        // Inject a session to test authorization independently of MockBukkit's unimplemented native merchant opening.
        var type = Class.forName("com.mdvcraft.mdvnpc.shop.ShopService$Session");
        var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object session = constructor.newInstance("shop", merchant, active, 0L, List.of(new MerchantRecipe(recipe), new MerchantRecipe(second())));
        var field = ShopService.class.getDeclaredField("merchants"); field.setAccessible(true);
        ((Map<UUID, Object>) field.get(shops)).put(player.getUniqueId(), session);
    }
    @AfterEach void stop() { shops.closeAll(); MockBukkit.unmock(); }
    private PlayerPurchaseEvent purchase() { var event = new PlayerPurchaseEvent(player, recipe, false, true); shops.guardPurchase(event); return event; }
    MerchantRecipe second(){ var r=new MerchantRecipe(new ItemStack(Material.GOLD_INGOT),999); r.setIgnoreDiscounts(true); r.setIngredients(List.of(new ItemStack(Material.EMERALD,5))); return r; }
    private boolean cancelled(MerchantRecipe traded, int paid) {
        when(inventory.getSelectedRecipe()).thenReturn(traded);
        when(inventory.getItem(0)).thenReturn(new ItemStack(Material.EMERALD, paid));
        var event = new PlayerPurchaseEvent(player, traded, false, true);
        shops.guardPurchase(event); return event.isCancelled();
    }
    @Test void secondOfferWorksWhenTheHintStillPointsAtTheFirst() { assertFalse(cancelled(second(), 5)); }
    @Test void offerOutsideTheSessionIsRejected() {
        var forged = second(); forged.setIngredients(List.of(new ItemStack(Material.EMERALD, 1)));
        assertTrue(cancelled(forged, 1));
    }
    @Test void underpaymentIsRejected() { assertTrue(cancelled(second(), 4)); }
}
