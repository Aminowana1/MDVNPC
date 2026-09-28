package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.routine.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offer transaction tests; visual rendering and MMO identity resolution require the real server. */
class BeerOfferTest {
    MockedStatic<Bukkit> bukkit;TraitService service;ActiveNpc npc;Item item;Player player;
    PluginManager events;RoutineService routines;ItemStack stack;
    @BeforeEach void setup() {
        MockBukkit.mock();
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);events=mock(PluginManager.class);bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        var plugin=mock(MdvNpcPlugin.class);when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        routines=mock(RoutineService.class);when(plugin.routines()).thenReturn(routines);
        var yaml=new YamlConfiguration();yaml.set("npcs.manolito.location.world","world");yaml.set("npcs.manolito.trait.type","alcoholico");
        World world=mock(World.class);Villager entity=mock(Villager.class);
        Location at=new Location(world,0,64,0);when(entity.getLocation()).thenReturn(at);when(entity.getWorld()).thenReturn(world);when(entity.isValid()).thenReturn(true);
        npc=new ActiveNpc(NpcParser.parse(yaml).get("manolito"),at,entity,null);
        var manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.activeNpcs()).thenReturn(List.of(npc));
        when(routines.canReceiveBeer(npc)).thenReturn(true);
        when(routines.beginDrink(eq(npc),any(),anyLong())).thenReturn(new RoutineVisuals.Pose());
        player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(at);bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);
        item=mock(Item.class);when(item.isValid()).thenReturn(true);when(item.canMobPickup()).thenReturn(true);when(item.canPlayerPickup()).thenReturn(true);
        when(item.getWorld()).thenReturn(world);when(item.getLocation()).thenReturn(at);when(item.getThrower()).thenReturn(id);when(entity.hasLineOfSight(item)).thenReturn(true);
        // Mock stack avoids server ItemFactory: equality must detect listeners replacing the stack.
        stack=mock(ItemStack.class);when(stack.getAmount()).thenReturn(4);
        ItemStack remainder=mock(ItemStack.class);when(stack.clone()).thenReturn(remainder);when(remainder.getAmount()).thenReturn(3);
        when(item.getItemStack()).thenReturn(stack);service=new TraitService(plugin);
    }
    @AfterEach void cleanup(){if(bukkit!=null)bukkit.close();MockBukkit.unmock();}
    @Test void cancelledPickupLeavesStackAndHandUntouched() {
        doAnswer(c->{((EntityPickupItemEvent)c.getArgument(0)).setCancelled(true);return null;}).when(events).callEvent(any());
        assertFalse(service.accept(npc,item,player,stack,System.nanoTime()));
        verify(item,never()).setItemStack(any());verify(item,never()).remove();verify(routines,never()).beginDrink(any(),any(),anyLong());
    }
    @Test void oneBeerOnlyAndCooldownSurvivesVisualCancellation() {
        long now=System.nanoTime();assertTrue(service.accept(npc,item,player,stack,now));
        verify(stack.clone()).setAmount(3);verify(item).setItemStack(stack.clone());
        assertFalse(service.accept(npc,item,player,stack,now));
        service.cancel("manolito");assertFalse(service.accept(npc,item,player,stack,now+1_000_000_000L));
        verify(events,times(1)).callEvent(any());
    }
    @Test void listenerChangingStackAbortsWithoutDebit() {
        doAnswer(c->{when(item.getItemStack()).thenReturn(mock(ItemStack.class));return null;}).when(events).callEvent(any());
        assertFalse(service.accept(npc,item,player,stack,System.nanoTime()));verify(item,never()).setItemStack(any());
    }
    @Test void foreignOwnerPreventsTakingBeer() {
        when(item.getOwner()).thenReturn(UUID.randomUUID());
        assertFalse(service.accept(npc,item,player,stack,System.nanoTime()));verify(item,never()).setItemStack(any());
    }
}
