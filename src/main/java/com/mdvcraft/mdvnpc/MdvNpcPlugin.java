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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public final class MdvNpcPlugin extends JavaPlugin {
    private com.mdvcraft.mdvnpc.trait.NpcSounds sounds;
    private com.mdvcraft.mdvnpc.trait.TraitService traits;
    private com.mdvcraft.mdvnpc.trait.TraitEditor traitEditor;
    private com.mdvcraft.mdvnpc.editor.NpcEditor npcEditor;
    private com.mdvcraft.mdvnpc.work.ShopWorkEditor workEditor;
    public com.mdvcraft.mdvnpc.work.ShopWorkEditor workEditor(){return workEditor;}
    public com.mdvcraft.mdvnpc.editor.NpcEditor npcEditor(){return npcEditor;}
    public com.mdvcraft.mdvnpc.trait.TraitEditor traitEditor(){return traitEditor;}
    public com.mdvcraft.mdvnpc.trait.NpcSounds sounds(){return sounds;}
    public com.mdvcraft.mdvnpc.trait.TraitService traits(){return traits;}
    private com.mdvcraft.mdvnpc.trait.PrefixEditor prefixEditor;
    private com.mdvcraft.mdvnpc.trait.HitReactionService reactions;
    public com.mdvcraft.mdvnpc.trait.PrefixEditor prefixEditor(){return prefixEditor;}
    public com.mdvcraft.mdvnpc.trait.HitReactionService reactions(){return reactions;}
    private com.mdvcraft.mdvnpc.music.MusicService music;
    public com.mdvcraft.mdvnpc.music.MusicService music() { return music; }
    private Settings settings;
    private NpcRepository repository;
    private NpcManager manager;
    private com.mdvcraft.mdvnpc.runtime.InteractionService interactions;
    private ShopService shops;
    private com.mdvcraft.mdvnpc.routine.RoutineService routines;
    private com.mdvcraft.mdvnpc.routine.RoutineCommands routineCommands;
    private com.mdvcraft.mdvnpc.skin.SkinCacheService skins;
    private com.mdvcraft.mdvnpc.companion.CompanionService companions;
    private com.mdvcraft.mdvnpc.runtime.NpcVisibilityService visibility;
    private final com.mdvcraft.mdvnpc.runtime.NpcSpacing spacing = new com.mdvcraft.mdvnpc.runtime.NpcSpacing();
    /** Where every NPC stands, so that none walks through another. */
    public com.mdvcraft.mdvnpc.runtime.NpcSpacing spacing() { return spacing; }
    private final Messages messages = new Messages(this::settings);
    private Map<String, NpcDefinition> definitions = Map.of();

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            var data = getDataFolder().toPath();
            if (!java.nio.file.Files.isDirectory(com.mdvcraft.mdvnpc.storage.NpcPaths.root(data))
                    && !java.nio.file.Files.exists(data.resolve("npcs.yml"))
                    && !java.nio.file.Files.exists(data.resolve("npcs.yml.legacy-backup"))) saveResource("npcs.yml", false);
            // Before any NPC spawns: every NPC entity is registered here so other plugins can hide them per player.
            visibility = new com.mdvcraft.mdvnpc.runtime.NpcVisibilityService(this);
            com.mdvcraft.mdvnpc.runtime.PlayerFilter.hiddenViewers(visibility::isHidden);
            repository = new NpcRepository(data);
            interactions = new com.mdvcraft.mdvnpc.runtime.InteractionService(messages, getLogger(), data.resolve("cooldowns.dat"));
            skins = new com.mdvcraft.mdvnpc.skin.SkinCacheService(this);
            sounds = new com.mdvcraft.mdvnpc.trait.NpcSounds(this);
            traits = new com.mdvcraft.mdvnpc.trait.TraitService(this);
            shops = new ShopService(this);
            shops.load();
            music = new com.mdvcraft.mdvnpc.music.MusicService(this);
            routines = new com.mdvcraft.mdvnpc.routine.RoutineService(this);
            routineCommands = new com.mdvcraft.mdvnpc.routine.RoutineCommands(this);
            workEditor = new com.mdvcraft.mdvnpc.work.ShopWorkEditor(this);
            traitEditor = new com.mdvcraft.mdvnpc.trait.TraitEditor(this);
            prefixEditor = new com.mdvcraft.mdvnpc.trait.PrefixEditor(this);
            npcEditor = new com.mdvcraft.mdvnpc.editor.NpcEditor(this);
            reactions = new com.mdvcraft.mdvnpc.trait.HitReactionService(this);
            reloadNpcs();
            getServer().getPluginManager().registerEvents(routineCommands, this);
            getServer().getPluginManager().registerEvents(routineCommands.editor(), this);
            getServer().getPluginManager().registerEvents(traitEditor, this);
            getServer().getPluginManager().registerEvents(prefixEditor, this);
            getServer().getPluginManager().registerEvents(npcEditor, this);
            getServer().getPluginManager().registerEvents(workEditor, this);
            getServer().getPluginManager().registerEvents(shops, this);
            getServer().getPluginManager().registerEvents(new NpcListener(this), this);
            getServer().getPluginManager().registerEvents(new com.mdvcraft.mdvnpc.runtime.NpcVisibilityListener(this, visibility), this);
            getServer().getPluginManager().registerEvents(new com.mdvcraft.mdvnpc.routine.NetheriteNodeListener(routines),this);
            // Acompañantes temporales (API para otros plugins): independientes de npcs.yml y de las rutinas.
            companions = new com.mdvcraft.mdvnpc.companion.CompanionService(this);
            getServer().getPluginManager().registerEvents(new com.mdvcraft.mdvnpc.companion.CompanionListener(companions), this);
            companions.start();
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
        var routineSnapshot = routines.repository().read();
        // Existing servers retain config.yml, while newly added message keys use jar defaults.
        try (var stream = getResource("config.yml")) {
            if (stream != null) snapshot.settings().messages().setDefaults(
                    YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        shops.prepareReload();
        shops.load(); // Validate everything before touching active NPCs.
        for (String problem : loadProblems())
            getLogger().severe("Archivo de NPC ignorado; se conserva su última versión válida, si la hay. " + problem);
        if(npcEditor!=null)npcEditor.closeAll();
        if(workEditor!=null)workEditor.closeAll();
        var previousSettings = settings;
        var previousDefinitions = definitions;
        var previousRoutines = routines.repository().snapshot();
        try {
            stopRuntime();
            activate(snapshot.settings(), snapshot.npcs(), routineSnapshot);
        } catch (RuntimeException | LinkageError failure) {
            if (previousSettings == null) throw failure; // First start: nothing to go back to.
            try {
                stopRuntime();
                activate(previousSettings, previousDefinitions, previousRoutines);
            } catch (RuntimeException | LinkageError restore) {
                failure.addSuppressed(restore);
            }
            getLogger().log(Level.SEVERE, "La recarga falló; se intentó restaurar la configuración anterior", failure);
            throw failure;
        }
        if (companions != null) companions.reconfigure();
    }
    private void stopRuntime() {
        music.stop();
        routines.stop();
        if (manager != null) manager.stop();
    }
    private void activate(Settings nextSettings, Map<String, NpcDefinition> nextDefinitions,
                          com.mdvcraft.mdvnpc.routine.RoutineRepository.Snapshot nextRoutines) {
        settings = nextSettings;
        definitions = nextDefinitions;
        traits.reloaded();
        reactions.reloaded();
        manager = new NpcManager(this, interactions);
        routines.repository().install(nextRoutines);
        routines.start();
        manager.start(definitions);
    }
    @Override public void onDisable() {
        if (companions != null) companions.close();
        if(npcEditor!=null)npcEditor.closeAll();
        if(workEditor!=null)workEditor.closeAll();
        if(prefixEditor!=null)prefixEditor.clear();
        if(reactions!=null)reactions.clear();
        if (shops != null) shops.closeAll();
        if (routineCommands != null) routineCommands.clear();
        if (routines != null) try { routines.close(); }
        catch (RuntimeException ex) { getLogger().log(Level.SEVERE, "No se pudo restaurar un reloj; conserva clock-state.yml para recuperarlo", ex); }
        if (music != null) music.stop();
        if (manager != null) manager.stop();
        if (traits != null) traits.close();
        if (sounds != null) sounds.clear();
        if (skins != null) skins.close();
        if (interactions != null) interactions.close();
        if (visibility != null) visibility.close();
        com.mdvcraft.mdvnpc.runtime.PlayerFilter.hiddenViewers(null);
    }
    /** Files skipped by the last reload, for the log and for whoever ran it. */
    public List<String> loadProblems() {
        List<String> all = new ArrayList<>();
        if (repository != null) all.addAll(repository.problems());
        if (routines != null) all.addAll(routines.repository().problems());
        if (shops != null) all.addAll(shops.problems());
        return all;
    }
    public com.mdvcraft.mdvnpc.runtime.NpcVisibilityService visibility() { return visibility; }
    public Settings settings() { return settings; }
    public com.mdvcraft.mdvnpc.skin.SkinCacheService skins() { return skins; }
    public com.mdvcraft.mdvnpc.companion.CompanionService companions() { return companions; }
    public ShopService shops() { return shops; }
    public com.mdvcraft.mdvnpc.routine.RoutineService routines() { return routines; }
    public com.mdvcraft.mdvnpc.routine.RoutineCommands routineCommands() { return routineCommands; }
    public boolean canInteract(com.mdvcraft.mdvnpc.runtime.ActiveNpc npc) { return routines == null || routines.canUseJob(npc); }
    public Messages messages() { return messages; }
    public NpcManager manager() { return manager; }
    public NpcRepository repository() { return repository; }
    public Map<String, NpcDefinition> definitions() { return definitions; }
}

