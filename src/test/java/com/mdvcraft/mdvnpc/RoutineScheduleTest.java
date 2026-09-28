package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.routine.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutineScheduleTest {
    static final UUID WORLD=UUID.randomUUID();
    static RoutineGoal goal(int id,RoutineGoal.Type type,RoutineGoal.WalkMode mode,String start,String end) {
        return new RoutineGoal(id,type,mode,RoutineSchedule.parseHour(start),RoutineSchedule.parseHour(end),2.4,20,List.of(new RoutineGoal.Point(WORLD,0,64,0,0)));
    }
    static RoutineGoal timed(int id,String start,String end) {return goal(id,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,start,end);}
    static RoutineGoal meta(int id) {return goal(id,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.TARGET,"00:00","00:00");}
    @Test void minecraftZeroIsSixAndMidnightWraps() {
        assertEquals(360,RoutineSchedule.minute(0));assertEquals(720,RoutineSchedule.minute(6000));
        assertEquals(1080,RoutineSchedule.minute(12000));assertEquals(0,RoutineSchedule.minute(18000));assertEquals(360,RoutineSchedule.minute(24000));
    }
    @Test void strictHoursAndBoundaries() {
        assertEquals(1439,RoutineSchedule.parseHour("23:59"));assertEquals(420,RoutineSchedule.parseHour("7"));
        for(String bad:List.of("24:00","-1","12:60","NaN","7:1","00:00:00"))assertThrows(IllegalArgumentException.class,()->RoutineSchedule.parseHour(bad));
    }
    @Test void overnightSleepIsSameOccurrenceAcrossMidnight() {
        var sleep=goal(1,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE,"22:00","07:00");
        var evening=RoutineSchedule.window(List.of(sleep),16000);var morning=RoutineSchedule.window(List.of(sleep),24000);
        assertNotNull(evening);assertEquals(evening.occurrence(),morning.occurrence());
        assertNull(RoutineSchedule.window(List.of(sleep),25000));
    }
    @Test void metaGoalsChainBeforeNextTimedGoal() {
        var goals=List.of(timed(1,"22","7"),meta(2),meta(3),timed(4,"7","18"));
        RoutineSchedule.validate(goals);assertEquals(List.of(2,3,4),RoutineSchedule.window(goals,1000).chain().stream().map(RoutineGoal::order).toList());
        assertEquals(List.of(1),RoutineSchedule.window(goals,16000).chain().stream().map(RoutineGoal::order).toList());
    }
    @Test void trailingMetaWrapsToFirstGoalAndPureMetaRunsOncePerDay() {
        assertEquals(List.of(2,1),RoutineSchedule.window(List.of(timed(1,"0","0"),meta(2)),0).chain().stream().map(RoutineGoal::order).toList());
        assertEquals(-1,RoutineSchedule.window(List.of(meta(1)),0).timedOrder());
        assertNotEquals(RoutineSchedule.window(List.of(meta(1)),0).occurrence(),RoutineSchedule.window(List.of(meta(1)),24000).occurrence());
    }
    @Test void overlapRejectedButAdjacentAndGapsAllowed() {
        assertThrows(IllegalArgumentException.class,()->RoutineSchedule.validate(List.of(timed(1,"22","7"),timed(2,"6","12"))));
        assertDoesNotThrow(()->RoutineSchedule.validate(List.of(timed(1,"22","7"),timed(2,"7","12"))));
        assertNull(RoutineSchedule.window(List.of(timed(1,"7","12")),12000));
        assertThrows(IllegalArgumentException.class,()->RoutineSchedule.validate(List.of(timed(1,"0","0"),timed(2,"7","8"))));
    }
    @Test void worldAndNumericValidation() {
        assertThrows(IllegalArgumentException.class,()->new RoutineGoal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,Double.NaN,20,List.of(new RoutineGoal.Point(WORLD,0,64,0,0))));
        assertThrows(IllegalArgumentException.class,()->new RoutineRepository.Clock(Double.POSITIVE_INFINITY,10));
        var other=new RoutineGoal(2,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,60,2,20,List.of(new RoutineGoal.Point(UUID.randomUUID(),0,64,0,0)));
        assertThrows(IllegalArgumentException.class,()->RoutineSchedule.validate(List.of(timed(1,"1","2"),other)));
    }
    @Test void exactMinuteBoundaryDoesNotResetWindow() {
        var goal=timed(1,"06:01","07:00");
        assertNull(RoutineSchedule.window(List.of(goal),16));
        assertEquals(RoutineSchedule.window(List.of(goal),17).occurrence(),RoutineSchedule.window(List.of(goal),18).occurrence());
    }
}
