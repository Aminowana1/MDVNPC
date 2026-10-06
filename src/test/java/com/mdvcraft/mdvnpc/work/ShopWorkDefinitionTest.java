package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopWorkDefinitionTest {
    private YamlConfiguration config(){var yaml=new YamlConfiguration();yaml.set("npcs.smith.location.world","world");yaml.set("npcs.smith.mode","shop");yaml.set("npcs.smith.custom.preserve","yes");return yaml;}
    private NpcDefinition parse(YamlConfiguration yaml){return NpcParser.parse(yaml).get("smith");}
    private void station(YamlConfiguration yaml,String key,UUID world){String p="npcs.smith.shop.blacksmith.stations."+key;yaml.set(p+".world","world");yaml.set(p+".world-uuid",world.toString());yaml.set(p+".x",-3);yaml.set(p+".y",65);yaml.set(p+".z",8);}
    @Test void existingShopAndAllOldConstructorsDefaultToVendor(){
        var npc=parse(config());assertEquals(NpcDefinition.Mode.SHOP,npc.mode());assertEquals(ShopWorkDefinition.defaults(),npc.shopWork());
        var old=new NpcDefinition(npc.id(),npc.enabled(),npc.name(),npc.nameVisible(),npc.position(),npc.skin(),npc.look(),npc.dialogue(),npc.interaction(),npc.mode(),npc.tradeDialogue(),npc.traits(),npc.speech());
        assertEquals(ShopWorkDefinition.defaults(),old.shopWork());assertFalse(old.shopWork().complete());
    }
    @Test void spanishCategoryAndThreeStationCoordinatesPersist(){
        var yaml=config();yaml.set("npcs.smith.shop.category","herrero");UUID world=UUID.randomUUID();
        for(String key:new String[]{"smeltery","cauldron","anvil"})station(yaml,key,world);
        var npc=parse(yaml);assertEquals(NpcDefinition.Mode.SHOP,npc.mode());assertEquals(ShopWorkDefinition.Category.BLACKSMITH,npc.shopWork().category());assertTrue(npc.shopWork().complete());
        assertEquals(new ShopWorkDefinition.Station(world,"world",-3,65,8),npc.shopWork().smeltery());
    }
    @Test void missingAndEmptyStationKeepBlacksmithCategoryWithVendorFallback(){
        var yaml=config();yaml.set("npcs.smith.shop.category","blacksmith");yaml.createSection("npcs.smith.shop.blacksmith.stations.smeltery");
        var work=parse(yaml).shopWork();assertEquals(ShopWorkDefinition.Category.BLACKSMITH,work.category());assertNull(work.smeltery());assertNull(work.cauldron());assertNull(work.anvil());assertFalse(work.complete());
    }
    @Test void coordinatesNeedWorldAndWholeBlockValues(){
        var yaml=config();station(yaml,"smeltery",UUID.randomUUID());String p="npcs.smith.shop.blacksmith.stations.smeltery";
        yaml.set(p+".x",-.5);assertThrows(IllegalArgumentException.class,()->parse(yaml));yaml.set(p+".x",-3);yaml.set(p+".y",null);assertThrows(IllegalArgumentException.class,()->parse(yaml));
        yaml.set(p+".y",65);yaml.set(p+".world","");yaml.set(p+".world-uuid","");assertThrows(IllegalArgumentException.class,()->parse(yaml));
    }
    @Test void invalidCategoryAndMalformedStationAreRejectedClearly(){
        var yaml=config();yaml.set("npcs.smith.shop.category","wizard");assertThrows(IllegalArgumentException.class,()->parse(yaml));
        yaml.set("npcs.smith.shop.category","vendor");yaml.set("npcs.smith.shop.blacksmith.stations.anvil","bad");assertThrows(IllegalArgumentException.class,()->parse(yaml));
    }
    @Test void stationLocationUsesUuidIdentityAndKeepsIntegerCoordinates(){
        UUID id=UUID.randomUUID();World world=mock(World.class);when(world.getUID()).thenReturn(id);when(world.getName()).thenReturn("renamed");
        var station=new ShopWorkDefinition.Station(id,"old-name",2,60,-5);var location=station.location(world);assertNotNull(location);assertEquals(2,location.getX());assertEquals(60,location.getY());assertEquals(-5,location.getZ());
        assertNull(station.location(null));World other=mock(World.class);when(other.getUID()).thenReturn(UUID.randomUUID());assertNull(station.location(other));
        assertNotNull(new ShopWorkDefinition.Station(null,"renamed",2,60,-5).location(world));assertNull(new ShopWorkDefinition.Station(null,"elsewhere",2,60,-5).location(world));
    }
}
