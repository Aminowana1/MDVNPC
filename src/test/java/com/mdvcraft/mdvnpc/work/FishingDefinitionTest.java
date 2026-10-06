package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingDefinitionTest {
    private YamlConfiguration yaml(){var y=new YamlConfiguration();y.set("npcs.fisher.location.world","world");y.set("npcs.fisher.mode","shop");y.set("npcs.fisher.shop.category","pescador");return y;}
    private NpcDefinition parse(YamlConfiguration y){return NpcParser.parse(y).get("fisher");}
    private Map<String,Object> point(){var row=new LinkedHashMap<String,Object>();row.put("world","world");row.put("x",2.5);row.put("y",65.0);row.put("z",-3.5);row.put("yaw",90.0);return row;}
    @Test void missingFishingStationsRetainShopAndFallback(){
        var definition=parse(yaml());assertEquals(NpcDefinition.Mode.SHOP,definition.mode());assertEquals(ShopWorkDefinition.Category.FISHERMAN,definition.shopWork().category());
        assertEquals(FishingDefinition.defaults(),definition.shopWork().fishing());assertFalse(definition.shopWork().complete());
        assertEquals(ShopWorkDefinition.Category.FISHERMAN,ShopWorkDefinition.Category.parse(" fisherman "));
    }
    @Test void fractionalPositionsAndDirectionsRoundTripThroughYaml()throws Exception{
        var y=yaml();String base="npcs.fisher.shop.fisherman";UUID id=UUID.randomUUID();var p=point();p.put("world-uuid",id.toString());
        y.set(base+".shore-points",List.of(p));y.createSection(base+".dock",p);y.set(base+".boat-points",List.of(p));
        var reload=new YamlConfiguration();reload.loadFromString(y.saveToString());var work=parse(reload).shopWork();assertTrue(work.complete());
        assertEquals(new FishingDefinition.Point(id,"world",2.5,65,-3.5,90),work.fishing().dock());
        assertEquals(work.fishing().shorePoints().getFirst(),work.fishing().boatPoints().getFirst());
    }
    @Test void incompleteCycleNeedsShoreDockAndBoat(){
        var p=new FishingDefinition.Point(null,"world",0.5,65,.5,0);
        assertFalse(new FishingDefinition(List.of(),p,List.of(p)).complete());assertFalse(new FishingDefinition(List.of(p),null,List.of(p)).complete());assertFalse(new FishingDefinition(List.of(p),p,List.of()).complete());
        assertTrue(new FishingDefinition(List.of(p),p,List.of(p)).complete());
    }
    @Test void listsAreImmutableAndBounded(){
        var p=new FishingDefinition.Point(null,"world",.5,65,.5,0);var list=new ArrayList<>(List.of(p));var fishing=new FishingDefinition(list,p,list);list.clear();assertEquals(1,fishing.shorePoints().size());
        assertThrows(UnsupportedOperationException.class,()->fishing.boatPoints().clear());assertThrows(IllegalArgumentException.class,()->new FishingDefinition(Collections.nCopies(33,p),p,List.of(p)));
    }
    @Test void invalidMapsMissingYawAndNonFiniteCoordinatesCannotLoad(){
        var y=yaml();String path="npcs.fisher.shop.fisherman.shore-points";
        y.set(path,"bad");assertThrows(IllegalArgumentException.class,()->parse(y));y.set(path,List.of("bad"));assertThrows(IllegalArgumentException.class,()->parse(y));
        var p=point();p.remove("yaw");y.set(path,List.of(p));assertThrows(IllegalArgumentException.class,()->parse(y));p.put("yaw",0);p.put("x",Double.NaN);y.set(path,List.of(p));assertThrows(IllegalArgumentException.class,()->parse(y));
        p.put("x",.5);p.put("world","");y.set(path,List.of(p));assertThrows(IllegalArgumentException.class,()->parse(y));
    }
    @Test void tooManyPointsAndMalformedDockAreRejected(){
        var y=yaml();y.set("npcs.fisher.shop.fisherman.boat-points",Collections.nCopies(33,point()));assertThrows(IllegalArgumentException.class,()->parse(y));
        y.set("npcs.fisher.shop.fisherman.boat-points",null);y.set("npcs.fisher.shop.fisherman.dock","bad");assertThrows(IllegalArgumentException.class,()->parse(y));
    }
    @Test void pointIdentityUsesUuidAndLocationKeepsYaw(){
        UUID id=UUID.randomUUID();World w=mock(World.class);when(w.getUID()).thenReturn(id);when(w.getName()).thenReturn("renamed");var point=new FishingDefinition.Point(id,"old",2.5,65,-3.5,-135);
        var location=point.location(w);assertNotNull(location);assertEquals(2.5,location.getX());assertEquals(-135,location.getYaw());assertEquals(0,location.getPitch());
        World other=mock(World.class);when(other.getUID()).thenReturn(UUID.randomUUID());assertNull(point.location(other));assertNull(point.location(null));
        assertThrows(IllegalArgumentException.class,()->new FishingDefinition.Point(null,"world",0,65,0,Float.NaN));
    }
    @Test void changingCategoryKeepsOtherStationsAndFishingData(){
        var y=yaml();var p=point();y.set("npcs.fisher.shop.fisherman.shore-points",List.of(p));String forge="npcs.fisher.shop.blacksmith.stations.smeltery";
        y.set(forge+".world","world");y.set(forge+".x",1);y.set(forge+".y",64);y.set(forge+".z",1);y.set("npcs.fisher.shop.category","blacksmith");
        var work=parse(y).shopWork();assertNotNull(work.smeltery());assertEquals(1,work.fishing().shorePoints().size());assertEquals(FishingDefinition.defaults(),new ShopWorkDefinition(null,null,null,null).fishing());
    }
}
