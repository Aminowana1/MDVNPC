package com.mdvcraft.mdvnpc.shop;

import com.mdvcraft.mdvnpc.config.NpcParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Saves only one page at a time. Nothing about NPC entities changes when shop offers change. */
public final class ShopRepository {
    public static final int PAGE_SIZE = 9;
    public static final int MAX_PAGES = 100;
    private final Path file;
    private Map<String, NavigableMap<Integer, ShopOffer>> data = Map.of();
    public ShopRepository(Path folder) { this.file = folder.resolve("shops.yml"); }
    public NavigableMap<Integer, ShopOffer> offers(String npc) {
        var offers = data.get(npc);
        return offers == null ? Collections.emptyNavigableMap() : Collections.unmodifiableNavigableMap(offers);
    }
    public int pages(String npc) { var offers = offers(npc); return offers.isEmpty() ? 1 : offers.lastKey() / PAGE_SIZE + 1; }
    public void load() throws Exception { data = parse(read()); }
    public void validate() throws Exception { parse(read()); }
    private YamlConfiguration read() throws Exception {
        var yaml = new YamlConfiguration();
        if (Files.exists(file)) yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        else yaml.createSection("shops");
        return yaml;
    }
    public static Map<String, NavigableMap<Integer, ShopOffer>> parse(YamlConfiguration yaml) {
        Map<String, NavigableMap<Integer, ShopOffer>> result = new LinkedHashMap<>();
        ConfigurationSection shops = yaml.getConfigurationSection("shops");
        if (shops == null) throw new IllegalArgumentException("Falta shops: {} en shops.yml");
        for (String npc : shops.getKeys(false)) {
            NpcParser.validateId(npc);
            NavigableMap<Integer, ShopOffer> offers = new TreeMap<>();
            ConfigurationSection section = shops.getConfigurationSection(npc + ".offers");
            if (section == null) continue;
            for (String index : section.getKeys(false)) {
                int i;
                try { i = Integer.parseInt(index); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("shops." + npc + ": índice inválido " + index); }
                if (i < 0 || i >= PAGE_SIZE * MAX_PAGES) throw new IllegalArgumentException("Índice de trueque fuera de rango: " + i);
                if (!index.equals(Integer.toString(i))) throw new IllegalArgumentException("Índice no canónico: " + index);
                ConfigurationSection trade = section.getConfigurationSection(index);
                if (trade == null) throw new IllegalArgumentException("Trueque vacío/incorrecto: " + npc + "." + index);
                try {
                    ShopOffer offer = new ShopOffer(ShopItem.read(trade.getConfigurationSection("result")),
                            ShopItem.read(trade.getConfigurationSection("cost-1")),
                            ShopItem.read(trade.getConfigurationSection("cost-2")));
                    offers.put(i, offer);
                } catch (RuntimeException ex) {
                    throw new IllegalArgumentException("Error trueque " + npc + "." + index + ": " + ex.getMessage(), ex);
                }
            }
            result.put(npc, offers);
        }
        return result;
    }
    public void savePage(String npc, int page, Map<Integer, ShopOffer> offers) throws Exception {
        NpcParser.validateId(npc);
        if (page < 0 || page >= MAX_PAGES) throw new IllegalArgumentException("Página fuera de rango");
        if (offers.keySet().stream().anyMatch(i -> i == null || i < 0 || i >= PAGE_SIZE))
            throw new IllegalArgumentException("Columna fuera de rango");
        var yaml = read();
        var disk = parse(yaml).getOrDefault(npc, new TreeMap<>());
        if (!disk.subMap(page * PAGE_SIZE, true, (page + 1) * PAGE_SIZE, false)
                .equals(offers(npc).subMap(page * PAGE_SIZE, true, (page + 1) * PAGE_SIZE, false)))
            throw new IllegalStateException("La página cambió en disco; cierra el editor y recarga antes de editar.");
        String prefix = "shops." + npc + ".offers.";
        for (int local = 0; local < PAGE_SIZE; local++) {
            int absolute = page * PAGE_SIZE + local;
            yaml.set(prefix + absolute, null);
            ShopOffer offer = offers.get(local);
            if (offer == null) continue;
            ConfigurationSection section = yaml.createSection(prefix + absolute);
            offer.result().write(section.createSection("result"));
            offer.cost1().write(section.createSection("cost-1"));
            if (offer.cost2() != null) offer.cost2().write(section.createSection("cost-2"));
        }
        writeValidated(yaml);
    }
    public void delete(String npc) throws Exception {
        NpcParser.validateId(npc);
        var yaml = read();
        yaml.set("shops." + npc, null);
        writeValidated(yaml);
    }
    private void writeValidated(YamlConfiguration yaml) throws Exception {
        var parsed = parse(yaml); // Reject invalid edits before touching disk or in-memory state.
        com.mdvcraft.mdvnpc.storage.AtomicFile.write(file, yaml.saveToString());
        data = parsed;
    }
}
