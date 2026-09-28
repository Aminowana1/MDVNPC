package com.mdvcraft.mdvnpc.model;

import java.util.List;
import java.util.UUID;

public record NpcDefinition(String id, boolean enabled, String name, boolean nameVisible,
                            Position position, Skin skin, Look look, Dialogue dialogue,
                            Interaction interaction, Mode mode, TradeDialogue tradeDialogue) {
    public NpcDefinition(String id, boolean enabled, String name, boolean nameVisible,
                         Position position, Skin skin, Look look, Dialogue dialogue, Interaction interaction) {
        this(id, enabled, name, nameVisible, position, skin, look, dialogue, interaction,
                Mode.NORMAL, new TradeDialogue(false, 20, true, List.of()));
    }
    public enum Mode { NORMAL, SHOP }
    public record TradeDialogue(boolean enabled, double cooldownSeconds, boolean random, List<String> lines) {
        public TradeDialogue { lines = List.copyOf(lines); }
    }
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
    public record UnavailableDialogue(double cooldownSeconds, boolean random, List<String> lines) {
        public UnavailableDialogue { lines = List.copyOf(lines == null ? List.of() : lines); }
        public static UnavailableDialogue defaults() {
            return new UnavailableDialogue(3, true, List.of("&7{npc} &f» &7Ahora mismo no estoy trabajando. Vuelve durante mi horario."));
        }
    }
    public record Interaction(double range, double cooldownSeconds, boolean lineOfSight,
                              String permission, List<Action> actions, UnavailableDialogue unavailable) {
        public Interaction { actions = List.copyOf(actions); unavailable = unavailable == null ? UnavailableDialogue.defaults() : unavailable; }
        public Interaction(double range, double cooldownSeconds, boolean lineOfSight, String permission, List<Action> actions) {
            this(range, cooldownSeconds, lineOfSight, permission, actions, UnavailableDialogue.defaults());
        }
    }
    public enum Click { RIGHT, LEFT, BOTH }
    public enum Executor { CONSOLE, PLAYER }
    public record Action(Click click, Executor executor, String command) {}
}
