package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.shop.ShopService;
import me.libraryaddict.disguise.DisguiseConfig;
import me.libraryaddict.disguise.disguisetypes.DisguiseInternals;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NpcNameServiceTest {
    MdvNpcPlugin plugin;World world;Villager entity;PlayerDisguise disguise;PlayerWatcher watcher;
    DisguiseInternals<?> internals;TextDisplay display;NpcNameService names;ActiveNpc npc;
    AtomicReference<Location> position=new AtomicReference<>(),displayPosition=new AtomicReference<>();
    AtomicReference<String> text=new AtomicReference<>("&6Lorenzo");AtomicReference<Component> displayedText=new AtomicReference<>();
    AtomicLong clock=new AtomicLong();YamlConfiguration config;

    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("MDVNPC-name-test");when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.routines()).thenReturn(mock(RoutineService.class));
        world=mock(World.class);when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        position.set(new Location(world,1,64,2));when(entity.getLocation()).thenAnswer(i->position.get().clone());
        disguise=mock(PlayerDisguise.class);watcher=mock(PlayerWatcher.class);internals=mock(DisguiseInternals.class);
        when(disguise.getInternals()).thenReturn(internals);when(internals.getNameDisplayType()).thenReturn(DisguiseConfig.PlayerNameType.ARMORSTANDS);
        when(disguise.getWatcher()).thenReturn(watcher);when(disguise.getHeight()).thenReturn(1.8);when(disguise.getDisguiseScale()).thenReturn(1d);
        when(watcher.getNameYModifier()).thenReturn(.35f);when(disguise.getName()).thenAnswer(i->text.get());
        var displayData=pdc();
        display=mock(TextDisplay.class);when(display.isValid()).thenReturn(true);when(display.getPersistentDataContainer()).thenReturn(displayData);
        when(world.spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class))).thenAnswer(i->{
            displayPosition.set(((Location)i.getArgument(0)).clone());((Consumer<TextDisplay>)i.getArgument(2)).accept(display);return display;
        });
        when(display.teleport(any(Location.class))).thenAnswer(i->{displayPosition.set(((Location)i.getArgument(0)).clone());return true;});
        doAnswer(i->{displayedText.set(i.getArgument(0));return null;}).when(display).text(any(Component.class));
        config=new YamlConfiguration();config.set("npcs.actor.location.world","world");config.set("npcs.actor.name","&6Lorenzo");config.set("npcs.actor.name-visible",true);
        npc=new ActiveNpc(NpcParser.parse(config).get("actor"),position.get().clone(),entity,disguise);
        names=new NpcNameService(plugin,clock::get);
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    private PersistentDataContainer pdc(){
        var pdc=mock(PersistentDataContainer.class);Map<NamespacedKey,String> values=new HashMap<>();
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(2));return null;}).when(pdc).set(any(NamespacedKey.class),eq(PersistentDataType.STRING),anyString());
        when(pdc.get(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(i->values.get(i.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(i->values.containsKey(i.getArgument(0)));
        return pdc;
    }
    @Test void armorStandModeUsesTextWithoutChangingHiddenLibsName(){
        names.register(npc);assertTrue(names.manages(npc));assertTrue(names.isName(display));assertTrue(names.liveName(display));
        assertEquals(66.42,displayPosition.get().getY(),.00001);assertEquals(LegacyComponentSerializer.legacySection().deserialize("§6Lorenzo"),displayedText.get());
        verify(display).setBillboard(Display.Billboard.CENTER);verify(display).setLineWidth(4096);verify(display).setViewRange(1);
        verify(display).setDefaultBackground(false);verify(display).setBackgroundColor(Color.fromARGB(0));
        verify(display).setPersistent(false);verify(display).setGravity(false);verify(display).setInvulnerable(true);
        verify(world,never()).spawn(any(Location.class),eq(ArmorStand.class),any(Consumer.class));verify(disguise,never()).setNameVisible(anyBoolean());
    }
    @Test void allOtherNameModesRetainTheirLibsRenderer(){
        for(var mode:DisguiseConfig.PlayerNameType.values())if(mode!=DisguiseConfig.PlayerNameType.ARMORSTANDS){
            when(internals.getNameDisplayType()).thenReturn(mode);assertFalse(names.manages(npc));names.register(npc);names.offset(npc,1);names.tick(npc);
        }
        verify(world,never()).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));verify(disguise,never()).setNameVisible(anyBoolean());
    }
    @Test void hiddenNpcKeepsItsNameHiddenThroughOffsetsAndMotion(){
        config.set("npcs.actor.name-visible",false);npc=new ActiveNpc(NpcParser.parse(config).get("actor"),position.get().clone(),entity,disguise);
        names.register(npc);names.offset(npc,.5);names.tick(npc);assertTrue(names.manages(npc));assertEquals(.5,names.offset(npc));
        verify(world,never()).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));verify(disguise,never()).setNameVisible(anyBoolean());
    }
    @Test void stableNamesSendNoPacketsAndRotationDoesNotMoveTheLabel(){
        names.register(npc);clearInvocations(display,world);position.get().setYaw(85);position.get().setPitch(20);
        for(int i=0;i<100;i++)names.tick(npc);
        verify(display,never()).teleport(any(Location.class));verify(display,never()).text(any(Component.class));
        verify(world,never()).getNearbyPlayers(any(Location.class),anyDouble());verify(world,never()).getPlayers();verify(world,never()).getEntities();
    }
    @Test void dancingAndNameEditsUpdateOnlyChangedLocationOrText(){
        names.register(npc);clearInvocations(display);position.set(new Location(world,3,64.7,2,30,0));names.tick(npc);
        assertEquals(3,displayPosition.get().getX());assertEquals(67.12,displayPosition.get().getY(),.00001);
        names.tick(npc);verify(display).teleport(any(Location.class));verify(display,never()).text(any(Component.class));
        text.set("&aBardo");names.tick(npc);names.tick(npc);verify(display).text(LegacyComponentSerializer.legacySection().deserialize("§aBardo"));
    }
    @Test void poseOffsetRestoresItsPriorBaselineAndScale(){
        when(disguise.getDisguiseScale()).thenReturn(1.5);names.register(npc);names.offset(npc,.2);double original=names.offset(npc);
        names.offset(npc,original-.75);assertEquals(-.55,names.offset(npc),.00001);assertEquals(66.67,displayPosition.get().getY(),.00001);
        names.offset(npc,original);assertEquals(.2,names.offset(npc));assertEquals(67.795,displayPosition.get().getY(),.00001);
        verify(watcher,never()).setNameYModifier(anyFloat());
    }
    @Test void unloadedDestinationDoesNotLoadOrTeleportTheDisplay(){
        names.register(npc);clearInvocations(display);position.set(new Location(world,40,64,2));when(world.isChunkLoaded(2,0)).thenReturn(false);names.tick(npc);
        verify(display,never()).teleport(any(Location.class));verify(world,never()).getChunkAt(anyInt(),anyInt());
        when(world.isChunkLoaded(2,0)).thenReturn(true);names.tick(npc);verify(display).teleport(any(Location.class));
    }
    @Test void removalIsIdempotentAndLatePoseRestorationDoesNotRecreateTheLabel(){
        names.register(npc);names.remove(npc);names.offset(npc,0);names.tick(npc);names.remove(npc);names.clear();
        verify(display).remove();assertFalse(names.liveName(display));verify(world).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));
    }
    @Test void invalidNpcRemovesItsLabelAndDoesNotCreateAnOrphan(){
        names.register(npc);when(entity.isValid()).thenReturn(false);names.tick(npc);names.tick(npc);verify(display).remove();assertFalse(names.liveName(display));
    }
    @Test void spawnFailureIsCleanedAndRetryIsBounded(){
        doThrow(new IllegalStateException("Rejected metadata")).when(display).text(any(Component.class));
        assertDoesNotThrow(()->names.register(npc));verify(display).remove();assertFalse(names.liveName(display));
        for(int i=0;i<100;i++)names.tick(npc);verify(world).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));
        doNothing().when(display).text(any(Component.class));clock.set(5_000_000_000L);names.tick(npc);assertTrue(names.liveName(display));
        verify(world,times(2)).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));
    }
    @Test void entityLoadCleanupKeepsCurrentLabelAndRemovesOnlyOurOrphans(){
        NpcManager manager=new NpcManager(plugin);names=manager.names();names.register(npc);
        var orphanData=pdc();
        TextDisplay orphan=mock(TextDisplay.class);when(orphan.getPersistentDataContainer()).thenReturn(orphanData);
        orphan.getPersistentDataContainer().set(new NamespacedKey(plugin,"npc-name"),PersistentDataType.STRING,"missing");
        var foreignData=pdc();
        Entity foreign=mock(Entity.class);when(foreign.getPersistentDataContainer()).thenReturn(foreignData);
        manager.cleanupLoadedEntities(List.of(display,orphan,foreign));verify(orphan).remove();verify(display,never()).remove();verify(foreign,never()).remove();
        names.remove(npc);manager.cleanupLoadedEntities(List.of(display));verify(display,times(2)).remove();
    }
    @Test void spawnCallbackIsAlreadyRecognizedAsALiveOwnedLabel(){
        NpcManager manager=new NpcManager(plugin);names=manager.names();
        doAnswer(i->{((Consumer<TextDisplay>)i.getArgument(2)).accept(display);manager.cleanupLoadedEntities(List.of(display));return display;}).when(world).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));
        names.register(npc);verify(display,never()).remove();assertTrue(names.liveName(display));
    }
    @Test @SuppressWarnings("unchecked") void managerFollowsSeatedLabelsBeforeItsCanLookGate() throws Exception {
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));when(plugin.shops()).thenReturn(mock(ShopService.class));
        NpcManager manager=new NpcManager(plugin);manager.names().register(npc);
        var active=NpcManager.class.getDeclaredField("active");active.setAccessible(true);((Map<String,ActiveNpc>)active.get(manager)).put("actor",npc);
        clearInvocations(display);position.set(new Location(world,3,64.5,2));
        try(var bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS)){
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(mock(Player.class)));
            var tick=NpcManager.class.getDeclaredMethod("tick",int.class);tick.setAccessible(true);tick.invoke(manager,10);
        }
        verify(display).teleport(any(Location.class));verify(world,never()).getNearbyPlayers(any(Location.class),anyDouble(),any());
    }
}
