package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Location;
import java.util.function.BiPredicate;

/** Controlled floor-loss recovery: NoAI mobs do not run vanilla travel/gravity. */
public final class RoutineGravity {
    public enum Result { STABLE, FALLING, BLOCKED }
    private static final double FLOOR_EPSILON=.02, MAXIMUM_SCAN=8, MAXIMUM_STEP=.4;
    private static final int IDLE_CHECK_TICKS=20;
    public static final class State {
        long nextCheck;
        double velocity;
        boolean falling;
        Location checkedAt;
        RoutineTerrain terrain;
        Result result=Result.STABLE;
        public boolean falling(){return falling;}
    }
    private final DoorController doors;
    private final BiPredicate<ActiveNpc,Location> teleport;
    public RoutineGravity(DoorController doors,BiPredicate<ActiveNpc,Location> teleport) {
        this.doors=doors;this.teleport=teleport;
    }
    public void reset(State state) {
        state.nextCheck=0;state.velocity=0;state.falling=false;
        state.checkedAt=null;state.terrain=null;state.result=Result.STABLE;
    }
    public Result tick(ActiveNpc npc,State state,long tick,int cadence) {
        Location at=npc.position();
        if(at.getWorld()==null || npc.entity().isInsideVehicle()) {reset(state);return Result.STABLE;}
        boolean displaced=state.checkedAt==null || state.checkedAt.getWorld()!=at.getWorld()
                || state.checkedAt.distanceSquared(at)>.0025;
        if(!state.falling && !displaced && tick<state.nextCheck)return state.result;
        if(state.terrain==null || state.checkedAt==null || state.checkedAt.getWorld()!=at.getWorld())
            state.terrain=new RoutineTerrain(at.getWorld(),doors);
        state.checkedAt=at.clone();state.nextCheck=tick+IDLE_CHECK_TICKS;
        try(RoutineTerrain.Update ignored=state.terrain.beginUpdate()) {
        // Most NPCs still have a floor: widen the vertical probe only after support loss.
        RoutineTerrain.FallColumn column=state.terrain.fallColumn(at.getX(),at.getY(),at.getZ(),FLOOR_EPSILON);
        if(!column.safe())return blocked(state);
        double support=column.support();
        if(Double.isFinite(support) && at.getY()-support<=FLOOR_EPSILON) {
            state.velocity=0;state.falling=false;return state.result=Result.STABLE;
        }
        column=state.terrain.fallColumn(at.getX(),at.getY(),at.getZ(),MAXIMUM_SCAN);
        if(!column.safe())return blocked(state);
        support=column.support();
        state.falling=true;
        // Do not catch up a whole second of idle/reaction time in one visible movement.
        int elapsed=Math.max(1,Math.min(4,cadence));double amount=0;
        for(int i=0;i<elapsed;i++) {
            state.velocity=Math.min(.4,(state.velocity+.08)*.98);amount+=state.velocity;
        }
        double floor=Double.isFinite(support)?support:at.getWorld().getMinHeight();
        double drop=Math.min(Math.min(MAXIMUM_STEP,amount),at.getY()-floor);
        if(drop<=1e-7)return blocked(state);
        int samples=Math.max(1,(int)Math.ceil(drop/.08));
        for(int i=1;i<=samples;i++) {
            if(!state.terrain.fits(at.getX(),at.getY()-drop*i/samples,at.getZ(),false))return blocked(state);
        }
        Location next=at.clone().add(0,-drop,0);
        if(!teleport.test(npc,next))return blocked(state);
        Location moved=npc.position();
        if(moved.getWorld()!=at.getWorld() || at.distanceSquared(moved)<1e-8)return blocked(state);
        state.checkedAt=moved.clone();state.nextCheck=tick+elapsed;
        return state.result=Result.FALLING;
        }
    }
    private static Result blocked(State state) {
        state.velocity=0;state.falling=false;return state.result=Result.BLOCKED;
    }
}
