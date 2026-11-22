package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.shop.*;
import com.mdvcraft.mdvnpc.util.Messages;
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

/** What a player is offered each time a shop opens. */
class ShopOpeningTest {
    @TempDir Path folder;
    ShopService shops;
    MmoItemBridge bridge;
    ActiveNpc npc;
    World world;
    final List<Material> offered = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        var server = MockBukkit.mock(new TestServer()); world = server.addSimpleWorld("world");
        var plugin = mock(MdvNpcPlugin.class); var manager = mock(NpcManager.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.manager()).thenReturn(manager); when(plugin.messages()).thenReturn(mock(Messages.class));
        when(plugin.canInteract(any())).thenReturn(true);
        var config = new YamlConfiguration(); config.set("ignore-invisible-players", false);
        when(plugin.settings()).thenReturn(Settings.parse(config));
        var yaml = new YamlConfiguration(); yaml.set("npcs.shop.mode", "shop"); yaml.set("npcs.shop.location.world", "world");
        yaml.set("npcs.shop.interaction.require-line-of-sight", false);
        var entity = mock(Villager.class); when(entity.isValid()).thenReturn(true); when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(call -> new Location(world, 0, 64, 0));
        npc = new ActiveNpc(NpcParser.parse(yaml).get("shop"), new Location(world, 0, 64, 0), entity, null);
        when(manager.find(entity)).thenReturn(npc);

        shops = new ShopService(plugin);
        bridge = mock(MmoItemBridge.class);
        when(bridge.create("SWORD", "RANDOM")).thenAnswer(new org.mockito.stubbing.Answer<ItemStack>() {
            int roll;
            public ItemStack answer(org.mockito.invocation.InvocationOnMock call) {
                return new ItemStack(List.of(Material.DIAMOND, Material.EMERALD, Material.IRON_INGOT, Material.GOLD_INGOT).get(roll++));
            }
        });
        var offer = new ShopOffer(new ShopItem(ShopItem.Kind.MMOITEMS, "SWORD", "RANDOM", null, 1),
                new ShopItem(ShopItem.Kind.SNAPSHOT, null, null, new ItemStack(Material.STICK), 1), null);
        var repository = mock(ShopRepository.class);
        var offers = new TreeMap<Integer, ShopOffer>(Map.of(0, offer));
        doReturn(offers).when(repository).offers("shop");
        set("bridge", bridge); set("repository", repository);
    }
    @AfterEach void cleanup() { PlayerFilter.hiddenViewers(id -> false); MockBukkit.unmock(); }
    private void set(String name, Object value) throws Exception {
        var field = ShopService.class.getDeclaredField(name); field.setAccessible(true); field.set(shops, value);
    }
    private Player player() {
        var player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenAnswer(call -> new Location(world, 1, 64, 0));
        var view = mock(InventoryView.class); var top = mock(Inventory.class);
        when(view.getTopInventory()).thenReturn(top); when(player.getOpenInventory()).thenReturn(view);
        doAnswer(call -> {
            offered.add(((Merchant) call.getArgument(0)).getRecipe(0).getResult().getType()); return null;
        }).when(player).openMerchant(any(Merchant.class), eq(true));
        return player;
    }
    private void open(Player player) throws Exception {
        var method = ShopService.class.getDeclaredMethod("openShopAfterClick", Player.class, ActiveNpc.class);
        method.setAccessible(true);
        var merchant = mock(Merchant.class); // MockBukkit has none and would silently skip the test.
        var recipes = new ArrayList<MerchantRecipe>();
        doAnswer(call -> { recipes.addAll(call.getArgument(0)); return null; }).when(merchant).setRecipes(any());
        when(merchant.getRecipe(0)).thenAnswer(call -> recipes.getFirst());
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.createMerchant(anyString())).thenReturn(merchant);
            method.invoke(shops, player, npc);
        }
    }

    @Test void reopeningDoesNotRerollAnMmoItem() throws Exception {
        var player = player(); open(player); open(player);
        assertEquals(List.of(Material.DIAMOND, Material.DIAMOND), offered);
    }
    @Test void everyPlayerHasTheirOwnRoll() throws Exception {
        open(player()); open(player());
        assertEquals(List.of(Material.DIAMOND, Material.EMERALD), offered);
    }
    @Test void adminReloadRefreshesTheRoll() throws Exception {
        var player = player(); open(player); shops.load(); open(player);
        assertEquals(List.of(Material.DIAMOND, Material.EMERALD), offered);
    }
    @Test void hiddenPlayerCannotOpenTheShop() throws Exception {
        var player = player(); var id = player.getUniqueId(); PlayerFilter.hiddenViewers(id::equals);
        open(player);
        assertTrue(offered.isEmpty());
    }
}
