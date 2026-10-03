package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.skin.SkinCacheService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Renders no names; cleans only TextDisplays left by the retired MDVNPC name service. */
class NpcManagerLegacyNameCleanupTest {
    MdvNpcPlugin plugin;NpcManager manager;
    @BeforeEach void setup(){
        var server=MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);
        when(plugin.getName()).thenReturn("MDVNPC-name-cleanup");when(plugin.getServer()).thenReturn(server);when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var settings=Settings.parse(new YamlConfiguration());when(plugin.settings()).thenReturn(settings);
        var routines=mock(RoutineService.class);when(plugin.routines()).thenReturn(routines);
        var skins=mock(SkinCacheService.class);when(plugin.skins()).thenReturn(skins);
        manager=new NpcManager(plugin);
    }
    @AfterEach void cleanup(){try{if(manager!=null)manager.stop();}finally{MockBukkit.unmock();}}
    private TextDisplay display(boolean legacy){
        var data=mock(PersistentDataContainer.class);
        var key=new NamespacedKey(plugin,"npc-name");
        when(data.has(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(i->legacy && key.equals(i.getArgument(0)));
        var display=mock(TextDisplay.class);when(display.getPersistentDataContainer()).thenReturn(data);return display;
    }
    @Test void loadedChunksRemoveOldNamesWithoutTouchingForeignDisplays(){
        var old=display(true);var foreign=display(false);
        manager.cleanupLoadedEntities(List.of(old,foreign));verify(old).remove();verify(foreign,never()).remove();
        verify(plugin.routines(),never()).liveSeat(any(Entity.class));
    }
    @Test void startupReloadCleanupRemovesRetiredNames(){
        var old=display(true);var foreign=display(false);var world=mock(World.class);when(world.getEntities()).thenReturn(List.of(old,foreign));
        try(var bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS)){
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));manager.start(Map.of());
        }
        verify(old).remove();verify(foreign,never()).remove();verify(world,never()).spawn(any(),eq(TextDisplay.class));
    }
}
