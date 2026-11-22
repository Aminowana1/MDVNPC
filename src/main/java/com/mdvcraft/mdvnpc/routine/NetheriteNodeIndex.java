package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Main-thread, incremental discovery of buried route markers. Never loads a chunk. */
public final class NetheriteNodeIndex {
    private static final int MAX_MARKERS = 512;
    private static final int MAX_CHUNKS = 1024;
    private static final int SNAPSHOTS_PER_ADVANCE = 4;
    private static final int MAX_INVALIDATIONS = 2048;
    private record ChunkKey(World world, int x, int z) {}
    private record CachedChunk(long expires, List<Node> markers) {}
    private final int maxCachedChunks;
    private final long cacheNanos;
    private final Map<ChunkKey, CachedChunk> cache = new LinkedHashMap<>(32, .75f, true);
    private final Map<ChunkKey, Long> invalidations = new LinkedHashMap<>();
    private long revision;
    private long discardedRevision;

    // Our listener reports marker edits; the expiry only covers silent edits (WorldEdit, /setblock).
    public NetheriteNodeIndex() { this(256, 30_000); }
    public NetheriteNodeIndex(int maxCachedChunks, long cacheMillis) {
        this.maxCachedChunks = Math.max(1, Math.min(1024, maxCachedChunks));
        cacheNanos = Math.max(0, Math.min(60_000, cacheMillis)) * 1_000_000L;
    }

    /** Full-height snapshots also discover markers on bridges and other stacked floors.
     * A trip beyond the configured horizontal span declines node routing. */
    public Scan discover(World world, Location start, Location goal, int padding, int maxDistance) {
        return new Scan(world, start, goal, padding, maxDistance);
    }

    public void invalidate(World world, int blockX, int blockZ) {
        ChunkKey key = new ChunkKey(world, blockX >> 4, blockZ >> 4);
        cache.remove(key);
        invalidations.remove(key); invalidations.put(key, ++revision);
        while (invalidations.size() > MAX_INVALIDATIONS) {
            ChunkKey oldest = invalidations.keySet().iterator().next();
            discardedRevision = Math.max(discardedRevision, invalidations.remove(oldest));
        }
    }
    public void clear() {
        cache.clear(); invalidations.clear(); discardedRevision = ++revision;
    }

    public final class Scan {
        private final World world;
        private final List<ChunkKey> chunks = new ArrayList<>();
        private final LinkedHashSet<Node> markers = new LinkedHashSet<>();
        private final Map<ChunkKey, List<Node>> scanned = new HashMap<>();
        private final int minimumHeight, maximumHeight;
        private int chunkIndex, column, y, top;
        private boolean columnStarted;
        private boolean done, truncated;
        private ChunkKey scanning;
        private ChunkSnapshot snapshot;
        private final List<Node> chunkMarkers = new ArrayList<>();
        private BlockData markerData;
        private long scanRevision;

        private Scan(World world, Location start, Location goal, int padding, int maxDistance) {
            this.world = world;
            scanRevision = revision;
            minimumHeight = world == null ? 0 : world.getMinHeight();
            maximumHeight = world == null ? 0 : world.getMaxHeight();
            if (world == null || start == null || goal == null || start.getWorld() != world
                    || goal.getWorld() != world || !finite(start) || !finite(goal)
                    || maximumHeight <= minimumHeight) { done = true; return; }
            int limit = Math.max(16, Math.min(512, maxDistance));
            if (Math.abs((long) goal.getBlockX() - start.getBlockX()) > limit
                    || Math.abs((long) goal.getBlockZ() - start.getBlockZ()) > limit) {
                done = true; truncated = true; return;
            }
            int margin = Math.max(0, Math.min(64, padding));
            int firstX = Math.floorDiv(Math.min(start.getBlockX(), goal.getBlockX()) - margin, 16);
            int lastX = Math.floorDiv(Math.max(start.getBlockX(), goal.getBlockX()) + margin, 16);
            int firstZ = Math.floorDiv(Math.min(start.getBlockZ(), goal.getBlockZ()) - margin, 16);
            int lastZ = Math.floorDiv(Math.max(start.getBlockZ(), goal.getBlockZ()) + margin, 16);
            for (int x = firstX; x <= lastX; x++) for (int z = firstZ; z <= lastZ; z++) {
                if (chunks.size() >= MAX_CHUNKS) { truncated = true; continue; }
                chunks.add(new ChunkKey(world, x, z));
            }
            // Nearby markers become available first, and cached short trips finish cheaply.
            chunks.sort(Comparator.comparingDouble(key -> {
                double dx = key.x * 16.0 + 8 - start.getX();
                double dz = key.z * 16.0 + 8 - start.getZ();
                return dx * dx + dz * dz;
            }));
        }

