package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.routine.RoutineGoal;
import com.mdvcraft.mdvnpc.routine.RoutineRepository;
import com.mdvcraft.mdvnpc.routine.RoutineSchedule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class RoutineWorkInteractionModelTest {
    @TempDir Path folder;
    final UUID world=UUID.randomUUID();
    private RoutineGoal action(int order,RoutineGoal.Type type,int from,int until,int x){
        return new RoutineGoal(order,type,RoutineGoal.WalkMode.CYCLE,from,until,2.4,20,List.of(new RoutineGoal.Point(world,x,64,0,0)));
    }
    private RoutineGoal options(){
        return action(1,RoutineGoal.Type.SIT,420,1080,0).withAlternatives(List.of(action(1,RoutineGoal.Type.WALK,0,60,4),action(1,RoutineGoal.Type.WORK,0,60,8)));
    }
    private void assertInherited(RoutineGoal root,boolean enabled){
        assertEquals(enabled,root.workInteraction());
        for(int index=0;index<root.choiceCount();index++)assertEquals(enabled,root.choice(index).workInteraction(),"choice "+index);
        for(var option:root.alternatives())assertEquals(enabled,option.workInteraction());
    }
    @Test void oldConstructorsDefaultToFalseIncludingAlternativeOverrides(){
        var base=action(1,RoutineGoal.Type.SIT,420,1080,0);assertFalse(base.workInteraction());
        var dialogueConstructor=new RoutineGoal(base.order(),base.type(),base.mode(),base.start(),base.end(),base.speed(),base.radius(),base.points(),base.dialogue());
        assertFalse(dialogueConstructor.workInteraction());
        var oldFullConstructor=new RoutineGoal(base.order(),base.type(),base.mode(),base.start(),base.end(),base.speed(),base.radius(),base.points(),base.dialogue(),
                List.of(action(1,RoutineGoal.Type.WORK,0,60,4).withWorkInteraction(true)),true);
        assertInherited(oldFullConstructor,false);
    }
    @Test void switchingTheRootOverridesEveryAlternativeInBothDirections(){
        var root=options().withWorkInteraction(true);assertInherited(root,true);
        root=root.withAlternatives(List.of(action(1,RoutineGoal.Type.SLEEP,1080,420,4).withWorkInteraction(false)));assertInherited(root,true);
        assertInherited(root.withWorkInteraction(false),false);
        assertEquals(420,root.choice(1).start());assertEquals(1080,root.choice(1).end());
    }
    @Test void ordinaryEditsAndReplacingPrincipalOrVariantsCannotDropTheRootSwitch(){
        var root=options().withWorkInteraction(true);
        List<UnaryOperator<RoutineGoal>> edits=List.of(g->g.withTimes(480,1000),g->g.withSpeed(1.2),g->g.withRadius(12),g->g.withMode(RoutineGoal.WalkMode.RANDOM),
                g->g.withDialogue(new RoutineGoal.Dialogue(true,5,20,1,false,false,List.of("Hola"))),g->g.withRandomChoice(false));
        for(var edit:edits)assertInherited(edit.apply(root),true);
        var replacedBase=root.withChoice(0,action(1,RoutineGoal.Type.SLEEP,480,1000,2));assertInherited(replacedBase,true);assertEquals(480,replacedBase.choice(1).start());
        var replacedVariant=replacedBase.withChoice(1,action(1,RoutineGoal.Type.SIT,0,60,6).withWorkInteraction(false));assertInherited(replacedVariant,true);
        assertInherited(replacedVariant.withoutChoice(1),true);assertInherited(replacedVariant.withoutChoice(1).withoutChoice(1),true);
        assertInherited(options().withChoice(0,options().choice(0).withWorkInteraction(true)),false);
    }
    @Test void fixedAndRandomSelectionsInheritTheSwitchWithoutNestedAlternatives(){
        var root=options().withWorkInteraction(true);var random=new Random(9);Set<RoutineGoal.Type> selected=new HashSet<>();
        for(int draw=0;draw<100;draw++){var choice=root.choose(random);assertTrue(choice.workInteraction());assertTrue(choice.alternatives().isEmpty());selected.add(choice.type());}
        assertEquals(Set.of(RoutineGoal.Type.SIT,RoutineGoal.Type.WALK,RoutineGoal.Type.WORK),selected);
        assertTrue(root.withRandomChoice(false).choose(random).workInteraction());
    }
    @Test void saveReloadAndSortedGoalsRetainIndependentRootSwitchesAndStoreNoVariantKey() throws Exception {
        var repo=new RoutineRepository(folder);var night=action(2,RoutineGoal.Type.SLEEP,1080,420,12);var day=options().withWorkInteraction(true).withRandomChoice(false);
        repo.put("guest",night);repo.put("guest",day);
        var goals=new RoutineRepository(folder).read().plans().get("guest").goals();assertEquals(List.of(day,night),goals);assertInherited(goals.getFirst(),true);assertInherited(goals.get(1),false);
        var file=YamlConfiguration.loadConfiguration(folder.resolve("NPCs/guest/routines.yml").toFile());
        assertTrue(file.getBoolean("npcs.guest.goals.1.work-interaction"));assertFalse(file.getBoolean("npcs.guest.goals.2.work-interaction"));
        for(int index=1;index<day.choiceCount();index++)assertFalse(file.contains("npcs.guest.goals.1.alternatives."+index+".work-interaction"));
        var edited=day.withChoice(0,day.choice(0).withTimes(480,1080)).withChoice(1,day.choice(1).withSpeed(1.2));repo.put("guest",edited);
        assertEquals(edited,repo.read().plans().get("guest").goals().getFirst());assertInherited(repo.read().plans().get("guest").goals().getFirst(),true);
        assertDoesNotThrow(()->RoutineSchedule.validate(repo.read().plans().get("guest").goals()));
    }
    @Test void oldYamlWithoutTheSwitchKeepsInteractionsDisabledForEveryChoice() throws Exception {
        var repo=new RoutineRepository(folder);repo.put("guest",options());Path path=folder.resolve("NPCs/guest/routines.yml");
        var yaml=YamlConfiguration.loadConfiguration(path.toFile());yaml.set("npcs.guest.goals.1.work-interaction",null);yaml.save(path.toFile());
        var old=new RoutineRepository(folder).read().plans().get("guest").goals().getFirst();assertInherited(old,false);
        repo.put("guest",old.withSpeed(1.5));assertInherited(repo.read().plans().get("guest").goals().getFirst(),false);
    }
    @Test void yamlVariantOverridesAreIgnoredAndAnEditedGoalWritesOnlyItsRootSwitch() throws Exception {
        var repo=new RoutineRepository(folder);repo.put("guest",options());Path path=folder.resolve("NPCs/guest/routines.yml");
        for(boolean enabled:List.of(false,true)){
            var yaml=YamlConfiguration.loadConfiguration(path.toFile());yaml.set("npcs.guest.goals.1.work-interaction",enabled);
            yaml.set("npcs.guest.goals.1.alternatives.1.work-interaction",!enabled);yaml.set("npcs.guest.goals.1.alternatives.2.work-interaction",!enabled);yaml.save(path.toFile());
            var loaded=new RoutineRepository(folder).read().plans().get("guest").goals().getFirst();assertInherited(loaded,enabled);
            repo.put("guest",loaded.withSpeed(enabled?1.7:1.6));var rewritten=YamlConfiguration.loadConfiguration(path.toFile());
            assertEquals(enabled,rewritten.getBoolean("npcs.guest.goals.1.work-interaction"));assertFalse(rewritten.contains("npcs.guest.goals.1.alternatives.1.work-interaction"));
            assertFalse(rewritten.contains("npcs.guest.goals.1.alternatives.2.work-interaction"));
        }
    }
}
