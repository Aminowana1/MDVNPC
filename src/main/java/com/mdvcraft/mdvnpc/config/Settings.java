package com.mdvcraft.mdvnpc.config;

import org.bukkit.configuration.file.YamlConfiguration;

public record Settings(int intervalTicks, boolean ignoreInvisible, boolean ignoreSpectators,
                       double rotationThreshold, YamlConfiguration messages) {
    public static Settings parse(YamlConfiguration yaml) {
        int ticks = yaml.getInt("update-interval-ticks", 10);
        double threshold = yaml.getDouble("rotation-threshold-degrees", 3);
        if (ticks < 1 || ticks > 200) throw new IllegalArgumentException("update-interval-ticks: usar 1..200");
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 180)
            throw new IllegalArgumentException("rotation-threshold-degrees: usar 0..180");
        return new Settings(ticks, yaml.getBoolean("ignore-invisible-players", true),
                yaml.getBoolean("ignore-spectators", true), threshold, yaml);
    }
}
