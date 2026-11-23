package com.mdvcraft.mdvnpc.trait;
import com.mdvcraft.mdvnpc.*;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.routine.*;
import org.bukkit.*; import org.bukkit.entity.*; import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*; import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*; import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*; import static org.mockito.Mockito.*;
class HiddenPlayerBeerTest {
    @AfterEach void down(){ PlayerFilter.hiddenViewers(id->false); MockBukkit.unmock(); }
    @Test void hiddenPlayerGetsNeitherDrinkNorLine(){
        var server=MockBukkit.mock(); var w=server.addSimpleWorld("world"); var p=server.addPlayer();
        p.teleport(new Location(w,1,64,0));
        var plugin=mock(MdvNpcPlugin.class); var c=new YamlConfiguration(); c.set("ignore-invisible-players",false);
        var settings=Settings.parse(c); when(plugin.settings()).thenReturn(settings); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var sounds=mock(NpcSounds.class); when(plugin.sounds()).thenReturn(sounds);
        var routines=mock(RoutineService.class); when(plugin.routines()).thenReturn(routines);
        when(routines.canReceiveBeer(any())).thenReturn(true); when(routines.drinkTick(any(),anyLong())).thenReturn(true);
        var v=mock(Villager.class); when(v.isValid()).thenReturn(true); when(v.getWorld()).thenReturn(w); when(v.getLocation()).thenAnswer(i->new Location(w,0,64,0));
        var y=new YamlConfiguration(); y.set("npcs.n.location.world","world"); y.set("npcs.n.trait.type","alcoholic"); y.set("npcs.n.trait.beer-lines",List.of("gracias"));
        var npc=new ActiveNpc(NpcParser.parse(y).get("n"),new Location(w,0,64,0),v,null);
        var m=mock(NpcManager.class); when(plugin.manager()).thenReturn(m); when(m.activeNpcs()).thenReturn(List.of(npc));
        var item=mock(Item.class); var stack=new ItemStack(Material.POTION); var thrower=p.getUniqueId();
        when(item.isValid()).thenReturn(true); when(item.canMobPickup()).thenReturn(true); when(item.canPlayerPickup()).thenReturn(true);
        when(item.getWorld()).thenReturn(w); when(item.getLocation()).thenAnswer(i->new Location(w,1,64,0));
        when(item.getThrower()).thenReturn(thrower); when(item.getItemStack()).thenReturn(stack); when(v.hasLineOfSight(item)).thenReturn(true);
        PlayerFilter.hiddenViewers(thrower::equals);
        var traits=new TraitService(plugin);
        assertFalse(traits.accept(npc,item,p,stack.clone(),System.nanoTime()));
        traits.tick(1); verify(sounds,never()).say(any(),any(),anyString());
    }
}
