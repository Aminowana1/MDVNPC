package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Prefix editing by menu + one private chat input, stored in the existing NPC repository. */
public final class PrefixEditor implements Listener {
    private final MdvNpcPlugin plugin;
    private record Input(String npc,int page,String previous,long expires){}
    private final Map<UUID,Input> inputs=new ConcurrentHashMap<>();
    private static final class Holder implements InventoryHolder {
        final String npc;final int page;Inventory inventory;
        Holder(String npc,int page){this.npc=npc;this.page=page;}
        public Inventory getInventory(){return inventory;}
    }
    public PrefixEditor(MdvNpcPlugin plugin){this.plugin=plugin;}
    public void clear(){inputs.clear();}
    public void cancel(Player p){inputs.remove(p.getUniqueId());}
    public void prune(){long now=System.nanoTime();inputs.values().removeIf(v->now>=v.expires());}
    public void open(Player p,String id,int page){
        if(!p.hasPermission("mdvnpc.admin"))return;
        var def=plugin.definitions().get(id);if(def==null)return;
        plugin.routineCommands().cancelSelection(p);cancel(p);
        Holder h=new Holder(id,page);h.inventory=Bukkit.createInventory(h,27,color("&5Prefijo: &f"+id));
        String prefix=def.speech().prefix();
        h.inventory.setItem(4,item(Material.NAME_TAG,"&ePrefijo actual",prefix==null?"&7Formato original de las frases":prefix.isEmpty()?"&7Sin prefijo":prefix));
        h.inventory.setItem(10,item(Material.WRITABLE_BOOK,"&aEditar prefijo","&7Escribe el nuevo prefijo por chat.","&7Colores & y variables {npc}, {player}."));
        h.inventory.setItem(13,item(Material.PAPER,"&eVista previa",com.mdvcraft.mdvnpc.util.DialogueText.render("&7{npc} &f» &7Hola, {player}.",p,def)));
        h.inventory.setItem(15,item(Material.BARRIER,"&cSin prefijo","&7Deja únicamente el texto de cada frase."));
        h.inventory.setItem(16,item(Material.BOOK,"&eRestaurar formato original","&7Vuelve al formato anterior a personalizarlo."));
        h.inventory.setItem(22,item(Material.ARROW,"&eVolver"));p.openInventory(h.inventory);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Holder h))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory()!=h.inventory
                || e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT)return;
        int slot=e.getRawSlot();if(!Set.of(10,15,16,22).contains(slot))return;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!p.isOnline() || !p.hasPermission("mdvnpc.admin") || p.getOpenInventory().getTopInventory()!=h.inventory)return;
            var def=plugin.definitions().get(h.npc);if(def==null){p.closeInventory();return;}
            if(slot==22){plugin.routineCommands().editor().openMain(p,h.npc,h.page);return;}
            if(slot==10){
                plugin.routineCommands().editor().cancelInput(p);
                inputs.put(p.getUniqueId(),new Input(h.npc,h.page,def.speech().prefix(),System.nanoTime()+120_000_000_000L));
                p.closeInventory();message(p,"&eEscribe el prefijo, por ejemplo &6[Tabernero] {npc} »&e. Escribe &fcancelar&e para volver. Tienes 2 minutos.");return;
            }
            try{save(h.npc,slot==15?"":null);open(p,h.npc,h.page);}catch(Exception ex){message(p,"&cNo se pudo actualizar: "+ex.getMessage());}
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void chat(AsyncPlayerChatEvent e){
        UUID id=e.getPlayer().getUniqueId();Input in=inputs.get(id);if(in==null)return;
        e.setCancelled(true);String text=e.getMessage();
        Bukkit.getScheduler().runTask(plugin,()->{
            Player p=Bukkit.getPlayer(id);if(p==null || inputs.get(id)!=in)return;
            if(!p.hasPermission("mdvnpc.admin") || System.nanoTime()>=in.expires()){inputs.remove(id,in);message(p,"&cLa edición venció o no tienes permiso.");return;}
            if(text.trim().equalsIgnoreCase("cancelar")){inputs.remove(id,in);open(p,in.npc(),in.page());return;}
            var def=plugin.definitions().get(in.npc());
            if(def==null || !Objects.equals(def.speech().prefix(),in.previous())){inputs.remove(id,in);message(p,"&cEl NPC o su prefijo cambió. Abre el editor de nuevo.");return;}
            try{new com.mdvcraft.mdvnpc.model.NpcDefinition.Speech(text,null);save(in.npc(),text);inputs.remove(id,in);message(p,"&aPrefijo guardado.");open(p,in.npc(),in.page());}
            catch(Exception ex){message(p,"&cNo se pudo actualizar: "+ex.getMessage()+". Intenta otra vez o escribe cancelar.");}
        });
    }
    private void save(String id,String prefix)throws Exception{
        plugin.shops().prepareReload();plugin.repository().edit(y->y.set("npcs."+id+".speech.prefix",prefix));plugin.reloadNpcs();
    }
    @EventHandler public void quit(PlayerQuitEvent e){cancel(e.getPlayer());}
    private static String color(String s){return ChatColor.translateAlternateColorCodes('&',s);}
    private static void message(Player p,String text){p.sendMessage(color("&6[MDVNPC] "+text));}
    private static ItemStack item(Material m,String title,String... lore){
        ItemStack item=new ItemStack(m);var meta=item.getItemMeta();meta.setDisplayName(color(title));
        meta.setLore(Arrays.stream(lore).map(PrefixEditor::color).toList());item.setItemMeta(meta);return item;
    }
}
