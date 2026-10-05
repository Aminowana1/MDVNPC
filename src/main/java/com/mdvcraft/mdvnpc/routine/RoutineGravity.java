package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.bukkit.Location;
import java.util.function.BiPredicate;

/** Controlled floor recovery: NoAI mobs do not run vanilla travel/gravity. */
public final class RoutineGravity {
    public enum Result {
        STABLE, FALLING, LIFTING, BLOCKED;
        public boolean moving(){return this==FALLING || this==LIFTING;}
    }
    private static final double FLOOR_EPSILON=.02, MAXIMUM_SCAN=8, MAXIMUM_STEP=.4;
    private static final int IDLE_CHECK_TICKS=20;
    public static final class State {
        long nextCheck;
        double velocity;
        boolean falling;
        Location checkedAt;
        RoutineTerrain terrain;
        Node liftSupport;
        double liftHeight;
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
        state.liftSupport=null;state.liftHeight=0;
        state.checkedAt=null;state.terrain=null;state.result=Result.STABLE;
    }
    public Result tick(ActiveNpc npc,State state,long tick,int cadence) {
        return tick(npc,state,tick,cadence,2.4);
    }
    public Result tick(ActiveNpc npc,State state,long tick,int cadence,double speed) {
        Location at=npc.position();
        if(at.getWorld()==null || npc.entity().isInsideVehicle()) {reset(state);return Result.STABLE;}
        boolean displaced=state.checkedAt==null || state.checkedAt.getWorld()!=at.getWorld()
                || state.checkedAt.distanceSquared(at)>.0025;
        // A push or teleport must not inherit the previous entity position's escape floor.
        if(state.liftSupport!=null && (state.checkedAt==null || state.checkedAt.getWorld()!=at.getWorld()
                || state.checkedAt.distanceSquared(at)>1e-8)) {
            state.liftSupport=null;state.falling=false;displaced=true;
        }
        if(!state.falling && !displaced && tick<state.nextCheck)return state.result;
        if(state.terrain==null || state.checkedAt==null || state.checkedAt.getWorld()!=at.getWorld())
            state.terrain=new RoutineTerrain(at.getWorld(),doors);
        state.checkedAt=at.clone();state.nextCheck=tick+IDLE_CHECK_TICKS;
        try(RoutineTerrain.Update ignored=state.terrain.beginUpdate()) {
        if(state.liftSupport!=null) {
            double top=state.terrain.supportHeightAtNode(state.liftSupport,at.getX(),at.getZ());
            if(state.terrain.partialSupport(state.liftSupport,at.getX(),at.getZ()) && Math.abs(top-state.liftHeight)<1e-7
                    && top>=at.getY()-1e-7 && top-at.getY()<=.5100001)
                return lift(npc,state,at,tick,cadence,speed);
            state.liftSupport=null;state.falling=false;
        }
        // Most NPCs still have a floor: widen the vertical probe only after support loss.
        RoutineTerrain.FallColumn column=state.terrain.fallColumn(at.getX(),at.getY(),at.getZ(),FLOOR_EPSILON);
        // Restored feet, or a newly placed carpet/slab, may overlap only the known partial
        // support. Persist its target across small steps; the body epsilon is not arrival.
        if(!column.safe() || Double.isFinite(column.support()) && column.support()>at.getY()+1e-7) {
            Node supportNode=state.terrain.near(at);
            if(supportNode!=null && state.terrain.partialSupport(supportNode,at.getX(),at.getZ())) {
                double top=state.terrain.supportHeightAtNode(supportNode,at.getX(),at.getZ()),rise=top-at.getY();
                if(rise>1e-7 && rise<=.5100001
                        && state.terrain.fitsPartialEscape(at.getX(),at.getY(),at.getZ(),supportNode)
                        && state.terrain.fits(at.getX(),top,at.getZ(),false)) {
                    state.liftSupport=supportNode;state.liftHeight=top;
                    return lift(npc,state,at,tick,cadence,speed);
                }
            }
            if(!column.safe())return blocked(state);
        }
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
    private Result lift(ActiveNpc npc,State state,Location at,long tick,int cadence,double speed) {
        double remaining=state.liftHeight-at.getY();
        if(remaining<=1e-7) {
            if(!state.terrain.fits(at.getX(),state.liftHeight,at.getZ(),false))return blocked(state);
            state.liftSupport=null;state.velocity=0;state.falling=false;
            return state.result=Result.STABLE;
        }
        if(!state.terrain.fitsPartialEscape(at.getX(),at.getY(),at.getZ(),state.liftSupport)
                || !state.terrain.fits(at.getX(),state.liftHeight,at.getZ(),false))return blocked(state);
        int elapsed=Math.max(1,Math.min(4,cadence));
        if(!Double.isFinite(speed) || speed<=0)return blocked(state);
        double rise=Math.min(remaining,Math.min(MAXIMUM_STEP,speed*elapsed/20.0));
        if(rise<=1e-7)return blocked(state);
        int samples=Math.max(1,(int)Math.ceil(rise/.08));
        for(int i=1;i<=samples;i++) {
            double y=at.getY()+rise*i/samples;
            if(!state.terrain.fitsPartialEscape(at.getX(),y,at.getZ(),state.liftSupport))return blocked(state);
        }
        Location next=at.clone().add(0,rise,0);
        if(!teleport.test(npc,next))return blocked(state);
        Location moved=npc.position();
        if(moved.getWorld()!=at.getWorld() || at.distanceSquared(moved)<1e-8)return blocked(state);
        state.checkedAt=moved.clone();state.nextCheck=tick+elapsed;state.velocity=0;state.falling=true;
        return state.result=Result.LIFTING;
    }
    private static Result blocked(State state) {
        state.velocity=0;state.falling=false;state.liftSupport=null;return state.result=Result.BLOCKED;
    }
}
