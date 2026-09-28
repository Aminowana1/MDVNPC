package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.util.DialogueText;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DialoguePrefixTest {
    private String render(String prefix,String line){
        var yaml=new YamlConfiguration();yaml.set("npcs.manolito.location.world","world");
        yaml.set("npcs.manolito.name","Manolito");if(prefix!=null)yaml.set("npcs.manolito.speech.prefix",prefix);
        Player player=mock(Player.class);when(player.getName()).thenReturn("Ana");when(player.getUniqueId()).thenReturn(new UUID(0,1));
        return ChatColor.stripColor(DialogueText.render(line,player,NpcParser.parse(yaml).get("manolito")));
    }
    @Test void customPrefixReplacesLegacySpeakerForUnavailableAndBeer(){
        assertEquals("[Tabernero] Manolito » Ahora no trabajo.",render("&6[Tabernero] {npc} »","&7{npc} &f» &7Ahora no trabajo."));
        assertEquals("[Tabernero] » Gracias, Ana.",render("[Tabernero] »","&7{npc} &f» Gracias, {player}."));
    }
    @Test void oldFormatEmptyPrefixAndExplicitPlaceholder(){
        assertEquals("Manolito » Hola",render(null,"&7{npc} &f» Hola"));
        assertEquals("Hola",render("","&7{npc} &f» Hola"));
        assertEquals("[M] Hola",render("[M]","{prefix} Hola"));
        assertEquals("Manolito » Hola",render(null,"{prefix} Hola"));
        assertEquals("Hola",render("","{prefix} Hola"));
        assertEquals("[M] Mi nombre es Manolito.",render("[M]","Mi nombre es {npc}."));
    }
    @Test void rejectsMultilineAndOversizedPrefixes(){
        assertThrows(IllegalArgumentException.class,()->render("x\ny","Hola"));
        assertThrows(IllegalArgumentException.class,()->render("x".repeat(257),"Hola"));
    }
}
