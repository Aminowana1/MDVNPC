package com.mdvcraft.mdvnpc.routine;
import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.trait.BehaviorConfig;

/** Independent walking/seated settings, with old interval keys as fallback. */
record LookOptions(boolean enabled,long minDelay,long maxDelay,long minHold,long maxHold,
                   int update,double chance,float yawMin,float yawMax,float pitchMin,float pitchMax,
                   float yawLimit,float yawStep,float pitchStep,float readingPitch,
                   boolean players,double range,double playerChance,boolean sight,float trackMin,float trackMax) {
    static LookOptions read(MdvNpcPlugin plugin,boolean seated){
        String b="routines.looking."+(seated?"seated.":"walking.");var c=plugin.settings().messages();
        double oldMin=BehaviorConfig.number(plugin,"routines.glance-min-seconds",8,.2,3600);
        double oldMax=BehaviorConfig.number(plugin,"routines.glance-max-seconds",18,.2,3600);
        long min=c.contains(b+"interval-min-seconds",true)?BehaviorConfig.ticks(plugin,b+"interval-min-seconds",oldMin,.2,3600):Math.round(oldMin*20);
        long max=Math.max(min,c.contains(b+"interval-max-seconds",true)?BehaviorConfig.ticks(plugin,b+"interval-max-seconds",oldMax,.2,3600):Math.round(oldMax*20));
        long hold=BehaviorConfig.ticks(plugin,b+"duration-min-seconds",1.25,.1,60);
        long end=Math.max(hold,BehaviorConfig.ticks(plugin,b+"duration-max-seconds",3,.1,60));
        float y0=n(plugin,b,"yaw-min",-35,-85,85),y1=n(plugin,b,"yaw-max",35,y0,85);
        float p0=n(plugin,b,"pitch-min",-10,-80,80),p1=n(plugin,b,"pitch-max",12,p0,80);
        float t0=n(plugin,b,"player-pitch-min",-25,-80,80),t1=n(plugin,b,"player-pitch-max",35,t0,80);
        return new LookOptions(c.getBoolean(b+"enabled",true),min,max,hold,end,
                (int)n(plugin,b,"update-interval-ticks",4,1,20),n(plugin,b,"chance",1,0,1),y0,y1,p0,p1,
                n(plugin,b,"yaw-limit",seated?65:40,0,85),n(plugin,b,"yaw-step",12,.1,90),
                n(plugin,b,"pitch-step",7,.1,90),n(plugin,b,"reading-pitch",28,-80,80),
                c.getBoolean(b+"look-at-players",seated),n(plugin,b,"player-range",3,0,16),
                n(plugin,b,"player-chance",1,0,1),c.getBoolean(b+"require-line-of-sight",true),t0,t1);
    }
    private static float n(MdvNpcPlugin p,String b,String k,double d,double min,double max){return (float)BehaviorConfig.number(p,b+k,d,min,max);}
}
