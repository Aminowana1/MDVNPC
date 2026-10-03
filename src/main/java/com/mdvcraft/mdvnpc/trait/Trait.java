package com.mdvcraft.mdvnpc.trait;

import java.text.Normalizer;
import java.util.Locale;

/** Exactly one enum value per NPC, including NONE for existing configurations. */
public enum Trait {
    NONE, ALCOHOLIC, READER, GLUTTON, RESTLESS, NOISY, PARTYGOER;
    public static Trait parse(String value) {
        String text=Normalizer.normalize(value,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        return switch(text) {
            case "ninguno","none" -> NONE;
            case "alcoholico","alcholico","alcoholic" -> ALCOHOLIC;
            case "lector","reader" -> READER;
            case "gloton","glutton" -> GLUTTON;
            case "inquieto","restless" -> RESTLESS;
            case "ruidoso","noisy" -> NOISY;
            case "fiestero","partygoer" -> PARTYGOER;
            default -> throw new IllegalArgumentException("Rasgo: ninguno, alcoholico, lector, gloton, inquieto, ruidoso o fiestero");
        };
    }
}
