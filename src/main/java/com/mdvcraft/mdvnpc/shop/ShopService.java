package com.mdvcraft.mdvnpc.shop;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Mode;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.util.Text;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;
import java.util.logging.Level;

/** Handles editing, persistent offers and the native Minecraft villager trading interface. */
public final class ShopService implements Listener {
    private final MdvNpcPlugin plugin;
    private final ShopRepository repository;
    private final MmoItemBridge bridge;
    private final TradeDialogueService tradeDialogue = new TradeDialogueService();
    private final Map<UUID, Editor> editors = new HashMap<>();
    private final Map<String, UUID> locks = new HashMap<>();
    private final Map<UUID, Session> merchants = new HashMap<>();
    private final Map<UUID, Long> recentOpens = new HashMap<>();
    private final Map<UUID, Long> recentEditors = new HashMap<>();
    private record MessageKey(UUID player, String npc) {}
    private final Map<MessageKey, PlayerPurchaseEvent> pendingMessages = new HashMap<>();
    private long generation;
    private final Map<String, Long> revisions = new HashMap<>();
    private record Session(String npc, Merchant merchant, ActiveNpc active, long revision, List<MerchantRecipe> recipes) {}

    private static final class EditorHolder implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class Editor {
        final String npc;
        final int page;
        final Inventory inventory;
        final ShopItem[] original = new ShopItem[27];
        final BitSet changed = new BitSet(27);
        final BitSet unavailable = new BitSet(27);
        final ItemStack[] displayed = new ItemStack[27];
        boolean switching;
        Editor(String npc, int page, Inventory inventory) {
            this.npc = npc; this.page = page; this.inventory = inventory;
        }
    }
    public ShopService(MdvNpcPlugin plugin) {
        this.plugin = plugin;
        repository = new ShopRepository(plugin.getDataFolder().toPath());
        bridge = new MmoItemBridge(plugin.getLogger());
    }
    public void load() throws Exception { repository.load(); }
    public void deleteShop(String npc) throws Exception { repository.delete(npc); invalidateNpc(npc); }

