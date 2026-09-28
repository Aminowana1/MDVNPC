package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.PlayerFilter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Short, infrequent glances. Uses the existing routine ticker and preserves body heading. */
public final class RoutineLook {
    public static final class State {
        long nextGlance, until, nextUpdate;
        float yawOffset, pitch, headYaw, headPitch, bodyYaw;
        boolean initialized, applied;
        UUID player;
    }
    private final MdvNpcPlugin plugin;
    public RoutineLook(MdvNpcPlugin plugin) { this.plugin=plugin; }
    private long delay() {
        int min=Math.max(3,Math.min(300,plugin.settings().messages().getInt("routines.glance-min-seconds",8)));
        int max=Math.max(min,Math.min(300,plugin.settings().messages().getInt("routines.glance-max-seconds",18)));
        return ThreadLocalRandom.current().nextLong(min,(long)max+1)*20;
    }
    public void tick(ActiveNpc npc,State state,long tick,float bodyYaw,boolean seated,boolean reading) {
        if(!plugin.settings().messages().getBoolean("routines.occasional-looking",true)) {clear(npc,state);return;}
        if(!state.initialized) {
            state.initialized=true;state.nextGlance=tick+delay();
            state.headYaw=bodyYaw;state.headPitch=0;
        }
        state.bodyYaw=bodyYaw;
        if(tick<state.nextUpdate) {
            if(state.applied) {npc.entity().setRotation(bodyYaw+clamp(angle(bodyYaw,state.headYaw),-65,65),state.headPitch);npc.entity().setBodyYaw(bodyYaw);}
            return;
        }
        state.nextUpdate=tick+4;
        float wantedYaw=bodyYaw,wantedPitch=reading?28:0;
        if(!reading && tick>=state.nextGlance) {
            state.until=tick+ThreadLocalRandom.current().nextInt(25,61);
            state.nextGlance=state.until+delay();state.player=null;
            state.yawOffset=ThreadLocalRandom.current().nextFloat(-35,35);
            state.pitch=ThreadLocalRandom.current().nextFloat(-10,12);
            if(seated) {
                Player nearest=null;double best=9;Location origin=npc.position();
                for(Player player:origin.getWorld().getNearbyPlayers(origin,3,p -> PlayerFilter.accepts(p,plugin.settings()))) {
                    double distance=player.getLocation().distanceSquared(origin);
                    if(distance<=best && npc.entity().hasLineOfSight(player)) {best=distance;nearest=player;}
                }
                if(nearest!=null)state.player=nearest.getUniqueId();
            }
        }
        if(!reading && tick<state.until) {
            wantedYaw=bodyYaw+state.yawOffset;wantedPitch=state.pitch;
            if(state.player!=null) {
                Player player=plugin.getServer().getPlayer(state.player);
                if(player==null || !PlayerFilter.accepts(player,plugin.settings()) || player.getWorld()!=npc.entity().getWorld()
                        || player.getLocation().distanceSquared(npc.position())>9 || !npc.entity().hasLineOfSight(player)) {
                    state.player=null;state.until=tick;wantedYaw=bodyYaw;wantedPitch=0;
                } else {
                    var direction=player.getEyeLocation().toVector().subtract(npc.entity().getEyeLocation().toVector());
                    if(direction.lengthSquared()>.001) {
                        Location facing=npc.position().setDirection(direction);
                        wantedYaw=bodyYaw+clamp(angle(bodyYaw,facing.getYaw()),-65,65);
                        wantedPitch=clamp(facing.getPitch(),-25,35);
                    }
                }
            }
        }
        float offsetLimit=seated?65:40;
        state.headYaw=bodyYaw+clamp(angle(bodyYaw,approach(state.headYaw,wantedYaw,12)),-offsetLimit,offsetLimit);
        state.headPitch+=clamp(wantedPitch-state.headPitch,-7,7);
        if(Math.abs(angle(state.headYaw,bodyYaw))<.5 && Math.abs(state.headPitch)<.5 && !reading && tick>=state.until) {
            if(state.applied)npc.entity().setRotation(bodyYaw,0);
            state.applied=false;return;
        }
        npc.entity().setRotation(state.headYaw,state.headPitch);
        npc.entity().setBodyYaw(bodyYaw);
        state.applied=true;
    }
    public void clear(ActiveNpc npc,State state) {
        if(state.applied && npc.entity().isValid()) {npc.entity().setRotation(state.bodyYaw,0);npc.entity().setBodyYaw(state.bodyYaw);}
        state.initialized=false;state.applied=false;state.player=null;state.until=0;state.nextUpdate=0;
    }
    static float angle(float from,float to) {return ((to-from)%360+540)%360-180;}
    static float approach(float from,float to,float max) {return from+clamp(angle(from,to),-max,max);}
    private static float clamp(float value,float min,float max) {return Math.max(min,Math.min(max,value));}
}
