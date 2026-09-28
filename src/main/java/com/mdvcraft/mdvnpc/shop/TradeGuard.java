package com.mdvcraft.mdvnpc.shop;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import java.util.List;

/** Native ingredient matching permits extra components. Require exact metadata to avoid consuming valuables. */
public final class TradeGuard {
    private TradeGuard() {}
    public static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }
    private static boolean pays(ItemStack actual, ItemStack required) {
        return empty(required) ? empty(actual) : !empty(actual) && actual.isSimilar(required) && actual.getAmount() >= required.getAmount();
    }
    public static boolean inputs(MerchantRecipe recipe, ItemStack a, ItemStack b) {
        List<ItemStack> costs = recipe.getIngredients();
        if (costs.isEmpty() || costs.size() > 2) return false;
        ItemStack first = costs.getFirst(), second = costs.size() == 2 ? costs.get(1) : null;
        return pays(a, first) && pays(b, second) || pays(b, first) && pays(a, second);
    }
    public static boolean same(MerchantRecipe a, MerchantRecipe b) {
        return a != null && b != null && a.getResult().equals(b.getResult())
                && a.getIngredients().equals(b.getIngredients()) && b.getSpecialPrice() == 0 && b.getDemand() == 0
                && b.getPriceMultiplier() == 0 && b.shouldIgnoreDiscounts();
    }
}
