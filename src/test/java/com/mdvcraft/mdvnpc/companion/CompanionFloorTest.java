package com.mdvcraft.mdvnpc.companion;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionFloorTest {
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void destinationOnASlabUsesTheHeightWhereFeetRest() throws Exception {
        MockBukkit.mock();
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(128);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block air = block(Material.AIR, 0), stone = block(Material.STONE, 1), slab = block(Material.STONE_SLAB, .5);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int y = call.getArgument(1);
            return y < 63 ? stone : y == 63 ? slab : air;
        });
        var method = CompanionService.class.getDeclaredMethod("onFloor", Location.class);
        method.setAccessible(true);
        var service = mock(CompanionService.class, CALLS_REAL_METHODS);
        var floor = (Location) method.invoke(service, new Location(world, 10.5, 64, 10.5));
        assertEquals(63.5, floor.getY(), 1e-6);
    }

    private static Block block(Material material, double height) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(material.isAir());
        VoxelShape shape = mock(VoxelShape.class);
        when(shape.getBoundingBoxes()).thenReturn(material.isAir() ? List.of() : List.of(new BoundingBox(0, 0, 0, 1, height, 1)));
        when(block.getCollisionShape()).thenReturn(shape);
        return block;
    }
}
