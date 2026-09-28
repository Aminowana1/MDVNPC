package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.storage.AtomicFile;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;

/** Optional clock ownership; remembers the original game rule across unclean restarts. */
public final class WorldClockManager {
    private final MdvNpcPlugin plugin;
    private final Path file;
    private final YamlConfiguration owned = new YamlConfiguration();
    private final Map<UUID,Double> fractions = new HashMap<>();
    private final Map<UUID,Long> last = new HashMap<>();
    private Map<String,RoutineRepository.Clock> clocks=Map.of();
    public WorldClockManager(MdvNpcPlugin plugin) throws Exception {
        this.plugin=plugin; file=plugin.getDataFolder().toPath().resolve("clock-state.yml");
        if (Files.exists(file)) owned.load(file.toFile());
    }
    public void configure(Map<String,RoutineRepository.Clock> clocks) { this.clocks=clocks; sync(); }
    public void sync() {
        for (World world:Bukkit.getWorlds()) {
            String id=world.getUID().toString();
            try {
                if (clocks.containsKey(world.getName())) {
                    if (!owned.contains(id)) { owned.set(id,Boolean.TRUE.equals(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))); persist(); }
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);
                } else if (owned.contains(id)) {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,owned.getBoolean(id)); owned.set(id,null); persist();
                    fractions.remove(world.getUID()); last.remove(world.getUID());
                }
            } catch (Exception ex) { throw new IllegalStateException("No se pudo guardar/restaurar el reloj de "+world.getName(),ex); }
        }
    }
    private void persist() throws Exception { AtomicFile.write(file,owned.saveToString()); }
    public void tick(int elapsedTicks) {
        for (World world:Bukkit.getWorlds()) {
            var clock=clocks.get(world.getName());
            if (clock==null || !owned.contains(world.getUID().toString())) continue;
            long current=world.getFullTime(); UUID id=world.getUID();
            double fraction=Objects.equals(last.get(id),current) ? fractions.getOrDefault(id,0d) : 0;
            double advance=fraction;
            // Integrate each server tick so crossing 18:00 uses the correct new rate.
            for(int i=0;i<elapsedTicks;i++) {
                long phase=Math.floorMod(current+(long)advance,24000);
                advance+=12000.0/((phase<12000?clock.dayMinutes():clock.nightMinutes())*1200);
            }
            long whole=(long)advance; fractions.put(id,advance-whole);
            if (whole!=0) world.setFullTime(current+whole);
            last.put(id,current+whole);
        }
    }
    public void close() { clocks=Map.of(); sync(); }
}
