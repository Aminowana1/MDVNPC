package com.mdvcraft.mdvnpc.editor;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.view.AnvilView;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/** NPC configuration buttons and a private, virtual Paper anvil for the name. */
public final class NpcEditor implements Listener {
    private static final int MAX_NAME_LENGTH=50;
    private enum Screen { MAIN, JOB, INSTRUMENT }
    private static final class Holder implements InventoryHolder {
        final UUID owner; final String npc; final Screen screen; Inventory inventory;
        Holder(Player p,String npc,Screen screen){owner=p.getUniqueId();this.npc=npc;this.screen=screen;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private record RenameSession(UUID owner,String npc,String previous,AnvilView view) {
        Inventory inventory(){return view.getTopInventory();}
    }
    private final MdvNpcPlugin plugin;
    private final BiFunction<Player,String,AnvilView> nameViews;
    private final Map<UUID,RenameSession> names=new HashMap<>();
    public NpcEditor(MdvNpcPlugin plugin){this(plugin,(p,id)->MenuType.ANVIL.builder().checkReachable(false).title(Component.text("Nombre del NPC: "+id)).build(p));}
    NpcEditor(MdvNpcPlugin plugin,BiFunction<Player,String,AnvilView> nameViews){this.plugin=plugin;this.nameViews=nameViews;}

    public void open(Player p,String id){
        if(!valid(p,id))return;
        cancel(p);cancelOtherInputs(p);
        var def=plugin.definitions().get(id);Holder h=menu(p,id,Screen.MAIN,"&2Editar NPC: &f"+id);
        h.inventory.setItem(4,item(Material.PLAYER_HEAD,"&6"+def.name(),"&7ID: &f"+id,"&7Trabajo: &f"+jobName(def.mode())));
        h.inventory.setItem(10,item(Material.NAME_TAG,"&eCambiar nombre","&7Actual: &f"+def.name(),"&7Escribe el nombre en el yunque.","&7Admite colores &. Máximo "+MAX_NAME_LENGTH+" caracteres.","&eClic para abrir"));
        h.inventory.setItem(11,item(Material.IRON_PICKAXE,"&aTrabajo del NPC","&7Actual: &f"+jobName(def.mode()),"&7Normal, Tienda o Músico.","&eClic para elegir"));
        h.inventory.setItem(12,item(Material.CLOCK,"&bRutinas y horarios","&7Dormir, caminar, sentarse o trabajar.","&7Actividades fijas o alternativas aleatorias.","&eClic para abrir"));
        h.inventory.setItem(13,item(Material.CHEST,"&6Editar tienda",def.mode()==NpcDefinition.Mode.SHOP?"&7Configura los intercambios del NPC.":"&7Primero selecciona el trabajo Tienda.","&eClic para abrir"));
        h.inventory.setItem(14,item(Material.FIREWORK_ROCKET,"&dRasgo del NPC","&7Actual: &f"+com.mdvcraft.mdvnpc.trait.TraitEditor.name(def.traits().type()),"&eClic para elegir"));
        h.inventory.setItem(15,item(Material.WRITABLE_BOOK,"&dPrefijo de los diálogos","&7Personaliza cómo habla este NPC.","&eClic para abrir"));
        h.inventory.setItem(16,item(def.nameVisible()?Material.ENDER_EYE:Material.ENDER_PEARL,"&eNombre visible",def.nameVisible()?"&aActivado":"&7Desactivado","&eClic para cambiar"));
        h.inventory.setItem(19,item(def.enabled()?Material.LIME_DYE:Material.GRAY_DYE,def.enabled()?"&aNPC activado":"&7NPC desactivado","&eClic para cambiar"));
        h.inventory.setItem(22,item(Material.ARROW,"&eVolver a las rutinas"));p.openInventory(h.inventory);
    }
    private void openJob(Player p,String id){
        if(!valid(p,id))return;var def=plugin.definitions().get(id);Holder h=menu(p,id,Screen.JOB,"&2Trabajo: &f"+id);
        h.inventory.setItem(10,item(Material.PLAYER_HEAD,"&eNormal","&7NPC sin tienda ni instrumento.",def.mode()==NpcDefinition.Mode.NORMAL?"&aSeleccionado":"&eClic para asignar"));
        h.inventory.setItem(13,item(Material.CHEST,"&6Tienda","&7Vende mediante sus intercambios.","&7Usa Trabajo en la rutina para definir horario.",def.mode()==NpcDefinition.Mode.SHOP?"&aSeleccionado":"&eClic para asignar"));
        h.inventory.setItem(16,item(Material.NOTE_BLOCK,"&bMúsico","&7Toca durante su rutina de Trabajo.","&7Elige Flauta o Guitarra.",def.mode().musician()?"&aActual: "+jobName(def.mode()):"&eClic para elegir instrumento"));
        h.inventory.setItem(22,item(Material.ARROW,"&eVolver al NPC"));p.openInventory(h.inventory);
    }
    private void openInstrument(Player p,String id){
        if(!valid(p,id))return;var def=plugin.definitions().get(id);Holder h=menu(p,id,Screen.INSTRUMENT,"&3Instrumento: &f"+id);
        h.inventory.setItem(11,item(Material.BAMBOO,"&bFlauta","&7Parte de flauta de la canción.",def.mode()==NpcDefinition.Mode.MUSICIAN_FLUTE?"&aSeleccionada":"&eClic para asignar Músico con Flauta"));
        h.inventory.setItem(15,item(Material.STICK,"&6Guitarra","&7Parte de guitarra de la canción.",def.mode()==NpcDefinition.Mode.MUSICIAN_GUITAR?"&aSeleccionada":"&eClic para asignar Músico con Guitarra"));
        h.inventory.setItem(22,item(Material.ARROW,"&eVolver al trabajo"));p.openInventory(h.inventory);
    }
    private Holder menu(Player p,String id,Screen screen,String title){
        Holder h=new Holder(p,id,screen);h.inventory=Bukkit.createInventory(h,27,color(title));return h;
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player p))return;
        Inventory top=event.getView().getTopInventory();RenameSession rename=names.get(p.getUniqueId());
        if(rename!=null && rename.inventory().equals(top)){
            event.setCancelled(true);
            if(event.getRawSlot()!=2 || event.getClickedInventory()!=top || !buttonClick(event) || !valid(p,rename.npc()))return;
            String name=rename.view().getRenameText();
            if(!validName(name)){message(p,"&cEscribe un nombre de 1 a "+MAX_NAME_LENGTH+" caracteres.");return;}
            String desired=name.trim();
            Bukkit.getScheduler().runTask(plugin,()->saveName(p,rename,desired));return;
        }
        if(!(top.getHolder() instanceof Holder h))return;event.setCancelled(true);
        if(!h.owner.equals(p.getUniqueId()) || event.getClickedInventory()!=top || !buttonClick(event))return;
        int slot=event.getRawSlot();
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!p.isOnline() || p.getOpenInventory().getTopInventory()!=h.inventory || !valid(p,h.npc))return;
            try{
                switch(h.screen){
                    case MAIN -> clickMain(p,h,slot);
                    case JOB -> {switch(slot){case 10->setMode(p,h.npc,NpcDefinition.Mode.NORMAL);case 13->setMode(p,h.npc,NpcDefinition.Mode.SHOP);case 16->openInstrument(p,h.npc);case 22->open(p,h.npc);}}
                    case INSTRUMENT -> {switch(slot){case 11->setMode(p,h.npc,NpcDefinition.Mode.MUSICIAN_FLUTE);case 15->setMode(p,h.npc,NpcDefinition.Mode.MUSICIAN_GUITAR);case 22->openJob(p,h.npc);}}
                }
            }catch(Exception ex){message(p,"&cNo se pudo guardar: &f"+Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()));}
        });
    }
    private void clickMain(Player p,Holder h,int slot)throws Exception{
        var def=plugin.definitions().get(h.npc);
        switch(slot){
            case 10->openName(p,h.npc);
            case 11->openJob(p,h.npc);
            case 12,22->plugin.routineCommands().editor().openMain(p,h.npc);
            case 13->{if(def.mode()!=NpcDefinition.Mode.SHOP){message(p,"&eSelecciona Trabajo → Tienda y vuelve a editar sus intercambios.");openJob(p,h.npc);}else plugin.shops().openEditor(p,h.npc);}
            case 14->plugin.traitEditor().open(p,h.npc,0);
            case 15->plugin.prefixEditor().open(p,h.npc,0);
            case 16->{save(p,h.npc,y->y.set("npcs."+h.npc+".name-visible",!def.nameVisible()));open(p,h.npc);}
            case 19->{save(p,h.npc,y->y.set("npcs."+h.npc+".enabled",!def.enabled()));open(p,h.npc);}
        }
    }
    private void setMode(Player p,String id,NpcDefinition.Mode mode)throws Exception{
        if(plugin.definitions().get(id).mode()==mode){open(p,id);return;}
        save(p,id,y->y.set("npcs."+id+".mode",mode.name().toLowerCase(Locale.ROOT)));
        message(p,"&aTrabajo guardado: &f"+jobName(mode)+(mode.musician()?"&a. Configura una rutina de Trabajo con horario y puesto.":""));open(p,id);
    }
    public void openName(Player p,String id){
        if(!valid(p,id))return;cancel(p);cancelOtherInputs(p);
        var def=plugin.definitions().get(id);
        AnvilView view=nameViews.apply(p,id);
        RenameSession session=new RenameSession(p.getUniqueId(),id,def.name(),view);names.put(p.getUniqueId(),session);
        view.setMaximumRepairCost(Integer.MAX_VALUE);view.setRepairCost(0);view.setRepairItemCountCost(0);
        ItemStack token=item(Material.NAME_TAG,def.name(),"&7Edita el nombre en el campo de arriba.","&7Clic en el resultado para guardar.","&7Sin coste de experiencia ni objetos.","&7Escape para volver al editor.");
        var tokenMeta=token.getItemMeta();tokenMeta.setDisplayName(def.name());token.setItemMeta(tokenMeta);view.getTopInventory().setItem(0,token);
        p.openInventory(view);message(p,"&eEscribe el nombre arriba y pulsa el resultado para guardarlo. &7Escape vuelve al editor.");
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void prepare(PrepareAnvilEvent event){
        if(!(event.getView().getPlayer() instanceof Player p))return;RenameSession session=names.get(p.getUniqueId());
        if(session==null || !session.inventory().equals(event.getInventory()))return;
        String name=event.getView().getRenameText();
        event.getView().setRepairCost(0);event.getView().setRepairItemCountCost(0);
        if(!p.hasPermission("mdvnpc.admin") || !plugin.definitions().containsKey(session.npc()) || !validName(name)){event.setResult(null);return;}
        event.setResult(item(Material.NAME_TAG,name.trim(),"&aClic para guardar este nombre","&7Sin coste de experiencia ni objetos."));
    }
    private void saveName(Player p,RenameSession session,String desired){
        if(!p.isOnline() || names.get(p.getUniqueId())!=session || !isViewing(p,session.inventory()) || !valid(p,session.npc()))return;
        try{
            save(p,session.npc(),y->{String path="npcs."+session.npc()+".name";
                if(!Objects.equals(y.getString(path,session.npc()),session.previous()))throw new IllegalArgumentException("Otro administrador cambió el nombre. Abre el editor de nuevo.");
                y.set(path,desired);
            });
            cancel(p);message(p,"&aNombre guardado: &f"+desired);open(p,session.npc());
        }catch(Exception ex){message(p,"&cNo se pudo guardar: &f"+Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()));}
    }
    private void save(Player p,String id,Consumer<YamlConfiguration> mutation)throws Exception{
        if(!valid(p,id))throw new IllegalArgumentException("El NPC ya no existe o no tienes permiso");
        plugin.shops().prepareReload();plugin.repository().edit(y->{if(y.getConfigurationSection("npcs."+id)==null)throw new IllegalArgumentException("El NPC ya no existe");mutation.accept(y);});plugin.reloadNpcs();
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event){
        if(event.getView().getTopInventory().getHolder() instanceof Holder){event.setCancelled(true);return;}
        RenameSession session=names.get(event.getWhoClicked().getUniqueId());if(session!=null && session.inventory().equals(event.getView().getTopInventory()))event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event){
        RenameSession session=names.get(event.getPlayer().getUniqueId());if(session==null || !session.inventory().equals(event.getInventory()))return;
        names.remove(session.owner(),session);session.inventory().clear();
        if(event.getPlayer() instanceof Player p)Bukkit.getScheduler().runTask(plugin,()->{
            if(p.isOnline() && p.getOpenInventory().getType()==InventoryType.CRAFTING && valid(p,session.npc()))open(p,session.npc());
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event){cancel(event.getPlayer());}
    public void cancel(Player p){RenameSession session=names.remove(p.getUniqueId());if(session==null)return;session.inventory().clear();if(isViewing(p,session.inventory()))p.closeInventory();}
    /** Removes virtual tokens before Vanilla closes the anvil and returns its inputs. */
    public void closeAll(){
        var sessions=List.copyOf(names.values());names.clear();
        for(var session:sessions){session.inventory().clear();Player p=Bukkit.getPlayer(session.owner());if(p!=null && isViewing(p,session.inventory()))p.closeInventory();}
    }
    private static boolean isViewing(Player p,Inventory inventory){InventoryView view=p.getOpenInventory();return view!=null && inventory.equals(view.getTopInventory());}
    private void cancelOtherInputs(Player p){if(plugin.routineCommands()!=null)plugin.routineCommands().cancelSelection(p);if(plugin.prefixEditor()!=null)plugin.prefixEditor().cancel(p);}
    private boolean valid(Player p,String id){if(!p.hasPermission("mdvnpc.admin"))return false;if(!plugin.definitions().containsKey(id)){message(p,"&cEl NPC ya no existe.");return false;}return true;}
    private static boolean buttonClick(InventoryClickEvent e){return e.getClick()==ClickType.LEFT || e.getClick()==ClickType.RIGHT;}
    private static boolean validName(String name){return name!=null && !name.trim().isEmpty() && name.length()<=MAX_NAME_LENGTH && name.chars().noneMatch(Character::isISOControl);}
    private static String jobName(NpcDefinition.Mode mode){return switch(mode){case NORMAL->"Normal";case SHOP->"Tienda";case MUSICIAN_FLUTE->"Músico · Flauta";case MUSICIAN_GUITAR->"Músico · Guitarra";};}
    private static String color(String value){return ChatColor.translateAlternateColorCodes('&',value);}
    private static void message(Player p,String value){p.sendMessage(color("&6[MDVNPC] "+value));}
    private static ItemStack item(Material material,String title,String... lore){ItemStack item=new ItemStack(material);var meta=item.getItemMeta();meta.setDisplayName(color(title));meta.setLore(Arrays.stream(lore).map(NpcEditor::color).toList());item.setItemMeta(meta);return item;}
}
