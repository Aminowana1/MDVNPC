package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Bounded speech sequences shared by recipients; uses the existing routine ticker. */
public final class NpcSounds {
    private final MdvNpcPlugin plugin;
    private static final class Voice {ActiveNpc npc;int remaining;long next,expires;boolean idle;}
    private final Map<String,Voice> voices=new HashMap<>();
    private final Map<String,Long> idleDue=new HashMap<>();
    private long ticks,nextIdleScan;
    private final Map<String,Step> steps=new HashMap<>();
    private static final class Step {double distance;long last;}
    public NpcSounds(MdvNpcPlugin plugin){this.plugin=plugin;}
    public void say(ActiveNpc npc,Player player,String line) {
        player.sendMessage(com.mdvcraft.mdvnpc.util.DialogueText.render(line,player,npc.definition()));
        if(line.isBlank() || !plugin.settings().messages().getBoolean("npc-sounds.voice-enabled",true))return;
        startVoice(npc,false);
    }
    private void startVoice(ActiveNpc npc,boolean idle){
        String id=npc.definition().id();Voice existing=voices.get(id);
        // Multiple recipients share the same spoken phrase; no unbounded audio queue.
        if(existing!=null && (!existing.idle || idle))return;
        Voice voice=new Voice();voice.npc=npc;voice.idle=idle;
        String key=idle?"idle-syllables":npc.definition().traits().type()==Trait.NOISY?"noisy-syllables":"voice-syllables";
        voice.remaining=(int)BehaviorConfig.number(plugin,"npc-sounds."+key,idle?8:key.equals("noisy-syllables")?5:3,1,20);
        voice.expires=ticks+200;voices.put(id,voice);pulse(voice);
    }
    private void pulse(Voice v){
        boolean noisy=v.npc.definition().traits().type()==Trait.NOISY;
        float volume=volume(v.idle?"idle-volume":noisy?"noisy-volume":"voice-volume",v.idle?1.2f:noisy?1f:.3f);
        double min=BehaviorConfig.number(plugin,"npc-sounds.pitch-min",.8,.5,2);
        double max=BehaviorConfig.number(plugin,"npc-sounds.pitch-max",1.3,min,2);
        Location at=v.npc.position();at.getWorld().playSound(at,Sound.ENTITY_VILLAGER_AMBIENT,volume,(float)BehaviorConfig.between(min,max));
        v.remaining--;v.next=ticks+(int)BehaviorConfig.number(plugin,"npc-sounds."+(v.idle?"idle-gap-ticks":"voice-gap-ticks"),v.idle?7:5,2,40);
    }
    public void tick(long tick){
        ticks=tick;
        for(var entry:List.copyOf(voices.entrySet())){
            Voice voice=entry.getValue();
            if(voice.idle && !plugin.routines().canReceiveBeer(voice.npc)){voices.remove(entry.getKey());continue;}
            if(!voice.npc.entity().isValid() || tick>=voice.expires || voice.remaining<=0 || !plugin.settings().messages().getBoolean("npc-sounds.voice-enabled",true)) {voices.remove(entry.getKey());continue;}
            if(tick>=voice.next)pulse(voice);
        }
        if(tick<nextIdleScan)return;nextIdleScan=tick+20;
        if(!plugin.settings().messages().getBoolean("npc-sounds.voice-enabled",true) || !plugin.settings().messages().getBoolean("npc-sounds.noisy-idle-enabled",true))return;
        for(ActiveNpc npc:plugin.manager().activeNpcs()){
            if(npc.definition().traits().type()!=Trait.NOISY || !npc.entity().isValid())continue;
            String id=npc.definition().id();
            Long due=idleDue.get(id);if(due==null){idleDue.put(id,tick+idleDelay());continue;}if(tick<due)continue;
            idleDue.put(id,tick+idleDelay());
            if(!plugin.routines().canReceiveBeer(npc) || plugin.reactions()!=null && plugin.reactions().busy(id) || voices.containsKey(id))continue;
            double range=BehaviorConfig.number(plugin,"npc-sounds.idle-player-range",12,1,48);
            if(npc.position().getWorld().getNearbyPlayers(npc.position(),range,p->com.mdvcraft.mdvnpc.runtime.PlayerFilter.accepts(p,plugin.settings())).isEmpty())continue;
            startVoice(npc,true);
        }
    }
    private long idleDelay(){
        long min=BehaviorConfig.ticks(plugin,"npc-sounds.idle-min-seconds",40,5,3600);
        long max=BehaviorConfig.ticks(plugin,"npc-sounds.idle-max-seconds",90,5,3600);
        return BehaviorConfig.between(min,max);
    }
    private float volume(String key,float fallback) {
        double n=plugin.settings().messages().getDouble("npc-sounds."+key,fallback);
        return Double.isFinite(n)?(float)Math.max(0,Math.min(4,n)):fallback;
    }
    public void moved(ActiveNpc npc,Location before,Location after,long tick) {
        if(!plugin.settings().messages().getBoolean("npc-sounds.steps-enabled",true) || before.getWorld()!=after.getWorld())return;
        double dx=after.getX()-before.getX(),dz=after.getZ()-before.getZ(),distance=Math.hypot(dx,dz);
        if(distance<.005 || distance>1.5)return;
        Step s=steps.computeIfAbsent(npc.definition().id(),k->new Step());s.distance+=distance;
        if(s.distance<.85 || tick-s.last<5)return;s.distance=0;s.last=tick;
        int x=after.getBlockX(),z=after.getBlockZ(),y=(int)Math.floor(after.getY()-.05);
        World w=after.getWorld();if(!w.isChunkLoaded(x>>4,z>>4) || y<w.getMinHeight() || y>=w.getMaxHeight())return;
        var floor=w.getBlockAt(x,y,z);
        if(floor.getType().isAir())return;
        w.playSound(after,floor.getBlockData().getSoundGroup().getStepSound(),volume("step-volume",.25f),1);
    }
    public void forget(String id){voices.remove(id);steps.remove(id);idleDue.remove(id);}
    public void clear(){voices.clear();steps.clear();idleDue.clear();}
}
