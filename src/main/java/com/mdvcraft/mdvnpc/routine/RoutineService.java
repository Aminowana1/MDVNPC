package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.PlayerFilter;
import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.*;
import org.bukkit.block.data.type.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class RoutineService {
    private final MdvNpcPlugin plugin;
    private final RoutineRepository repository;
    private final WorldClockManager clocks;
    private final DoorController doors;
    private final RoutineVisuals visuals;
    private final RoutineLook looks;
    private RoutineNavigator navigator;
    private BukkitTask task;
    private long ticks;
    private int cadence;
    private int clockElapsed;
    private UUID internalEntity;
    private boolean mounting;
    private final Map<String,State> states=new HashMap<>();
    private final Map<RoutineGoal.Point,String> occupied=new HashMap<>();
    private final Set<String> failed=new HashSet<>();
    private record DialogueKey(String npc,int goal,UUID player) {}
    private static final class DialogueState { long due,lastSeen; int nextLine; DialogueState(long due,long now){this.due=due;this.lastSeen=now;} }
    private record UnavailableKey(String npc,UUID player) {}
    private static final class UnavailableState { long due; int nextLine; }
    private final Map<DialogueKey,DialogueState> dialogueStates=new HashMap<>();
    private final Map<UnavailableKey,UnavailableState> unavailableStates=new HashMap<>();
    private static final class State {
        ActiveNpc npc; RoutineSchedule.Window window; int chain,point; RoutineGoal goal;
        RoutineGoal.Point destination; Location approach; RoutineVisuals.Pose pose;
        boolean working,paused; long nextPick, travelSince, nextPresence, nextDialogue, nextPoseCheck; String status="esperando";
        final RoutineLook.State look=new RoutineLook.State();
    }
    public RoutineService(MdvNpcPlugin plugin) throws Exception {
        this(plugin,null);
    }
    RoutineService(MdvNpcPlugin plugin,RoutineVisuals suppliedVisuals) throws Exception {
        this.plugin=plugin; repository=new RoutineRepository(plugin.getDataFolder().toPath());
        clocks=new WorldClockManager(plugin); doors=new DoorController(plugin);
        visuals=suppliedVisuals==null?new RoutineVisuals(plugin,this::teleport):suppliedVisuals;looks=new RoutineLook(plugin);
    }
    public RoutineRepository repository() { return repository; }
    public void start() {
        cadence=Math.max(1,Math.min(4,plugin.settings().messages().getInt("routines.movement-interval-ticks",2)));
        int nodes=Math.max(128,Math.min(8192,plugin.settings().messages().getInt("routines.max-search-nodes",2048)));
        int cache=Math.max(0,Math.min(128,plugin.settings().messages().getInt("routines.cached-routes",32)));
        navigator=new RoutineNavigator(doors,this::teleport,nodes,cache); failed.clear();
        clocks.configure(repository.snapshot().clocks());
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,cadence,cadence);
    }
    public void stop() {
        if(task!=null) {task.cancel();task=null;}
        for(String id:List.copyOf(states.keySet())) remove(id);
        if(navigator!=null) navigator.clear(); doors.close();
        dialogueStates.clear(); unavailableStates.clear();
    }
    public void close() { stop(); clocks.close(); }
    public void worldLoaded() { clocks.sync(); }
    public boolean enabled(String id) { var plan=repository.snapshot().plans().get(id); return plan!=null && plan.enabled() && !plan.goals().isEmpty(); }
    public List<RoutineGoal.Point> points(String id) {
        var plan=repository.snapshot().plans().get(id);
        return plan==null || !plan.enabled()?List.of():plan.goals().stream().flatMap(g -> g.points().stream()).toList();
    }
    public boolean internal(Entity e) { return e.getUniqueId().equals(internalEntity); }
    public boolean mounting(Entity e) { return mounting && internal(e); }
    public boolean isSeat(Entity e) { return visuals.isSeat(e); }
    public boolean liveSeat(Entity e) { return states.values().stream().anyMatch(s -> s.pose!=null && s.pose.seat==e); }
    public boolean claimed(RoutineGoal.Point point) { return occupied.containsKey(key(point)); }
    private static RoutineGoal.Point key(RoutineGoal.Point p) { return new RoutineGoal.Point(p.world(),p.x(),p.y(),p.z(),0); }
    public boolean teleport(ActiveNpc npc,Location to) {
        World world=to.getWorld();
        if(world==null || world!=npc.entity().getWorld() || !world.isChunkLoaded(to.getBlockX()>>4,to.getBlockZ()>>4)) return false;
        UUID previous=internalEntity; internalEntity=npc.entity().getUniqueId();
        try { return npc.entity().teleport(to); } finally { internalEntity=previous; }
    }
    private RoutineSchedule.Window window(String id,World world) {
        var plan=repository.snapshot().plans().get(id);
        if(plan==null || !plan.enabled() || plan.goals().isEmpty() || !plan.goals().getFirst().points().getFirst().world().equals(world.getUID())) return null;
        return RoutineSchedule.window(plan.goals(),world.getFullTime());
    }
    /** Recover by current world time when a destination chunk loads, with no forced chunk tickets. */
    public Location spawnLocation(NpcDefinition definition,World world) {
        var p=definition.position();
        if(!enabled(definition.id())) return new Location(world,p.x(),p.y(),p.z(),p.yaw(),p.pitch());
        var w=window(definition.id(),world);
        if(w==null) return new Location(world,p.x(),p.y(),p.z(),p.yaw(),p.pitch());
        var goal=w.chain().getFirst(); var terrain=new RoutineTerrain(world,doors);
        for(var point:goal.points()) {
            var node=terrain.approach(point,goal.type()==RoutineGoal.Type.SIT || goal.type()==RoutineGoal.Type.SLEEP);
            if(node!=null) { Location location=terrain.location(node); location.setYaw(point.yaw()); return location; }
        }
        return null;
    }
    public boolean canInteract(ActiveNpc npc) {
        if(!enabled(npc.definition().id())) return true;
        State s=states.get(npc.definition().id());
        if(s==null || !s.working || s.paused || s.npc!=npc || s.approach==null) return false;
        var w=window(npc.definition().id(),npc.entity().getWorld());
        return same(s.window,w) && npc.entity().getLocation().distanceSquared(s.approach)<.36;
    }
    public boolean canLook(ActiveNpc npc) { return !enabled(npc.definition().id()) || canInteract(npc); }
    public RoutineGoal activeGoal(ActiveNpc npc) {
        State s=states.get(npc.definition().id());
        return s!=null && s.npc==npc && !s.paused ? s.goal : null;
    }
    public void unavailable(ActiveNpc npc,Player player) {
        if(!enabled(npc.definition().id()) || canInteract(npc)) return;
        var cfg=npc.definition().interaction().unavailable(); if(cfg.lines().isEmpty()) return;
        long now=System.nanoTime(); var key=new UnavailableKey(npc.definition().id(),player.getUniqueId());
        var state=unavailableStates.computeIfAbsent(key,k->new UnavailableState()); if(now<state.due) return;
        int index=cfg.random()?ThreadLocalRandom.current().nextInt(cfg.lines().size()):state.nextLine;
        state.nextLine=(index+1)%cfg.lines().size();
        state.due=now+(long)(cfg.cooldownSeconds()*1_000_000_000L);
        String line=cfg.lines().get(index);
        player.sendMessage(Text.color(Text.placeholders(line,player,npc.definition())));
    }
    public void forget(UUID player) {
        dialogueStates.keySet().removeIf(k->k.player().equals(player));
        unavailableStates.keySet().removeIf(k->k.player().equals(player));
    }
    private void clearGoalDialogue(String npc) {
        dialogueStates.keySet().removeIf(k->k.npc().equals(npc));
    }
    private static boolean same(RoutineSchedule.Window a,RoutineSchedule.Window b) {
        return a==b || a!=null && b!=null && a.occurrence()==b.occurrence() && a.timedOrder()==b.timedOrder();
    }
    private void release(State s,boolean reposition) {
        looks.clear(s.npc,s.look);
        if(s.working) plugin.shops().invalidateNpc(s.npc.definition().id());
        s.working=false;
        if(s.pose!=null) { visuals.leave(s.pose,reposition);s.pose=null; }
        if(s.destination!=null) occupied.remove(key(s.destination),s.npc.definition().id());
        s.destination=null;s.approach=null; navigator.cancel(s.npc.definition().id());
    }
    public void remove(String id) {
        State s=states.remove(id); if(s!=null) release(s,false);
        clearGoalDialogue(id);
        unavailableStates.keySet().removeIf(k->k.npc().equals(id));
    }
    private void tick() {
        ticks+=cadence; clockElapsed+=cadence;
        if(clockElapsed>=10) {clocks.tick(clockElapsed);clockElapsed=0;}
        var manager=plugin.manager(); if(manager==null) return;
        for(ActiveNpc npc:manager.activeNpcs()) {
            String id=npc.definition().id(); if(!enabled(id) || failed.contains(id) || !npc.entity().isValid()) continue;
            try { update(npc); }
            catch(RuntimeException ex) {
                failed.add(id); try { remove(id); } catch(RuntimeException cleanup) { ex.addSuppressed(cleanup); }
                plugin.getLogger().log(java.util.logging.Level.SEVERE,"Rutina pausada por error: "+id+". Corrige y usa /mdvnpc reload",ex);
            }
        }
        int budget=Math.max(16,Math.min(2048,plugin.settings().messages().getInt("routines.search-nodes-per-tick",160)));
        navigator.searchBudget(budget,2_000_000);
        if(ticks%20<cadence) {
            doors.tick(ticks,false); if(plugin.routineCommands()!=null)plugin.routineCommands().prune();
            long now=System.nanoTime(); dialogueStates.values().removeIf(v->now>=v.due && now-v.lastSeen>60_000_000_000L);
        }
    }
    private void update(ActiveNpc npc) {
        String id=npc.definition().id(); State s=states.computeIfAbsent(id,k -> {State n=new State(); n.npc=npc;return n;});
        Location position=npc.entity().getLocation();
        double range=Math.max(16,Math.min(128,plugin.settings().messages().getDouble("routines.activation-range",48)));
        // Nearby-player queries every second; no per-tick whole-world scans.
        if(ticks>=s.nextPresence) {
            s.nextPresence=ticks+20;
            boolean absent=position.getWorld().getNearbyPlayers(position,range,p -> !p.isDead() && p.getGameMode()!=GameMode.SPECTATOR).isEmpty();
            if(absent) {
                if(!s.paused) {
                    // Keep furniture poses when the same schedule remains active. In particular,
                    // never wake and displace a sleeper just because its last observer left.
                    if(s.pose!=null) {looks.clear(npc,s.look);visuals.suspend(s.pose,ticks);navigator.cancel(id);}
                    else release(s,true);
                    clearGoalDialogue(id); s.nextDialogue=0;
                    s.paused=true;s.status="suspendido: sin jugadores cerca";
                }
                var dormantWindow=window(id,position.getWorld());
                if(s.pose!=null && (!same(s.window,dormantWindow) || !validFurniture(s.goal,s.destination,position.getWorld()))) {
                    release(s,true);s.window=null;s.goal=null;s.chain=0;s.point=0;s.nextPick=0;
                }
                if(s.pose!=null)return;
                // Off-screen recovery only into an already-loaded destination observed by a player.
                Location recovery=spawnLocation(npc.definition(),position.getWorld());
                if(recovery!=null && recovery.distanceSquared(position)>4
                        && !recovery.getWorld().getNearbyPlayers(recovery,range,p -> !p.isDead() && p.getGameMode()!=GameMode.SPECTATOR).isEmpty()
                        && teleport(npc,recovery)) {s.window=null;s.goal=null;s.chain=0;s.point=0;s.nextPick=0;s.paused=false;}
                return;
            }
            if(s.paused) {
                s.nextPick=0;s.nextDialogue=0;s.nextPoseCheck=0;
                // Only restore an ongoing sleep, never yesterday's pose after a time jump.
                if(s.pose!=null && s.pose.sleeping && same(s.window,window(id,position.getWorld()))) {
                    if(!visuals.restoreSleep(s.pose,true))release(s,true);
                }
            }
            s.paused=false;
        }
        if(s.paused) return;
        position=npc.position();
        var w=window(id,position.getWorld());
        if(!same(s.window,w)) { release(s,true); s.window=w; s.chain=0; s.point=0; s.goal=null; s.nextPick=0; }
        if(w==null || s.chain>=w.chain().size()) { s.status="fuera de horario / secuencia terminada"; return; }
        RoutineGoal goal=w.chain().get(s.chain);
        if(s.goal!=goal) {
            // Cada entrada a un goal comienza su propio intervalo/demora inicial. Sin esto,
            // un intervalo largo de ayer podría impedir que el NPC hable hoy.
            clearGoalDialogue(id);
            s.goal=goal; s.point=0; s.nextDialogue=0;
        }
        if(ticks>=s.nextDialogue) { s.nextDialogue=ticks+20; updateDialogue(npc,goal,System.nanoTime()); }
        if(s.pose!=null) {
            if(!validFurniture(goal,s.destination,position.getWorld()) || s.pose.seat!=null && (!s.pose.seat.isValid() || !npc.entity().isInsideVehicle())) {
                release(s,true);s.nextPick=ticks+100;return;
            }
            if(s.pose.sleeping && ticks>=s.nextPoseCheck) {
                s.nextPoseCheck=ticks+40;
                if(!visuals.restoreSleep(s.pose,false)) {release(s,true);s.nextPick=ticks+40;return;}
            }
            visuals.tick(s.pose,ticks);
            s.status=s.pose.sleeping?"durmiendo":s.pose.reading?"sentado: leyendo":"sentado";
            if(!s.pose.sleeping)looks.tick(npc,s.look,ticks,s.pose.bodyYaw,true,s.pose.reading);
            return;
        }
        if(s.working) { s.status="trabajando";return; }
        if(s.destination!=null && ticks-s.travelSince>1200) {
            release(s,true);s.nextPick=ticks+100;s.status="ruta inaccesible; esperando reintento";return;
        }
        if(s.destination==null) {
            if(ticks<s.nextPick) return;
            s.nextPick=ticks+100;
            s.destination=choose(goal,s,position);
            if(s.destination==null) {s.status="esperando destino disponible";return;}
            var terrain=new RoutineTerrain(position.getWorld(),doors);
            var node=terrain.approach(s.destination,goal.type()==RoutineGoal.Type.SIT || goal.type()==RoutineGoal.Type.SLEEP);
            if(node==null) {s.destination=null;s.status="destino descargado o sin acceso";return;}
            s.approach=terrain.location(node);s.approach.setYaw(s.destination.yaw());
            s.travelSince=ticks;
            if(goal.type()==RoutineGoal.Type.SIT || goal.type()==RoutineGoal.Type.SLEEP) occupied.put(key(s.destination),id);
        }
        Location beforeMove=npc.position();
        var result=navigator.move(npc,s.approach,goal.speed(),ticks,cadence);
        if(result==RoutineNavigator.Result.MOVING) {
            var moved=npc.position().toVector().subtract(beforeMove.toVector());moved.setY(0);
            float bodyYaw=moved.lengthSquared()>.00001?beforeMove.clone().setDirection(moved).getYaw():s.look.initialized?s.look.bodyYaw:beforeMove.getYaw();
            looks.tick(npc,s.look,ticks,bodyYaw,false,false);
        } else if(goal.type()==RoutineGoal.Type.WALK) {
            // Keep the occasional-glance timer across short waypoints; otherwise a route
            // made of short segments would reset it forever and never produce a glance.
            looks.tick(npc,s.look,ticks,s.look.initialized?s.look.bodyYaw:beforeMove.getYaw(),false,false);
        } else looks.clear(npc,s.look);
        s.status=result==RoutineNavigator.Result.WAITING?"esperando ruta (reintentos limitados)":"caminando a "+goal.type();
        if(result!=RoutineNavigator.Result.ARRIVED) return;
        switch(goal.type()) {
            case WORK -> { s.working=true; npc.anchor().setYaw(s.approach.getYaw()); npc.entity().setRotation(s.approach.getYaw(),0);s.status="trabajando"; }
            case WALK -> {
                s.point++;
                if(goal.target() && s.point>=goal.points().size()) {s.chain++;s.goal=null;s.point=0;}
                else if(s.point>=goal.points().size()) s.point=0;
                s.destination=null;s.approach=null;s.nextPick=ticks+(goal.mode()==RoutineGoal.WalkMode.RANDOM?40:0);
            }
            case SIT,SLEEP -> {
                UUID before=internalEntity; internalEntity=npc.entity().getUniqueId();mounting=true;
                try {s.pose=visuals.enter(npc,goal,s.destination,s.approach,ticks);}
                finally {internalEntity=before;mounting=false;}
                if(s.pose==null) {release(s,true);s.nextPick=ticks+100;}
                else s.status=goal.type()==RoutineGoal.Type.SLEEP?"durmiendo":"sentado";
            }
        }
    }
    private void updateDialogue(ActiveNpc npc,RoutineGoal goal,long now) {
        RoutineGoal.Dialogue dialogue=goal.dialogue();
        if(!dialogue.configured() && goal.type()==RoutineGoal.Type.WORK) {
            var legacy=npc.definition().dialogue();
            dialogue=new RoutineGoal.Dialogue(legacy.enabled(),legacy.range(),legacy.intervalSeconds(),legacy.initialDelaySeconds(),
                    legacy.random(),legacy.lineOfSight(),legacy.lines(),false);
        }
        if(!dialogue.enabled() || dialogue.lines().isEmpty() || dialogue.range()<=0) return;
        Collection<Player> players=npc.position().getWorld().getNearbyPlayers(npc.position(),dialogue.range(),
                player->PlayerFilter.accepts(player,plugin.settings()));
        final RoutineGoal.Dialogue activeDialogue=dialogue;
        for(Player player:players) {
            if(activeDialogue.lineOfSight() && !npc.entity().hasLineOfSight(player)) continue;
            var key=new DialogueKey(npc.definition().id(),goal.order(),player.getUniqueId());
            var state=dialogueStates.computeIfAbsent(key,k->new DialogueState(now+(long)(activeDialogue.initialDelaySeconds()*1_000_000_000L),now));
            state.lastSeen=now; if(now<state.due) continue;
            int index=activeDialogue.random()?ThreadLocalRandom.current().nextInt(activeDialogue.lines().size()):state.nextLine;
            state.nextLine=(index+1)%activeDialogue.lines().size();
            player.sendMessage(Text.color(Text.placeholders(activeDialogue.lines().get(index),player,npc.definition())));
            state.due=now+(long)(activeDialogue.intervalSeconds()*1_000_000_000L);
        }
    }
    private boolean validFurniture(RoutineGoal goal,RoutineGoal.Point point,World world) {
        if(point==null || !world.isChunkLoaded(point.x()>>4,point.z()>>4)) return false;
        var data=world.getBlockAt(point.x(),point.y(),point.z()).getBlockData();
        return goal.type()==RoutineGoal.Type.SLEEP ? data instanceof Bed : data instanceof Stairs;
    }
    private RoutineGoal.Point choose(RoutineGoal goal,State state,Location from) {
        if(goal.type()==RoutineGoal.Type.WALK && goal.mode()!=RoutineGoal.WalkMode.RANDOM) return goal.points().get(state.point);
        if(goal.type()==RoutineGoal.Type.WORK) return goal.points().getFirst();
        List<RoutineGoal.Point> candidates=new ArrayList<>(); RoutineGoal.Point nearest=null;double best=Double.MAX_VALUE;
        for(var point:goal.points()) {
            if(!point.world().equals(from.getWorld().getUID())) continue;
            if(goal.type()==RoutineGoal.Type.SIT || goal.type()==RoutineGoal.Type.SLEEP) {
                if(occupied.containsKey(key(point)) || !validFurniture(goal,point,from.getWorld())) continue;
                var data=from.getWorld().getBlockAt(point.x(),point.y(),point.z()).getBlockData();
                if(data instanceof Bed bed && bed.isOccupied()) continue;
                candidates.add(point);continue;
            }
            double distance=point.location(from.getWorld()).distanceSquared(from);
            if(distance<.25 && goal.points().size()>1) continue;
            if(distance<best) {best=distance;nearest=point;}
            if(distance<=goal.radius()*goal.radius()) candidates.add(point);
        }
        return candidates.isEmpty()?nearest:candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
    public String status(String id) {
        if(!enabled(id)) return "sin rutina activa";
        if(failed.contains(id)) return "error: revisa consola";
        State s=states.get(id); return s==null?"pendiente de cargar zona":s.status+(s.goal==null?"":"; goal "+s.goal.order());
    }
    public String metrics() { return "rutas cacheadas="+navigator.cachedRoutes()+", búsquedas pendientes="+navigator.searches()+", NPC con estado="+states.size(); }
}
