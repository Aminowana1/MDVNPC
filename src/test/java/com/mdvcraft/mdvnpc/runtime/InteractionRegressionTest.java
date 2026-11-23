package com.mdvcraft.mdvnpc.runtime;
import com.mdvcraft.mdvnpc.*;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.listener.NpcListener;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Click;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.util.Messages;
import org.bukkit.*; import org.bukkit.entity.*; import org.bukkit.util.Vector;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.*; import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.*; import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*; import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*; import static org.mockito.Mockito.*;
class InteractionRegressionTest {
    InteractionService service; ActiveNpc npc; Player p; Villager v;
    @BeforeEach void up(){
        MockBukkit.mock(); World w=mock(World.class); v=mock(Villager.class); when(v.isValid()).thenReturn(true); when(v.getUniqueId()).thenReturn(UUID.randomUUID());
        when(v.getWorld()).thenReturn(w); when(v.getLocation()).thenAnswer(i->new Location(w,0,64,0));
        var y=new YamlConfiguration(); y.set("npcs.n.location.world","world"); y.set("npcs.n.interaction.cooldown-seconds",86400);
        y.set("npcs.n.interaction.require-line-of-sight",false);
        y.set("npcs.n.interaction.commands",List.of(Map.of("command","reward","executor","PLAYER")));
        npc=new ActiveNpc(NpcParser.parse(y).get("n"),new Location(w,0,64,0),v,null);
        p=mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID()); when(p.getName()).thenReturn("Tester");
        when(p.getWorld()).thenReturn(w); when(p.getLocation()).thenAnswer(i->new Location(w,1,64,0));
        when(p.performCommand(anyString())).thenReturn(true);
        service=new InteractionService(mock(Messages.class),Logger.getAnonymousLogger());
    }
    @AfterEach void down(){MockBukkit.unmock();}
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder;
    private void twoClicks(Runnable between) {
        service.click(npc,p,Click.RIGHT,0); between.run(); service.click(npc,p,Click.RIGHT,2_000_000_000L);
        verify(p,times(1)).performCommand(anyString());
    }
    @Test void reloginKeepsTheCooldown() { twoClicks(()->service.forget(p.getUniqueId())); }
    @Test void managerReloadKeepsTheCooldown() {
        var plugin=mock(MdvNpcPlugin.class); when(plugin.getName()).thenReturn("cooldown-test");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger()); when(plugin.messages()).thenReturn(mock(Messages.class));
        when(plugin.skins()).thenReturn(mock(com.mdvcraft.mdvnpc.skin.SkinCacheService.class));
        var manager=new NpcManager(plugin); service=manager.interactions();
        twoClicks(manager::stop);
    }
    @Test void restartKeepsALongCooldown() throws Exception {
        var create=InteractionService.class.getConstructor(Messages.class,Logger.class,java.nio.file.Path.class);
        var file=folder.resolve("cooldowns.dat");
        var before=create.newInstance(mock(Messages.class),Logger.getAnonymousLogger(),file);
        before.click(npc,p,Click.RIGHT,System.nanoTime());
        InteractionService.class.getMethod("close").invoke(before);
        var after=create.newInstance(mock(Messages.class),Logger.getAnonymousLogger(),file);
        after.click(npc,p,Click.RIGHT,System.nanoTime()+2_000_000_000L);
        InteractionService.class.getMethod("close").invoke(after);
        verify(p,times(1)).performCommand(anyString());
    }
    NpcListener listener(){
        var plugin=mock(MdvNpcPlugin.class); var m=mock(NpcManager.class); var c=new YamlConfiguration(); c.set("ignore-invisible-players",false);
        when(plugin.settings()).thenReturn(Settings.parse(c)); when(plugin.manager()).thenReturn(m); when(plugin.canInteract(any())).thenReturn(true);
        when(plugin.routines()).thenReturn(mock(RoutineService.class));
        when(m.owned(v)).thenReturn(true); when(m.find(v)).thenReturn(npc); when(m.interactions()).thenReturn(service);
        when(p.isOnline()).thenReturn(true); when(p.getGameMode()).thenReturn(GameMode.SURVIVAL); return new NpcListener(plugin);
    }
    private PlayerInteractAtEntityEvent at() { return new PlayerInteractAtEntityEvent(p,v,new Vector(),EquipmentSlot.HAND); }
    private PlayerInteractEntityEvent click() { return new PlayerInteractEntityEvent(p,v,EquipmentSlot.HAND); }
    @Test void oneClickRunsOnceAndCancelsBothVanillaEvents() {
        var l=listener(); var first=at(); var second=click(); l.interactAt(first); l.interact(second);
        assertTrue(first.isCancelled()); assertTrue(second.isCancelled()); verify(p,times(1)).performCommand(anyString());
    }
    @Test void protectionOfTheSecondEventIsRespected() {
        var l=listener(); l.interactAt(at()); var second=click(); second.setCancelled(true); l.interact(second);
        verify(p,never()).performCommand(anyString());
    }
    @Test void protectionOfTheFirstEventIsRespected() {
        var l=listener(); var first=at(); first.setCancelled(true); l.interactAt(first); l.interact(click());
        verify(p,never()).performCommand(anyString());
    }
}
