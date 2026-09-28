package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutinePathTest {
    BoundedPathfinder.Grid flat(Set<Node> blocked) {return new BoundedPathfinder.Grid(){public boolean stand(Node n){return n.y()==64&&!blocked.contains(n);}public boolean edge(Node a,Node b){return true;}};}
    @Test void routeGoesAroundWallWithoutDiagonalCornerCutting() {
        var blocked=Set.of(new Node(1,64,0),new Node(1,64,1));
        var search=new BoundedPathfinder(flat(blocked),new Node(0,64,0),new Node(3,64,0),128);
        while(!search.done())assertTrue(search.advance(3)<=3);
        assertNotNull(search.result());assertEquals(new Node(3,64,0),search.result().getLast());
        for(int i=1;i<search.result().size();i++) {var a=search.result().get(i-1);var b=search.result().get(i);assertEquals(1,Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z()));assertFalse(blocked.contains(b));}
    }
    @Test void impossibleSearchHasHardMemoryAndWorkBounds() {
        var search=new BoundedPathfinder(flat(Set.of()),new Node(0,64,0),new Node(400,64,400),100);
        int updates=0;while(!search.done()&&updates++<1000)assertTrue(search.advance(7)<=7);
        assertTrue(search.done());assertNull(search.result());assertTrue(search.visited()<=100);
    }
    @Test void blockedOrUnloadedDestinationCannotStartSearch() {
        var search=new BoundedPathfinder(flat(Set.of(new Node(3,64,0))),new Node(0,64,0),new Node(3,64,0),100);
        assertTrue(search.done());assertNull(search.result());assertEquals(0,search.visited());
    }
    @Test void samePositionAndDisconnectedIslandTerminate() {
        var start=new Node(0,64,0);var same=new BoundedPathfinder(flat(Set.of()),start,start,100);same.advance(1);assertEquals(List.of(start),same.result());
        var isolated=new BoundedPathfinder(flat(Set.of(new Node(1,64,0),new Node(-1,64,0),new Node(0,64,1),new Node(0,64,-1))),start,new Node(5,64,0),100);
        isolated.advance(10);assertTrue(isolated.done());assertNull(isolated.result());
    }
    @Test void stepsRequireEdgeClearance() {
        var start=new Node(0,64,0);var target=new Node(1,65,0);
        var grid=new BoundedPathfinder.Grid(){public boolean stand(Node n){return n.equals(start)||n.equals(target);}public boolean edge(Node a,Node b){return false;}};
        var search=new BoundedPathfinder(grid,start,target,100);search.advance(10);assertNull(search.result());assertTrue(search.done());
    }
}
