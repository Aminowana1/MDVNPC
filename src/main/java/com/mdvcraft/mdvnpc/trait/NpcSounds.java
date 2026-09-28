package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

/** One short voice per NPC, not one simultaneous sound per message recipient. */
public final class NpcSounds {
    private final MdvNpcPlugin plugin;
    private final Map<String,Long> voices=new HashMap<>();
    private final Map<String,Step> steps=new HashMap<>();
    private static final class Step {double distance;long last;}
    public NpcSounds(MdvNpcPlugin plugin){this.plugin=plugin;}
    public void say(ActiveNpc npc,Player player,String line) {
        player.sendMessage(Text.color(Text.placeholders(line,player,npc.definition())));
        if(line.isBlank() || !plugin.settings().messages().getBoolean("npc-sounds.voice-enabled",true))return;
        long now=System.nanoTime();String id=npc.definition().id();
        if(now<voices.getOrDefault(id,0L))return;voices.put(id,now+600_000_000L);
        float volume=volume(npc.definition().traits().type()==Trait.NOISY?"noisy-volume":"voice-volume",npc.definition().traits().type()==Trait.NOISY?1f:.3f);
        Location location=npc.position();location.getWorld().playSound(location,Sound.ENTITY_VILLAGER_AMBIENT,volume,1f);
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
    public void forget(String id){voices.remove(id);steps.remove(id);}
    public void clear(){voices.clear();steps.clear();}
}
