package com.mdvcraft.mdvnpc.work;

import org.bukkit.Location;
import org.bukkit.World;
import java.util.Locale;
import java.util.UUID;

/** Optional work visuals for a shop; trading remains Mode.SHOP. */
public record ShopWorkDefinition(Category category, Station smeltery, Station cauldron, Station anvil, FishingDefinition fishing) {
    public ShopWorkDefinition { category = category == null ? Category.VENDOR : category; fishing = fishing == null ? FishingDefinition.defaults() : fishing; }
    public ShopWorkDefinition(Category category, Station smeltery, Station cauldron, Station anvil) { this(category,smeltery,cauldron,anvil,FishingDefinition.defaults()); }
    public static ShopWorkDefinition defaults() { return new ShopWorkDefinition(Category.VENDOR,null,null,null); }
    public boolean complete() { return category == Category.FISHERMAN ? fishing.complete() : smeltery != null && cauldron != null && anvil != null; }
    public enum Category {
        VENDOR, BLACKSMITH, FISHERMAN;
        public static Category parse(String text) {
            return switch(text == null ? "vendor" : text.trim().toLowerCase(Locale.ROOT)) {
                case "vendor", "vendedor", "tienda" -> VENDOR;
                case "blacksmith", "herrero" -> BLACKSMITH;
                case "fisherman", "pescador" -> FISHERMAN;
                default -> throw new IllegalArgumentException("shop.category: usar vendor/vendedor, blacksmith/herrero o fisherman/pescador");
            };
        }
    }
    /** Block coordinates persist when a selected smeltery block is subsequently removed. */
    public record Station(UUID worldId, String worldName, int x, int y, int z) {
        public Station {
            worldName = worldName == null ? "" : worldName;
            if(worldId == null && worldName.isBlank())throw new IllegalArgumentException("La estación necesita mundo o UUID");
            if(x < -29999984 || x > 29999984 || z < -29999984 || z > 29999984 || y < -2048 || y > 2048)
                throw new IllegalArgumentException("Coordenadas de estación fuera del mundo");
        }
        public Location location(World world) {
            if(world == null || (worldId != null ? !worldId.equals(world.getUID()) : !worldName.equals(world.getName())))return null;
            return new Location(world,x,y,z);
        }
    }
}
