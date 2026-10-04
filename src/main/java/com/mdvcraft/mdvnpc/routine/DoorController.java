package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.util.BoundingBox;
import java.util.*;

/** Tracks only doors a routine actually passes; rechecks protection and clearance before closing. */
public final class DoorController {
    private record Opened(Block bottom, Material type, String state, String topState,
                          List<UUID> actors, long due, long expires) {}
    private record Pair(Block bottom, Door lower, Door upper) {
        boolean powered() { return lower.isPowered() || upper.isPowered(); }
    }
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
        Set<String> checked = new HashSet<>();
        for (int x=(int)Math.floor(next.getX()-.32); x <= (int)Math.floor(next.getX()+.32); x++)
            for (int z=(int)Math.floor(next.getZ()-.32); z <= (int)Math.floor(next.getZ()+.32); z++)
                for (int y=next.getBlockY(); y <= next.getBlockY()+1; y++) {
                    if (!next.getWorld().isChunkLoaded(x>>4,z>>4)) return false;
                    Pair pair = pair(next.getWorld().getBlockAt(x,y,z));
                    if (pair == null) continue;
                    Block block = pair.bottom;
                    String key=key(block);
                    if (!checked.add(key)) continue;
                    if (pair.lower.isOpen()) {
                        // Track a pre-opened doorway only when this NPC's path uses it.
                        if ((opened.containsKey(key) || plugin.settings().messages().getBoolean("routines.close-preopened-doors",true))
                                && !pair.powered() && canOpen(block)) remember(block,npc,tick);
                        continue;
                    }
                    opened.remove(key);
                    if (!canOpen(block) || pair.powered()) return false;
                    Material type = block.getType();
                    String state = pair.lower.getAsString(), topState = pair.upper.getAsString();
                    var event = new EntityInteractEvent(npc, block); Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled() || !unchanged(block,type,state,topState) || !canOpen(block)) return false;
                    change(block, true);
                    remember(block,npc,tick);
                    block.getWorld().playSound(block.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, .5f, 1);
                }
        return true;
    }
    private static Pair pair(Block block) {
        if (!(block.getBlockData() instanceof Door door)) return null;
        if (door.getHalf() == Bisected.Half.TOP) block = block.getRelative(0,-1,0);
        if (!(block.getBlockData() instanceof Door lower) || lower.getHalf()!=Bisected.Half.BOTTOM) return null;
        Block top = block.getRelative(0,1,0);
        if (!(top.getBlockData() instanceof Door upper) || upper.getHalf()!=Bisected.Half.TOP || top.getType()!=block.getType()) return null;
        return new Pair(block,lower,upper);
    }
    private void remember(Block bottom,Entity npc,long tick) {
        String key=key(bottom);
        // Bound both retained door state and recent NPC identities.
        Opened previous = opened.get(key);
        if (previous==null && opened.size()>=256) return;
        ArrayList<UUID> actors = new ArrayList<>(previous==null ? List.of() : previous.actors);
        actors.remove(npc.getUniqueId()); actors.add(npc.getUniqueId());
        if (actors.size()>8) actors.removeFirst();
        opened.put(key,new Opened(bottom,bottom.getType(),bottom.getBlockData().getAsString(),
                bottom.getRelative(0,1,0).getBlockData().getAsString(),List.copyOf(actors),tick+20,tick+1200));
    }
    private static String key(Block b) { return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ(); }
    private static void change(Block bottom, boolean open) {
        for (int dy=0; dy<2; dy++) {
            Block b = bottom.getRelative(0,dy,0);
            if (b.getBlockData() instanceof Door door) { door.setOpen(open); b.setBlockData(door, false); }
        }
    }
    private static boolean unchanged(Block bottom, Material type, String state, String topState) {
        return bottom.getType()==type && bottom.getRelative(0,1,0).getType()==type
                && bottom.getBlockData().getAsString().equals(state)
                && bottom.getRelative(0,1,0).getBlockData().getAsString().equals(topState);
    }
    private static boolean clear(Block bottom) {
        // Only occupants of this two-block doorway can be caught by the closing leaf.
        // The former 2.2 x 3.2 x 2.2 area kept doors open beside idle/seated NPCs.
        BoundingBox passage = new BoundingBox(bottom.getX()-.05,bottom.getY(),bottom.getZ()-.05,
                bottom.getX()+1.05,bottom.getY()+2,bottom.getZ()+1.05);
        return bottom.getWorld().getNearbyEntities(passage,e -> e instanceof LivingEntity).isEmpty();
    }
    public void tick(long tick, boolean closing) {
        var it = opened.values().iterator();
        while (it.hasNext()) {
            Opened entry = it.next(); Block b = entry.bottom;
            if (!b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4)) { it.remove(); continue; }
            if (tick>entry.expires && !closing) { it.remove(); continue; }
            if (!unchanged(b,entry.type,entry.state,entry.topState)) { it.remove(); continue; }
            Pair pair = pair(b);
            if (pair==null || !pair.lower.isOpen() || pair.powered() || !canOpen(b)) { it.remove(); continue; }
            if (!closing && tick < entry.due) continue;
            if (!clear(b)) { if (closing) it.remove(); continue; }
            Entity actor = null;
            // A later traveller may disappear while an earlier one is still available.
            for (int i=entry.actors.size()-1; i>=0 && actor==null; i--) {
                Entity candidate = b.getWorld().getEntity(entry.actors.get(i));
                if (candidate!=null && candidate.isValid()) actor=candidate;
            }
            if (actor==null) { it.remove(); continue; }
            var event=new EntityInteractEvent(actor,b); Bukkit.getPluginManager().callEvent(event);
            // Listeners may alter protection, a door, or doorway occupancy during the event.
            if (event.isCancelled() || !unchanged(b,entry.type,entry.state,entry.topState) || !canOpen(b)) {
                it.remove(); continue;
            }
            if (!clear(b)) { if (closing) it.remove(); continue; }
            change(b,false); b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE,.5f,1);
            it.remove();
        }
    }
    public void close() { tick(Long.MAX_VALUE,true); opened.clear(); }
}
