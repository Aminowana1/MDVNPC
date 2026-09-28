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
        assertEquals(30,loaded.clocks().get("world5").dayMinutes());assertTrue(Files.exists(folder.resolve("clocks.yml")));
        assertFalse(Files.exists(folder.resolve("NPCs/shop/routines.yml.bak")),"Editar solo el reloj no debe reescribir la rutina del NPC");
    }
    @Test void invalidEditDoesNotOverwriteKnownGoodFile() throws Exception {
        var repo=new RoutineRepository(folder);repo.put("shop",RoutineScheduleTest.timed(1,"22","7"));Path file=folder.resolve("NPCs/shop/routines.yml");String before=Files.readString(file);
        assertThrows(IllegalArgumentException.class,()->repo.put("shop",RoutineScheduleTest.timed(2,"6","12")));
        assertEquals(before,Files.readString(file));assertEquals(1,repo.snapshot().plans().get("shop").goals().size());
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
    @Test void perGoalDialogueRoundTripsAndLegacyGoalStaysUnconfigured() throws Exception {
        var repo=new RoutineRepository(folder);
        var base=RoutineScheduleTest.timed(1,"7","18");
        repo.put("shop",base);
        var legacy=repo.read().plans().get("shop").goals().getFirst();
        assertFalse(legacy.dialogue().configured());
        var dialogue=new RoutineGoal.Dialogue(true,8,12.5,1.5,false,true,List.of("&7Hola {player}","&7Estoy trabajando"));
        repo.put("shop",base.withDialogue(dialogue));
        var loaded=repo.read().plans().get("shop").goals().getFirst();
        assertEquals(dialogue,loaded.dialogue());
        assertTrue(loaded.dialogue().configured());
    }
}
