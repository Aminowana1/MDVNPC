package com.mdvcraft.mdvnpc.routine;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Keeps the bounded marker cache current after ordinary world edits. */
public final class NetheriteNodeListener implements Listener {
    @FunctionalInterface private interface Invalidation {
        void apply(org.bukkit.World world,int blockX,int blockZ);
    }
    private final Invalidation invalidation, terrain;

    public NetheriteNodeListener(RoutineNavigator navigator) {
        this.invalidation = navigator::invalidateNodes; this.terrain = navigator::invalidateTerrain;
    }
    /** Resolve the service's current navigator after every configuration reload. */
    public NetheriteNodeListener(RoutineService service) {
        this.invalidation = service::invalidateNodes; this.terrain = service::invalidateTerrain;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        // Only markers live in the cache; the cover above them is checked live by the navigator.
        if (marker(event.getBlockPlaced())) invalidate(event.getBlockPlaced());
        else edited(event.getBlockPlaced());
        if (event instanceof BlockMultiPlaceEvent multi)
            for (BlockState state : multi.getReplacedBlockStates())
                if (state.getType() == Material.NETHERITE_BLOCK)
                    invalidation.apply(state.getWorld(), state.getX(), state.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (marker(event.getBlock())) invalidate(event.getBlock());
        else edited(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent event) {
        explosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent event) {
        explosion(event.blockList());
    }

    private void explosion(Iterable<Block> blocks) {
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        for (Block block : blocks) {
            if (marker(block)) invalidate(block);
            else if (chunks.add(((long) (block.getX() >> 4) << 32) ^ ((block.getZ() >> 4) & 0xffffffffL))) edited(block);
        }
    }

    private void edited(Block block) {
        terrain.apply(block.getWorld(), block.getX(), block.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent event) {
        piston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent event) {
        piston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    private static boolean marker(Block block) { return block.getType() == Material.NETHERITE_BLOCK; }

    private void piston(Block piston, BlockFace direction, Iterable<Block> moved) {
        boolean movesMarker = false;
        for (Block block : moved) movesMarker |= marker(block);
        if (!movesMarker) return;
        invalidate(piston);
        invalidateOffset(piston, direction, 1);
        for (Block block : moved) {
            invalidate(block);
            // Cover both ends for extension and retraction, including chunk boundaries.
            invalidateOffset(block, direction, 1);
            invalidateOffset(block, direction, -1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoad(ChunkLoadEvent event) {
        invalidate(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chunkUnload(ChunkUnloadEvent event) {
        invalidate(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void worldUnload(WorldUnloadEvent event) {
        for (Chunk chunk : event.getWorld().getLoadedChunks()) invalidate(chunk);
    }

    private void invalidate(Block block) {
        invalidation.apply(block.getWorld(), block.getX(), block.getZ());
    }

    private void invalidate(Chunk chunk) {
        invalidation.apply(chunk.getWorld(), chunk.getX() << 4, chunk.getZ() << 4);
    }

    private void invalidateOffset(Block block, BlockFace direction, int distance) {
        // Coordinates avoid accessing neighboring chunks merely to invalidate cached data.
        invalidation.apply(block.getWorld(), block.getX() + direction.getModX() * distance,
                block.getZ() + direction.getModZ() * distance);
    }
}
