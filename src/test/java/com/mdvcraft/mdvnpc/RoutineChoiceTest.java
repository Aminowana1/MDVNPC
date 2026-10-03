package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.routine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutineChoiceTest {
    @TempDir Path folder;
    private RoutineGoal action(int order,RoutineGoal.Type type,int start,int end,int x) {
        return new RoutineGoal(order,type,RoutineGoal.WalkMode.CYCLE,start,end,2.4,20,List.of(new RoutineGoal.Point(RoutineScheduleTest.WORLD,x,64,0,0)));
    }
    @Test void sleepingBarAndWalkShareOneWindowAndKeepIndependentDestinations() {
        var root=action(1,RoutineGoal.Type.SLEEP,1320,420,0).withAlternatives(List.of(action(5,RoutineGoal.Type.SIT,0,60,10),action(8,RoutineGoal.Type.WALK,600,800,20)));
        for(int index=0;index<3;index++){var choice=root.choice(index);assertEquals(1,choice.order());assertEquals(1320,choice.start());assertEquals(420,choice.end());assertEquals(index*10,choice.points().getFirst().x());}
        var window=RoutineSchedule.window(List.of(root),16000);assertEquals(1,window.chain().size());assertEquals(root,window.chain().getFirst());
        assertDoesNotThrow(()->RoutineSchedule.validate(List.of(root,action(2,RoutineGoal.Type.WORK,420,1080,30))));
        var changed=root.withTimes(1200,480);assertEquals(1200,changed.choice(2).start());assertEquals(480,changed.choice(1).end());
    }
    @Test void randomCanChooseEveryActionAndFixedAlwaysUsesPrincipal() {
        var root=action(1,RoutineGoal.Type.SLEEP,1320,420,0).withAlternatives(List.of(action(1,RoutineGoal.Type.SIT,1320,420,10),action(1,RoutineGoal.Type.WALK,1320,420,20)));
        Random random=new Random(9);Set<RoutineGoal.Type> selected=new HashSet<>();
        for(int i=0;i<100;i++){var choice=root.choose(random);selected.add(choice.type());assertTrue(choice.alternatives().isEmpty());}
        assertEquals(Set.of(RoutineGoal.Type.SLEEP,RoutineGoal.Type.SIT,RoutineGoal.Type.WALK),selected);
        var fixed=root.withRandomChoice(false);for(int i=0;i<30;i++)assertEquals(RoutineGoal.Type.SLEEP,fixed.choose(random).type());
        assertEquals(3,fixed.choiceCount());assertTrue(fixed.withoutChoice(1).alternatives().size()==1);
        assertFalse(fixed.withoutChoice(1).withoutChoice(1).randomChoice());
    }
    @Test void optionsAndFixedModeRoundTripWithoutSeparateHours() throws Exception {
        var dialogue=new RoutineGoal.Dialogue(true,5,30,1,true,false,List.of("A la taberna"));
        var root=action(1,RoutineGoal.Type.SLEEP,1320,420,0).withAlternatives(List.of(action(1,RoutineGoal.Type.SIT,1320,420,10).withDialogue(dialogue))).withRandomChoice(false);
        var repo=new RoutineRepository(folder);repo.put("bard",root);assertEquals(root,new RoutineRepository(folder).read().plans().get("bard").goals().getFirst());
        var file=YamlConfiguration.loadConfiguration(folder.resolve("NPCs/bard/routines.yml").toFile());assertFalse(file.contains("npcs.bard.goals.1.alternatives.1.from"));
        repo.put("bard",root.withChoice(1,root.choice(1).withSpeed(1.2)));var loaded=repo.read().plans().get("bard").goals().getFirst();
        assertEquals(2.4,loaded.speed());assertEquals(1.2,loaded.choice(1).speed());assertEquals(dialogue,loaded.choice(1).dialogue());assertFalse(loaded.randomChoice());
    }
    @Test void invalidOptionsCannotCreateNestedCrossWorldOrNonterminatingMetaChains() {
        var sleep=action(1,RoutineGoal.Type.SLEEP,1320,420,0);var sit=action(1,RoutineGoal.Type.SIT,1320,420,10);
        assertThrows(IllegalArgumentException.class,()->sleep.withAlternatives(List.of(sit.withAlternatives(List.of(sleep)))));
        assertThrows(IllegalArgumentException.class,()->sleep.withAlternatives(Collections.nCopies(RoutineGoal.MAX_CHOICES,sit)));
        var other=new RoutineGoal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,1320,420,2,20,List.of(new RoutineGoal.Point(UUID.randomUUID(),1,64,1,0)));
        assertThrows(IllegalArgumentException.class,()->sleep.withAlternatives(List.of(other)));
        assertThrows(IllegalArgumentException.class,()->RoutineScheduleTest.meta(1).withAlternatives(List.of(sit)));
        assertThrows(IllegalArgumentException.class,()->sleep.withAlternatives(List.of(RoutineScheduleTest.meta(1))));
    }
}
