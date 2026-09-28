package com.mdvcraft.mdvnpc.config;

import org.bukkit.configuration.file.YamlConfiguration;

public record Settings(int intervalTicks, int lookIntervalTicks, boolean ignoreInvisible, boolean ignoreSpectators,
                       double rotationThreshold, YamlConfiguration messages) {
    public static Settings parse(YamlConfiguration yaml) {
        for (String key : java.util.List.of("update-interval-ticks", "look-update-interval-ticks")) {
            Object value = yaml.get(key);
            if (value != null && (!(value instanceof Number number) || number.doubleValue() != number.intValue()))
                throw new IllegalArgumentException(key + " debe ser entero");
        }
        int ticks = yaml.getInt("update-interval-ticks", 10);
        int lookTicks = yaml.getInt("look-update-interval-ticks", ticks);
        if (lookTicks < 1 || lookTicks > 200) throw new IllegalArgumentException("look-update-interval-ticks: usar 1..200");
        double threshold = yaml.getDouble("rotation-threshold-degrees", 3);
        if (ticks < 1 || ticks > 200) throw new IllegalArgumentException("update-interval-ticks: usar 1..200");
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 180)
            throw new IllegalArgumentException("rotation-threshold-degrees: usar 0..180");
        return new Settings(ticks, lookTicks, yaml.getBoolean("ignore-invisible-players", true),
                yaml.getBoolean("ignore-spectators", true), threshold, yaml);
    }
}
