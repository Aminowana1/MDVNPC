package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Height between markers, shared connections. Pure: no server. */
class NodeNetworkTest {
    /** A ramp rising one block every two: the road between markers is always walkable. */
    private static final BoundedPathfinder.Grid RAMP = new BoundedPathfinder.Grid() {
        public boolean stand(Node n) { return n.y() == 64 + Math.floorDiv(n.x(), 2) && n.z() >= -2 && n.z() <= 2; }
        public boolean edge(Node from, Node to) { return true; }
    };
    private static Node ramp(int x) { return new Node(x, 64 + Math.floorDiv(x, 2), 0); }

    private static NetheriteNodeRouter route(List<Node> markers, Node start, Node goal, int maxY,
                                             NetheriteNodeRouter.EdgeCache cache) {
        var router = new NetheriteNodeRouter(markers, RAMP, start, goal, 12, 16, 2048, maxY, cache);
        while (!router.done()) router.advance(256);
        return router;
    }

    @Test void heightDoesNotShortenTheLinkRange() {
        // 11 blocks apart and 5 higher: 12.1 blocks in a straight line, but a link of 11.
        Node first = ramp(0), second = ramp(11), goal = ramp(26);
        var router = route(List.of(first, second), first, goal, 8, null);
        assertNotNull(router.result(), "markers within the horizontal range link whatever their height");
        assertTrue(router.result().contains(second));
    }

    @Test void markersTooFarApartInHeightDoNotLink() {
        Node first = ramp(0), second = ramp(11), goal = ramp(26);
        assertNull(route(List.of(first, second), first, goal, 4, null).result(),
                "5 blocks of height exceed a limit of 4");
    }

    @Test void tripsShareTheConnectionsBetweenMarkers() {
        World world = mock(World.class);
        var index = new NetheriteNodeIndex();
        List<Node> road = List.of(ramp(0), ramp(8), ramp(16), ramp(24), ramp(32));
        var first = route(road, new Node(-2, 63, 1), ramp(34), 8, index.edges(world));
        var second = route(road, new Node(-1, 63, -1), ramp(35), 8, index.edges(world));
        assertNotNull(first.result());
        assertNotNull(second.result());
        assertTrue(second.work() * 2 < first.work(), "second trip " + second.work() + ", first " + first.work());

        // A block edit beside the road measures its connections again.
        index.invalidateEdges(world, 16, 0);
        var third = route(road, new Node(-1, 63, -1), ramp(35), 8, index.edges(world));
        assertTrue(third.work() > second.work());
        assertEquals(second.result(), third.result());
    }

    @Test void aConnectionKnownToBeBlockedStaysBlocked() {
        World world = mock(World.class);
        var index = new NetheriteNodeIndex();
        NetheriteNodeRouter.EdgeCache cache = index.edges(world);
        Node first = ramp(0), second = ramp(11), goal = ramp(26);
        // Out of reach of the second marker: this trip can only use the connection between them.
        Node start = ramp(-6);
        cache.put(first, second, NetheriteNodeRouter.NO_PATH);
        assertNull(route(List.of(first, second), start, goal, 8, cache).result());
        index.invalidateEdges(world, 5, 0);
        assertNotNull(route(List.of(first, second), start, goal, 8, cache).result());
    }
}
