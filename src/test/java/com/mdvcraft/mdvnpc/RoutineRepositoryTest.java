package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.routine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutineRepositoryTest {
    @TempDir Path folder;
    @Test void persistenceRoundTripKeepsWorldPointsHoursAndClocks() throws Exception {
        var repo=new RoutineRepository(folder);var goal=RoutineScheduleTest.timed(1,"22","7");repo.put("shop",goal);
        repo.edit(y->{y.set("clocks.world5.day-minutes",30);y.set("clocks.world5.night-minutes",15);});
        var loaded=new RoutineRepository(folder).read();assertEquals(goal,loaded.plans().get("shop").goals().getFirst());
        assertEquals(30,loaded.clocks().get("world5").dayMinutes());assertTrue(Files.exists(folder.resolve("routines.yml.bak")));
    }
    @Test void invalidEditDoesNotOverwriteKnownGoodFile() throws Exception {
        var repo=new RoutineRepository(folder);repo.put("shop",RoutineScheduleTest.timed(1,"22","7"));String before=Files.readString(folder.resolve("routines.yml"));
        assertThrows(IllegalArgumentException.class,()->repo.put("shop",RoutineScheduleTest.timed(2,"6","12")));
        assertEquals(before,Files.readString(folder.resolve("routines.yml")));assertEquals(1,repo.snapshot().plans().get("shop").goals().size());
    }
    @Test void editingOneGoalPreservesOtherAndDisableFlag() throws Exception {
        var repo=new RoutineRepository(folder);repo.put("shop",RoutineScheduleTest.timed(1,"22","7"));repo.put("shop",RoutineScheduleTest.timed(2,"7","18"));
        repo.edit(y->y.set("npcs.shop.enabled",false));repo.put("shop",RoutineScheduleTest.timed(2,"8","18"));
        assertFalse(repo.read().plans().get("shop").enabled());assertEquals(2,repo.read().plans().get("shop").goals().size());
    }
    @Test void corruptFileIsRejectedAndPreserved() throws Exception {
        Files.writeString(folder.resolve("routines.yml"),"npcs: [ broken");var repo=new RoutineRepository(folder);
        assertThrows(Exception.class,repo::read);assertEquals("npcs: [ broken",Files.readString(folder.resolve("routines.yml")));
    }
}
