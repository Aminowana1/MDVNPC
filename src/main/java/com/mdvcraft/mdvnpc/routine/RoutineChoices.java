package com.mdvcraft.mdvnpc.routine;

import java.util.*;
import java.util.random.RandomGenerator;

/** One draw per scheduled occurrence; retained when a loaded NPC is suspended or respawned. */
final class RoutineChoices {
    private static final class Picks {
        long occurrence;int order;
        final Map<Integer,Pick> selected=new HashMap<>();
        Picks(RoutineSchedule.Window w){occurrence=w.occurrence();order=w.timedOrder();}
    }
    /** The goal is kept to redraw only when that goal has been edited. */
    private record Pick(RoutineGoal goal,RoutineGoal chosen) {}
    private final Map<String,Picks> picks=new HashMap<>();
    RoutineGoal select(String id,RoutineSchedule.Window window,RoutineGoal goal,RandomGenerator random) {
        Picks p=picks.get(id);
        if(p==null || p.occurrence!=window.occurrence() || p.order!=window.timedOrder()) {
            p=new Picks(window);picks.put(id,p);
        }
        Pick pick=p.selected.get(goal.order());
        if(pick==null || !pick.goal().equals(goal)) {
            pick=new Pick(goal,goal.choose(random));p.selected.put(goal.order(),pick);
        }
        return pick.chosen();
    }
    void clear(){picks.clear();}
}
