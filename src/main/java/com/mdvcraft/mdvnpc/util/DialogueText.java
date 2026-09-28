package com.mdvcraft.mdvnpc.util;

import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.entity.Player;
import java.util.regex.Pattern;

/** Only known leading speaker templates are removed; the body of a sentence is preserved. */
public final class DialogueText {
    private static final Pattern LEGACY=Pattern.compile("^(?:(?:&|§)[0-9a-fk-or]|\\s)*\\{npc}(?:(?:&|§)[0-9a-fk-or]|\\s)*(?:»|:|>|[-–—])\\s*",Pattern.CASE_INSENSITIVE);
    private DialogueText(){}
    public static String render(String line,Player player,NpcDefinition npc){
        String prefix=npc.speech().prefix();
        if(prefix==null)return Text.color(Text.placeholders(line.replace("{prefix}","&7{npc} &f»"),player,npc));
        String body=LEGACY.matcher(line).replaceFirst("");
        if(body.contains("{prefix}"))body=body.replace("{prefix}",prefix);
        else body=prefix+(prefix.isEmpty()?"":" ")+body;
        if(prefix.isEmpty())body=body.stripLeading();
        return Text.color(Text.placeholders(body,player,npc));
    }
}
