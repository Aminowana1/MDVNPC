package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/** Work retries by itself, so each distinct failure is logged once per reload instead of on every retry. */
final class WorkFailures {
    private static final int MAX_REMEMBERED = 512;
    private final MdvNpcPlugin plugin;
    private final String job;
    private final Set<String> reported = new HashSet<>();

    WorkFailures(MdvNpcPlugin plugin, String job) { this.plugin = plugin; this.job = job; }

    void report(String npc, Throwable error) {
        if (plugin == null || plugin.getLogger() == null) return;
        if (reported.size() >= MAX_REMEMBERED) reported.clear();
        if (reported.add(npc + ':' + error.getClass().getName() + ':' + error.getMessage()))
            plugin.getLogger().log(Level.WARNING, "Fallo en el trabajo de " + job + " del NPC " + npc, error);
    }
}
