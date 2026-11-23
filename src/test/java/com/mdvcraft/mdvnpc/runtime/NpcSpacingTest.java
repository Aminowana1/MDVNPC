package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.config.NpcParser;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcSpacingTest {
    long tick;
    /** A plain world object: a mock would make every lookup cost a mock call and hide the real cost. */
    private static final UUID WORLD_ID = UUID.randomUUID();
    final World world = (World) java.lang.reflect.Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "world";
                default -> null;
            });
    final Map<String, Location> feet = new HashMap<>();

    private ActiveNpc npc(String id, double x, double y, double z) {
        feet.put(id, new Location(world, x, y, z));
        Villager villager = mock(Villager.class, withSettings().stubOnly());
        when(villager.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(id.getBytes()));
        when(villager.isValid()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getLocation()).thenAnswer(call -> feet.get(id).clone());
        var yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world");
        return new ActiveNpc(NpcParser.parse(yaml).get(id), feet.get(id).clone(), villager, null);
    }
    private NpcSpacing spacing() {
        NpcSpacing spacing = new NpcSpacing(() -> tick);
        spacing.configure(true, 1, true, 0);
        return spacing;
    }
    private static UUID id(String name) { return UUID.nameUUIDFromBytes(name.getBytes()); }

    @Test void configurationIsClampedAndZeroTurnsItOff() {
        NpcSpacing spacing = spacing();
        var cfg = new YamlConfiguration();
        cfg.set("npc-spacing.min-distance", 9);
        cfg.set("npc-spacing.pass-through-after-seconds", 2.5);
        spacing.configure(cfg);
        assertEquals(3, spacing.minDistance());
        assertEquals(50, spacing.passThroughTicks());
        assertTrue(spacing.enabled());
        cfg.set("npc-spacing.min-distance", 0);
        spacing.configure(cfg);
        assertFalse(spacing.enabled());
        spacing.configure(new YamlConfiguration());
        assertTrue(spacing.enabled());
        assertEquals(1, spacing.minDistance());
        assertEquals(0, spacing.passThroughTicks(), "never walk through by default");
    }

    @Test void aMoveIntoSomeoneIsBlockedButMovingAwayIsNot() {
        NpcSpacing spacing = spacing();
        spacing.track(npc("other", 5, 64, 0));
        Location from = new Location(world, 3.9, 64, 0);
        assertNotNull(spacing.blocker(id("me"), from, new Location(world, 4.2, 64, 0), null));
        assertNull(spacing.blocker(id("me"), from, new Location(world, 3.6, 64, 0), null), "walking away");
        Location inside = new Location(world, 4.5, 64, 0);
        assertNull(spacing.blocker(id("me"), inside, new Location(world, 4.3, 64, 0), null), "leaving an overlap");
        assertNull(spacing.blocker(id("me"), from, new Location(world, 4.2, 66, 0), null), "another floor");
        assertNull(spacing.blocker(id("other"), from, new Location(world, 4.2, 64, 0), null), "itself");
    }

    @Test void allowanceStopsAStepAtTheEdge() {
        NpcSpacing spacing = spacing();
        spacing.track(npc("other", 5, 64, 0));
        double t = spacing.allowance(id("me"), new Location(world, 3, 64, 0), new Location(world, 4.5, 64, 0), null);
        assertEquals(2 / 3.0, t, 1e-9, "from x=3 the edge at x=4 is two thirds of the way");
        assertEquals(1, spacing.allowance(id("me"), new Location(world, 3, 64, 0), new Location(world, 3, 64, 2), null), 1e-9);
    }

    @Test void onlyIdleNpcsReportedRecentlyCanBePushed() {
        NpcSpacing spacing = spacing();
        ActiveNpc idle = npc("idle", 0, 64, 0);
        spacing.idle(idle, true, (n, to) -> true);
        var entry = spacing.entry(id("idle"));
        assertTrue(spacing.pushable(entry));
        assertFalse(spacing.settled(entry), "pushable is not settled");
        spacing.moved(idle);
        assertFalse(spacing.pushable(entry), "it just walked");
        tick += 20;
        assertTrue(spacing.pushable(entry));
        tick += 60;
        assertFalse(spacing.pushable(entry), "its owner stopped reporting it");
        spacing.idle(idle, false, true, (n, to) -> true);
        assertTrue(spacing.settled(entry), "seated or working");
    }

    @Test void refreshForgetsRemovedNpcs() {
        NpcSpacing spacing = spacing();
        ActiveNpc a = npc("a", 0, 64, 0), b = npc("b", 3, 64, 0);
        spacing.refresh(List.of(a, b));
        assertEquals(2, spacing.size());
        tick += 20;
        spacing.refresh(List.of(a));
        assertEquals(1, spacing.size());
        assertTrue(spacing.free(id("x"), new Location(world, 3, 64, 0), 1));
    }

    @Test void checksStayCheapWithManyNpcs() {
        NpcSpacing spacing = spacing();
        Random random = new Random(7);
        for (int i = 0; i < 300; i++) spacing.track(npc("n" + i, random.nextDouble() * 120, 64, random.nextDouble() * 120));
        Location from = new Location(world, 0, 64, 0), to = new Location(world, 0, 64, 0);
        int queries = 200_000;
        long started = System.nanoTime();
        int found = 0;
        for (int i = 0; i < queries; i++) {
            double x = random.nextDouble() * 120, z = random.nextDouble() * 120;
            from.setX(x); from.setZ(z); to.setX(x + .25); to.setZ(z);
            if (spacing.blocker(id("me"), from, to, null) != null) found++;
        }
        double perQuery = (System.nanoTime() - started) / (double) queries;
        System.out.printf("NpcSpacing.blocker: %.0f ns per step check with 300 NPCs (%d blocked)%n", perQuery, found);
        assertTrue(perQuery < 5_000, perQuery + " ns");
    }
}
