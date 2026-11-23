package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.api.CompanionGesture;
import com.mdvcraft.mdvnpc.companion.GestureTimeline;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.PlayerFilter;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Cosmetic only: NPC damage stays cancelled. One bounded reaction per NPC at a time:
 * an angry look after a hit, or a wave back at a player who crouched twice nearby. */
public final class HitReactionService {
    private static final class Reaction {
        final ActiveNpc npc;final UUID player;final long until;final float yaw,pitch;final boolean greeting;
        List<GestureTimeline.Step> gesture=List.of();long gestureStart=Long.MIN_VALUE;int gestureIndex;
        Reaction(ActiveNpc npc,UUID player,long until,float yaw,float pitch,boolean greeting){
            this.npc=npc;this.player=player;this.until=until;this.yaw=yaw;this.pitch=pitch;this.greeting=greeting;
        }
    }
    private final MdvNpcPlugin plugin;
    private final Map<String,Reaction> active=new HashMap<>();
    private final Map<String,Long> due=new HashMap<>();
    /** Per NPC and player, so one player cannot make the same NPC wave over and over. */
    private final Map<String,Long> greetDue=new HashMap<>();
    private final DoubleSneak sneaks=new DoubleSneak();
    private long nextUpdate;
    public HitReactionService(MdvNpcPlugin plugin){this.plugin=plugin;}
    public boolean busy(String id){return active.containsKey(id);}
    public boolean greeting(String id){Reaction r=active.get(id);return r!=null && r.greeting;}

    /** A crouch press; the second one within the window greets the nearest NPC. */
    public void sneaked(Player player){
        if(!plugin.settings().messages().getBoolean("npc-greetings.enabled",true))return;
        long now=System.nanoTime();
        long window=(long)(BehaviorConfig.number(plugin,"npc-greetings.double-sneak-seconds",.6,.1,2)*1_000_000_000L);
        if(sneaks.press(player.getUniqueId(),now,window))greet(player,now);
        else sneaks.prune(now,window);
    }
    public void forget(UUID player){sneaks.forget(player);}

    private void greet(Player player,long now){
        if(!PlayerFilter.accepts(player,plugin.settings()) || plugin.manager()==null)return;
        double range=BehaviorConfig.number(plugin,"npc-greetings.range",5,1,16);
        Location from=player.getLocation();ActiveNpc nearest=null;double best=range*range;
        for(ActiveNpc npc:plugin.manager().activeNpcs()) {
            if(!npc.entity().isValid() || npc.entity().getWorld()!=player.getWorld())continue;
            double distance=npc.position().distanceSquared(from);
            if(distance>best || busy(npc.definition().id()))continue;
            if(plugin.traits()!=null && plugin.traits().busy(npc.definition().id()))continue;
            if(plugin.routines()!=null && !plugin.routines().canGreet(npc))continue;
            if(!npc.entity().hasLineOfSight(player))continue;
            nearest=npc;best=distance;
        }
        if(nearest==null)return;
        String id=nearest.definition().id(),key=id+"|"+player.getUniqueId();
        if(now<greetDue.getOrDefault(key,0L))return;
        double cooldown=BehaviorConfig.number(plugin,"npc-greetings.cooldown-seconds",10,0,600);
        greetDue.put(key,now+(long)(cooldown*1_000_000_000L));
        Reaction r=begin(nearest,player,BehaviorConfig.number(plugin,"npc-greetings.pause-seconds",3,.5,15),true);
        if(nearest.disguise()!=null)r.gesture=GestureTimeline.steps(CompanionGesture.WAVE);
    }

