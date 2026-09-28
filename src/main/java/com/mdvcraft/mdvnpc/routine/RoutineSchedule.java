package com.mdvcraft.mdvnpc.routine;

import java.util.*;

/** Pure world-clock scheduling: 0 Minecraft ticks = 06:00. End times are exclusive. */
public final class RoutineSchedule {
    private RoutineSchedule() {}
    public static int parseHour(String text) {
        if (!text.matches("(?:[01]?\\d|2[0-3])(?::[0-5]\\d)?")) throw new IllegalArgumentException("Hora inválida: usa 00:00..23:59");
        String[] parts = text.split(":"); return Integer.parseInt(parts[0]) * 60 + (parts.length == 2 ? Integer.parseInt(parts[1]) : 0);
    }
    public static String format(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }
    public static int minute(long fullTime) { return (int)(Math.floorMod(fullTime + 6000, 24000) * 1440 / 24000); }
    public record Window(long occurrence, int timedOrder, List<RoutineGoal> chain) {}
    public static Window window(List<RoutineGoal> goals, long fullTime) {
        if (goals.isEmpty()) return null;
        long clock = fullTime + 6000;
        int minute = minute(fullTime);
        for (int i = 0; i < goals.size(); i++) {
            RoutineGoal g = goals.get(i);
            if (g.target() || !g.contains(minute)) continue;
            long startTicks = (g.start() * 24000L + 1439) / 1440;
            long day = Math.floorDiv(clock - startTicks, 24000);
            List<RoutineGoal> chain = new ArrayList<>();
            int previous = Math.floorMod(i - 1, goals.size());
            while (previous != i && goals.get(previous).target()) {
                chain.addFirst(goals.get(previous)); previous = Math.floorMod(previous - 1, goals.size());
            }
            chain.add(g);
            return new Window(day * 24000 + startTicks, g.order(), List.copyOf(chain));
        }
        if (goals.stream().allMatch(RoutineGoal::target)) return new Window(Math.floorDiv(clock, 24000) * 24000, -1, goals);
        return null;
    }
    public static void validate(List<RoutineGoal> goals) {
        Set<Integer> ids = new HashSet<>(); UUID world = null;
        for (RoutineGoal goal : goals) {
            if (!ids.add(goal.order())) throw new IllegalArgumentException("Número de goal repetido");
            UUID next = goal.points().getFirst().world();
            if (world != null && !world.equals(next)) throw new IllegalArgumentException("Una rutina no puede cruzar mundos");
            world = next;
        }
        for (int minute = 0; minute < 1440; minute++) {
            int count = 0;
            for (RoutineGoal goal : goals) if (!goal.target() && goal.contains(minute) && ++count > 1)
                throw new IllegalArgumentException("Horarios superpuestos a las " + format(minute));
        }
    }
}
