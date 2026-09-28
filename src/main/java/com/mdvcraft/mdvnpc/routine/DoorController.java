package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityInteractEvent;
import java.util.*;

/** Tracks only doors a routine actually passes; rechecks protection and clearance before closing. */
public final class DoorController {
    private record Opened(Block bottom, String state, String topState, UUID npc, long due, long expires) {}
    private final Map<String, Opened> opened = new HashMap<>();
    private final MdvNpcPlugin plugin;
    public DoorController(MdvNpcPlugin plugin) { this.plugin = plugin; }
    public boolean canOpen(Block block) {
        if (!Tag.WOODEN_DOORS.isTagged(block.getType())) return false;
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard"))
            return com.mdvcraft.mdvnpc.integration.RoutineRegionPolicy.canUse(block.getLocation());
        return true;
    }
    public boolean openNear(Entity npc, Location next, long tick) {
        for (int x=(int)Math.floor(next.getX()-.32); x <= (int)Math.floor(next.getX()+.32); x++)
            for (int z=(int)Math.floor(next.getZ()-.32); z <= (int)Math.floor(next.getZ()+.32); z++)
                for (int y=next.getBlockY(); y <= next.getBlockY()+1; y++) {
                    if (!next.getWorld().isChunkLoaded(x>>4,z>>4)) return false;
                    Block block = next.getWorld().getBlockAt(x,y,z);
                    if (!(block.getBlockData() instanceof Door door)) continue;
                    if (door.getHalf() == Bisected.Half.TOP) block = block.getRelative(0,-1,0);
                    if(!(block.getBlockData() instanceof Door lower) || lower.getHalf()!=Bisected.Half.BOTTOM)continue;
                    if(!(block.getRelative(0,1,0).getBlockData() instanceof Door upper)
                            || upper.getHalf()!=Bisected.Half.TOP || block.getRelative(0,1,0).getType()!=block.getType())continue;
                    String key=key(block);
                    if(lower.isOpen()) {
                        // Also schedule pre-opened doors, but only along the path, never a scan of the town.
                        if((opened.containsKey(key) || plugin.settings().messages().getBoolean("routines.close-preopened-doors",true))
                                && !lower.isPowered() && canOpen(block)) remember(block,npc,tick);
                        continue;
                    }
                    opened.remove(key);
                    if (!canOpen(block)) return false;
                    var event = new EntityInteractEvent(npc, block); Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) return false;
                    change(block, true);
                    remember(block,npc,tick);
                    block.getWorld().playSound(block.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, .5f, 1);
                }
        return true;
    }
    private void remember(Block bottom,Entity npc,long tick) {
        String key=key(bottom);
        // Bound retained state even if users configure very large NPC populations.
        if(!opened.containsKey(key) && opened.size()>=256)return;
        opened.put(key,new Opened(bottom,bottom.getBlockData().getAsString(),bottom.getRelative(0,1,0).getBlockData().getAsString(),
                npc.getUniqueId(),tick+20,tick+1200));
    }
    private static String key(Block b) { return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ(); }
    private static void change(Block bottom, boolean open) {
        for (int dy=0; dy<2; dy++) { Block b = bottom.getRelative(0,dy,0); if (b.getBlockData() instanceof Door door) { door.setOpen(open); b.setBlockData(door, false); } }
    }
    public void tick(long tick, boolean closing) {
        var it = opened.values().iterator();
        while (it.hasNext()) {
            Opened entry = it.next(); Block b = entry.bottom;
            if (!b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4)) { it.remove(); continue; }
            if(tick>entry.expires && !closing) {it.remove();continue;}
            if (!b.getBlockData().getAsString().equals(entry.state)
                    || !b.getRelative(0,1,0).getBlockData().getAsString().equals(entry.topState)) { it.remove(); continue; }
            if(!(b.getBlockData() instanceof Door door) || !door.isOpen() || door.isPowered() || !canOpen(b)) {it.remove();continue;}
            if (!closing && tick < entry.due) continue;
            if (!b.getWorld().getNearbyEntities(b.getLocation().add(.5,1,.5), 1.1, 1.6, 1.1, e -> e instanceof org.bukkit.entity.LivingEntity).isEmpty()) {
                if (closing) it.remove(); continue;
            }
            Entity actor=b.getWorld().getEntity(entry.npc);
            if(actor==null || !actor.isValid()) {it.remove();continue;}
            var event=new EntityInteractEvent(actor,b);Bukkit.getPluginManager().callEvent(event);
            if(event.isCancelled()) {it.remove();continue;}
            // Event listeners may alter a door while deciding whether to allow the action.
            if(b.getBlockData().getAsString().equals(entry.state) && b.getRelative(0,1,0).getBlockData().getAsString().equals(entry.topState)) {
                change(b,false); b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE,.5f,1);
            }
            it.remove();
        }
    }
    public void close() { tick(Long.MAX_VALUE,true); opened.clear(); }
}