    public void openShop(Player player, ActiveNpc npc) {
        if (!plugin.canInteract(npc)) return;
        long now = System.nanoTime();
        long last = recentOpens.getOrDefault(player.getUniqueId(), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && now - last < 150_000_000L) return;
        recentOpens.put(player.getUniqueId(), now);
        Inventory previous = player.getOpenInventory().getTopInventory();
        // Defer until after the original entity-interaction event has fully finished.
        long expected = generation;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (generation == expected && Objects.equals(recentOpens.get(player.getUniqueId()), now)
                    && player.getOpenInventory().getTopInventory() == previous) openShopAfterClick(player, npc);
        });
    }
    private void openShopAfterClick(Player player, ActiveNpc npc) {
        if (!player.isOnline() || plugin.manager().find(npc.entity()) != npc || !plugin.canInteract(npc)) return;
        var def = npc.definition();
        if (def.mode() != Mode.SHOP || !def.enabled() || !npc.entity().isValid() ||
                player.getWorld() != npc.position().getWorld()) return;
        var interaction = def.interaction();
        if (player.getLocation().distanceSquared(npc.position()) > interaction.range() * interaction.range() ||
                interaction.lineOfSight() && !player.hasLineOfSight(npc.entity())) return;
        if (!interaction.permission().isEmpty() && !player.hasPermission(interaction.permission())) {
            plugin.messages().send(player, "action-denied"); return;
        }
        List<MerchantRecipe> recipes = new ArrayList<>();
        Map<ShopItem, ItemStack> resolved = new HashMap<>();
        for (var offer : repository.offers(def.id()).values()) {
            ItemStack result = resolveForOpening(offer.result(), resolved), cost1 = resolveForOpening(offer.cost1(), resolved);
            ItemStack cost2 = offer.cost2() == null ? null : resolveForOpening(offer.cost2(), resolved);
            if (result == null || cost1 == null || offer.cost2() != null && cost2 == null) continue;
            MerchantRecipe recipe = new MerchantRecipe(result, 0, 999999, false, 0, 0f);
            recipe.setIgnoreDiscounts(true);
            List<ItemStack> ingredients = new ArrayList<>();
            ingredients.add(cost1);
            if (cost2 != null) ingredients.add(cost2);
            recipe.setIngredients(ingredients);
            recipes.add(recipe);
        }
        if (recipes.isEmpty()) { plugin.messages().send(player, "shop-empty"); return; }
        Merchant merchant = Bukkit.createMerchant(Text.color(def.name()));
        merchant.setRecipes(recipes);
        player.openMerchant(merchant, true);
        if (player.getOpenInventory().getTopInventory() instanceof MerchantInventory opened && opened.getMerchant() == merchant)
            merchants.put(player.getUniqueId(), new Session(def.id(), merchant, npc, revision(def.id()),
                    recipes.stream().map(MerchantRecipe::new).toList()));
    }
    private ItemStack resolveForOpening(ShopItem item, Map<ShopItem, ItemStack> resolved) {
        if (item.kind() == ShopItem.Kind.SNAPSHOT) return item.resolve(bridge);
        if (!resolved.containsKey(item)) resolved.put(item, item.resolve(bridge));
        ItemStack stack = resolved.get(item);
        return stack == null ? null : stack.clone();
    }

    public void openEditor(Player player, String npc) {
        recentOpens.remove(player.getUniqueId());
        var definition = plugin.definitions().get(npc);
        if (definition == null || definition.mode() != Mode.SHOP) {
            plugin.messages().send(player, "shop-invalid"); return;
        }
        if (!player.hasPermission("mdvnpc.admin")) { plugin.messages().send(player, "no-permission"); return; }
        long now = System.nanoTime();
        long last = recentEditors.getOrDefault(player.getUniqueId(), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && now - last < 150_000_000L) return;
        recentEditors.put(player.getUniqueId(), now);
        UUID owner = locks.get(npc);
        if (owner != null && !owner.equals(player.getUniqueId())) {
            plugin.messages().send(player, "shop-busy"); return;
        }
        if (editors.containsKey(player.getUniqueId())) player.closeInventory();
        showEditor(player, npc, 0);
    }

    private void showEditor(Player player, String npc, int page) {
        EditorHolder holder = new EditorHolder();
        Inventory inventory = Bukkit.createInventory(holder, 36, "Shop: " + npc + " | " + (page + 1));
        holder.inventory = inventory;
        Editor editor = new Editor(npc, page, inventory);
        ShopItem[] slots = repository.editorPage(npc, page);
        for (int slot = 0; slot < 27; slot++) {
            ShopItem item = slots[slot]; editor.original[slot] = item;
            if (item == null) continue;
            ItemStack stack = item.resolve(bridge);
            if (stack == null) {
                editor.unavailable.set(slot);
                stack = icon(Material.BARRIER, "&cNo disponible. Clic derecho vacío: quitar referencia");
            }
            inventory.setItem(slot, stack);
            editor.displayed[slot] = stack.clone();
        }
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 27; i < 36; i++) inventory.setItem(i, filler);
        inventory.setItem(29, icon(Material.ARROW, "&ePágina anterior"));
        inventory.setItem(31, icon(Material.EMERALD, "&aGuardar cambios"));
        inventory.setItem(33, icon(Material.ARROW, "&ePágina siguiente"));
        inventory.setItem(35, icon(Material.BOOK, "&7Arriba: resultado. Medio: costo 1. Abajo: costo 2."));
        editors.put(player.getUniqueId(), editor);
        locks.put(npc, player.getUniqueId());
        player.openInventory(inventory);
        if (player.getOpenInventory().getTopInventory() != inventory) {
            editors.remove(player.getUniqueId(), editor);
            locks.remove(npc, player.getUniqueId());
        }
    }
    private static ItemStack icon(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(Text.color(name)); item.setItemMeta(meta); }
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EditorHolder)) return;
        Editor editor = editors.get(player.getUniqueId());
        if (editor == null || top != editor.inventory || !player.hasPermission("mdvnpc.admin") || editor.switching) {
            event.setCancelled(true); return;
        }
        if (event.isCancelled()) return;
        int raw = event.getRawSlot();
        if (raw >= 27 && raw < 36) {
            event.setCancelled(true);
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
            if (raw == 31) {
                if (save(player, editor)) plugin.messages().send(player, "shop-saved", "npc", editor.npc, "page", "" + (editor.page + 1));
            } else if (raw == 29 && editor.page > 0 || raw == 33 && editor.page < ShopRepository.MAX_PAGES - 1) {
                if (save(player, editor)) switchPageNextTick(player, editor, editor.page + (raw == 33 ? 1 : -1));
            }
            return;
        }
        if (raw >= 0 && raw < 27 && editor.unavailable.get(raw)) {
            event.setCancelled(true);
            if (event.getClick() == ClickType.RIGHT && TradeGuard.empty(event.getCursor())) {
                editor.inventory.setItem(raw, null); editor.unavailable.clear(raw);
            }
            return;
        }
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && raw >= 0) {
            event.setCancelled(true);
            ItemStack source = event.getCurrentItem();
            ItemStack rest = raw < 27
                    ? EditorTransfers.move(source, player.getInventory(), 36, i -> true)
                    : EditorTransfers.move(source, editor.inventory, 27, i -> !editor.unavailable.get(i));
            event.setCurrentItem(rest);
            return;
        }
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            ItemStack cursor = EditorTransfers.collect(event.getCursor(), editor.inventory, 27, i -> !editor.unavailable.get(i));
            cursor = EditorTransfers.collect(cursor, player.getInventory(), 36, i -> true);
            player.setItemOnCursor(cursor);
            return;
        }
        // Native container semantics: pickup/place, split, swap, hotbar, offhand and drop.
        // Do not set slots or cursor here: Paper performs the transfer exactly once.
    }

    private void switchPageNextTick(Player player, Editor editor, int page) {
        editor.switching = true;
        // Bukkit forbids openInventory() within InventoryClickEvent; defer the switch one tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.hasPermission("mdvnpc.admin") && editors.get(player.getUniqueId()) == editor)
                showEditor(player, editor.npc, page);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder)) return;
        Editor editor = editors.get(event.getWhoClicked().getUniqueId());
        if (editor == null || editor.inventory != event.getView().getTopInventory() || editor.switching
                || !event.getWhoClicked().hasPermission("mdvnpc.admin")
                || event.getRawSlots().stream().anyMatch(i -> i >= 27 && i < 36 || i >= 0 && i < 27 && editor.unavailable.get(i)))
            event.setCancelled(true);
        // An allowed drag is committed by Paper, including its cursor debit.
    }

    @EventHandler
    public void close(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Editor editor = editors.get(player.getUniqueId());
        if (editor != null && editor.inventory == event.getInventory()) {
            if (!editor.switching && !save(player, editor)) recover(player, editor);
            editors.remove(player.getUniqueId(), editor);
            locks.remove(editor.npc, player.getUniqueId());
        }
        Session session = merchants.get(player.getUniqueId());
        if (session != null && event.getInventory() instanceof MerchantInventory trading &&
                trading.getMerchant() == session.merchant()) merchants.remove(player.getUniqueId());
    }
    private boolean save(Player player, Editor editor) {
        editor.changed.clear();
        for (int slot = 0; slot < 27; slot++) {
            ItemStack current = editor.inventory.getItem(slot);
            if (TradeGuard.empty(current) && TradeGuard.empty(editor.displayed[slot])) continue;
            if (!Objects.equals(current, editor.displayed[slot])) editor.changed.set(slot);
        }
        if (editor.changed.isEmpty()) return true;
        // Finish already accepted moves even if permission was revoked meanwhile.
        // Permission is checked before every new click/drag; rejecting this save could resurrect withdrawn items.
        try {
            ShopItem[] slots = editor.original.clone();
            for (int slot = 0; slot < 27; slot++) if (editor.changed.get(slot))
                slots[slot] = ShopItem.fromItem(editor.inventory.getItem(slot), bridge);
            repository.saveEditorPage(editor.npc, editor.page, slots);
            invalidateNpc(editor.npc);
            editor.changed.clear();
            for (int slot = 0; slot < 27; slot++) {
                editor.original[slot] = slots[slot];
                ItemStack current = editor.inventory.getItem(slot);
                editor.displayed[slot] = current == null ? null : current.clone();
            }
            return true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar la tienda " + editor.npc, ex);
            player.sendMessage(Text.color("&cNo se pudo guardar la tienda: " + ex.getMessage()));
            return false;
        }
    }

    private long revision(String npc) { return revisions.getOrDefault(npc, 0L); }
    private boolean allowed(Player player, Session session) {
        var active = session.active();
        var def = plugin.definitions().get(session.npc());
        if (!player.isOnline() || def == null || !def.enabled() || def.mode() != Mode.SHOP || !plugin.canInteract(active)
                || session.revision() != revision(session.npc()) || !active.entity().isValid()
                || plugin.manager().find(active.entity()) != active || player.getWorld() != active.position().getWorld()
                || !com.mdvcraft.mdvnpc.runtime.PlayerFilter.accepts(player, plugin.settings())) return false;
        var interaction = def.interaction();
        return player.getLocation().distanceSquared(active.position()) <= interaction.range() * interaction.range()
                && (!interaction.lineOfSight() || player.hasLineOfSight(active.entity()))
                && (interaction.permission().isBlank() || player.hasPermission(interaction.permission()));
    }
    private MerchantRecipe expected(Session session, MerchantInventory inventory) {
        int index = inventory.getSelectedRecipeIndex();
        if (index >= 0 && index < session.recipes().size()) return session.recipes().get(index);
        return session.recipes().stream().filter(r -> TradeGuard.same(r, inventory.getSelectedRecipe())).findFirst().orElse(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = merchants.get(player.getUniqueId());
        if (session == null || !(event.getView().getTopInventory() instanceof MerchantInventory inventory)
                || inventory.getMerchant() != session.merchant()) return;
        if (!allowed(player, session)) { event.setCancelled(true); closeLater(player, session); return; }
        if (event.getRawSlot() != 2) return;
        MerchantRecipe recipe = expected(session, inventory);
        boolean ordinary = event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT
                || event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;
        if (!ordinary || !TradeGuard.same(recipe, inventory.getSelectedRecipe())
                || !TradeGuard.inputs(recipe, inventory.getItem(0), inventory.getItem(1))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardPurchase(PlayerPurchaseEvent event) {
        Player player = event.getPlayer();
        Session session = merchants.get(player.getUniqueId());
        if (session == null || !(player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory)
                || inventory.getMerchant() != session.merchant()) return;
        MerchantRecipe recipe = expected(session, inventory);
        if (!allowed(player, session) || !TradeGuard.same(recipe, event.getTrade())
                || !TradeGuard.inputs(recipe, inventory.getItem(0), inventory.getItem(1))) {
            event.setCancelled(true); return;
        }
        event.setRewardExp(false);
        event.setIncreaseTradeUses(false);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void purchase(PlayerPurchaseEvent event) {
        Player player = event.getPlayer();
        Session session = merchants.get(player.getUniqueId());
        if (session == null || !(player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory) ||
                inventory.getMerchant() != session.merchant()) return;
        NpcDefinition npc = plugin.definitions().get(session.npc());
        if (npc != null && npc.mode() == Mode.SHOP) {
            MessageKey key = new MessageKey(player.getUniqueId(), npc.id());
            if (pendingMessages.put(key, event) != null) return;
            long expectedGeneration = generation;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (generation != expectedGeneration) return;
                var last = pendingMessages.remove(key);
                if (last != null && !last.isCancelled() && player.isOnline())
                    tradeDialogue.traded(player, npc);
            });
        }
    }
    @EventHandler
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        merchants.remove(id);
        tradeDialogue.forget(id);
        pendingMessages.keySet().removeIf(k -> k.player().equals(id));
        Editor editor = editors.remove(id);
        recentOpens.remove(id);
        recentEditors.remove(id);
        if (editor != null) {
            if (!save(event.getPlayer(), editor)) recover(event.getPlayer(), editor);
            locks.remove(editor.npc, id);
        }
    }
    public void closeAll() {
        generation++;
        for (UUID id : List.copyOf(editors.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.closeInventory();
        }
        for (UUID id : List.copyOf(merchants.keySet())) {
            Player player = Bukkit.getPlayer(id);
            Session session = merchants.get(id);
            if (player != null && session != null && player.getOpenInventory().getTopInventory() instanceof MerchantInventory inv &&
                    inv.getMerchant() == session.merchant()) player.closeInventory();
        }
        editors.clear(); locks.clear(); merchants.clear(); recentOpens.clear(); recentEditors.clear();
        tradeDialogue.clear(); revisions.clear(); pendingMessages.clear();
    }
    public void prepareReload() throws Exception {
        repository.validate();
        for (var entry : List.copyOf(editors.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && !save(player, entry.getValue()))
                throw new IllegalStateException("Corrige o cierra la página incompleta antes de recargar.");
        }
        closeAll();
    }
    private void closeLater(Player player, Session session) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && merchants.get(player.getUniqueId()) == session
                    && player.getOpenInventory().getTopInventory() instanceof MerchantInventory inv
                    && inv.getMerchant() == session.merchant()) player.closeInventory();
        });
    }
    public void invalidateNpc(String npc) {
        revisions.merge(npc, 1L, Long::sum);
        for (var entry : List.copyOf(merchants.entrySet())) if (entry.getValue().npc().equals(npc)) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) closeLater(player, entry.getValue());
        }
    }
    public void prune(long now) {
        recentOpens.values().removeIf(last -> now - last > 1_000_000_000L);
        recentEditors.values().removeIf(last -> now - last > 1_000_000_000L);
        tradeDialogue.prune(now);
    }
    private void recover(Player player, Editor editor) {
        try {
            var yaml = new org.bukkit.configuration.file.YamlConfiguration();
            yaml.set("npc", editor.npc); yaml.set("page", editor.page);
            for (int slot = 0; slot < 27; slot++) {
                if (editor.original[slot] != null) editor.original[slot].write(yaml.createSection("original." + slot));
                if (editor.changed.get(slot)) {
                    yaml.set("changed." + slot + ".edited", true);
                    yaml.set("changed." + slot + ".item", editor.inventory.getItem(slot));
                }
            }
            var folder = plugin.getDataFolder().toPath().resolve("shop-recovery");
            java.nio.file.Files.createDirectories(folder);
            var file = folder.resolve(player.getUniqueId() + "-" + editor.npc + "-" + editor.page + ".yml");
            com.mdvcraft.mdvnpc.storage.AtomicFile.write(file, yaml.saveToString());
            player.sendMessage(Text.color("&eCambios no aplicados. Copia recuperable para administración en shop-recovery/ (página " + (editor.page + 1) + ")."));
        } catch (Exception ex) { plugin.getLogger().log(Level.SEVERE, "No se pudo recuperar el borrador de " + editor.npc, ex); }
    }
}



