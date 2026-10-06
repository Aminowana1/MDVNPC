package com.mdvcraft.mdvnpc.work;

import org.bukkit.Location;
import org.bukkit.World;
import java.util.List;
import java.util.UUID;

/** Fishing positions use feet/surface coordinates and the administrator's horizontal direction. */
public record FishingDefinition(List<Point> shorePoints, Point dock, List<Point> boatPoints) {
    public static final int MAX_POINTS = 32;
    public FishingDefinition {
        shorePoints = shorePoints == null ? List.of() : List.copyOf(shorePoints);
        boatPoints = boatPoints == null ? List.of() : List.copyOf(boatPoints);
        if (shorePoints.size() > MAX_POINTS || boatPoints.size() > MAX_POINTS)
            throw new IllegalArgumentException("Pescador: máximo " + MAX_POINTS + " puntos por lista");
    }
    public static FishingDefinition defaults() { return new FishingDefinition(List.of(), null, List.of()); }
    public boolean complete() { return !shorePoints.isEmpty() && dock != null && !boatPoints.isEmpty(); }
    public record Point(UUID worldId, String worldName, double x, double y, double z, float yaw) {
        public Point {
            worldName = worldName == null ? "" : worldName;
            if (worldId == null && worldName.isBlank()) throw new IllegalArgumentException("El punto de pesca necesita mundo o UUID");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw)
                    || x < -29999984 || x > 29999984 || z < -29999984 || z > 29999984 || y < -2048 || y > 2048)
                throw new IllegalArgumentException("Coordenadas o dirección del punto de pesca inválidas");
        }
        public Location location(World world) {
            if (world == null || (worldId != null ? !worldId.equals(world.getUID()) : !worldName.equals(world.getName()))) return null;
            return new Location(world, x, y, z, yaw, 0);
        }
    }
}
