package com.mdvcraft.mdvnpc.trait;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Button-only menu. Mutations and inventory transitions run after the click event. */
public final class TraitEditor implements Listener {
    private final MdvNpcPlugin plugin;
    private static final Map<Integer,Trait> CHOICES=Map.of(10,Trait.ALCOHOLIC,11,Trait.READER,
            12,Trait.GLUTTON,13,Trait.RESTLESS,14,Trait.NOISY,16,Trait.NONE);
    private static final class Holder implements InventoryHolder {
        final UUID player;final String npc;final int page;Inventory inventory;
        Holder(Player player,String npc,int page){this.player=player.getUniqueId();this.npc=npc;this.page=page;}
        @Override public Inventory getInventory(){return inventory;}
    }
    public TraitEditor(MdvNpcPlugin plugin){this.plugin=plugin;}
    public static String name(Trait trait){return switch(trait){
        case NONE->"Sin rasgo";case ALCOHOLIC->"Alcohólico";case READER->"Lector";
        case GLUTTON->"Glotón";case RESTLESS->"Inquieto";case NOISY->"Ruidoso";
    };}
    public void open(Player player,String npc,int page){
        if(!player.hasPermission("mdvnpc.admin"))return;
        var def=plugin.definitions().get(npc);if(def==null){message(player,"&cEl NPC ya no existe.");return;}
        plugin.routineCommands().editor().cancelInput(player);
        Holder holder=new Holder(player,npc,page);
        Inventory inventory=Bukkit.createInventory(holder,27,color("&5Rasgo: &f"+npc));holder.inventory=inventory;
        inventory.setItem(4,item(Material.NAME_TAG,"&eActual: &f"+name(def.traits().type()),
                List.of("&7Cada NPC tiene un solo rasgo.","&7Elegir otro sustituye al anterior.")));
        CHOICES.forEach((slot,trait)->{
            Material icon=switch(trait){case ALCOHOLIC->Material.POTION;case READER->Material.BOOK;
                case GLUTTON->Material.COOKED_BEEF;case RESTLESS->Material.FEATHER;case NOISY->Material.NOTE_BLOCK;case NONE->Material.BARRIER;};
            String description=switch(trait){case ALCOHOLIC->"Recoge y bebe cerveza ofrecida.";case READER->"Lee más seguido y durante el triple de tiempo.";
                case GLUTTON->"Prefiere comer; apenas lee o bebe.";case RESTLESS->"Mira más seguido y gesticula.";
                case NOISY->"Habla con un sonido más fuerte.";case NONE->"Quita el rasgo actual.";};
            boolean selected=def.traits().type()==trait;
            inventory.setItem(slot,item(icon,(selected?"&a✔ ":"&e")+name(trait),List.of("&7"+description,
                    selected?"&aSeleccionado":trait==Trait.NONE?"&eClic para quitar el rasgo":"&eClic para asignar y guardar")));
        });
        inventory.setItem(22,item(Material.ARROW,"&eVolver al editor",List.of()));
        player.openInventory(inventory);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        event.setCancelled(true); // Also blocks shift-click, number keys and double-click from the player's inventory.
        if(!(event.getWhoClicked() instanceof Player player) || !holder.player.equals(player.getUniqueId())
                || event.getClickedInventory()!=holder.inventory || !player.hasPermission("mdvnpc.admin")
                || event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT)return;
        int slot=event.getRawSlot();Trait selected=CHOICES.get(slot);
        if(slot!=22 && selected==null)return;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!player.isOnline() || !player.hasPermission("mdvnpc.admin")
                    || player.getOpenInventory().getTopInventory()!=holder.inventory)return;
            if(slot==22){plugin.routineCommands().editor().openMain(player,holder.npc,holder.page);return;}
            var current=plugin.definitions().get(holder.npc);
            if(current==null){player.closeInventory();message(player,"&cEl NPC ya no existe.");return;}
            if(current.traits().type()==selected){open(player,holder.npc,holder.page);return;}
            try {
                plugin.shops().prepareReload();
                plugin.repository().edit(yaml->{
                    String path="npcs."+holder.npc+".trait";
                    yaml.set(path+".type",selected.name().toLowerCase(Locale.ROOT));
                    if(!yaml.contains(path+".beer-cooldown-seconds"))yaml.set(path+".beer-cooldown-seconds",current.traits().beerCooldownSeconds());
                    if(!yaml.contains(path+".beer-dialogues"))yaml.set(path+".beer-dialogues",current.traits().beerLines());
                });
                plugin.reloadNpcs();
                message(player,"&aRasgo de &f"+holder.npc+"&a: &f"+name(selected));
                open(player,holder.npc,holder.page);
            }catch(Exception ex){message(player,"&cNo se pudo completar la actualización: &f"+Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()));}
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event){
        if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);
    }
    private static String color(String text){return ChatColor.translateAlternateColorCodes('&',text);}
    private static void message(Player player,String text){player.sendMessage(color("&6[MDVNPC] "+text));}
    private static ItemStack item(Material material,String title,List<String> lore){
        ItemStack item=new ItemStack(material);var meta=item.getItemMeta();meta.setDisplayName(color(title));
        meta.setLore(lore.stream().map(TraitEditor::color).toList());item.setItemMeta(meta);return item;
    }
}
