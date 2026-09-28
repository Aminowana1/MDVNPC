package com.mdvcraft.mdvnpc.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.function.IntPredicate;

/** Container transfers restricted to editable slots, never the navigation row. */
public final class EditorTransfers {
    private EditorTransfers() {}
    public static ItemStack move(ItemStack source, Inventory target, int size, IntPredicate allowed) {
        if (TradeGuard.empty(source)) return null;
        ItemStack rest = source.clone();
        for (int pass = 0; pass < 2; pass++) for (int slot = 0; slot < size && rest.getAmount() > 0; slot++) {
            if (!allowed.test(slot)) continue;
            ItemStack existing = target.getItem(slot);
            boolean empty = TradeGuard.empty(existing);
            if (pass == 0 && empty || pass == 1 && !empty || !empty && !existing.isSimilar(rest)) continue;
            int limit = Math.min(target.getMaxStackSize(), rest.getMaxStackSize());
            int present = empty ? 0 : existing.getAmount();
            int count = Math.min(rest.getAmount(), Math.max(0, limit - present));
            if (count == 0) continue;
            ItemStack placed = empty ? rest.clone() : existing.clone();
            placed.setAmount(present + count); target.setItem(slot, placed);
            rest.setAmount(rest.getAmount() - count);
        }
        return rest.getAmount() == 0 ? null : rest;
    }
    public static ItemStack collect(ItemStack cursor, Inventory inventory, int size, IntPredicate allowed) {
        if (TradeGuard.empty(cursor)) return cursor;
        ItemStack result = cursor.clone();
        for (int slot = 0; slot < size && result.getAmount() < result.getMaxStackSize(); slot++) {
            if (!allowed.test(slot)) continue;
            ItemStack item = inventory.getItem(slot);
            if (TradeGuard.empty(item) || !item.isSimilar(result)) continue;
            int count = Math.min(item.getAmount(), result.getMaxStackSize() - result.getAmount());
            ItemStack remaining = item.clone(); remaining.setAmount(item.getAmount() - count);
            inventory.setItem(slot, remaining.getAmount() == 0 ? null : remaining);
            result.setAmount(result.getAmount() + count);
        }
        return result;
    }
}
