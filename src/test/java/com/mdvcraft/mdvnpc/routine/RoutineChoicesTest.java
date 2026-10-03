package com.mdvcraft.mdvnpc.routine;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.random.RandomGenerator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutineChoicesTest {
    final UUID world=UUID.randomUUID();
    RoutineGoal goal(RoutineGoal.Type type,int x) {
        return new RoutineGoal(1,type,RoutineGoal.WalkMode.CYCLE,1320,420,2.4,20,
                List.of(new RoutineGoal.Point(world,x,64,0,0)));
    }
    @Test void sameOccurrenceKeepsOneDrawAcrossSpawnAndRuntime() {
        var base=goal(RoutineGoal.Type.SLEEP,0).withAlternatives(List.of(goal(RoutineGoal.Type.SIT,2),goal(RoutineGoal.Type.WALK,4)));
        var random=mock(RandomGenerator.class);when(random.nextInt(3)).thenReturn(1,2);
        var cache=new RoutineChoices();var w=new RoutineSchedule.Window(18000,1,List.of(base));
        var first=cache.select("npc",w,base,random);assertEquals(RoutineGoal.Type.SIT,first.type());
        assertSame(first,cache.select("npc",new RoutineSchedule.Window(18000,1,List.of(base)),base,random));
        verify(random,times(1)).nextInt(3);
        var tomorrow=cache.select("npc",new RoutineSchedule.Window(42000,1,List.of(base)),base,random);
        assertEquals(RoutineGoal.Type.WALK,tomorrow.type());verify(random,times(2)).nextInt(3);
    }
    @Test void fixedChoiceDoesNotDrawAndClearingDiscardsPreviousSelection() {
        var base=goal(RoutineGoal.Type.SLEEP,0).withAlternatives(List.of(goal(RoutineGoal.Type.SIT,2))).withRandomChoice(false);
        var random=mock(RandomGenerator.class);var cache=new RoutineChoices();
        var w=new RoutineSchedule.Window(18000,1,List.of(base));
        assertEquals(RoutineGoal.Type.SLEEP,cache.select("npc",w,base,random).type());verifyNoInteractions(random);
        cache.clear();assertEquals(RoutineGoal.Type.SLEEP,cache.select("npc",w,base,random).type());
    }
}
