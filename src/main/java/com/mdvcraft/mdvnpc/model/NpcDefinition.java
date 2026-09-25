package com.mdvcraft.mdvnpc.model;

import java.util.List;
import java.util.UUID;

public record NpcDefinition(String id, boolean enabled, String name, boolean nameVisible,
                            Position position, Skin skin, Look look, Dialogue dialogue,
                            Interaction interaction) {
    public record Position(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {
        public int chunkX() { return ((int) Math.floor(x)) >> 4; }
        public int chunkZ() { return ((int) Math.floor(z)) >> 4; }
    }
    public record Skin(String name, String texture, String signature, UUID profileId) {}
    public record Look(boolean enabled, double range, boolean lineOfSight, boolean resetWhenAlone) {}
    public record Dialogue(boolean enabled, double range, double intervalSeconds, double initialDelaySeconds,
                           boolean random, boolean lineOfSight, List<String> lines) {
        public Dialogue { lines = List.copyOf(lines); }
    }
    public record Interaction(double range, double cooldownSeconds, boolean lineOfSight,
                              String permission, List<Action> actions) {
        public Interaction { actions = List.copyOf(actions); }
    }
    public enum Click { RIGHT, LEFT, BOTH }
    public enum Executor { CONSOLE, PLAYER }
    public record Action(Click click, Executor executor, String command) {}
}
