package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.model.NpcDefinition.*;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.skin.DisguiseService;
import com.mdvcraft.mdvnpc.storage.NpcRepository;
import com.mdvcraft.mdvnpc.util.Messages;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcTests {
    @TempDir Path directory;
    private YamlConfiguration defaults() throws Exception {
        var yaml = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/npcs.yml")) {
            yaml.loadFromString(new String(Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return yaml;
    }
    @Test void thurgHasOriginalIdentityAndContractCommand() throws Exception {
        var npc = NpcParser.parse(defaults()).get("thurg");
        assertEquals(UUID.fromString("eafd8793-7fca-495e-9950-68b276c0f21f"), npc.position().worldId());
        assertEquals(-12.5, npc.position().x()); assertEquals(205, npc.position().y());
        assertEquals(10, npc.dialogue().lines().size()); assertEquals(2, npc.interaction().cooldownSeconds());
        assertEquals(new Action(Click.RIGHT, Executor.CONSOLE, "mdvquest npc <p>"), npc.interaction().actions().getFirst());
        assertTrue(DisguiseService.skinInput(npc.skin()).contains(npc.skin().signature()));
        assertEquals(512, Base64.getDecoder().decode(npc.skin().signature()).length);
    }
    @Test void parserRejectsBrokenCommandsAndNonFiniteLocations() throws Exception {
        var yaml = defaults(); yaml.set("npcs.thurg.location.x", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> NpcParser.parse(yaml));
        yaml.set("npcs.thurg.location.x", -12.5);
        yaml.set("npcs.thurg.interaction.commands", List.of(Map.of("executor", "OP", "command", "test")));
        assertThrows(IllegalArgumentException.class, () -> NpcParser.parse(yaml));
    }
    @Test void negativeCoordinatesUseFloorChunksAndYawWraps() {
        assertEquals(-1, new Position(null, "world", -0.5, 0, -16, 0, 0).chunkX());
        assertEquals(-1, new Position(null, "world", 0, 0, -16, 0, 0).chunkZ());
        assertEquals(2, LookService.angularDifference(179, -179));
    }
    @Test void invalidEditPreservesDiskAndValidEditSurvivesReload() throws Exception {
        for (String name : List.of("config.yml", "npcs.yml")) {
            try (var in = getClass().getResourceAsStream("/" + name)) { Files.copy(Objects.requireNonNull(in), directory.resolve(name)); }
        }
        var repo = new NpcRepository(directory);
        String before = Files.readString(directory.resolve("npcs.yml"));
        assertThrows(IllegalArgumentException.class, () -> repo.edit(y -> y.set("npcs.thurg.skin.texture", "")));
        assertEquals(before, Files.readString(directory.resolve("npcs.yml")));
        repo.edit(y -> y.set("npcs.thurg.name", "&aOtro"));
        assertEquals("&aOtro", repo.load().npcs().get("thurg").name());
        assertEquals(before, Files.readString(directory.resolve("npcs.yml.bak")));
        repo.edit(y -> y.set("npcs.thurg", null));
        assertTrue(repo.load().npcs().isEmpty());
    }
    private ActiveNpc npc(World world) throws Exception {
        var def = NpcParser.parse(defaults()).get("thurg");
        def = new NpcDefinition(def.id(), true, def.name(), true, def.position(), def.skin(), def.look(), def.dialogue(),
                new Interaction(6, 2, true, "", List.of(new Action(Click.RIGHT, Executor.PLAYER, "quest {player}"))));
        Villager entity = mock(Villager.class); when(entity.isValid()).thenReturn(true);
        return new ActiveNpc(def, new Location(world, 0, 64, 0), entity, null);
    }
    private Player player(World world, ActiveNpc npc) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn("TestPlayer");
        when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        when(player.hasLineOfSight(npc.entity())).thenReturn(true); when(player.performCommand(anyString())).thenReturn(true);
        return player;
    }
    @Test void clickDeduplicatesAndCooldownIsPerPlayer() throws Exception {
        World world = mock(World.class); var npc = npc(world); var a = player(world, npc); var b = player(world, npc);
        var service = new InteractionService(mock(Messages.class), Logger.getAnonymousLogger());
        long t = 10_000_000_000L;
        service.click(npc, a, Click.RIGHT, t);
        service.click(npc, a, Click.RIGHT, t + 1);
        service.click(npc, a, Click.RIGHT, t + 1_000_000_000L);
        service.click(npc, b, Click.RIGHT, t + 1_000_000_000L);
        verify(a, times(1)).performCommand("quest TestPlayer"); verify(b, times(1)).performCommand("quest TestPlayer");
        service.click(npc, a, Click.RIGHT, t + 2_100_000_000L);
        verify(a, times(2)).performCommand("quest TestPlayer");
    }
    @Test void interactionRejectsDistanceObstaclesWrongWorldAndWrongButton() throws Exception {
        World world = mock(World.class); var npc = npc(world); var p = player(world, npc);
        var service = new InteractionService(mock(Messages.class), Logger.getAnonymousLogger());
        service.click(npc, p, Click.LEFT, 10_000_000_000L);
        when(p.getLocation()).thenReturn(new Location(world, 100, 64, 0));
        service.click(npc, p, Click.RIGHT, 11_000_000_000L);
        when(p.getLocation()).thenReturn(new Location(world, 1, 64, 0)); when(p.hasLineOfSight(npc.entity())).thenReturn(false);
        service.click(npc, p, Click.RIGHT, 12_000_000_000L);
        when(p.hasLineOfSight(npc.entity())).thenReturn(true); when(p.getWorld()).thenReturn(mock(World.class));
        service.click(npc, p, Click.RIGHT, 13_000_000_000L);
        verify(p, never()).performCommand(anyString());
    }
    @Test void dialogueWaitsAndDoesNotSpamOnReentry() throws Exception {
        World world = mock(World.class); var npc = npc(world); var p = player(world, npc);
        var service = new DialogueService();
        service.update(npc, List.of(p), 0); service.update(npc, List.of(p), 1_000_000_000L);
        verify(p, never()).sendMessage(anyString());
        service.update(npc, List.of(p), 2_000_000_000L);
        service.update(npc, List.of(), 3_000_000_000L);
        service.update(npc, List.of(p), 4_000_000_000L);
        verify(p, times(1)).sendMessage(anyString());
        service.update(npc, List.of(p), 42_000_000_000L);
        verify(p, times(2)).sendMessage(anyString());
    }
}
