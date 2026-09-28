package com.mdvcraft.mdvnpc.trait;

/** Small deterministic policies, independent of Bukkit and animation state. */
public final class TraitBehavior {
    private TraitBehavior() {}
    public static double readingChance(Trait trait,double normal) {
        return switch(trait) {case READER -> Math.min(1,Math.max(.75,normal*2));case GLUTTON -> .02;default -> normal;};
    }
    public static long readingDuration(Trait trait,long ticks) {return trait==Trait.READER?ticks*3:ticks;}
    public static double drinkChance(Trait trait) {return trait==Trait.GLUTTON?.05:1d/3;}
    public static long activityDelay(Trait trait,long ticks) {return trait==Trait.GLUTTON?Math.max(40,ticks/4):ticks;}
    public static long glanceDelay(Trait trait,long ticks) {return trait==Trait.RESTLESS?Math.max(20,Math.round(ticks/3.5)):ticks;}
}
