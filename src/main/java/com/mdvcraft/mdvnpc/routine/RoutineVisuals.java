package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.trait.TraitBehavior;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.shop.MmoItemBridge;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.*;
import org.bukkit.block.data.type.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Cosmetic items are never inserted in an inventory or dropped; no MMO consumable effects. */
public final class RoutineVisuals {
    public static final class Pose {
        ActiveNpc npc; ArmorStand seat; Location exit; boolean sleeping;
        long nextMeal, mealUntil, nextEffect; ItemStack previous, meal; boolean drinking, reading;
        Location sleepingLocation, seatLocation; Vector3i bedPosition; float bodyYaw;
        Set<UUID> seatViewers=Set.of();
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
        Location current=npc.position();
        // Mounting/sleep metadata changes position. Require the checked doorway to have
        // actually been reached, even if a stale route reported ARRIVED.
        if(exit==null || exit.getWorld()!=world || current.getWorld()!=world || !point.world().equals(world.getUID())
                || current.distanceSquared(exit)>.36 || !local(current,point.location(world),2))return null;
        if (!world.isChunkLoaded(point.x()>>4,point.z()>>4)) return null;
        var block=world.getBlockAt(point.x(),point.y(),point.z());
        Pose p=new Pose(); p.npc=npc; p.exit=exit.clone(); p.nextMeal=tick+TraitBehavior.activityDelay(p.npc.definition().traits().type(),mealDelay());
        if(goal.type()==RoutineGoal.Type.SLEEP) {
            if(!(block.getBlockData() instanceof Bed bed) || bed.isOccupied()) return null;
            Location location=block.getLocation().add(.5,.5625,.5); location.setDirection(bed.getFacing().getDirection());
            if(!teleport.test(npc,location)) return null;
            p.sleepingLocation=location.clone();p.bedPosition=new Vector3i(point.x(),point.y(),point.z());
            npc.disguise().getWatcher().setBedPosition(p.bedPosition);
            npc.disguise().getWatcher().setSleeping(true); p.sleeping=true;
            return p;
        }
        if (!(block.getBlockData() instanceof Stairs stairs) || stairs.getHalf()!=org.bukkit.block.data.Bisected.Half.BOTTOM) return null;
        Location location=block.getLocation().add(.5,plugin.settings().messages().getDouble("routines.seat-offset-y",.5),.5);
        location.setDirection(stairs.getFacing().getOppositeFace().getDirection());
        p.seatLocation=location.clone();
        try {
            p.seat=world.spawn(location,ArmorStand.class,stand -> {
                // Retain the support even if another spawn callback or configuration step fails.
                p.seat=stand;
                stand.setVisible(false);stand.setInvisible(true);stand.setMarker(true);stand.setSmall(true);stand.setGravity(false);
                stand.setInvulnerable(true);stand.setSilent(true);stand.setPersistent(false);stand.setBasePlate(false);stand.setArms(false);stand.setGlowing(false);
                stand.setCustomNameVisible(false);stand.setCustomName(null);
                stand.getPersistentDataContainer().set(marker,PersistentDataType.STRING,npc.definition().id());
            });
            if(!p.seat.isValid()) {removeSeat(p);return null;}
            // Paper's persistent invisibility also protects the shared entity metadata flag.
            // Reapply after spawn listeners have run, before exposing a mounted passenger.
            p.seat.setInvisible(true);repairSeat(p);
            if(!p.seat.addPassenger(npc.entity()) || npc.entity().getVehicle()!=p.seat) {leave(p,true);return null;}
            p.bodyYaw=location.getYaw();npc.entity().setRotation(p.bodyYaw,0);repairSeat(p);
            p.seatViewers=seatViewers(p);
            return p;
        }catch(RuntimeException | LinkageError ex) {
            try{leave(p,true);}catch(RuntimeException | LinkageError cleanup){ex.addSuppressed(cleanup);}
            throw ex;
        }
    }
    private static Set<UUID> seatViewers(Pose p) {
        Set<UUID> viewers=new HashSet<>();
        for(Player player:p.npc.entity().getTrackedBy())viewers.add(player.getUniqueId());
        return viewers;
    }
    /** Restore only this chair's passenger, and resend the mount when a new observer joins. */
    public boolean restoreSeat(Pose p,boolean force) {
        if(p.sleeping)return true;
        ArmorStand seat=p.seat;Location expected=p.seatLocation;
        if(seat==null || !seat.isValid() || expected==null || expected.getWorld()==null
                || !expected.getWorld().isChunkLoaded(expected.getBlockX()>>4,expected.getBlockZ()>>4))return false;
        Location support=seat.getLocation(),current=p.npc.position();
        // A moved support or passenger needs a new walking approach, not a warp to an old chair.
        if(support==null || support.getWorld()!=expected.getWorld() || support.distanceSquared(expected)>.04
                || !local(current,expected,1))return false;
        var vehicle=p.npc.entity().getVehicle();
        if(vehicle!=null && vehicle!=seat)return false;
        if(vehicle==null && !local(current,p.exit,1))return false;
        repairSeat(p);
        Set<UUID> viewers=seatViewers(p);
        boolean newViewer=!p.seatViewers.containsAll(viewers);
        // New trackers can receive the disguise spawn after the original passenger packet.
        // A bounded remount resends the sitting state without changing a stable pose every tick.
        if(vehicle==null || force || newViewer) {
            if(vehicle==seat && (!p.npc.entity().leaveVehicle() || p.npc.entity().getVehicle()!=null))return false;
            if(!seat.addPassenger(p.npc.entity()) || p.npc.entity().getVehicle()!=seat)return false;
            p.npc.entity().setRotation(p.bodyYaw,0);
        }
        p.seatViewers=viewers;
        return true;
    }
    /** Existing pose cadence repairs external changes without scheduling another task or resending stable flags. */
    private void repairSeat(Pose p) {
        ArmorStand seat=p.seat;if(seat==null || !seat.isValid())return;
        if(seat.isVisible() || !seat.isInvisible()) {seat.setVisible(false);seat.setInvisible(true);}
        if(seat.isGlowing())seat.setGlowing(false);
        if(!seat.isMarker())seat.setMarker(true);
        if(!seat.isSmall())seat.setSmall(true);
        if(seat.hasGravity())seat.setGravity(false);
        if(seat.hasBasePlate())seat.setBasePlate(false);
        if(seat.hasArms())seat.setArms(false);
        if(seat.isCustomNameVisible())seat.setCustomNameVisible(false);
    }
    private void removeSeat(Pose p) {
        ArmorStand seat=p.seat;if(seat==null)return;
        try{if(p.npc.entity().getVehicle()==seat)p.npc.entity().leaveVehicle();}
        finally {
            try{seat.remove();}
            finally{p.seat=null;}
        }
    }
    private static boolean local(Location from,Location to,double verticalLimit) {
        if(from==null || to==null || from.getWorld()==null || from.getWorld()!=to.getWorld())return false;
        double dx=from.getX()-to.getX(),dz=from.getZ()-to.getZ();
        return dx*dx+dz*dz<=4 && Math.abs(from.getY()-to.getY())<=verticalLimit;
    }
    private boolean canLeaveAtExit(Pose p) {
        if(p.exit==null)return false;
        Location current=p.npc.position();
        if(!p.sleeping && p.seat!=null && p.seatLocation!=null && p.npc.entity().getVehicle()==p.seat) {
            // A configured chair height is valid while still on its original support;
            // it must not widen the permitted displacement after dismounting.
            return local(current,p.seatLocation,1)
                    && local(current,p.exit,Math.abs(p.seatLocation.getY()-p.exit.getY())+1);
        }
        return local(current,p.exit,1);
    }
    private long mealDelay() {
        int min=Math.max(10,Math.min(3600,plugin.settings().messages().getInt("routines.meal-min-seconds",30)));
        int max=Math.max(min,Math.min(3600,plugin.settings().messages().getInt("routines.meal-max-seconds",90)));
        return ThreadLocalRandom.current().nextLong(min,Math.max((long)min+1,(long)max+1))*20;
    }
    public void tick(Pose p,long tick) {
        repairSeat(p);
        if(p.sleeping) return;
        if(p.mealUntil>0 && tick>=p.mealUntil) { finishMeal(p); p.nextMeal=tick+TraitBehavior.activityDelay(p.npc.definition().traits().type(),mealDelay()); }
        if(p.mealUntil==0 && tick>=p.nextMeal) {
            p.nextMeal=tick+TraitBehavior.activityDelay(p.npc.definition().traits().type(),mealDelay());
            boolean meals=plugin.settings().messages().getBoolean("routines.seated-consumption",true);
            boolean books=plugin.settings().messages().getBoolean("routines.seated-reading",true);
            double chance=plugin.settings().messages().getDouble("routines.reading-chance",.3);
            if(!Double.isFinite(chance))chance=.3;
            chance=TraitBehavior.readingChance(p.npc.definition().traits().type(),chance);
            p.reading=books && (!meals || ThreadLocalRandom.current().nextDouble()<Math.max(0,Math.min(1,chance)));
            if(!meals && !p.reading)return;
            int choice=ThreadLocalRandom.current().nextDouble()<TraitBehavior.drinkChance(p.npc.definition().traits().type())?ThreadLocalRandom.current().nextInt(4,6):ThreadLocalRandom.current().nextInt(4); p.drinking=choice>=4;
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
            p.mealUntil=tick+(p.reading?TraitBehavior.readingDuration(p.npc.definition().traits().type(),ThreadLocalRandom.current().nextLong(min,(long)max+1)*20):64); p.nextEffect=tick;
        }
        effects(p,tick);
    }
    private void effects(Pose p,long tick) {
        if(!p.reading && p.mealUntil>tick && tick>=p.nextEffect) {
            p.nextEffect=tick+8; Location mouth=p.npc.entity().getEyeLocation();
            mouth.add(mouth.getDirection().multiply(.25));
            mouth.getWorld().playSound(mouth,p.drinking?Sound.ENTITY_GENERIC_DRINK:Sound.ENTITY_GENERIC_EAT,.35f,1);
            if(!p.drinking) mouth.getWorld().spawnParticle(Particle.ITEM,mouth,3,.07,.07,.07,.015,p.meal);
            else mouth.getWorld().spawnParticle(Particle.SPLASH,mouth,2,.04,.04,.04,0);
        }
    }
    public Pose beginDrink(ActiveNpc npc,Pose existing,ItemStack beer,long tick) {
        Pose p=existing==null?new Pose():existing;p.npc=npc;finishMeal(p);
        p.previous=npc.disguise().getWatcher().getItemInMainHand();
        if(p.previous!=null)p.previous=p.previous.clone();
        p.meal=beer.clone();p.meal.setAmount(1);p.drinking=true;p.reading=false;
        p.mealUntil=tick+64;p.nextEffect=tick;
        try {npc.disguise().getWatcher().setItemInMainHand(p.meal);npc.disguise().getWatcher().setMainHandRaised(true);}
        catch(RuntimeException ex){finishMeal(p);throw ex;}
        return p;
    }
    public boolean drinkTick(Pose p,long tick) {
        if(tick>=p.mealUntil){suspend(p,tick);return true;}
        effects(p,tick);return false;
    }
    private void finishMeal(Pose p) {
        if(p.mealUntil==0) return;
        var watcher=p.npc.disguise().getWatcher(); watcher.setMainHandRaised(false); watcher.setItemInMainHand(p.previous);
        p.meal=null; p.previous=null; p.mealUntil=0;p.reading=false;
    }
    public void suspend(Pose p,long tick) {repairSeat(p);finishMeal(p);p.nextMeal=tick+TraitBehavior.activityDelay(p.npc.definition().traits().type(),mealDelay());}
    /** Called on activation; periodic calls only send metadata if the sleep pose drifted. */
    public boolean restoreSleep(Pose p,boolean force) {
        if(!p.sleeping || p.sleepingLocation==null)return true;
        Location bed=p.sleepingLocation;
        if(!bed.getWorld().isChunkLoaded(bed.getBlockX()>>4,bed.getBlockZ()>>4))return false;
        if(!(bed.getBlock().getBlockData() instanceof Bed data) || data.isOccupied())return false;
        Location current=p.npc.position();
        // A displaced sleeper must walk back through the routine instead of being warped
        // across the room; small pose drift is still repaired in place.
        if(!local(current,bed,1))return false;
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
        // Capture before dismounting: removing a support must not disguise a distant
        // external displacement as a nearby exit. The seat height remains configurable.
        boolean localExit=reposition && canLeaveAtExit(p);
        try{
            finishMeal(p);
            if(p.sleeping) { p.npc.disguise().getWatcher().setSleeping(false); p.npc.disguise().getWatcher().setBedPosition(Optional.empty()); }
        }finally {
            // Dance, deletion and reload must never retain a mount if item metadata failed.
            removeSeat(p);
        }
        if(localExit && p.exit.getWorld().isChunkLoaded(p.exit.getBlockX()>>4,p.exit.getBlockZ()>>4)) {
            Location exit=p.exit.clone();exit.setPitch(0);teleport.test(p.npc,exit);
        }
    }
}
