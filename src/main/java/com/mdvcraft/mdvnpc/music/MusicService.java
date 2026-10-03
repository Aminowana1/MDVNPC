package com.mdvcraft.mdvnpc.music;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Mode;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/** Main-thread only. Routine lifecycle supplies working musicians; never scans all NPCs or players. */
public final class MusicService {
    static final List<String> REPERTOIRE=List.of("tourdion","jabali","farol","romeria","cuervo","roble");
    private final MdvNpcPlugin plugin;
    private final MusicianVisuals visuals;
    private final List<Song> songs=REPERTOIRE.stream().map(Song::load).toList();
    private final Map<String,ActiveNpc> workers=new HashMap<>();
    private final Set<String> visualFailures=new HashSet<>();
    private Map<Set<String>,Session> sessions=new HashMap<>();
    private final Map<Cell,List<ActiveNpc>> performers=new HashMap<>();
    private BukkitTask task;
    private long tick;
    private double groupRadius=8,audioRadius=14;
    private record Cell(UUID world,int x,int z) {}
    private static final class Member {
        final ActiveNpc npc;
        List<Player> audience=List.of();
        Member(ActiveNpc npc){this.npc=npc;}
    }
    private static final class Session {
        final List<Member> members;
        Song song; long start;
        Session(List<Member> members){this.members=members;}
    }
    public MusicService(MdvNpcPlugin plugin){this(plugin,new MusicianVisuals(plugin));}
    MusicService(MdvNpcPlugin plugin,MusicianVisuals visuals){this.plugin=plugin;this.visuals=Objects.requireNonNull(visuals);}
    public void working(ActiveNpc npc) {
        if(!npc.definition().mode().musician())return;
        String id=npc.definition().id();
        ActiveNpc prior=workers.put(id,npc);
        if(prior!=npc){if(prior!=null)removeVisuals(id);visualFailures.remove(id);}
        if(task==null) {
            groupRadius=bounded("music.group-radius",8,2,16);
            audioRadius=bounded("music.audio-radius",14,12,15);
            // One clock, independent of the number of musicians. First grouping collects arrivals for one second.
            task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,1);
        }
    }
    private double bounded(String key,double fallback,double min,double max) {
        double value=plugin.settings().messages().getDouble(key,fallback);
        return Double.isFinite(value)?Math.max(min,Math.min(max,value)):fallback;
    }
    public void remove(String id) {
        removeVisuals(id);
        workers.remove(id);
        visualFailures.remove(id);
        if(workers.isEmpty())stop();
    }
    public void stop() {
        if(task!=null)task.cancel(); task=null;
        try{visuals.stop();}catch(RuntimeException | LinkageError ex){plugin.getLogger().log(Level.WARNING,"No se pudieron restaurar todos los efectos musicales",ex);}
        workers.clear();sessions.clear();performers.clear();tick=0;
        visualFailures.clear();
    }
    /** Give temporary reactions/beer ownership before they capture equipment or head orientation. */
    public void suspendVisuals(String id){removeVisuals(id);}
    public boolean isAnimating(ActiveNpc npc){return npc!=null && !visualFailures.contains(npc.definition().id()) && eligible(npc) && visuals.isAnimating(npc);}
    private void removeVisuals(String id){
        try{visuals.remove(id);}catch(RuntimeException | LinkageError ex){visualFailure(id,ex);}
    }
    private void visualFailure(String id,Throwable ex){
        if(visualFailures.add(id))plugin.getLogger().log(Level.WARNING,"Animación musical pausada para "+id+"; el audio continúa. Revisa el error y usa /mdvnpc reload",ex);
    }
    private void animate(Member member,long elapsed,boolean played){
        String id=member.npc.definition().id();if(visualFailures.contains(id))return;
        try{visuals.tick(member.npc,member.audience,audioRadius,elapsed,tick,played);}
        catch(RuntimeException | LinkageError ex){visualFailure(id,ex);removeVisuals(id);}
    }
    private boolean eligible(ActiveNpc npc) {
        String id=npc.definition().id();
        return workers.get(id)==npc && npc.entity().isValid() && plugin.routines().canInteract(npc)
                && (plugin.traits()==null || !plugin.traits().busy(id))
                && (plugin.reactions()==null || !plugin.reactions().busy(id));
    }
    private Cell cell(Location p) {
        return new Cell(p.getWorld().getUID(),(int)Math.floor(p.getX()/groupRadius),(int)Math.floor(p.getZ()/groupRadius));
    }
    /** Indexed local lookup for seated spectators; refreshed with the existing session maintenance. */
    public ActiveNpc nearestPerformer(Location from,double radius) {
        if(from.getWorld()==null || performers.isEmpty())return null;
        radius=Math.min(audioRadius,Math.max(0,radius));
        Cell center=cell(from);int span=(int)Math.ceil(radius/groupRadius);
        ActiveNpc nearest=null;double best=radius*radius;
        for(int dx=-span;dx<=span;dx++)for(int dz=-span;dz<=span;dz++)
            for(ActiveNpc npc:performers.getOrDefault(new Cell(center.world(),center.x()+dx,center.z()+dz),List.of())) {
                if(!isPerforming(npc))continue;
                Location p=npc.position();if(p.getWorld()!=from.getWorld())continue;
                double distance=from.distanceSquared(p);
                if(distance<=best){best=distance;nearest=npc;}
            }
        return nearest;
    }
    public boolean isPerforming(ActiveNpc npc) {
        return npc!=null && eligible(npc);
    }
    private void regroup() {
        Map<String,Location> positions=new HashMap<>();
        Map<Cell,Set<String>> grid=new HashMap<>();
        performers.clear();
        for(var e:workers.entrySet())if(eligible(e.getValue())) {
            Location p=e.getValue().entity().getLocation();positions.put(e.getKey(),p);
            grid.computeIfAbsent(cell(p),k->new HashSet<>()).add(e.getKey());
            performers.computeIfAbsent(cell(p),k->new ArrayList<>()).add(e.getValue());
        }
        Map<Set<String>,Session> next=new HashMap<>();
        Set<String> remaining=new TreeSet<>(positions.keySet());
        while(!remaining.isEmpty()) {
            String seed=remaining.iterator().next(); remaining.remove(seed);
            Set<String> group=new HashSet<>();group.add(seed);
            ArrayDeque<String> queue=new ArrayDeque<>();queue.add(seed);
            grid.get(cell(positions.get(seed))).remove(seed);
            // Connected components; spatial buckets avoid comparing unrelated ensembles.
            while(!queue.isEmpty()) {
                Location p=positions.get(queue.remove());Cell c=cell(p);
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                    var bucket=grid.get(new Cell(c.world(),c.x()+dx,c.z()+dz));if(bucket==null)continue;
                    var it=bucket.iterator();
                    while(it.hasNext()) {
                        String id=it.next();
                        if(p.distanceSquared(positions.get(id))<=groupRadius*groupRadius) {
                            it.remove();remaining.remove(id);group.add(id);queue.add(id);
                        }
                    }
                }
            }
            Set<String> key=Set.copyOf(group);
            Session session=sessions.get(key);
            if(session==null || session.members.stream().anyMatch(m->workers.get(m.npc.definition().id())!=m.npc)) {
                session=new Session(group.stream().sorted().map(id->new Member(workers.get(id))).toList());
                choose(session,tick+2);
            }
            for(Member member:session.members) {
                Location p=positions.get(member.npc.definition().id());
                member.audience=List.copyOf(p.getWorld().getNearbyPlayers(p,audioRadius));
            }
            next.put(key,session);
        }
        sessions=next;
    }
    private void choose(Session s,long start) {
        s.song=songs.get(ThreadLocalRandom.current().nextInt(songs.size()));s.start=start;
    }
    private void tick() {
        // Includes off-duty/busy workers excluded by regroup: no stale instrument or head pose.
        if(tick%4==0)for(var worker:workers.values())if(!eligible(worker))removeVisuals(worker.definition().id());
        if(tick%20==0)regroup();
        for(Session session:sessions.values()) {
            long elapsed=tick-session.start;
            if(elapsed>=session.song.duration()) {choose(session,tick+20);elapsed=tick-session.start;}
            var notes=session.song.frames().get((int)elapsed);
            boolean flutePlayed=false,guitarPlayed=false;
            if(notes!=null)for(var note:notes){if(note.flute())flutePlayed=true;else guitarPlayed=true;}
            for(Member member:session.members) {
                if(!eligible(member.npc)){removeVisuals(member.npc.definition().id());continue;}
                boolean flute=member.npc.definition().mode()==Mode.MUSICIAN_FLUTE;
                boolean played=flute?flutePlayed:guitarPlayed;
                animate(member,elapsed,played);
                if(member.audience.isEmpty() || !played)continue;
                Location origin=member.npc.entity().getLocation();
                for(Player player:member.audience) {
                    if(!audible(origin,player,audioRadius))continue;
                    for(var note:notes)if(note.flute()==flute)
                        player.playSound(origin,flute?Sound.BLOCK_NOTE_BLOCK_FLUTE:Sound.BLOCK_NOTE_BLOCK_GUITAR,
                                SoundCategory.RECORDS,note.volume(),note.pitch());
                }
            }
        }
        tick++;
    }
    static boolean audible(Location origin,Player player,double radius) {
        return player.isOnline() && !player.isDead() && player.getWorld()==origin.getWorld()
                && player.getLocation().distanceSquared(origin)<=radius*radius;
    }
}