        /** At most budget snapshot block reads and four snapshot creations in one call. */
        public int advance(int budget) {
            int used = 0, snapshots = 0;
            if (!done && scanRevision != revision) {
                // A placement/removal can happen while this snapshot is spread across ticks.
                // Read again only the chunks that changed; the others keep their progress.
                if (dirty()) discardChanged();
                scanRevision = revision;
            }
            while (!done && used < Math.max(0, budget)) {
                if (snapshot == null) {
                    if (chunkIndex >= chunks.size()) { done = true; break; }
                    ChunkKey next = chunks.get(chunkIndex);
                    if (scanned.containsKey(next)) { chunkIndex++; continue; }
                    // Check even a cached chunk: cached data cannot make an unloaded route live.
                    if (!world.isChunkLoaded(next.x, next.z)) {
                        cache.remove(next); scanned.put(next, List.of()); chunkIndex++; used++; continue;
                    }
                    CachedChunk cached = cache.get(next);
                    if (cached != null && System.nanoTime() < cached.expires) {
                        scanned.put(next, cached.markers); add(cached.markers); chunkIndex++; used++; continue;
                    }
                    if (snapshots >= SNAPSHOTS_PER_ADVANCE) break;
                    snapshots++; used++;
                    scanning = next;
                    // isChunkLoaded above and synchronous access ensure this cannot load terrain.
                    org.bukkit.Chunk chunk=world.getChunkAt(next.x,next.z);
                    if(chunk==null) {truncated=true;done=true;break;}
                    try {
                        snapshot = chunk.getChunkSnapshot(true, false, false);
                    } catch(UnsupportedOperationException unsupported) {
                        truncated=true;done=true;break;
                    }
                    if(snapshot==null) {truncated=true;done=true;break;}
                    chunkMarkers.clear(); column = 0; columnStarted = false;
                    if (markerData == null) markerData = Material.NETHERITE_BLOCK.createBlockData();
                    if (!snapshot.contains(markerData)) finishChunk();
                    continue;
                }
                // A chunk can unload between advance calls; discard its unfinished snapshot.
                if (!world.isChunkLoaded(scanning.x, scanning.z)) {
                    scanned.put(scanning, List.of());
                    snapshot = null; chunkMarkers.clear(); chunkIndex = 0; used++; continue;
                }
                int x = column & 15, z = column >> 4;
                if (!columnStarted) {
                    // Nothing can be buried above the highest block, so skip that sky.
                    top = Math.min(maximumHeight - 1, snapshot.getHighestBlockYAt(x, z));
                    y = minimumHeight; columnStarted = true;
                }
                if (y > top) { // An empty (void) column still costs one read of the height map.
                    used++; nextColumn(); continue;
                }
                if (snapshot.getBlockType(x, y, z) == Material.NETHERITE_BLOCK && y + 2 < maximumHeight)
                    chunkMarkers.add(new Node((scanning.x << 4) + x, y + 2, (scanning.z << 4) + z));
                used++;
                if (chunkMarkers.size() > MAX_MARKERS) {
                    truncated = true; done = true; markers.clear(); snapshot = null;
                } else if (++y > top) nextColumn();
            }
            if (!done && snapshot == null && chunkIndex >= chunks.size()) done = true;
            return used;
        }

        private void nextColumn() {
            columnStarted = false;
            if (++column == 256) finishChunk();
        }
        private void discardChanged() {
            boolean all = scanRevision < discardedRevision;
            scanned.keySet().removeIf(chunk -> all || invalidations.getOrDefault(chunk, 0L) > scanRevision);
            if (snapshot != null && (all || invalidations.getOrDefault(scanning, 0L) > scanRevision)) {
                snapshot = null; chunkMarkers.clear();
            }
            markers.clear();
            scanned.values().forEach(this::add);
            if (snapshot == null) chunkIndex = 0;
        }
        private void finishChunk() {
            List<Node> found = List.copyOf(chunkMarkers);
            cache.put(scanning, new CachedChunk(System.nanoTime() + cacheNanos, found));
            while (cache.size() > maxCachedChunks) cache.remove(cache.keySet().iterator().next());
            scanned.put(scanning, found);
            // From the start: an edit may have reopened a chunk behind this one.
            add(found); snapshot = null; chunkMarkers.clear(); chunkIndex = 0;
        }
        private void add(List<Node> found) {
            for (Node marker : found) {
                if (markers.size() >= MAX_MARKERS && !markers.contains(marker)) {
                    truncated = true; done = true; markers.clear(); return;
                }
                markers.add(marker);
            }
        }
        public boolean done() { return done; }
        /** Partial discoveries are intentionally hidden until the complete bounded scan finishes. */
        public List<Node> result() { return done && !truncated && !dirty() ? List.copyOf(markers) : List.of(); }
        public boolean truncated() { return truncated; }

        private boolean dirty() {
            if (scanRevision == revision) return false;
            if (scanRevision < discardedRevision) return true;
            for (ChunkKey chunk : chunks)
                if (invalidations.getOrDefault(chunk, 0L) > scanRevision) return true;
            return false;
        }
    }

    private static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ()) && Math.abs(location.getX()) <= 30_000_000
                && Math.abs(location.getZ()) <= 30_000_000;
    }
}