    public void hit(ActiveNpc npc,Player player){
        if(!plugin.settings().messages().getBoolean("npc-reactions.enabled",true) || !npc.entity().isValid()
                || !PlayerFilter.accepts(player,plugin.settings()) || player.getWorld()!=npc.entity().getWorld())return;
        double range=BehaviorConfig.number(plugin,"npc-reactions.max-hit-distance",4.5,1,8);
        if(player.getLocation().distanceSquared(npc.position())>range*range || !npc.entity().hasLineOfSight(player))return;
        String id=npc.definition().id();long now=System.nanoTime();
        // A hit interrupts a wave: being struck matters more than a greeting.
        if(busy(id) && !greeting(id) || now<due.getOrDefault(id,0L))return;
        double cooldown=BehaviorConfig.number(plugin,"npc-reactions.cooldown-seconds",5,.5,300);
        due.put(id,now+(long)(cooldown*1_000_000_000L));
        cancel(id);
        begin(npc,player,BehaviorConfig.number(plugin,"npc-reactions.look-seconds",3,.2,15),false);
        Location head=npc.entity().getEyeLocation();
        int angry=(int)BehaviorConfig.number(plugin,"npc-reactions.angry-particles",5,0,30);
        int impact=(int)BehaviorConfig.number(plugin,"npc-reactions.impact-particles",7,0,30);
        if(angry>0)head.getWorld().spawnParticle(Particle.ANGRY_VILLAGER,head.clone().subtract(0,.35,0),angry,.22,.12,.22,0);
        if(impact>0)head.getWorld().spawnParticle(Particle.CRIT,head,impact,.25,.25,.25,.05);
        float volume=(float)BehaviorConfig.number(plugin,"npc-reactions.hit-volume",.6,0,4);
        head.getWorld().playSound(head,Sound.ENTITY_VILLAGER_HURT,volume,1);
        List<String> lines=npc.definition().speech().angerLines();
        if(lines==null){lines=plugin.settings().messages().getStringList("npc-reactions.lines");
            if(!plugin.settings().messages().contains("npc-reactions.lines"))lines=List.of("&7{npc} &f» &c¡Eh, no me pegues!","&7{npc} &f» &c¡Un poco de respeto, {player}!");}
        if(!lines.isEmpty())plugin.sounds().say(npc,player,lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
    }

    /** Takes the NPC from its routine, music and drink, and turns it towards the player. */
    private Reaction begin(ActiveNpc npc,Player player,double seconds,boolean greeting){
        String id=npc.definition().id();
        if(plugin.music()!=null)plugin.music().suspendVisuals(id);
        if(plugin.traits()!=null)plugin.traits().cancel(id);
        if(plugin.routines()!=null)plugin.routines().prepareReaction(npc);
        Location location=npc.position();
        Reaction r=new Reaction(npc,player.getUniqueId(),System.nanoTime()+(long)(seconds*1_000_000_000L),
                location.getYaw(),location.getPitch(),greeting);
        active.put(id,r);
        face(npc,player);
        return r;
    }

    public void tick(long tick){
        if(tick<nextUpdate)return;nextUpdate=tick+2;long now=System.nanoTime();
        for(var e:List.copyOf(active.entrySet())){
            Reaction r=e.getValue();Player player=Bukkit.getPlayer(r.player);
            double range=BehaviorConfig.number(plugin,"npc-reactions.look-range",8,1,32);
            if(now>=r.until || !r.npc.entity().isValid() || player==null || !PlayerFilter.accepts(player,plugin.settings())
                    || player.getWorld()!=r.npc.entity().getWorld() || player.getLocation().distanceSquared(r.npc.position())>range*range
                    || !r.npc.entity().hasLineOfSight(player)){cancel(e.getKey());continue;}
            face(r.npc,player);
            gesture(r,tick);
        }
        if(tick%200<4) {
            due.entrySet().removeIf(e->now>=e.getValue() && !active.containsKey(e.getKey()));
            greetDue.values().removeIf(at->now>=at);
        }
    }

    /** The companions' wave: crouch and swing an arm twice. It needs a player disguise. */
    private void gesture(Reaction r,long tick){
        if(r.gesture.isEmpty())return;
        if(r.gestureStart==Long.MIN_VALUE)r.gestureStart=tick;
        while(r.gestureIndex<r.gesture.size() && r.gesture.get(r.gestureIndex).tick()<=tick-r.gestureStart) {
            switch(r.gesture.get(r.gestureIndex++).action()) {
                case SNEAK_ON -> sneaking(r.npc,true);
                case SNEAK_OFF -> sneaking(r.npc,false);
                case SWING -> r.npc.entity().swingMainHand();
            }
        }
        if(r.gestureIndex>=r.gesture.size()){r.gesture=List.of();sneaking(r.npc,false);}
    }
    private static void sneaking(ActiveNpc npc,boolean value){
        if(npc.entity().isValid() && npc.disguise()!=null)npc.disguise().getWatcher().setSneaking(value);
    }

    private void face(ActiveNpc npc,Player player){
        var direction=player.getEyeLocation().toVector().subtract(npc.entity().getEyeLocation().toVector());
        if(direction.lengthSquared()<.001)return;
        Location facing=npc.position().setDirection(direction);
        npc.entity().setRotation(facing.getYaw(),Math.max(-60,Math.min(60,facing.getPitch())));
    }
    public void cancel(String id){
        Reaction r=active.remove(id);if(r==null || !r.npc.entity().isValid())return;
        if(!r.gesture.isEmpty())sneaking(r.npc,false);
        r.npc.entity().setRotation(r.yaw,r.pitch);
    }
    public void reloaded(){for(String id:List.copyOf(active.keySet()))cancel(id);due.keySet().retainAll(plugin.definitions().keySet());}
    public void clear(){for(String id:List.copyOf(active.keySet()))cancel(id);due.clear();greetDue.clear();}
}
