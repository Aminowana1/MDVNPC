package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.music.MusicService;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.skin.SkinCacheService;
import com.mdvcraft.mdvnpc.trait.HitReactionService;
import com.mdvcraft.mdvnpc.trait.NpcSounds;
import com.mdvcraft.mdvnpc.trait.TraitService;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NpcManagerMusicOwnershipTest {
    MdvNpcPlugin plugin;
    NpcManager manager;
    MusicService music;
    RoutineService routines;
    NpcSounds sounds;
    World world;
    Villager entity;
    Player observer;
    MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() {
        MockBukkit.mock();
        plugin=mock(MdvNpcPlugin.class);
        when(plugin.getName()).thenReturn("music-owner-test");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        music=mock(MusicService.class);when(plugin.music()).thenReturn(music);
        routines=mock(RoutineService.class);when(plugin.routines()).thenReturn(routines);
        when(routines.canLook(any())).thenReturn(true);
        sounds=mock(NpcSounds.class);when(plugin.sounds()).thenReturn(sounds);
        when(plugin.shops()).thenReturn(mock(ShopService.class));
        when(plugin.skins()).thenReturn(mock(SkinCacheService.class));
        when(plugin.reactions()).thenReturn(mock(HitReactionService.class));
        when(plugin.traits()).thenReturn(mock(TraitService.class));
        world=mock(World.class);
        entity=mock(Villager.class);when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(i->new Location(world,0,64,0));
        when(entity.getEyeLocation()).thenAnswer(i->new Location(world,0,65.6,0));
        observer=mock(Player.class);when(observer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(observer.getLocation()).thenReturn(new Location(world,1,64,0));
        when(observer.getEyeLocation()).thenReturn(new Location(world,1,65.6,0));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(observer));
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(observer));
        manager=new NpcManager(plugin);
    }

    @AfterEach void cleanup() {
        if(bukkit!=null)bukkit.close();
        MockBukkit.unmock();
    }

    @SuppressWarnings("unchecked") private ActiveNpc addNpc(boolean dialogue) throws Exception {
        var yaml=new YamlConfiguration();
        yaml.set("npcs.band.location.world","world");
        yaml.set("npcs.band.look.range",6);
        yaml.set("npcs.band.dialogue.enabled",dialogue);
        yaml.set("npcs.band.dialogue.range",4);
        yaml.set("npcs.band.dialogue.initial-delay-seconds",0);
        yaml.set("npcs.band.dialogue.lines",List.of("Buenas noches"));
        var npc=new ActiveNpc(NpcParser.parse(yaml).get("band"),new Location(world,0,64,0),entity,
                mock(PlayerDisguise.class));
        var field=NpcManager.class.getDeclaredField("active");field.setAccessible(true);
        ((Map<String,ActiveNpc>)field.get(manager)).put("band",npc);
        return npc;
    }

    private void tick() throws Exception {
        Method method=NpcManager.class.getDeclaredMethod("tick",int.class);method.setAccessible(true);
        method.invoke(manager,10);
    }

    @Test void musicOwnsHeadWhileNormalDialogueStillRuns() throws Exception {
        ActiveNpc npc=addNpc(true);when(music.isAnimating(npc)).thenReturn(true);
        tick();
        verify(world).getNearbyPlayers(any(Location.class),eq(4d),any());
        verify(entity,never()).setRotation(anyFloat(),anyFloat());
        verify(sounds).say(npc,observer,"Buenas noches");
    }

    @Test void activeMusicWithoutDialogueSkipsTheNormalLookQuery() throws Exception {
        ActiveNpc npc=addNpc(false);when(music.isAnimating(npc)).thenReturn(true);
        tick();
        verify(world,never()).getNearbyPlayers(any(Location.class),anyDouble(),any());
        verify(entity,never()).setRotation(anyFloat(),anyFloat());
        verifyNoInteractions(sounds);
    }

    @Test void musicianWithoutAnActiveAnimationKeepsNormalPlayerLooking() throws Exception {
        addNpc(false);
        tick();
        verify(world).getNearbyPlayers(any(Location.class),eq(6d),any());
        verify(entity).setRotation(floatThat(yaw->Math.abs(Math.IEEEremainder(yaw+90,360))<.001),anyFloat());
    }

    @Test void removalRestoresMusicBeforeReactionAndDrinkCleanup() throws Exception {
        ActiveNpc npc=addNpc(false);
        Method remove=NpcManager.class.getDeclaredMethod("remove",ActiveNpc.class);remove.setAccessible(true);
        remove.invoke(manager,npc);
        var order=inOrder(music,plugin.reactions(),plugin.traits(),routines);
        order.verify(music).remove("band");
        order.verify(plugin.reactions()).cancel("band");
        order.verify(plugin.traits()).cancel("band");
        order.verify(routines).remove("band");
    }
}
