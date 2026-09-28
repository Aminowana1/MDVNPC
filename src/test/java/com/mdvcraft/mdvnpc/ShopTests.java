package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.shop.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopTests {
    @org.junit.jupiter.api.BeforeEach void startServer() { org.mockbukkit.mockbukkit.MockBukkit.mock(); }
    @org.junit.jupiter.api.AfterEach void stopServer() { org.mockbukkit.mockbukkit.MockBukkit.unmock(); }
    @TempDir Path temp;
    @Test void oldNpcsRemainNormalAndShopModeIsValidated() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/npcs.yml")) {
            yaml.loadFromString(new String(Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(NpcDefinition.Mode.NORMAL, NpcParser.parse(yaml).get("thurg").mode());
        yaml.set("npcs.thurg.mode", "shop");
        yaml.set("npcs.thurg.shop.trade-dialogue.lines", List.of("Hola"));
        assertEquals(NpcDefinition.Mode.SHOP, NpcParser.parse(yaml).get("thurg").mode());
        yaml.set("npcs.thurg.mode", "broken");
        assertThrows(IllegalArgumentException.class, () -> NpcParser.parse(yaml));
    }
    @Test void lookHasIndependentCadence() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("update-interval-ticks", 20); yaml.set("look-update-interval-ticks", 4);
        Settings settings = Settings.parse(yaml);
        assertEquals(20, settings.intervalTicks()); assertEquals(4, settings.lookIntervalTicks());
        yaml.set("look-update-interval-ticks", 0);
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(yaml));
    }
    @Test void itemIdentityAndSnapshotRoundTrip() {
        YamlConfiguration yaml = new YamlConfiguration();
        ShopItem id = new ShopItem(ShopItem.Kind.MMOITEMS, "SWORD", "LUNAR", null, 3);
        id.write(yaml.createSection("mmo"));
        assertEquals(id, ShopItem.read(yaml.getConfigurationSection("mmo")));
        ShopItem custom = new ShopItem(ShopItem.Kind.SNAPSHOT, null, null, new ItemStack(Material.EMERALD, 7), 7);
        custom.write(yaml.createSection("custom"));
        ShopItem copy = ShopItem.read(yaml.getConfigurationSection("custom"));
        assertEquals(Material.EMERALD, copy.snapshot().getType());
        assertEquals(7, copy.amount());
        assertThrows(IllegalArgumentException.class, () -> new ShopOffer(id, null, null));
    }
    @Test void pagesPreserveOtherOffersAndBackup() throws Exception {
        Files.writeString(temp.resolve("shops.yml"), "shops: {}\n");
        ShopRepository repo = new ShopRepository(temp);
        repo.load();
        ShopItem price = new ShopItem(ShopItem.Kind.MMOITEMS, "MATERIAL", "DENAR", null, 2);
        ShopItem result = new ShopItem(ShopItem.Kind.MMOITEMS, "SWORD", "LUNAR", null, 1);
        repo.savePage("merchant", 0, Map.of(0, new ShopOffer(result, price, null)));
        repo.savePage("merchant", 1, Map.of(1, new ShopOffer(result, price, null)));
        assertEquals(2, repo.offers("merchant").size());
        assertEquals(2, repo.pages("merchant"));
        assertTrue(Files.exists(temp.resolve("NPCs/merchant/shop.yml.bak")));
        repo.savePage("merchant", 0, Map.of());
        assertFalse(repo.offers("merchant").containsKey(0));
        assertTrue(repo.offers("merchant").containsKey(10));
        repo.load();
        assertEquals(1, repo.offers("merchant").size());
        repo.delete("merchant");
        assertTrue(repo.offers("merchant").isEmpty());
    }
}
