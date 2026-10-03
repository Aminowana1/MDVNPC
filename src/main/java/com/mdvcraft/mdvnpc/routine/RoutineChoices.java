package com.mdvcraft.mdvnpc.routine;

import java.util.*;
import java.util.random.RandomGenerator;

/** One draw per scheduled occurrence; retained when a loaded NPC is suspended or respawned. */
final class RoutineChoices {
    private static final class Picks {
        long occurrence;int order;
        final Map<Integer,RoutineGoal> selected=new HashMap<>();
        Picks(RoutineSchedule.Window w){occurrence=w.occurrence();order=w.timedOrder();}
    }
    private final Map<String,Picks> picks=new HashMap<>();
    RoutineGoal select(String id,RoutineSchedule.Window window,RoutineGoal goal,RandomGenerator random) {
        Picks p=picks.get(id);
        if(p==null || p.occurrence!=window.occurrence() || p.order!=window.timedOrder()) {
            p=new Picks(window);picks.put(id,p);
        }
        return p.selected.computeIfAbsent(goal.order(),k->goal.choose(random));
    }
    void clear(){picks.clear();}
}
