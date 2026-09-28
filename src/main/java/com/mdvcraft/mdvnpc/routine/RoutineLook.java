package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.PlayerFilter;
import com.mdvcraft.mdvnpc.trait.BehaviorConfig;
import com.mdvcraft.mdvnpc.trait.Trait;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Shared ticker, bounded rotations and local player queries only when a glance starts. */
public final class RoutineLook {
    public static final class State {
        long nextGlance,until,nextUpdate,nextArm;
        float yawOffset,pitch,headYaw,headPitch,bodyYaw;
        boolean initialized,applied;
        UUID player;
    }
    private final MdvNpcPlugin plugin;
    private LookOptions walking,seatedOptions;
    private Object settingsIdentity;
    public RoutineLook(MdvNpcPlugin plugin){this.plugin=plugin;}
    private LookOptions options(boolean seated){
        if(settingsIdentity!=plugin.settings()){
            walking=LookOptions.read(plugin,false);seatedOptions=LookOptions.read(plugin,true);settingsIdentity=plugin.settings();
        }
        return seated?seatedOptions:walking;
    }
    private boolean restless(ActiveNpc npc){return npc.definition().traits().type()==Trait.RESTLESS;}
    private long delay(ActiveNpc npc,LookOptions o){
        long delay=BehaviorConfig.between(o.minDelay(),o.maxDelay());
        double multiplier=restless(npc)?BehaviorConfig.number(plugin,"routines.restless.frequency-multiplier",6,1,20):1;
        return Math.max(4,Math.round(delay/multiplier));
    }
    private long armDelay(){return BehaviorConfig.between(BehaviorConfig.ticks(plugin,"routines.restless.arm-min-seconds",2,.2,300),BehaviorConfig.ticks(plugin,"routines.restless.arm-max-seconds",5,.2,300));}
    public void tick(ActiveNpc npc,State state,long tick,float bodyYaw,boolean seated,boolean reading){tick(npc,state,tick,bodyYaw,seated,reading,false);}
    public void tick(ActiveNpc npc,State state,long tick,float bodyYaw,boolean seated,boolean reading,boolean consuming){
        LookOptions o=options(seated);
        if(!plugin.settings().messages().getBoolean("routines.occasional-looking",true) || !o.enabled()){clear(npc,state);return;}
        if(!state.initialized){state.initialized=true;state.nextGlance=tick+delay(npc,o);state.nextArm=tick+armDelay();state.headYaw=bodyYaw;state.headPitch=0;}
        state.bodyYaw=bodyYaw;
        if(tick<state.nextUpdate){if(state.applied){npc.entity().setRotation(bodyYaw+clamp(angle(bodyYaw,state.headYaw),-o.yawLimit(),o.yawLimit()),state.headPitch);npc.entity().setBodyYaw(bodyYaw);}return;}
        state.nextUpdate=tick+o.update();
        if(restless(npc) && tick>=state.nextArm){
            state.nextArm=tick+armDelay();
            if(!reading && !consuming && plugin.settings().messages().getBoolean("routines.restless.arms-enabled",true)
                    && ThreadLocalRandom.current().nextDouble()<BehaviorConfig.number(plugin,"routines.restless.arm-chance",.8,0,1))npc.entity().swingMainHand();
        }
        float wantedYaw=bodyYaw,wantedPitch=reading?o.readingPitch():0;
        if(!reading && tick>=state.nextGlance){
            state.player=null;state.until=tick;
            if(ThreadLocalRandom.current().nextDouble()<o.chance()){
                double duration=restless(npc)?BehaviorConfig.number(plugin,"routines.restless.duration-multiplier",.5,.1,3):1;
                state.until=tick+Math.max(2,Math.round(BehaviorConfig.between(o.minHold(),o.maxHold())*duration));
                double amplitude=restless(npc)?BehaviorConfig.number(plugin,"routines.restless.angle-multiplier",1.5,1,3):1;
                state.yawOffset=clamp((float)(BehaviorConfig.between((double)o.yawMin(),o.yawMax())*amplitude),-o.yawLimit(),o.yawLimit());
                state.pitch=clamp((float)(BehaviorConfig.between((double)o.pitchMin(),o.pitchMax())*amplitude),-80,80);
                if(o.players() && o.range()>0 && ThreadLocalRandom.current().nextDouble()<o.playerChance()){
                    Player nearest=null;double best=o.range()*o.range();Location origin=npc.position();
                    for(Player player:origin.getWorld().getNearbyPlayers(origin,o.range(),p->PlayerFilter.accepts(p,plugin.settings()))){
                        double distance=player.getLocation().distanceSquared(origin);
                        if(distance<=best && (!o.sight() || npc.entity().hasLineOfSight(player))){best=distance;nearest=player;}
                    }
                    if(nearest!=null)state.player=nearest.getUniqueId();
                }
            }
            state.nextGlance=state.until+delay(npc,o);
        }
        if(!reading && tick<state.until){
            wantedYaw=bodyYaw+state.yawOffset;wantedPitch=state.pitch;
            if(state.player!=null){
                Player player=plugin.getServer().getPlayer(state.player);
                if(player==null || !PlayerFilter.accepts(player,plugin.settings()) || player.getWorld()!=npc.entity().getWorld()
                        || player.getLocation().distanceSquared(npc.position())>o.range()*o.range() || o.sight() && !npc.entity().hasLineOfSight(player)){
                    state.player=null;state.until=tick;wantedYaw=bodyYaw;wantedPitch=0;
                }else{
                    var direction=player.getEyeLocation().toVector().subtract(npc.entity().getEyeLocation().toVector());
                    if(direction.lengthSquared()>.001){Location facing=npc.position().setDirection(direction);
                        wantedYaw=bodyYaw+clamp(angle(bodyYaw,facing.getYaw()),-o.yawLimit(),o.yawLimit());wantedPitch=clamp(facing.getPitch(),o.trackMin(),o.trackMax());}
                }
            }
        }
        state.headYaw=bodyYaw+clamp(angle(bodyYaw,approach(state.headYaw,wantedYaw,o.yawStep())),-o.yawLimit(),o.yawLimit());
        state.headPitch+=clamp(wantedPitch-state.headPitch,-o.pitchStep(),o.pitchStep());
        if(Math.abs(angle(state.headYaw,bodyYaw))<.5 && Math.abs(state.headPitch)<.5 && !reading && tick>=state.until){
            if(state.applied)npc.entity().setRotation(bodyYaw,0);state.applied=false;return;
        }
        npc.entity().setRotation(state.headYaw,state.headPitch);npc.entity().setBodyYaw(bodyYaw);state.applied=true;
    }
    public void clear(ActiveNpc npc,State s){
        if(s.applied && npc.entity().isValid()){npc.entity().setRotation(s.bodyYaw,0);npc.entity().setBodyYaw(s.bodyYaw);}
        s.initialized=false;s.applied=false;s.player=null;s.until=0;s.nextUpdate=0;
    }
    static float angle(float from,float to){return ((to-from)%360+540)%360-180;}
    static float approach(float from,float to,float max){return from+clamp(angle(from,to),-max,max);}
    private static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
}
