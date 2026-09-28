package com.mdvcraft.mdvnpc.shop;

/** One villager-style trade: one result, one required ingredient and one optional ingredient. */
public record ShopOffer(ShopItem result, ShopItem cost1, ShopItem cost2) {
    public ShopOffer {
        if (result == null || cost1 == null) throw new IllegalArgumentException("Un trueque necesita resultado y primer costo");
    }
}
