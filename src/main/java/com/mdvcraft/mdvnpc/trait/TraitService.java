package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.routine.RoutineVisuals;
import com.mdvcraft.mdvnpc.shop.MmoItemBridge;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Main-thread, bounded-radius offers. No inventory storage, chunk tickets or per-NPC tasks. */
public final class TraitService {
    private record Drink(ActiveNpc npc,UUID player,RoutineVisuals.Pose pose) {}
    private final MdvNpcPlugin plugin;
    private final MmoItemBridge mmo;
    private final Map<String,Drink> drinks=new HashMap<>();
    private final Map<String,Long> due=new HashMap<>();
    private final Set<String> reserving=new HashSet<>(),failed=new HashSet<>();
    private long tick,nextScan;
    public TraitService(MdvNpcPlugin plugin){this.plugin=plugin;mmo=new MmoItemBridge(plugin.getLogger());}
    public boolean busy(String id){return drinks.containsKey(id) || reserving.contains(id);}
    public void cancel(String id) {
        Drink d=drinks.remove(id);
        if(d!=null)plugin.routines().cancelDrink(d.pose(),tick);
    }
    public void reloaded(){failed.clear();due.keySet().retainAll(plugin.definitions().keySet());}
    public void close(){for(String id:List.copyOf(drinks.keySet()))cancel(id);due.clear();failed.clear();}
    public void tick(long tick) {
        this.tick=tick;
        for(var entry:List.copyOf(drinks.entrySet())) {
            String id=entry.getKey();Drink d=entry.getValue();
            try {
                if(!d.npc().entity().isValid() || !plugin.routines().canReceiveBeer(d.npc())) {cancel(id);continue;}
                if(!plugin.routines().drinkTick(d.pose(),tick))continue;
                drinks.remove(id);
                Location at=d.npc().position();at.getWorld().playSound(at,Sound.ENTITY_PLAYER_BURP,.6f,.9f);
                Player player=Bukkit.getPlayer(d.player());var lines=d.npc().definition().traits().beerLines();
                if(player!=null && player.isOnline() && player.getWorld()==at.getWorld()
                        && player.getLocation().distanceSquared(at)<=64 && !lines.isEmpty())
                    plugin.sounds().say(d.npc(),player,lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
            } catch(RuntimeException ex){fail(id,ex);}
        }
        if(tick<nextScan)return;nextScan=tick+10;
        if(!Bukkit.getPluginManager().isPluginEnabled("MMOItems"))return;
        long now=System.nanoTime();
        for(ActiveNpc npc:plugin.manager().activeNpcs()) {
            String id=npc.definition().id();
            if(npc.definition().traits().type()!=Trait.ALCOHOLIC || busy(id) || failed.contains(id)
                    || now<due.getOrDefault(id,0L) || !npc.entity().isValid() || !plugin.routines().canReceiveBeer(npc))continue;
            try {
                // Skip dormant zones even for static NPCs. Item queries never search the entire world.
                Location at=npc.position();
                if(at.getWorld().getNearbyPlayers(at,8,p->!p.isDead() && p.getGameMode()!=GameMode.SPECTATOR).isEmpty())continue;
                int checked=0;
                for(var entity:npc.entity().getNearbyEntities(2.5,2.5,2.5)) {
                    if(++checked>64)break;
                    if(!(entity instanceof Item item))continue;
                    Player player=eligible(npc,item);if(player==null)continue;
                    ItemStack original=item.getItemStack().clone();
                    if(!beer(original))continue;
                    if(accept(npc,item,player,original,now))break;
                }
            } catch(RuntimeException ex){fail(id,ex);}
        }
    }
    private Player eligible(ActiveNpc npc,Item item) {
        if(!item.isValid() || item.isDead() || !item.canMobPickup() || !item.canPlayerPickup() || item.getPickupDelay()>=32767
                || item.getWorld()!=npc.entity().getWorld() || item.getLocation().distanceSquared(npc.position())>6.25
                || !npc.entity().hasLineOfSight(item))return null;
        UUID thrower=item.getThrower();if(thrower==null || item.getOwner()!=null && !item.getOwner().equals(thrower))return null;
        Player player=Bukkit.getPlayer(thrower);
        return player!=null && player.isOnline() && !player.isDead() && player.getGameMode()!=GameMode.SPECTATOR
                && player.getWorld()==item.getWorld() && player.getLocation().distanceSquared(npc.position())<=64?player:null;
    }
    private boolean beer(ItemStack stack) {
        if(stack==null || stack.getAmount()<1 || stack.getType().isAir())return false;
        var identity=mmo.identity(stack);
        return identity!=null && identity.type().equalsIgnoreCase(plugin.settings().messages().getString("routines.beer-type","CONSUMABLE"))
                && identity.id().equalsIgnoreCase(plugin.settings().messages().getString("routines.beer-id","CERVEZA"));
    }
    boolean accept(ActiveNpc npc,Item item,Player player,ItemStack original,long now) {
        String id=npc.definition().id();
        if(busy(id) || now<due.getOrDefault(id,0L))return false;
        reserving.add(id);
        RoutineVisuals.Pose pose=null;boolean debited=false;
        try {
            var event=new EntityPickupItemEvent(npc.entity(),item,original.getAmount()-1);
            Bukkit.getPluginManager().callEvent(event);
            // Other plugins may cancel, change the stack, remove the NPC or reload us in the event.
            if(event.isCancelled() || !npc.entity().isValid() || !plugin.manager().activeNpcs().contains(npc)
                    || !plugin.routines().canReceiveBeer(npc) || eligible(npc,item)!=player
                    || !original.equals(item.getItemStack()))return false;
            pose=plugin.routines().beginDrink(npc,original,tick);
            ItemStack rest=original.clone();rest.setAmount(original.getAmount()-1);
            if(rest.getAmount()==0)item.remove();else item.setItemStack(rest);
            debited=true;
            due.put(id,now+(long)(npc.definition().traits().beerCooldownSeconds()*1_000_000_000L));
            drinks.put(id,new Drink(npc,player.getUniqueId(),pose));
            return true;
        } finally {
            reserving.remove(id);
            if(!debited && pose!=null)plugin.routines().cancelDrink(pose,tick);
        }
    }
    private void fail(String id,RuntimeException ex) {
        try {cancel(id);}catch(RuntimeException cleanup){ex.addSuppressed(cleanup);}
        if(failed.add(id))plugin.getLogger().log(java.util.logging.Level.WARNING,"Rasgo pausado para "+id+"; revisa el error y usa /mdvnpc reload",ex);
    }
}
