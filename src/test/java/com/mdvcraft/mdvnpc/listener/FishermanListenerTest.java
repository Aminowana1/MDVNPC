package com.mdvcraft.mdvnpc.listener;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/** Fishing boats borrow a narrow movement exception while retaining NPC protection. */
class FishermanListenerTest {
    MdvNpcPlugin plugin;
    RoutineService routines;
    NpcManager manager;
    NpcListener listener;
    Boat boat;
    Villager npc;
    Player player;

    @BeforeEach void setup() {
        plugin=mock(MdvNpcPlugin.class);routines=mock(RoutineService.class);manager=mock(NpcManager.class);
        when(plugin.routines()).thenReturn(routines);when(plugin.manager()).thenReturn(manager);
        boat=mock(Boat.class);npc=mock(Villager.class);player=mock(Player.class);
        when(routines.isBoat(boat)).thenReturn(true);when(manager.owned(npc)).thenReturn(true);
        listener=new NpcListener(plugin);
    }

    @Test void aPlayerCannotInteractWithOrBoardTheAnimationBoat() {
        PlayerInteractEntityEvent interaction=mock(PlayerInteractEntityEvent.class);
        when(interaction.getRightClicked()).thenReturn(boat);when(interaction.getPlayer()).thenReturn(player);
        listener.interact(interaction);verify(interaction).setCancelled(true);
        VehicleEnterEvent enter=mock(VehicleEnterEvent.class);when(enter.getVehicle()).thenReturn(boat);when(enter.getEntered()).thenReturn(player);
        listener.vehicle(enter);verify(enter).setCancelled(true);
        EntityMountEvent mount=mock(EntityMountEvent.class);when(mount.getEntity()).thenReturn(player);when(mount.getMount()).thenReturn(boat);
        listener.mount(mount);verify(mount).setCancelled(true);
    }

    @Test void anAssignedInternalMountIsAllowedButExternalNpcMountsRemainBlocked() {
        VehicleEnterEvent enter=mock(VehicleEnterEvent.class);when(enter.getVehicle()).thenReturn(boat);when(enter.getEntered()).thenReturn(npc);
        when(routines.mounting(npc)).thenReturn(true);when(routines.canMountFishingBoat(boat,npc)).thenReturn(true);
        listener.vehicle(enter);verify(enter,never()).setCancelled(true);
        when(routines.mounting(npc)).thenReturn(false);when(routines.canMountFishingBoat(boat,npc)).thenReturn(false);
        listener.vehicle(enter);verify(enter,atLeastOnce()).setCancelled(true);
    }

    @Test void theOwnedPassengerCanMoveWithItsBoatButOtherVehiclesCannotMoveTheNpc() {
        EntityMoveEvent movement=mock(EntityMoveEvent.class);when(movement.getEntity()).thenReturn(npc);when(movement.hasChangedPosition()).thenReturn(true);
        when(npc.getVehicle()).thenReturn(boat);when(routines.inFishingBoat(npc)).thenReturn(true);
        listener.move(movement);verify(movement,never()).setCancelled(true);
        when(routines.inFishingBoat(npc)).thenReturn(false);when(npc.getVehicle()).thenReturn(mock(Boat.class));
        listener.move(movement);verify(movement).setCancelled(true);
    }

    @Test void controlledBoatMovementDoesNotGrantExternalNpcTeleportPermission() {
        when(routines.inFishingBoat(npc)).thenReturn(true);
        EntityTeleportEvent event=mock(EntityTeleportEvent.class);when(event.getEntity()).thenReturn(npc);
        listener.teleport(event);verify(event).setCancelled(true);
    }

    @Test void theAnimationBoatCannotBeDamagedDestroyedOrBurned() {
        EntityDamageEvent damage=mock(EntityDamageEvent.class);when(damage.getEntity()).thenReturn(boat);
        listener.damage(damage);verify(damage).setCancelled(true);
        VehicleDamageEvent vehicleDamage=mock(VehicleDamageEvent.class);when(vehicleDamage.getVehicle()).thenReturn(boat);
        listener.vehicleDamage(vehicleDamage);verify(vehicleDamage).setCancelled(true);
        VehicleDestroyEvent destroy=mock(VehicleDestroyEvent.class);when(destroy.getVehicle()).thenReturn(boat);
        listener.vehicleDestroy(destroy);verify(destroy).setCancelled(true);
        EntityCombustEvent burn=mock(EntityCombustEvent.class);when(burn.getEntity()).thenReturn(boat);
        listener.burn(burn);verify(burn).setCancelled(true);
    }

    @Test void disembarkingRequiresTheInternalMountGuard() {
        VehicleExitEvent exit=mock(VehicleExitEvent.class);when(exit.getVehicle()).thenReturn(boat);when(exit.getExited()).thenReturn(npc);
        EntityDismountEvent dismount=mock(EntityDismountEvent.class);when(dismount.getDismounted()).thenReturn(boat);when(dismount.getEntity()).thenReturn(npc);
        when(routines.mounting(npc)).thenReturn(true);listener.vehicleExit(exit);listener.dismount(dismount);
        verify(exit,never()).setCancelled(true);verify(dismount,never()).setCancelled(true);
        when(routines.mounting(npc)).thenReturn(false);listener.vehicleExit(exit);listener.dismount(dismount);
        verify(exit).setCancelled(true);verify(dismount).setCancelled(true);
    }

    @Test void ordinaryBoatsKeepTheirExistingInteractionsAndDamage() {
        Boat ordinary=mock(Boat.class);
        VehicleEnterEvent enter=mock(VehicleEnterEvent.class);when(enter.getVehicle()).thenReturn(ordinary);when(enter.getEntered()).thenReturn(player);
        listener.vehicle(enter);verify(enter,never()).setCancelled(true);
        VehicleDestroyEvent destroy=mock(VehicleDestroyEvent.class);when(destroy.getVehicle()).thenReturn(ordinary);
        listener.vehicleDestroy(destroy);verify(destroy,never()).setCancelled(true);
        EntityDamageEvent damage=mock(EntityDamageEvent.class);when(damage.getEntity()).thenReturn(ordinary);
        listener.damage(damage);verify(damage,never()).setCancelled(true);
    }
}
