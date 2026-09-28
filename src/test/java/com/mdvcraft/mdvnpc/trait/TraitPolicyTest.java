package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.config.NpcParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraitPolicyTest {
    @Test void oldNpcDefaultsAndSpanishNames() {
        var yaml=new YamlConfiguration();yaml.set("npcs.manolito.location.world","world");
        assertEquals(Trait.NONE,NpcParser.parse(yaml).get("manolito").traits().type());
        yaml.set("npcs.manolito.trait.type","alcohólico");yaml.set("npcs.manolito.trait.beer-cooldown-seconds",37);
        var traits=NpcParser.parse(yaml).get("manolito").traits();
        assertEquals(Trait.ALCOHOLIC,traits.type());assertEquals(37,traits.beerCooldownSeconds());
        assertEquals(Trait.GLUTTON,Trait.parse("glotón"));
        assertThrows(IllegalArgumentException.class,()->Trait.parse("lector,gloton"));
        yaml.set("npcs.manolito.trait.beer-cooldown-seconds",-1);
        assertThrows(IllegalArgumentException.class,()->NpcParser.parse(yaml));
    }
    @Test void readerTriplesDurationAndGluttonFavorsFood() {
        assertEquals(1500,TraitBehavior.readingDuration(Trait.READER,500));
        assertEquals(500,TraitBehavior.readingDuration(Trait.NONE,500));
        assertTrue(TraitBehavior.readingChance(Trait.READER,.3)>.7);
        assertTrue(TraitBehavior.readingChance(Trait.GLUTTON,.3)<.03);
        assertEquals(.05,TraitBehavior.drinkChance(Trait.GLUTTON));
        assertEquals(200,TraitBehavior.activityDelay(Trait.GLUTTON,800));
        assertEquals(58,TraitBehavior.glanceDelay(Trait.RESTLESS,350));
        assertEquals(350,TraitBehavior.glanceDelay(Trait.NONE,350));
    }
}
