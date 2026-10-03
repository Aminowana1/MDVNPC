package com.mdvcraft.mdvnpc.music;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Mode;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Cosmetic performance state; MusicService owns the scheduler and the cached local audience. */
public final class MusicianVisuals {
    private final MdvNpcPlugin plugin;
    private final Function<Mode, ItemStack> instruments;
    private final Map<String, State> active = new HashMap<>();
    private Settings settingsIdentity;
    private boolean enabled = true, headMovement = true, noteParticles = true;

    private static final class State {
        ActiveNpc npc;
        ItemStack previousHand, instrument;
        boolean previousRaised, appliedRaised, headApplied;
        float previousYaw, previousPitch, previousBodyYaw, headYaw, headPitch, appliedBodyYaw;
        long nextHead, nextSwing, nextNote;
    }

    public MusicianVisuals(MdvNpcPlugin plugin) { this(plugin, MusicianVisuals::instrument); }

    /** The test seam avoids replacing Paper's real item-component registry with MockBukkit. */
    MusicianVisuals(MdvNpcPlugin plugin, Function<Mode, ItemStack> instruments) {
        this.plugin = plugin;
        this.instruments = instruments;
    }

    @SuppressWarnings("UnstableApiUsage")
    private static ItemStack instrument(Mode mode) {
        ItemStack item = new ItemStack(mode == Mode.MUSICIAN_FLUTE ? Material.BAMBOO : Material.IRON_HORSE_ARMOR);
        if (mode == Mode.MUSICIAN_FLUTE) {
            // Bamboo normally has no use animation. This cosmetic, long-duration component
            // lets the disguised player bring it to its mouth without consuming a real item.
            item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                    .animation(ItemUseAnimation.TOOT_HORN).consumeSeconds(3600f)
                    .hasConsumeParticles(false).build());
        }
        return item;
    }

    private void settings() {
        Settings current = plugin.settings();
        if (settingsIdentity == current) return;
        settingsIdentity = current;
        enabled = current == null || current.messages().getBoolean("music.visuals.enabled", true);
        headMovement = current == null || current.messages().getBoolean("music.visuals.head-movement", true);
        noteParticles = current == null || current.messages().getBoolean("music.visuals.note-particles", true);
    }

    public void tick(ActiveNpc npc, List<Player> audience, double radius, long elapsed, long clock, boolean notePlayed) {
        settings();
        String id = npc.definition().id();
        if (!enabled || !npc.definition().mode().musician() || !npc.entity().isValid() || npc.disguise() == null) {
            remove(id);
            return;
        }
        State state = active.get(id);
        if (state != null && state.npc != npc) { remove(id); state = null; }
        if (state == null) state = enter(npc);
        if (!headMovement && state.headApplied) restoreHead(state);
        boolean headDue = headMovement && clock >= state.nextHead;
        boolean swingDue = notePlayed && clock >= state.nextSwing;
        boolean notesDue = notePlayed && noteParticles && clock >= state.nextNote;
        // Empty taverns keep their instrument but incur no repeated pose/particle packets.
        if (!(headDue || swingDue || notesDue) || !hasAudience(npc.position(), audience, radius)) return;
        if (headDue) {
            state.nextHead = clock + 4;
            head(state, elapsed);
        }
        if (swingDue) {
            state.nextSwing = clock + (npc.definition().mode() == Mode.MUSICIAN_FLUTE ? 24 : 16);
            npc.entity().swingMainHand();
        }
        if (notesDue) {
            state.nextNote = clock + 8;
            notes(state, audience, radius, elapsed);
        }
    }

    private State enter(ActiveNpc npc) {
        State state = new State();
        state.npc = npc;
        var watcher = npc.disguise().getWatcher();
        ItemStack previous = watcher.getItemInMainHand();
        state.previousHand = previous == null ? null : previous.clone();
        state.previousRaised = watcher.isMainHandRaised();
        state.appliedRaised = npc.definition().mode() == Mode.MUSICIAN_FLUTE;
        Location position = npc.position();
        state.previousYaw = state.headYaw = position.getYaw();
        state.previousPitch = state.headPitch = position.getPitch();
        state.previousBodyYaw = npc.entity().getBodyYaw();
        state.instrument = instruments.apply(npc.definition().mode()).clone();
        // Keep state even if a metadata setter fails part-way, so the owner can restore it.
        active.put(npc.definition().id(), state);
        watcher.setItemInMainHand(state.instrument.clone());
        watcher.setMainHandRaised(state.appliedRaised);
        return state;
    }

    private void head(State state, long elapsed) {
        boolean flute = state.npc.definition().mode() == Mode.MUSICIAN_FLUTE;
        double beat = Math.max(0, elapsed) * Math.PI / 24;
        float wantedYaw = state.previousYaw + (float) Math.sin(beat) * (flute ? 6 : 8);
        float wantedPitch = clamp(state.previousPitch + (flute ? 4 : 9)
                + (float) Math.cos(beat * 2) * 3, -40, 40);
        state.headYaw += clamp(angle(state.headYaw, wantedYaw), -2.5f, 2.5f);
        state.headPitch += clamp(wantedPitch - state.headPitch, -2.5f, 2.5f);
        state.appliedBodyYaw = state.previousYaw;
        state.headApplied = true;
        state.npc.entity().setRotation(state.headYaw, state.headPitch);
        state.npc.entity().setBodyYaw(state.appliedBodyYaw);
    }

    private static boolean hasAudience(Location origin, List<Player> audience, double radius) {
        if (audience == null || audience.isEmpty() || !Double.isFinite(radius) || radius <= 0) return false;
        for (Player player : audience) if (MusicService.audible(origin, player, Math.min(15, radius))) return true;
        return false;
    }

    private void notes(State state, List<Player> audience, double radius, long elapsed) {
        if (audience == null || audience.isEmpty() || !Double.isFinite(radius) || radius <= 0) return;
        Location origin = state.npc.position();
        double yaw = Math.toRadians(state.previousYaw), forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        boolean flute = state.npc.definition().mode() == Mode.MUSICIAN_FLUTE;
        Location hand = origin.clone().add(forwardX * .4 - Math.cos(yaw) * .18,
                flute ? 1.42 : 1.02, forwardZ * .4 - Math.sin(yaw) * .18);
        double hue = Math.floorMod(Math.max(0, elapsed) / 4, 24) / 24.0;
        double localRadius = Math.min(15, radius);
        for (Player player : audience) if (MusicService.audible(origin, player, localRadius)) {
            // NOTE rises by itself. Count zero passes its colour in X rather than spraying
            // randomly coloured particles; one packet creates one note for this spectator.
            player.spawnParticle(Particle.NOTE, hand, 0, hue, 0, 0, 1);
        }
    }

    public boolean isAnimating(ActiveNpc npc) {
        settings();
        State state = active.get(npc.definition().id());
        return enabled && headMovement && state != null && state.npc == npc;
    }

    public void remove(String id) {
        State state = active.remove(id);
        if (state == null) return;
        try {
            var watcher = state.npc.disguise().getWatcher();
            ItemStack current = watcher.getItemInMainHand();
            // A beer, a reaction or another feature may already have claimed the hand. Their
            // pose must survive even if an owner calls remove after installing its own item.
            if (current != null && current.isSimilar(state.instrument)) {
                if (watcher.isMainHandRaised() == state.appliedRaised) watcher.setMainHandRaised(state.previousRaised);
                watcher.setItemInMainHand(state.previousHand == null ? null : state.previousHand.clone());
            }
        } finally { restoreHead(state); }
    }

    private void restoreHead(State state) {
        if (!state.headApplied) return;
        state.headApplied = false;
        if (!state.npc.entity().isValid()) return;
        Location current = state.npc.position();
        if (Math.abs(angle(current.getYaw(), state.headYaw)) > .1
                || Math.abs(current.getPitch() - state.headPitch) > .1) return;
        state.npc.entity().setRotation(state.previousYaw, state.previousPitch);
        if (Math.abs(angle(state.npc.entity().getBodyYaw(), state.appliedBodyYaw)) <= .1)
            state.npc.entity().setBodyYaw(state.previousBodyYaw);
    }

    public void stop() {
        Throwable failure = null;
        for (String id : List.copyOf(active.keySet())) try { remove(id); }
        catch (RuntimeException | LinkageError error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof LinkageError error) throw error;
    }
    private static float angle(float from, float to) { return ((to - from) % 360 + 540) % 360 - 180; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
