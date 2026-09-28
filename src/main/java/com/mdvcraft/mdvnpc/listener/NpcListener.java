package com.mdvcraft.mdvnpc.listener;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Click;
import com.mdvcraft.mdvnpc.runtime.PlayerFilter;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Mode;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.*;
import org.bukkit.inventory.EquipmentSlot;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;

public final class NpcListener implements Listener {
    private final MdvNpcPlugin plugin;
    public NpcListener(MdvNpcPlugin plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interactAt(PlayerInteractAtEntityEvent event) { interact(event); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEntityEvent event) {
        var manager = plugin.manager();
        if (!manager.owned(event.getRightClicked())) return;
        boolean wasCancelled = event.isCancelled();
        event.setCancelled(true); // Never open the base villager's trading interface.
        if (event.getHand() != EquipmentSlot.HAND) return;
        var npc = manager.find(event.getRightClicked());
        if (npc == null || !PlayerFilter.accepts(event.getPlayer(), plugin.settings())) return;
        // Even inside protected regions, an off-duty NPC may explain why it cannot be used.
        // Actual commands/shops still respect the previous cancelled-interaction policy.
        if (wasCancelled && !plugin.canInteract(npc)) {
            plugin.routines().unavailable(npc, event.getPlayer());
            return;
        }
        // A protected lobby may cancel the villager's normal interaction; permit our own SHOP UI only when configured.
        if (wasCancelled && (npc.definition().mode() != Mode.SHOP
                || !plugin.settings().messages().getBoolean("shop-allow-cancelled-interaction", false))) return;
        if (npc.definition().mode() == Mode.SHOP) {
            if (event.getPlayer().isSneaking() && event.getPlayer().hasPermission("mdvnpc.admin"))
                plugin.shops().openEditor(event.getPlayer(), npc.definition().id());
            else if (plugin.canInteract(npc)) plugin.shops().openShop(event.getPlayer(), npc);
            else plugin.routines().unavailable(npc, event.getPlayer());
        } else if (plugin.canInteract(npc)) manager.interactions().click(npc, event.getPlayer(), Click.RIGHT, System.nanoTime());
        else plugin.routines().unavailable(npc, event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void damage(EntityDamageEvent event) {
        var manager = plugin.manager();
        if (!manager.owned(event.getEntity()) && !plugin.routines().isSeat(event.getEntity())) return;
        event.setCancelled(true);
    }
    // Fires before damage checks, including attacks on invulnerable NPCs.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void attack(PrePlayerAttackEntityEvent event) {
        var manager = plugin.manager();
        if (!manager.owned(event.getAttacked())) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        var npc = manager.find(event.getAttacked());
        if (!cancelled && npc!=null && plugin.reactions()!=null)plugin.reactions().hit(npc,event.getPlayer());
        if (!cancelled && npc != null && plugin.canInteract(npc) && PlayerFilter.accepts(event.getPlayer(), plugin.settings()))
            manager.interactions().click(npc, event.getPlayer(), Click.LEFT, System.nanoTime());
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void target(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() != null && plugin.manager().owned(event.getTarget())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(EntityTeleportEvent event) { if (plugin.manager().owned(event.getEntity()) && !plugin.routines().internal(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void move(EntityMoveEvent event) {
        if (event.hasChangedPosition() && plugin.manager().owned(event.getEntity()) && !plugin.routines().internal(event.getEntity()) && !plugin.routines().isSeat(event.getEntity().getVehicle() == null ? event.getEntity() : event.getEntity().getVehicle())) event.setCancelled(true);
        if (plugin.routines().isSeat(event.getEntity()) && event.hasChangedPosition()) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void burn(EntityCombustEvent event) { if (plugin.manager().owned(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void transform(EntityTransformEvent event) { if (plugin.manager().owned(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void vehicle(VehicleEnterEvent event) { if (plugin.manager().owned(event.getEntered()) && !plugin.routines().mounting(event.getEntered())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void mount(org.bukkit.event.entity.EntityMountEvent event) {
        if (plugin.manager().owned(event.getEntity()) && !plugin.routines().mounting(event.getEntity())) event.setCancelled(true);
        if (plugin.routines().isSeat(event.getMount()) && !plugin.routines().mounting(event.getEntity())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void armorStand(PlayerArmorStandManipulateEvent event) { if (plugin.routines().isSeat(event.getRightClicked())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void bed(PlayerBedEnterEvent event) {
        var b = event.getBed();
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Bed bed && bed.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT) b = b.getRelative(bed.getFacing());
        if (plugin.routines().claimed(new com.mdvcraft.mdvnpc.routine.RoutineGoal.Point(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(), 0))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void observeNpcSpawn(CreatureSpawnEvent event) { plugin.manager().observeSpawn(event); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chunkLoad(ChunkLoadEvent event) { plugin.manager().chunkLoaded(event.getChunk()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chunkUnload(ChunkUnloadEvent event) { plugin.manager().chunkUnloaded(event.getChunk()); }
    @EventHandler
    public void entitiesLoad(EntitiesLoadEvent event) {
        plugin.manager().cleanupLoadedEntities(event.getEntities());
        plugin.manager().chunkLoaded(event.getChunk());
    }
    @EventHandler
    public void entitiesUnload(EntitiesUnloadEvent event) { event.getEntities().forEach(plugin.manager()::entityUnloaded); }
    @EventHandler
    public void worldLoad(WorldLoadEvent event) { plugin.manager().worldLoaded(); }
    @EventHandler
    public void quit(PlayerQuitEvent event) {
        plugin.manager().forget(event.getPlayer().getUniqueId());
        plugin.routines().forget(event.getPlayer().getUniqueId());
    }
}
