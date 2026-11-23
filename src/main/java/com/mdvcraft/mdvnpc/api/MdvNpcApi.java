package com.mdvcraft.mdvnpc.api;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API pública de MDVNPC, registrada en el ServicesManager de Bukkit.
 *
 * <pre>{@code
 * MdvNpcApi npcs = Bukkit.getServicesManager().load(MdvNpcApi.class);
 * }</pre>
 *
 * <p>Los acompañantes son NPC <b>temporales</b>: no se guardan en {@code npcs.yml},
 * no tienen rutinas, sobreviven a {@code /mdvnpc reload} y desaparecen al reiniciar el
 * servidor o al deshabilitarse el plugin que los creó. Solo los ven los jugadores
 * indicados en {@link CompanionSpec}.</p>
 *
 * <p>Todos los métodos deben llamarse desde el hilo principal del servidor.</p>
 */
public interface MdvNpcApi {
    /** false si {@code companions.enabled} está desactivado en la config de MDVNPC. */
    boolean companionsEnabled();

    /**
     * Crea un acompañante temporal.
     *
     * @throws CompanionSpawnException si no se pudo crear (límite, mundo, plantilla, WorldGuard…).
     */
    Companion spawnCompanion(CompanionSpec spec);

    Optional<Companion> companion(UUID companionId);

    /** El acompañante cuya entidad base es {@code entity}, si existe. */
    Optional<Companion> companion(Entity entity);

    boolean isCompanion(Entity entity);

    /** Acompañantes vivos creados por {@code owner}. */
    List<Companion> companions(Plugin owner);

    int activeCompanions();

    int maxCompanions();

    // ---------------------------------------------------------------- NPC normales por jugador

    /**
     * Oculta a {@code viewer} todos los NPC normales de {@code npcs.yml}, incluidas sus
     * entidades auxiliares (asiento, bote, objetos del herrero, anzuelo). Mientras estén
     * ocultos, esos NPC tampoco le hablan, no lo miran, no reaccionan a él, no abren tiendas
     * y no oye su música.
     *
     * <p>Se mantiene aunque los NPC reaparezcan, cambie de chunk/mundo o se haga
     * {@code /mdvnpc reload}. Los acompañantes no se ven afectados: su visibilidad depende
     * solo de sus visores.</p>
     *
     * <p>Cada plugin pide la ocultación por su cuenta: los NPC vuelven a verse cuando
     * ningún plugin la mantiene. Se olvida sola cuando el jugador se desconecta o cuando se
     * deshabilita {@code owner}, así que hay que volver a pedirla al reconectar.</p>
     *
     * @return true si el jugador no tenía los NPC ocultos antes de esta llamada
     */
    boolean hideNpcs(Plugin owner, Player viewer);

    /**
     * Retira la ocultación pedida por {@code owner}.
     *
     * @return true si el jugador vuelve a ver los NPC tras esta llamada
     */
    boolean showNpcs(Plugin owner, Player viewer);

    /** true si algún plugin oculta los NPC normales a este jugador. */
    boolean isHidingNpcs(Player viewer);
}
