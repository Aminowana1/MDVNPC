package com.mdvcraft.mdvnpc.shop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import java.util.Locale;

/** Item storage is either a live MMOItems type/id, or a full Bukkit item snapshot (including item meta/PDC). */
public record ShopItem(Kind kind, String type, String id, ItemStack snapshot, int amount) {
    public enum Kind { MMOITEMS, SNAPSHOT }
    public ShopItem {
        java.util.Objects.requireNonNull(kind, "Falta kind");
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Cantidad fuera de 1..64");
        if (kind == Kind.MMOITEMS && (type == null || type.isBlank() || id == null || id.isBlank()))
            throw new IllegalArgumentException("MMOItems necesita tipo e ID");
        if (kind == Kind.SNAPSHOT && (snapshot == null || snapshot.getType() == Material.AIR))
            throw new IllegalArgumentException("Item vacío");
        if (snapshot != null) snapshot = snapshot.clone();
    }
    @Override public ItemStack snapshot() { return snapshot == null ? null : snapshot.clone(); }
    public static ShopItem fromItem(ItemStack item, MmoItemBridge bridge) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) return null;
        if (item.getAmount() > item.getMaxStackSize()) throw new IllegalArgumentException("Cantidad mayor al máximo de este ítem");
        var identity = bridge.identity(item);
        if (identity != null) return new ShopItem(Kind.MMOITEMS, identity.type(), identity.id(), null, item.getAmount());
        return new ShopItem(Kind.SNAPSHOT, null, null, item.clone(), item.getAmount());
    }
    public ItemStack resolve(MmoItemBridge bridge) {
        ItemStack result = kind == Kind.MMOITEMS ? bridge.create(type, id) : snapshot();
        if (result == null || result.getType() == Material.AIR) return null;
        if (amount > result.getMaxStackSize()) return null; // Native prices clamp; never silently discount oversized costs.
        result.setAmount(amount);
        return result;
    }
    public static ShopItem read(ConfigurationSection section) {
        if (section == null) return null;
        Kind kind = Kind.valueOf(section.getString("kind", "SNAPSHOT").toUpperCase(Locale.ROOT));
        Object rawAmount = section.get("amount", 1);
        if (!(rawAmount instanceof Number number) || number.doubleValue() != number.intValue())
            throw new IllegalArgumentException("Cantidad debe ser un entero");
        int amount = ((Number) rawAmount).intValue();
        return new ShopItem(kind, section.getString("type"), section.getString("id"),
                kind == Kind.SNAPSHOT ? section.getItemStack("item") : null, amount);
    }
    public void write(ConfigurationSection section) {
        section.set("kind", kind.name()); section.set("amount", amount);
        if (kind == Kind.MMOITEMS) { section.set("type", type); section.set("id", id); }
        else section.set("item", snapshot());
    }
}
