package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.shop.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TradeGuardTest {
    @TempDir Path folder;
    @BeforeEach void start() { MockBukkit.mock(new TestServer()); }
    @AfterEach void stop() { MockBukkit.unmock(); }
    private MerchantRecipe recipe(ItemStack a, ItemStack b) {
        MerchantRecipe r = new MerchantRecipe(new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.DIAMOND), 999);
        r.setIgnoreDiscounts(true); r.setIngredients(b == null ? List.of(a) : List.of(a,b)); return r;
    }
    @Test void requiresBothCostsAndAcceptsSwappedSlots() {
        var r = recipe(new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 3), new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.GOLD_INGOT, 2));
        assertTrue(TradeGuard.inputs(r, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 3), new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.GOLD_INGOT, 2)));
        assertTrue(TradeGuard.inputs(r, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.GOLD_INGOT, 2), new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 64)));
        assertFalse(TradeGuard.inputs(r, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 2), new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.GOLD_INGOT, 2)));
        assertFalse(TradeGuard.inputs(r, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 3), null));
    }
    @Test void neverConsumesCustomVariantForVanillaCost() {
        ItemStack custom = org.mockito.Mockito.spy(new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 3));
        var meta = custom.getItemMeta(); meta.setDisplayName("Special coin");
        meta.getPersistentDataContainer().set(new NamespacedKey("test", "coin"), PersistentDataType.STRING, "rare");
        custom.setItemMeta(meta);
        // MockBukkit 4.56 isSimilar compares material only. Supply Bukkit's metadata contract for this test.
        org.mockito.Mockito.doAnswer(call -> {
            ItemStack other = call.getArgument(0);
            return other != null && other.getType() == custom.getType() && Objects.equals(other.getItemMeta(), custom.getItemMeta());
        }).when(custom).isSimilar(org.mockito.ArgumentMatchers.any());
        assertFalse(TradeGuard.inputs(recipe(new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 3), null), custom, null));
        assertTrue(TradeGuard.inputs(recipe(custom, null), custom.clone(), null));
        assertFalse(TradeGuard.inputs(recipe(custom, null), custom, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.DIRT)));
    }
    @Test void identicalTwoCostsCannotSpendOneStackTwice() {
        var cost = new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 3);
        assertFalse(TradeGuard.inputs(recipe(cost, cost), new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 6), null));
        assertTrue(TradeGuard.inputs(recipe(cost, cost), cost.clone(), cost.clone()));
    }
    @Test void alteredPricesAndResultsAreRejected() {
        var r = recipe(new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD), null);
        var changed = new MerchantRecipe(r); assertTrue(TradeGuard.same(r, changed));
        changed.setSpecialPrice(-1); assertFalse(TradeGuard.same(r, changed));
        assertFalse(TradeGuard.same(null, r));
        var other = recipe(new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 2), null); assertFalse(TradeGuard.same(r, other));
    }
    @Test void repositoryRoundTripWithTestYamlAdapterPreservesMetadata() throws Exception {
        ItemStack item = new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.EMERALD, 7);
        var meta = item.getItemMeta(); meta.setDisplayName("Moneda"); meta.setLore(List.of("Custom lore"));
        meta.getPersistentDataContainer().set(new NamespacedKey("test", "id"), PersistentDataType.STRING, "custom"); item.setItemMeta(meta);
        ShopItem stored = new ShopItem(ShopItem.Kind.SNAPSHOT, null, null, item, 7);
        var repo = new ShopRepository(folder); repo.load();
        repo.savePage("shop", 0, Map.of(0, new ShopOffer(stored, stored, null)));
        var reboot = new ShopRepository(folder); reboot.load();
        assertEquals(item, reboot.offers("shop").get(0).result().snapshot());
        assertEquals(item.getItemMeta(), reboot.offers("shop").get(0).result().snapshot().getItemMeta());
        item.setAmount(1); assertEquals(7, stored.snapshot().getAmount());
    }
    @Test void oversizedNonStackableOfferIsUnavailableRatherThanDiscounted() {
        ShopItem item = new ShopItem(ShopItem.Kind.SNAPSHOT, null, null, new org.mockbukkit.mockbukkit.inventory.ItemStackMock(Material.DIAMOND_SWORD), 2);
        assertNull(item.resolve(null));
    }
    @Test void externalEditsAndInvalidIndicesDoNotOverwriteDisk() throws Exception {
        ShopItem item = new ShopItem(ShopItem.Kind.MMOITEMS, "MATERIAL", "COIN", null, 1);
        var repo = new ShopRepository(folder); repo.load();
        repo.savePage("shop", 0, Map.of(0, new ShopOffer(item, item, null)));
        Path file = folder.resolve("NPCs/shop/shop.yml");
        String external = Files.readString(file).replace("COIN", "OTHER");
        Files.writeString(file, external);
        assertThrows(IllegalStateException.class, () -> repo.savePage("shop", 0, Map.of()));
        assertEquals(external, Files.readString(file));
        assertThrows(IllegalArgumentException.class, () -> repo.savePage("shop", 0, Map.of(9, new ShopOffer(item, item, null))));
    }
}

