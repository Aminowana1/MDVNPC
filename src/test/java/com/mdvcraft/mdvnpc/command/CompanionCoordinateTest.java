package com.mdvcraft.mdvnpc.command;

import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.*;

class CompanionCoordinateTest {
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    private Location target(String x, String y, String z) throws Exception {
        var server = MockBukkit.mock(); var world = server.addSimpleWorld("world"); var player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        return CompanionCommand.target(player, new String[]{"companion", "moveto", x, y, z});
    }
    @Test void integerHeightIsTheFloorWhileXAndZAreCentred() throws Exception {
        var target = target("10", "64", "12");
        assertEquals(10.5, target.getX()); assertEquals(64, target.getY()); assertEquals(12.5, target.getZ());
    }
    @Test void negativeHeightIsNotCentred() throws Exception { assertEquals(-60, target("1", "-60", "1").getY()); }
    @Test void decimalAndRelativeHeightsAreKept() throws Exception { assertEquals(65, target("1", "~1", "1").getY()); }
}
