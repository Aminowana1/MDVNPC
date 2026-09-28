package com.mdvcraft.mdvnpc.routine;

import java.util.*;
import org.bukkit.*;

public record RoutineGoal(int order, Type type, WalkMode mode, int start, int end,
                          double speed, double radius, List<Point> points, Dialogue dialogue) {
    public enum Type { SLEEP, WALK, SIT, WORK }
    public enum WalkMode { TARGET, RANDOM, CYCLE }

    public record Dialogue(boolean enabled, double range, double intervalSeconds, double initialDelaySeconds,
                           boolean random, boolean lineOfSight, List<String> lines, boolean configured) {
        public Dialogue(boolean enabled,double range,double intervalSeconds,double initialDelaySeconds,boolean random,boolean lineOfSight,List<String> lines) {
            this(enabled,range,intervalSeconds,initialDelaySeconds,random,lineOfSight,lines,true);
        }
        public Dialogue {
            lines = List.copyOf(lines == null ? List.of() : lines);
            if (!Double.isFinite(range) || range < 0 || range > 64)
                throw new IllegalArgumentException("Rango de diálogo: 0..64");
            if (!Double.isFinite(intervalSeconds) || intervalSeconds < .5 || intervalSeconds > 86400)
                throw new IllegalArgumentException("Intervalo de diálogo: 0.5..86400 segundos");
            if (!Double.isFinite(initialDelaySeconds) || initialDelaySeconds < 0 || initialDelaySeconds > 86400)
                throw new IllegalArgumentException("Demora inicial de diálogo: 0..86400 segundos");
            if (lines.size() > 128) throw new IllegalArgumentException("Máximo 128 líneas de diálogo por goal");
        }
        public static Dialogue disabled() { return new Dialogue(false, 6, 40, 2, true, false, List.of(), false); }
    }

    /** Backwards-compatible constructor used by old code/tests and migrated routines. */
    public RoutineGoal(int order, Type type, WalkMode mode, int start, int end,
                       double speed, double radius, List<Point> points) {
        this(order, type, mode, start, end, speed, radius, points, Dialogue.disabled());
    }

    public RoutineGoal {
        Objects.requireNonNull(type); Objects.requireNonNull(mode); Objects.requireNonNull(dialogue);
        points = List.copyOf(points);
        if (order < 1 || order > 100 || start < 0 || start >= 1440 || end < 0 || end >= 1440)
            throw new IllegalArgumentException("Goal 1..100; horas 00:00..23:59");
        if (!Double.isFinite(speed) || speed < .2 || speed > 6 || !Double.isFinite(radius) || radius < 1 || radius > 128)
            throw new IllegalArgumentException("Velocidad 0.2..6 bloques/segundo; radio 1..128");
        if (points.isEmpty() || points.size() > 128 || (type == Type.SLEEP || type == Type.WORK) && points.size() != 1)
            throw new IllegalArgumentException("Dormir/trabajo: un punto; caminar/sentarse: 1..128 puntos");
        UUID world = points.getFirst().world();
        if (points.stream().anyMatch(p -> !p.world().equals(world))) throw new IllegalArgumentException("Todos los puntos deben estar en el mismo mundo");
    }
    public boolean target() { return type == Type.WALK && mode == WalkMode.TARGET; }
    public boolean contains(int minute) { return start == end || (start < end ? minute >= start && minute < end : minute >= start || minute < end); }
    public RoutineGoal withTimes(int from, int until) { return new RoutineGoal(order,type,mode,from,until,speed,radius,points,dialogue); }
    public RoutineGoal withSpeed(double value) { return new RoutineGoal(order,type,mode,start,end,value,radius,points,dialogue); }
    public RoutineGoal withRadius(double value) { return new RoutineGoal(order,type,mode,start,end,speed,value,points,dialogue); }
    public RoutineGoal withMode(WalkMode value) { return new RoutineGoal(order,type,value,start,end,speed,radius,points,dialogue); }
    public RoutineGoal withDialogue(Dialogue value) { return new RoutineGoal(order,type,mode,start,end,speed,radius,points,value); }

    public record Point(UUID world, int x, int y, int z, float yaw) {
        public Point {
            Objects.requireNonNull(world);
            if (Math.abs((long)x) > 29999984 || Math.abs((long)z) > 29999984 || y < -2048 || y > 2048 || !Float.isFinite(yaw))
                throw new IllegalArgumentException("Coordenadas de rutina fuera de límites");
        }
        public Location location(World w) { return new Location(w, x + .5, y, z + .5, yaw, 0); }
    }
}
