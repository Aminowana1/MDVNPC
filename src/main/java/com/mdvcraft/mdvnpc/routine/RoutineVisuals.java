package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.shop.MmoItemBridge;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.*;
import org.bukkit.block.data.type.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Cosmetic items are never inserted in an inventory or dropped; no MMO consumable effects. */
public final class RoutineVisuals {
    public static final class Pose {
        ActiveNpc npc; ArmorStand seat; Location exit; boolean sleeping;
        long nextMeal, mealUntil, nextEffect; ItemStack previous, meal; boolean drinking, reading;
        Location sleepingLocation; Vector3i bedPosition; float bodyYaw;
    }
    private final MdvNpcPlugin plugin;
    private final MmoItemBridge mmo;
    private final NamespacedKey marker;
    private final java.util.function.BiPredicate<ActiveNpc,Location> teleport;
    public RoutineVisuals(MdvNpcPlugin plugin, java.util.function.BiPredicate<ActiveNpc,Location> teleport) {
        this.plugin=plugin; this.teleport=teleport; marker=new NamespacedKey(plugin,"routine-seat"); mmo=new MmoItemBridge(plugin.getLogger());
    }
    public boolean isSeat(org.bukkit.entity.Entity entity) { return entity.getPersistentDataContainer().has(marker,PersistentDataType.STRING); }
    public Pose enter(ActiveNpc npc, RoutineGoal goal, RoutineGoal.Point point, Location exit, long tick) {
        World world=npc.entity().getWorld();
        if (!world.isChunkLoaded(point.x()>>4,point.z()>>4)) return null;
        var block=world.getBlockAt(point.x(),point.y(),point.z());
        Pose p=new Pose(); p.npc=npc; p.exit=exit.clone(); p.nextMeal=tick+mealDelay();
        if(goal.type()==RoutineGoal.Type.SLEEP) {
            if(!(block.getBlockData() instanceof Bed bed) || bed.isOccupied()) return null;
            Location location=block.getLocation().add(.5,.5625,.5); location.setDirection(bed.getFacing().getDirection());
            if(!teleport.test(npc,location)) return null;
            p.sleepingLocation=location.clone();p.bedPosition=new Vector3i(point.x(),point.y(),point.z());
            npc.disguise().getWatcher().setBedPosition(p.bedPosition);
            npc.disguise().getWatcher().setSleeping(true); p.sleeping=true; return p;
        }
        if (!(block.getBlockData() instanceof Stairs stairs) || stairs.getHalf()!=org.bukkit.block.data.Bisected.Half.BOTTOM) return null;
        Location location=block.getLocation().add(.5,plugin.settings().messages().getDouble("routines.seat-offset-y",0),.5);
        location.setDirection(stairs.getFacing().getOppositeFace().getDirection());
        p.seat=world.spawn(location,ArmorStand.class,stand -> {
            stand.setVisible(false); stand.setMarker(true); stand.setSmall(true); stand.setGravity(false);
            stand.setInvulnerable(true); stand.setSilent(true); stand.setPersistent(false); stand.setBasePlate(false);
            stand.getPersistentDataContainer().set(marker,PersistentDataType.STRING,npc.definition().id());
        });
        if(!p.seat.isValid() || !p.seat.addPassenger(npc.entity())) { p.seat.remove(); return null; }
        p.bodyYaw=location.getYaw();npc.entity().setRotation(p.bodyYaw,0); return p;
    }
    private long mealDelay() {
        int min=Math.max(10,Math.min(3600,plugin.settings().messages().getInt("routines.meal-min-seconds",30)));
        int max=Math.max(min,Math.min(3600,plugin.settings().messages().getInt("routines.meal-max-seconds",90)));
        return ThreadLocalRandom.current().nextLong(min,Math.max((long)min+1,(long)max+1))*20;
    }
    public void tick(Pose p,long tick) {
        if(p.sleeping) return;
        if(p.mealUntil>0 && tick>=p.mealUntil) { finishMeal(p); p.nextMeal=tick+mealDelay(); }
        if(p.mealUntil==0 && tick>=p.nextMeal) {
            p.nextMeal=tick+mealDelay();
            boolean meals=plugin.settings().messages().getBoolean("routines.seated-consumption",true);
            boolean books=plugin.settings().messages().getBoolean("routines.seated-reading",true);
            double chance=plugin.settings().messages().getDouble("routines.reading-chance",.3);
            if(!Double.isFinite(chance))chance=.3;
            p.reading=books && (!meals || ThreadLocalRandom.current().nextDouble()<Math.max(0,Math.min(1,chance)));
            if(!meals && !p.reading)return;
            int choice=ThreadLocalRandom.current().nextInt(6); p.drinking=choice>=4;
            p.meal=new ItemStack(p.reading?Material.BOOK:switch(choice){case 0 -> Material.COOKED_BEEF; case 1 -> Material.APPLE; case 2 -> Material.MUSHROOM_STEW; case 3 -> Material.COOKED_BEEF; default -> Material.POTION;});
            if(!p.reading && choice==5 && Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
                ItemStack beer=mmo.create(plugin.settings().messages().getString("routines.beer-type","CONSUMABLE"),plugin.settings().messages().getString("routines.beer-id","CERVEZA"));
                if(beer!=null && !beer.getType().isAir()) { beer.setAmount(1); p.meal=beer; }
            }
            var watcher=p.npc.disguise().getWatcher(); p.previous=watcher.getItemInMainHand();
            if(p.previous!=null) p.previous=p.previous.clone();
            watcher.setItemInMainHand(p.meal); watcher.setMainHandRaised(!p.reading);
            int min=Math.max(3,Math.min(300,plugin.settings().messages().getInt("routines.reading-min-seconds",12)));
            int max=Math.max(min,Math.min(300,plugin.settings().messages().getInt("routines.reading-max-seconds",25)));
            p.mealUntil=tick+(p.reading?ThreadLocalRandom.current().nextLong(min,(long)max+1)*20:64); p.nextEffect=tick;
        }
        if(!p.reading && p.mealUntil>tick && tick>=p.nextEffect) {
            p.nextEffect=tick+8; Location mouth=p.npc.entity().getEyeLocation();
            mouth.add(mouth.getDirection().multiply(.25));
            mouth.getWorld().playSound(mouth,p.drinking?Sound.ENTITY_GENERIC_DRINK:Sound.ENTITY_GENERIC_EAT,.35f,1);
            if(!p.drinking) mouth.getWorld().spawnParticle(Particle.ITEM,mouth,3,.07,.07,.07,.015,p.meal);
            else mouth.getWorld().spawnParticle(Particle.SPLASH,mouth,2,.04,.04,.04,0);
        }
    }
    private void finishMeal(Pose p) {
        if(p.mealUntil==0) return;
        var watcher=p.npc.disguise().getWatcher(); watcher.setMainHandRaised(false); watcher.setItemInMainHand(p.previous);
        p.meal=null; p.previous=null; p.mealUntil=0;p.reading=false;
    }
    public void suspend(Pose p,long tick) {finishMeal(p);p.nextMeal=tick+mealDelay();}
    /** Called on activation; periodic calls only send metadata if the sleep pose drifted. */
    public boolean restoreSleep(Pose p,boolean force) {
        if(!p.sleeping || p.sleepingLocation==null)return true;
        Location bed=p.sleepingLocation;
        if(!bed.getWorld().isChunkLoaded(bed.getBlockX()>>4,bed.getBlockZ()>>4))return false;
        if(!(bed.getBlock().getBlockData() instanceof Bed data) || data.isOccupied())return false;
        Location current=p.npc.position();
        if(current.getWorld()!=bed.getWorld())return false;
        boolean moved=current.distanceSquared(bed)>.04;
        var watcher=p.npc.disguise().getWatcher();
        if(moved && !teleport.test(p.npc,bed))return false;
        if(force || moved || !watcher.isSleeping() || !p.bedPosition.equals(watcher.getBedPosition())) {
            watcher.setSleeping(false);watcher.setBedPosition(p.bedPosition);watcher.setSleeping(true);
            p.npc.entity().setRotation(bed.getYaw(),0);
        }
        return true;
    }
    public void leave(Pose p, boolean reposition) {
        finishMeal(p);
        if(p.sleeping) { p.npc.disguise().getWatcher().setSleeping(false); p.npc.disguise().getWatcher().setBedPosition(Optional.empty()); }
        if(p.seat!=null) { p.npc.entity().leaveVehicle(); p.seat.remove(); }
        if(reposition && p.exit.getWorld().isChunkLoaded(p.exit.getBlockX()>>4,p.exit.getBlockZ()>>4)) {
            Location exit=p.exit.clone();exit.setPitch(0);teleport.test(p.npc,exit);
        }
    }
}
