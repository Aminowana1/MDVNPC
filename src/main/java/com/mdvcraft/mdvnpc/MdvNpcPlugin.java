package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.command.NpcCommand;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.listener.NpcListener;
import com.mdvcraft.mdvnpc.integration.WorldGuardSpawnHook;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.storage.NpcRepository;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.util.Messages;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public final class MdvNpcPlugin extends JavaPlugin {
    private Settings settings;
    private NpcRepository repository;
    private NpcManager manager;
    private ShopService shops;
    private com.mdvcraft.mdvnpc.skin.SkinCacheService skins;
    private final Messages messages = new Messages(this::settings);
    private Map<String, NpcDefinition> definitions = Map.of();

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (!getDataFolder().toPath().resolve("npcs.yml").toFile().exists()) saveResource("npcs.yml", false);
            repository = new NpcRepository(getDataFolder().toPath());
            if (!getDataFolder().toPath().resolve("shops.yml").toFile().exists()) saveResource("shops.yml", false);
            skins = new com.mdvcraft.mdvnpc.skin.SkinCacheService(this);
            shops = new ShopService(this);
            shops.load();
            reloadNpcs();
            getServer().getPluginManager().registerEvents(shops, this);
            getServer().getPluginManager().registerEvents(new NpcListener(this), this);
            if (getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
                getServer().getPluginManager().registerEvents(new WorldGuardSpawnHook(this), this);
                getLogger().info("WorldGuard detectado: excepción de mob-spawning/deny-spawn "
                        + "activada únicamente para NPC propios durante su aparición.");
            }
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
        var snapshot = repository.load();
        // Existing servers retain config.yml, while newly added message keys use jar defaults.
        try (var stream = getResource("config.yml")) {
            if (stream != null) snapshot.settings().messages().setDefaults(
                    YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        shops.prepareReload();
        shops.load(); // Validate everything before touching active NPCs.
        if (manager != null) manager.stop();
        settings = snapshot.settings();
        definitions = snapshot.npcs();
        manager = new NpcManager(this);
        manager.start(definitions);
    }
    @Override public void onDisable() {
        if (shops != null) shops.closeAll();
        if (manager != null) manager.stop();
        if (skins != null) skins.close();
    }
    public Settings settings() { return settings; }
    public com.mdvcraft.mdvnpc.skin.SkinCacheService skins() { return skins; }
    public ShopService shops() { return shops; }
    public Messages messages() { return messages; }
    public NpcManager manager() { return manager; }
    public NpcRepository repository() { return repository; }
    public Map<String, NpcDefinition> definitions() { return definitions; }
}

