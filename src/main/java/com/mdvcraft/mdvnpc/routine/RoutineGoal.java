package com.mdvcraft.mdvnpc.routine;

import java.util.*;
import org.bukkit.*;

public record RoutineGoal(int order, Type type, WalkMode mode, int start, int end,
                          double speed, double radius, List<Point> points) {
    public enum Type { SLEEP, WALK, SIT, WORK }
    public enum WalkMode { TARGET, RANDOM, CYCLE }
    public RoutineGoal {
        Objects.requireNonNull(type); Objects.requireNonNull(mode);
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
    public record Point(UUID world, int x, int y, int z, float yaw) {
        public Point {
            Objects.requireNonNull(world);
            if (Math.abs((long)x) > 29999984 || Math.abs((long)z) > 29999984 || y < -2048 || y > 2048 || !Float.isFinite(yaw))
                throw new IllegalArgumentException("Coordenadas de rutina fuera de límites");
        }
        public Location location(World w) { return new Location(w, x + .5, y, z + .5, yaw, 0); }
    }
}
