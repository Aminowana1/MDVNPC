package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.skin.*;
import com.github.retrooper.packetevents.protocol.player.*;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkinCacheServiceTest {
    @TempDir Path folder;
    @AfterEach void stop() { MockBukkit.unmock(); }
    private MdvNpcPlugin plugin() {
        var server = MockBukkit.mock(new TestServer());
        var plugin = mock(MdvNpcPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger()); when(plugin.isEnabled()).thenReturn(true);
        var yaml = new YamlConfiguration(); yaml.createSection("npcs.npc"); yaml.set("npcs.npc.location.world", "world");
        when(plugin.definitions()).thenReturn(NpcParser.parse(yaml)); return plugin;
    }
    @Test void captureAndShutdownFlushThenRestartUsesSignedTexture() throws Exception {
        var plugin = plugin(); var npc = plugin.definitions().get("npc");
        Supplier<UserProfile> disguise = mock(Supplier.class);
        when(disguise.get()).thenReturn(new UserProfile(UUID.randomUUID(), "DisplayName",
                List.of(new TextureProperty("textures", "dGV4dHVyZQ==", "c2lnbmF0dXJl"))));
        var cache = new SkinCacheService(plugin); cache.watch(npc, disguise); cache.capturePending(); cache.close();
        var store = new SkinStore(folder); store.load();
        assertEquals("dGV4dHVyZQ==", store.resolve(npc.id(), npc.skin()).texture());
        assertEquals("Steve", store.resolve(npc.id(), npc.skin()).name());
    }
    @Test void lateResultAfterRefreshCannotRestoreOldSkin() throws Exception {
        var plugin = plugin(); var npc = plugin.definitions().get("npc");
        Supplier<UserProfile> disguise = mock(Supplier.class);
        when(disguise.get()).thenReturn(new UserProfile(UUID.randomUUID(), "Steve",
                List.of(new TextureProperty("textures", "dGV4dHVyZQ==", "c2lnbmF0dXJl"))));
        var cache = new SkinCacheService(plugin); cache.watch(npc, disguise); cache.invalidate(npc.id()); cache.close();
        var store = new SkinStore(folder); store.load(); assertTrue(store.resolve(npc.id(), npc.skin()).texture().isBlank());
        verify(disguise, never()).get();
    }
    @Test void emptyProfileDoesNotBecomePersistentFallback() throws Exception {
        var plugin = plugin(); var npc = plugin.definitions().get("npc");
        Supplier<UserProfile> disguise = mock(Supplier.class); when(disguise.get()).thenReturn(new UserProfile(UUID.randomUUID(), "Steve"));
        var cache = new SkinCacheService(plugin); cache.watch(npc, disguise); cache.close();
        var store = new SkinStore(folder); store.load(); assertTrue(store.resolve(npc.id(), npc.skin()).texture().isBlank());
    }
}

