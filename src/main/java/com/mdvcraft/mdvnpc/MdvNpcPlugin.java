package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.command.NpcCommand;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.listener.NpcListener;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.storage.NpcRepository;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.util.Messages;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public final class MdvNpcPlugin extends JavaPlugin {
    private Settings settings;
    private NpcRepository repository;
    private NpcManager manager;
    private final Messages messages = new Messages(this::settings);
    private Map<String, NpcDefinition> definitions = Map.of();

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (!getDataFolder().toPath().resolve("npcs.yml").toFile().exists()) saveResource("npcs.yml", false);
            repository = new NpcRepository(getDataFolder().toPath());
            reloadNpcs();
            getServer().getPluginManager().registerEvents(new NpcListener(this), this);
            NpcCommand command = new NpcCommand(this);
            Objects.requireNonNull(getCommand("mdvnpc")).setExecutor(command);
            getCommand("mdvnpc").setTabCompleter(command);
            getLogger().info("MDVNPC listo: " + definitions.size() + " NPC configurados.");
        } catch (Exception | LinkageError ex) {
            getLogger().log(Level.SEVERE, "No se pudo iniciar MDVNPC", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    public void reloadNpcs() throws Exception {
        var snapshot = repository.load(); // Validate everything before touching active NPCs.
        if (manager != null) manager.stop();
        settings = snapshot.settings();
        definitions = snapshot.npcs();
        manager = new NpcManager(this);
        manager.start(definitions);
    }
    @Override public void onDisable() { if (manager != null) manager.stop(); }
    public Settings settings() { return settings; }
    public Messages messages() { return messages; }
    public NpcManager manager() { return manager; }
    public NpcRepository repository() { return repository; }
    public Map<String, NpcDefinition> definitions() { return definitions; }
}
