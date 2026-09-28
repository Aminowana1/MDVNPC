package com.mdvcraft.mdvnpc;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.ItemStackMock;
import org.mockbukkit.mockbukkit.util.UnsafeValuesMock;
import java.util.*;

/** Test-only YAML adapter: MockBukkit 4.56 has no serializeStack implementation.
 * This exercises our repositories, NOT Paper's component/NBT serializer. */
final class TestServer extends ServerMock {
    TestServer() {
        org.bukkit.configuration.serialization.ConfigurationSerialization.registerClass(ItemStackMock.class);
    }
    private final UnsafeValuesMock values = new UnsafeValuesMock() {
        @Override public Map<String, Object> serializeStack(ItemStack item) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("v", getDataVersion());
            map.put("type", item.getType().name()); map.put("amount", item.getAmount());
            if (item.hasItemMeta()) map.put("meta", item.getItemMeta());
            return map;
        }
        @Override public ItemStack deserializeStack(Map<String, Object> map) {
            ItemStack item = new ItemStackMock(Material.valueOf((String) map.get("type")), ((Number) map.getOrDefault("amount", 1)).intValue());
            if (map.get("meta") instanceof ItemMeta meta) item.setItemMeta(meta);
            return item;
        }
    };
    @Override public UnsafeValuesMock getUnsafe() { return values; }
}
