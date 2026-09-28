package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import java.util.concurrent.ThreadLocalRandom;

/** Finite, bounded configuration values; reversed ranges collapse to the minimum. */
public final class BehaviorConfig {
    private BehaviorConfig(){}
    public static double number(MdvNpcPlugin p,String key,double fallback,double min,double max){
        double n=p.settings().messages().getDouble(key,fallback);
        return Double.isFinite(n)?Math.max(min,Math.min(max,n)):fallback;
    }
    public static long ticks(MdvNpcPlugin p,String key,double fallback,double min,double max){return Math.max(1,Math.round(number(p,key,fallback,min,max)*20));}
    public static long between(long min,long max){return ThreadLocalRandom.current().nextLong(min,Math.max(min,max)+1);}
    public static double between(double min,double max){return max<=min?min:ThreadLocalRandom.current().nextDouble(min,max);}
}
