package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.PlayerFilter;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Cosmetic only: NPC damage stays cancelled. One bounded reaction per NPC at a time. */
public final class HitReactionService {
    private record Reaction(ActiveNpc npc,UUID player,long until,float yaw,float pitch){}
    private final MdvNpcPlugin plugin;
    private final Map<String,Reaction> active=new HashMap<>();
    private final Map<String,Long> due=new HashMap<>();
    private long nextUpdate;
    public HitReactionService(MdvNpcPlugin plugin){this.plugin=plugin;}
    public boolean busy(String id){return active.containsKey(id);}
    public void hit(ActiveNpc npc,Player player){
        if(!plugin.settings().messages().getBoolean("npc-reactions.enabled",true) || !npc.entity().isValid()
                || !PlayerFilter.accepts(player,plugin.settings()) || player.getWorld()!=npc.entity().getWorld())return;
        double range=BehaviorConfig.number(plugin,"npc-reactions.max-hit-distance",4.5,1,8);
        if(player.getLocation().distanceSquared(npc.position())>range*range || !npc.entity().hasLineOfSight(player))return;
        String id=npc.definition().id();long now=System.nanoTime();
        if(busy(id) || now<due.getOrDefault(id,0L))return;
        double cooldown=BehaviorConfig.number(plugin,"npc-reactions.cooldown-seconds",5,.5,300);
        due.put(id,now+(long)(cooldown*1_000_000_000L));
        plugin.traits().cancel(id);plugin.routines().prepareReaction(npc);
        Location location=npc.position();
        long duration=(long)(BehaviorConfig.number(plugin,"npc-reactions.look-seconds",3,.2,15)*1_000_000_000L);
        active.put(id,new Reaction(npc,player.getUniqueId(),now+duration,location.getYaw(),location.getPitch()));
        face(npc,player);
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
    public void tick(long tick){
        if(tick<nextUpdate)return;nextUpdate=tick+2;long now=System.nanoTime();
        for(var e:List.copyOf(active.entrySet())){
            Reaction r=e.getValue();Player player=Bukkit.getPlayer(r.player());
            double range=BehaviorConfig.number(plugin,"npc-reactions.look-range",8,1,32);
            if(now>=r.until() || !r.npc().entity().isValid() || player==null || !PlayerFilter.accepts(player,plugin.settings())
                    || player.getWorld()!=r.npc().entity().getWorld() || player.getLocation().distanceSquared(r.npc().position())>range*range
                    || !r.npc().entity().hasLineOfSight(player)){cancel(e.getKey());continue;}
            face(r.npc(),player);
        }
        if(tick%200<4)due.entrySet().removeIf(e->now>=e.getValue() && !active.containsKey(e.getKey()));
    }
    private void face(ActiveNpc npc,Player player){
        var direction=player.getEyeLocation().toVector().subtract(npc.entity().getEyeLocation().toVector());
        if(direction.lengthSquared()<.001)return;
        Location facing=npc.position().setDirection(direction);
        npc.entity().setRotation(facing.getYaw(),Math.max(-60,Math.min(60,facing.getPitch())));
    }
    public void cancel(String id){Reaction r=active.remove(id);if(r!=null && r.npc().entity().isValid())r.npc().entity().setRotation(r.yaw(),r.pitch());}
    public void reloaded(){for(String id:List.copyOf(active.keySet()))cancel(id);due.keySet().retainAll(plugin.definitions().keySet());}
    public void clear(){for(String id:List.copyOf(active.keySet()))cancel(id);due.clear();}
}
