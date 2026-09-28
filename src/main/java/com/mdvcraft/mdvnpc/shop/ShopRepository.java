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
    private Map<String, Map<Integer, List<ShopItem>>> drafts = Map.of();
    public ShopRepository(Path folder) { this.file = folder.resolve("shops.yml"); }
    public NavigableMap<Integer, ShopOffer> offers(String npc) {
        var offers = data.get(npc);
        return offers == null ? Collections.emptyNavigableMap() : Collections.unmodifiableNavigableMap(offers);
    }
    public int pages(String npc) { var offers = offers(npc); return offers.isEmpty() ? 1 : offers.lastKey() / PAGE_SIZE + 1; }
    public void load() throws Exception { var yaml = read(); var offers = parse(yaml); var partial = parseDrafts(yaml); data = offers; drafts = partial; }
    public void validate() throws Exception { var yaml = read(); parse(yaml); parseDrafts(yaml); }
    public ShopItem[] editorPage(String npc, int page) { return editorPage(data, drafts, npc, page); }
    private static ShopItem[] editorPage(Map<String, NavigableMap<Integer, ShopOffer>> offers,
            Map<String, Map<Integer, List<ShopItem>>> partial, String npc, int page) {
        ShopItem[] slots = new ShopItem[27];
        for (int col = 0; col < 9; col++) {
            int index = page * 9 + col;
            ShopOffer offer = offers.getOrDefault(npc, Collections.emptyNavigableMap()).get(index);
            if (offer != null) { slots[col] = offer.result(); slots[9 + col] = offer.cost1(); slots[18 + col] = offer.cost2(); }
            var draft = partial.getOrDefault(npc, Map.of()).get(index);
            if (draft != null) for (int row = 0; row < 3; row++) slots[row * 9 + col] = draft.get(row);
        }
        return slots;
    }
    private static Map<String, Map<Integer, List<ShopItem>>> parseDrafts(YamlConfiguration yaml) {
        Map<String, Map<Integer, List<ShopItem>>> result = new HashMap<>();
        var root = yaml.getConfigurationSection("shops");
        if (root == null) throw new IllegalArgumentException("Falta shops");
        for (String npc : root.getKeys(false)) {
            var section = root.getConfigurationSection(npc + ".drafts");
            if (section == null) continue;
            Map<Integer, List<ShopItem>> items = new HashMap<>();
            for (String key : section.getKeys(false)) {
                int index = Integer.parseInt(key);
                if (index < 0 || index >= MAX_PAGES * PAGE_SIZE || !key.equals("" + index))
                    throw new IllegalArgumentException("Índice de borrador inválido: " + key);
                var entry = Objects.requireNonNull(section.getConfigurationSection(key), "Borrador inválido");
                ShopItem a = ShopItem.read(entry.getConfigurationSection("result"));
                ShopItem b = ShopItem.read(entry.getConfigurationSection("cost-1"));
                ShopItem c = ShopItem.read(entry.getConfigurationSection("cost-2"));
                if (a != null && b != null || a == null && b == null && c == null || root.contains(npc + ".offers." + key))
                    throw new IllegalArgumentException("Borrador vacío, completo o duplicado: " + npc + "." + key);
                items.put(index, Collections.unmodifiableList(Arrays.asList(a, b, c)));
            }
            result.put(npc, items);
        }
        return result;
    }
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
        ShopItem[] slots = new ShopItem[27];
        for (var entry : offers.entrySet()) {
            Integer col = entry.getKey();
            if (col == null || col < 0 || col >= 9) throw new IllegalArgumentException("Columna fuera de rango");
            var offer = entry.getValue();
            if (offer != null) { slots[col] = offer.result(); slots[col + 9] = offer.cost1(); slots[col + 18] = offer.cost2(); }
        }
        saveEditorPage(npc, page, slots);
    }
    public void saveEditorPage(String npc, int page, ShopItem[] slots) throws Exception {
        NpcParser.validateId(npc);
        if (page < 0 || page >= MAX_PAGES) throw new IllegalArgumentException("Página fuera de rango");
        if (slots.length != 27) throw new IllegalArgumentException("Se requieren 27 slots");
        var yaml = read();
        if (!Arrays.equals(editorPage(parse(yaml), parseDrafts(yaml), npc, page), editorPage(npc, page)))
            throw new IllegalStateException("La página cambió en disco; cierra el editor y recarga antes de editar.");
        String prefix = "shops." + npc + ".offers.";
        for (int local = 0; local < PAGE_SIZE; local++) {
            int absolute = page * PAGE_SIZE + local;
            yaml.set(prefix + absolute, null);
            String draftPath = "shops." + npc + ".drafts." + absolute;
            yaml.set(draftPath, null);
            ShopItem a = slots[local], b = slots[9 + local], c = slots[18 + local];
            if (a == null && b == null && c == null) continue;
            ConfigurationSection section = yaml.createSection(a != null && b != null ? prefix + absolute : draftPath);
            if (a != null) a.write(section.createSection("result"));
            if (b != null) b.write(section.createSection("cost-1"));
            if (c != null) c.write(section.createSection("cost-2"));
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
        var partial = parseDrafts(yaml);
        com.mdvcraft.mdvnpc.storage.AtomicFile.write(file, yaml.saveToString());
        data = parsed;
        drafts = partial;
    }
}
