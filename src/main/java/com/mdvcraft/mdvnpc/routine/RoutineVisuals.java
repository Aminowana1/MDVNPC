package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.trait.TraitBehavior;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.NpcNameService;
import com.mdvcraft.mdvnpc.shop.MmoItemBridge;
import com.mdvcraft.mdvnpc.util.Text;
import com.github.retrooper.packetevents.util.Vector3i;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.data.type.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
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
        PlayerWatcher nameWatcher; float originalNameOffset, posedNameOffset;
        TextDisplay nameDisplay; Location nameLocation; String displayedName;
        boolean restoreNativeName, originalNameVisible;
        NpcNameService ownedNames;double originalOwnedNameOffset, posedOwnedNameOffset;
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
        Pose p=new Pose(); p.npc=npc; p.exit=exit.clone(); p.nextMeal=tick+TraitBehavior.activityDelay(p.npc.definition().traits().type(),mealDelay());
        if(goal.type()==RoutineGoal.Type.SLEEP) {
            if(!(block.getBlockData() instanceof Bed bed) || bed.isOccupied()) return null;
            Location location=block.getLocation().add(.5,.5625,.5); location.setDirection(bed.getFacing().getDirection());
            if(!teleport.test(npc,location)) return null;
            p.sleepingLocation=location.clone();p.bedPosition=new Vector3i(point.x(),point.y(),point.z());
            npc.disguise().getWatcher().setBedPosition(p.bedPosition);
            npc.disguise().getWatcher().setSleeping(true); p.sleeping=true;
            return applyNameOffset(p,"routines.name-offset-sleeping-y");
        }
        if (!(block.getBlockData() instanceof Stairs stairs) || stairs.getHalf()!=org.bukkit.block.data.Bisected.Half.BOTTOM) return null;
        Location location=block.getLocation().add(.5,plugin.settings().messages().getDouble("routines.seat-offset-y",.5),.5);
        location.setDirection(stairs.getFacing().getOppositeFace().getDirection());
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
            if(!p.seat.addPassenger(npc.entity())) {removeSeat(p);return null;}
            p.bodyYaw=location.getYaw();npc.entity().setRotation(p.bodyYaw,0);repairSeat(p);
            return applyNameOffset(p,"routines.name-offset-seated-y");
        }catch(RuntimeException | LinkageError ex) {
            try{leave(p,false);}catch(RuntimeException | LinkageError cleanup){ex.addSuppressed(cleanup);}
            throw ex;
        }
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
    /** Native player nametags have no adjustable height; use a temporary display for those modes. */
    private Pose applyNameOffset(Pose p,String key) {
        double additional=plugin.settings().messages().getDouble(key,0);
        if(additional==0 || p.npc.disguise()==null)return p;
        try {
            if(!Double.isFinite(additional) || additional < -4 || additional > 4)throw new IllegalArgumentException(key+": usar -4..4 bloques");
            var manager=plugin.manager();var names=manager==null?null:manager.names();
            if(names!=null && names.manages(p.npc)) {
                p.ownedNames=names;p.originalOwnedNameOffset=names.offset(p.npc);
                p.posedOwnedNameOffset=p.originalOwnedNameOffset+additional;refreshNameOffset(p);return p;
            }
            p.nameWatcher=p.npc.disguise().getWatcher();p.originalNameOffset=p.nameWatcher.getNameYModifier();
            p.posedNameOffset=(float)(p.originalNameOffset+additional);
            if(p.npc.disguise().getInternals().getNameDisplayType().isFakeEntity())refreshNameOffset(p);
            else if(p.npc.definition().nameVisible() && p.npc.disguise().isNameVisible()) {
                p.originalNameVisible=p.npc.disguise().isNameVisible();
                Location location=nameLocation(p);p.displayedName=currentName(p);
                p.nameDisplay=location.getWorld().spawn(location,TextDisplay.class,display -> {
                    // Keep a reference before configuring, so a failed spawn callback can still be cleaned up.
                    p.nameDisplay=display;
                    display.setPersistent(false);display.setGravity(false);display.setInvulnerable(true);display.setSilent(true);
                    display.setBillboard(Display.Billboard.CENTER);display.setAlignment(TextDisplay.TextAlignment.CENTER);display.setLineWidth(4096);
                    display.setShadowed(true);display.setSeeThrough(false);display.setViewRange(1);
                    display.setInterpolationDuration(0);display.setTeleportDuration(0);
                    display.text(LegacyComponentSerializer.legacySection().deserialize(p.displayedName));
                });
                if(!p.nameDisplay.isValid())throw new IllegalStateException("No se pudo crear el nombre del NPC");
                p.nameLocation=location;p.restoreNativeName=true;
                p.npc.disguise().setNameVisible(false);
            }else p.nameWatcher=null;
            return p;
        }catch(RuntimeException | LinkageError ex){
            try{leave(p,true);}catch(RuntimeException | LinkageError cleanup){ex.addSuppressed(cleanup);}
            throw ex;
        }
    }
    private String currentName(Pose p) {
        String name=p.npc.disguise().getName();
        return Text.color(name==null?p.npc.definition().name():name);
    }
    private Location nameLocation(Pose p) {
        var disguise=p.npc.disguise();
        // Match the public LibsDisguises text-display baseline, including its existing name modifier.
        double scale=disguise.getDisguiseScale();
        Location location=p.npc.position().clone();
        location.add(0,(disguise.getHeight()+p.posedNameOffset)*scale+.27,0);
        location.setYaw(0);location.setPitch(0);return location;
    }
    private void refreshNameOffset(Pose p) {
        if(p.ownedNames!=null) {
            if(Double.compare(p.ownedNames.offset(p.npc),p.posedOwnedNameOffset)!=0)p.ownedNames.offset(p.npc,p.posedOwnedNameOffset);
        }else if(p.nameDisplay!=null) {
            Location location=nameLocation(p);
            if(!location.equals(p.nameLocation) && p.nameDisplay.teleport(location))p.nameLocation=location;
            String name=currentName(p);
            if(!name.equals(p.displayedName)) {p.nameDisplay.text(LegacyComponentSerializer.legacySection().deserialize(name));p.displayedName=name;}
        }else if(p.nameWatcher!=null && Float.compare(p.nameWatcher.getNameYModifier(),p.posedNameOffset)!=0)
            p.nameWatcher.setNameYModifier(p.posedNameOffset);
    }
    private void restoreNameOffset(Pose p) {
        try {
            if(p.ownedNames!=null)p.ownedNames.offset(p.npc,p.originalOwnedNameOffset);
            else if(p.restoreNativeName)p.npc.disguise().setNameVisible(p.originalNameVisible);
            else if(p.nameWatcher!=null && p.nameDisplay==null && Float.compare(p.nameWatcher.getNameYModifier(),p.originalNameOffset)!=0)
                p.nameWatcher.setNameYModifier(p.originalNameOffset);
        }finally {
            try{if(p.nameDisplay!=null)p.nameDisplay.remove();}
            finally{p.nameDisplay=null;p.nameLocation=null;p.displayedName=null;p.restoreNativeName=false;p.nameWatcher=null;p.ownedNames=null;}
        }
    }
    private long mealDelay() {
        int min=Math.max(10,Math.min(3600,plugin.settings().messages().getInt("routines.meal-min-seconds",30)));
        int max=Math.max(min,Math.min(3600,plugin.settings().messages().getInt("routines.meal-max-seconds",90)));
        return ThreadLocalRandom.current().nextLong(min,Math.max((long)min+1,(long)max+1))*20;
    }
    public void tick(Pose p,long tick) {
        repairSeat(p);
        refreshNameOffset(p);
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
        if(current.getWorld()!=bed.getWorld())return false;
        boolean moved=current.distanceSquared(bed)>.04;
        var watcher=p.npc.disguise().getWatcher();
        if(moved && !teleport.test(p.npc,bed))return false;
        if(force || moved || !watcher.isSleeping() || !p.bedPosition.equals(watcher.getBedPosition())) {
            watcher.setSleeping(false);watcher.setBedPosition(p.bedPosition);watcher.setSleeping(true);
            p.npc.entity().setRotation(bed.getYaw(),0);
        }
        refreshNameOffset(p);
        return true;
    }
    public void leave(Pose p, boolean reposition) {
        try{
            finishMeal(p);
            if(p.sleeping) { p.npc.disguise().getWatcher().setSleeping(false); p.npc.disguise().getWatcher().setBedPosition(Optional.empty()); }
        }finally {
            // Dance, deletion and reload must never retain a mount if item/name metadata failed.
            try{removeSeat(p);}
            finally{restoreNameOffset(p);}
        }
        if(reposition && p.exit!=null && p.exit.getWorld().isChunkLoaded(p.exit.getBlockX()>>4,p.exit.getBlockZ()>>4)) {
            Location exit=p.exit.clone();exit.setPitch(0);teleport.test(p.npc,exit);
        }
    }
}
