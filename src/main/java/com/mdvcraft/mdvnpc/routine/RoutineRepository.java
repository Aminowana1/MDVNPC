package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.storage.AtomicFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class RoutineRepository {
    public record Plan(boolean enabled, List<RoutineGoal> goals) { public Plan { goals = List.copyOf(goals); RoutineSchedule.validate(goals); } }
    public record Clock(double dayMinutes, double nightMinutes) {
        public Clock { if (!Double.isFinite(dayMinutes) || !Double.isFinite(nightMinutes) || dayMinutes < 1 || nightMinutes < 1 || dayMinutes > 1440 || nightMinutes > 1440) throw new IllegalArgumentException("Día/noche: 1..1440 minutos reales a 20 TPS"); }
    }
    public record Snapshot(Map<String, Plan> plans, Map<String, Clock> clocks) {}
    private final Path file;
    private Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    public RoutineRepository(Path folder) { file = folder.resolve("routines.yml"); }
    public Snapshot read() throws Exception {
        var yaml = new YamlConfiguration(); if (Files.exists(file)) yaml.load(file.toFile()); return parse(yaml);
    }
    public void install(Snapshot snapshot) { this.snapshot = snapshot; }
    public Snapshot snapshot() { return snapshot; }
    public void edit(Consumer<YamlConfiguration> edit) throws Exception {
        var yaml = new YamlConfiguration(); if (Files.exists(file)) yaml.load(file.toFile());
        edit.accept(yaml); Snapshot next = parse(yaml); AtomicFile.write(file, yaml.saveToString()); snapshot = next;
    }
    public void put(String npc, RoutineGoal goal) throws Exception {
        edit(y -> {
            String root = "npcs." + npc; String p = root + ".goals." + goal.order();
            if (!y.contains(root + ".enabled")) y.set(root + ".enabled", true);
            y.set(p, null); y.set(p + ".type", goal.type().name()); y.set(p + ".mode", goal.mode().name());
            y.set(p + ".from", RoutineSchedule.format(goal.start())); y.set(p + ".until", RoutineSchedule.format(goal.end()));
            y.set(p + ".speed", goal.speed()); y.set(p + ".radius", goal.radius());
            y.set(p + ".points", goal.points().stream().map(v -> Map.of("world", v.world().toString(), "x", v.x(), "y", v.y(), "z", v.z(), "yaw", v.yaw())).toList());
        });
    }
    public static Snapshot parse(YamlConfiguration y) {
        Map<String, Plan> plans = new LinkedHashMap<>();
        var root = y.getConfigurationSection("npcs");
        if (root != null) for (String id : root.getKeys(false)) {
            NpcParser.validateId(id); var section = Objects.requireNonNull(root.getConfigurationSection(id), "NPC inválido");
            List<RoutineGoal> goals = new ArrayList<>(); var gs = section.getConfigurationSection("goals");
            if (gs != null) for (String key : gs.getKeys(false)) {
                int order = Integer.parseInt(key); if (!Integer.toString(order).equals(key)) throw new IllegalArgumentException("Número no canónico: " + key);
                var g = Objects.requireNonNull(gs.getConfigurationSection(key), "Goal inválido");
                List<RoutineGoal.Point> points = new ArrayList<>();
                for (var point : g.getMapList("points")) points.add(new RoutineGoal.Point(UUID.fromString(String.valueOf(point.get("world"))),
                        integer(point.get("x")), integer(point.get("y")), integer(point.get("z")), point.containsKey("yaw") ? Float.parseFloat(point.get("yaw").toString()) : 0));
                goals.add(new RoutineGoal(order, RoutineGoal.Type.valueOf(g.getString("type", "").toUpperCase(Locale.ROOT)),
                        RoutineGoal.WalkMode.valueOf(g.getString("mode", "CYCLE").toUpperCase(Locale.ROOT)),
                        RoutineSchedule.parseHour(g.getString("from", "00:00")), RoutineSchedule.parseHour(g.getString("until", "00:00")),
                        number(g, "speed", 2.4), number(g, "radius", 20), points));
            }
            goals.sort(Comparator.comparingInt(RoutineGoal::order)); plans.put(id, new Plan(section.getBoolean("enabled", true), goals));
        }
        Map<String, Clock> clocks = new LinkedHashMap<>(); var clocksSection = y.getConfigurationSection("clocks");
        if (clocksSection != null) for (String name : clocksSection.getKeys(false)) {
            var c = Objects.requireNonNull(clocksSection.getConfigurationSection(name), "Reloj inválido");
            clocks.put(name, new Clock(number(c, "day-minutes", 10), number(c, "night-minutes", 10)));
        }
        return new Snapshot(Collections.unmodifiableMap(plans), Collections.unmodifiableMap(clocks));
    }
    private static int integer(Object value) { return Integer.parseInt(String.valueOf(value)); }
    private static double number(ConfigurationSection s, String key, double def) { return s.contains(key) ? Double.parseDouble(String.valueOf(s.get(key))) : def; }
}
