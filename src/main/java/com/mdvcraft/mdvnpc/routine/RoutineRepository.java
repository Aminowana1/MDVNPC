package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.storage.AtomicFile;
import com.mdvcraft.mdvnpc.storage.NpcPaths;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Per-NPC routine persistence. Legacy routines.yml is imported automatically. */
public final class RoutineRepository {
    public record Plan(boolean enabled, List<RoutineGoal> goals) { public Plan { goals = List.copyOf(goals); RoutineSchedule.validate(goals); } }
    public record Clock(double dayMinutes, double nightMinutes) {
        public Clock { if (!Double.isFinite(dayMinutes) || !Double.isFinite(nightMinutes) || dayMinutes < 1 || nightMinutes < 1 || dayMinutes > 1440 || nightMinutes > 1440) throw new IllegalArgumentException("Día/noche: 1..1440 minutos reales a 20 TPS"); }
    }
    public record Snapshot(Map<String, Plan> plans, Map<String, Clock> clocks) {}
    private final Path folder;
    private final Path npcRoot;
    private final Path clocksFile;
    private Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    public RoutineRepository(Path folder) {
        this.folder = folder;
        this.npcRoot = NpcPaths.root(folder);
        this.clocksFile = folder.resolve("clocks.yml");
    }
    public Snapshot read() throws Exception {
        Files.createDirectories(npcRoot); migrateLegacy(); return parse(aggregate());
    }
    public void install(Snapshot snapshot) { this.snapshot = snapshot; }
    public Snapshot snapshot() { return snapshot; }
    public void edit(Consumer<YamlConfiguration> edit) throws Exception {
        Files.createDirectories(npcRoot); migrateLegacy();
        YamlConfiguration yaml = aggregate();
        Snapshot before = parse(yaml);
        edit.accept(yaml);
        Snapshot next = parse(yaml);
        persist(yaml, before, next);
        snapshot = next;
    }
    public void put(String npc, RoutineGoal goal) throws Exception {
        edit(y -> {
            String root = "npcs." + npc; String p = root + ".goals." + goal.order();
            if (!y.contains(root + ".enabled")) y.set(root + ".enabled", true);
            writeGoal(y,p,goal,true);
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
                RoutineGoal base = readGoal(g,order,RoutineSchedule.parseHour(g.getString("from", "00:00")),RoutineSchedule.parseHour(g.getString("until", "00:00")));
                List<RoutineGoal> alternatives = new ArrayList<>(); var options = g.getConfigurationSection("alternatives");
                if (options != null) {
                    if (options.getKeys(false).size() >= RoutineGoal.MAX_CHOICES) throw new IllegalArgumentException("Demasiadas opciones de goal");
                    for (String option : options.getKeys(false).stream().sorted(Comparator.comparingInt(Integer::parseInt)).toList()) {
                        int index = Integer.parseInt(option);
                        if (index != alternatives.size()+1 || !Integer.toString(index).equals(option)) throw new IllegalArgumentException("Opciones numeradas consecutivamente desde 1");
                        var sectionOption = Objects.requireNonNull(options.getConfigurationSection(option),"Opción inválida");
                        if (sectionOption.contains("alternatives")) throw new IllegalArgumentException("Una opción no puede contener otras opciones");
                        alternatives.add(readGoal(sectionOption,order,base.start(),base.end()));
                    }
                }
                goals.add(base.withAlternatives(alternatives).withRandomChoice(g.getBoolean("random-choice",!alternatives.isEmpty())));
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

    private static RoutineGoal readGoal(ConfigurationSection g,int order,int start,int end) {
        List<RoutineGoal.Point> points = new ArrayList<>();
        for (var point : g.getMapList("points")) points.add(new RoutineGoal.Point(UUID.fromString(String.valueOf(point.get("world"))),
                integer(point.get("x")),integer(point.get("y")),integer(point.get("z")),point.containsKey("yaw")?Float.parseFloat(point.get("yaw").toString()):0));
        var d = g.getConfigurationSection("dialogue");
        RoutineGoal.Dialogue dialogue = d == null ? RoutineGoal.Dialogue.disabled() : new RoutineGoal.Dialogue(d.getBoolean("enabled",false),number(d,"range",6),
                number(d,"interval-seconds",40),number(d,"initial-delay-seconds",2),d.getBoolean("random",true),d.getBoolean("require-line-of-sight",false),d.getStringList("lines"));
        return new RoutineGoal(order,RoutineGoal.Type.valueOf(g.getString("type","").toUpperCase(Locale.ROOT)),RoutineGoal.WalkMode.valueOf(g.getString("mode","CYCLE").toUpperCase(Locale.ROOT)),
                start,end,number(g,"speed",2.4),number(g,"radius",20),points,dialogue);
    }
    private static void writeGoal(YamlConfiguration y,String p,RoutineGoal goal,boolean schedule) {
        y.set(p,null); y.set(p+".type",goal.type().name()); y.set(p+".mode",goal.mode().name());
        if (schedule) { y.set(p+".from",RoutineSchedule.format(goal.start())); y.set(p+".until",RoutineSchedule.format(goal.end())); }
        y.set(p+".speed",goal.speed()); y.set(p+".radius",goal.radius());
        y.set(p+".points",goal.points().stream().map(v->Map.of("world",v.world().toString(),"x",v.x(),"y",v.y(),"z",v.z(),"yaw",v.yaw())).toList());
        var d = goal.dialogue();
        if (d.configured()) {
            y.set(p+".dialogue.enabled",d.enabled()); y.set(p+".dialogue.range",d.range()); y.set(p+".dialogue.interval-seconds",d.intervalSeconds());
            y.set(p+".dialogue.initial-delay-seconds",d.initialDelaySeconds()); y.set(p+".dialogue.random",d.random());
            y.set(p+".dialogue.require-line-of-sight",d.lineOfSight()); y.set(p+".dialogue.lines",d.lines());
        }
        if (!goal.alternatives().isEmpty()) {
            y.set(p+".random-choice",goal.randomChoice());
            for (int index=1;index<goal.choiceCount();index++) writeGoal(y,p+".alternatives."+index,goal.choice(index),false);
        }
    }

    private YamlConfiguration aggregate() throws Exception {
        YamlConfiguration y = new YamlConfiguration(); y.createSection("npcs");
        if (Files.isDirectory(npcRoot)) try (var dirs = Files.list(npcRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                String id = dir.getFileName().toString(); NpcParser.validateId(id);
                Path file = dir.resolve("routines.yml"); if (!Files.exists(file)) continue;
                YamlConfiguration local = load(file); var section = local.getConfigurationSection("npcs." + id);
                if (section == null) throw new IllegalArgumentException("Falta npcs." + id + " en " + file);
                copy(section, y, "npcs." + id);
            }
        }
        if (Files.exists(clocksFile)) {
            YamlConfiguration clocks = load(clocksFile); var section = clocks.getConfigurationSection("clocks");
            if (section != null) copy(section, y, "clocks");
        }
        return y;
    }

    private void persist(YamlConfiguration aggregate, Snapshot before, Snapshot next) throws Exception {
        for (var entry : next.plans().entrySet()) {
            if (!Objects.equals(before.plans().get(entry.getKey()), entry.getValue())) writePlan(aggregate, entry.getKey());
        }
        for (String id : before.plans().keySet()) if (!next.plans().containsKey(id)) {
            Files.deleteIfExists(NpcPaths.routines(folder, id)); NpcPaths.cleanupIfEmpty(folder, id);
        }
        if (!Objects.equals(before.clocks(), next.clocks())) {
            if (next.clocks().isEmpty()) Files.deleteIfExists(clocksFile);
            else {
                YamlConfiguration clocks = new YamlConfiguration(); clocks.createSection("clocks");
                var cs = aggregate.getConfigurationSection("clocks"); if (cs != null) copy(cs, clocks, "clocks");
                AtomicFile.write(clocksFile, clocks.saveToString());
            }
        }
    }
    private void writePlan(YamlConfiguration aggregate, String id) throws Exception {
        var section = aggregate.getConfigurationSection("npcs." + id); if (section == null) return;
        YamlConfiguration local = new YamlConfiguration(); local.createSection("npcs"); copy(section, local, "npcs." + id);
        parse(local); NpcPaths.ensure(folder, id); AtomicFile.write(NpcPaths.routines(folder, id), local.saveToString());
    }
    private void migrateLegacy() throws Exception {
        Path legacy = folder.resolve("routines.yml"); if (!Files.exists(legacy)) return;
        YamlConfiguration old = load(legacy); Snapshot parsed = parse(old);
        for (String id : parsed.plans().keySet()) {
            Path target = NpcPaths.routines(folder, id); if (Files.exists(target)) continue;
            var section = old.getConfigurationSection("npcs." + id); if (section == null) continue;
            YamlConfiguration local = new YamlConfiguration(); local.createSection("npcs"); copy(section, local, "npcs." + id);
            NpcPaths.ensure(folder, id); AtomicFile.write(target, local.saveToString());
        }
        if (!Files.exists(clocksFile) && !parsed.clocks().isEmpty()) {
            YamlConfiguration c = new YamlConfiguration(); var section = old.getConfigurationSection("clocks");
            if (section != null) copy(section, c, "clocks"); AtomicFile.write(clocksFile, c.saveToString());
        }
        Files.move(legacy, folder.resolve("routines.yml.legacy-backup"), StandardCopyOption.REPLACE_EXISTING);
    }
    private static YamlConfiguration load(Path path) throws Exception { var y = new YamlConfiguration(); y.load(path.toFile()); return y; }
    private static void copy(ConfigurationSection from, YamlConfiguration to, String target) {
        to.set(target, null); to.createSection(target); copyInto(from, Objects.requireNonNull(to.getConfigurationSection(target)));
    }
    private static void copyInto(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) { var child = from.getConfigurationSection(key); if (child != null) { var next = to.createSection(key); copyInto(child,next); } else to.set(key,from.get(key)); }
    }
    private static int integer(Object value) { return Integer.parseInt(String.valueOf(value)); }
    private static double number(ConfigurationSection s, String key, double def) { return s.contains(key) ? Double.parseDouble(String.valueOf(s.get(key))) : def; }
}
