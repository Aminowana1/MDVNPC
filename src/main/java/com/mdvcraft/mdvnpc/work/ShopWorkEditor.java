package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.RoutineGoal;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Shop categories and private block selections; never changes station blocks. */
public final class ShopWorkEditor implements Listener {
    private enum Screen { CATEGORY, STATIONS, FISHING, SHORE_POINTS, BOAT_POINTS }
    private enum Kind {
        SMELTERY("smeltery","1. Fundición",Material.FURNACE),
        CAULDRON("cauldron","2. Caldero con agua",Material.CAULDRON),
        ANVIL("anvil","3. Yunque",Material.ANVIL),
        SHORE("shore-points","1. Punto de pesca en tierra",Material.FISHING_ROD),
        DOCK("dock","2. Muelle",Material.OAK_BOAT),
        BOAT("boat-points","3. Punto de pesca en bote",Material.COD);
        final String key,label;final Material icon;
        Kind(String key,String label,Material icon){this.key=key;this.label=label;this.icon=icon;}
        ShopWorkDefinition.Station station(ShopWorkDefinition work){return switch(this){case SMELTERY->work.smeltery();case CAULDRON->work.cauldron();case ANVIL->work.anvil();default->null;};}
        boolean fishing(){return this==SHORE || this==DOCK || this==BOAT;}
        ShopWorkDefinition.Category category(){return fishing()?ShopWorkDefinition.Category.FISHERMAN:ShopWorkDefinition.Category.BLACKSMITH;}
    }
    private static final class Holder implements InventoryHolder {
        final UUID owner;final String npc;final Screen screen;Inventory inventory;List<FishingDefinition.Point> points=List.of();
        Holder(Player p,String npc,Screen screen){owner=p.getUniqueId();this.npc=npc;this.screen=screen;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private record Selection(String npc,Kind kind,boolean guided,UUID world,long expires,Object previous) {}
    private final MdvNpcPlugin plugin;
    private final Map<UUID,Selection> selections=new ConcurrentHashMap<>();
    private final Map<UUID,Holder> menus=new HashMap<>();
    public ShopWorkEditor(MdvNpcPlugin plugin){this.plugin=plugin;}

    public void open(Player player,String npc){
        if(!valid(player,npc))return;
        cancel(player);cancelOtherInputs(player);
        var def=plugin.definitions().get(npc);var work=def.shopWork();Holder h=menu(player,npc,Screen.CATEGORY,"&6Categoría de tienda: &f"+npc);
        h.inventory.setItem(11,item(Material.EMERALD,"&aVendedor","&7Atiende en su puesto de trabajo.",work.category()==ShopWorkDefinition.Category.VENDOR?"&aSeleccionado":"&eClic para elegir"));
        h.inventory.setItem(13,item(Material.FISHING_ROD,"&bPescador","&7Conserva los mismos intercambios.","&7Pesca desde tierra y en bote.",work.category()==ShopWorkDefinition.Category.FISHERMAN?"&aSeleccionado · clic para puntos":"&eClic para elegir y configurar puntos"));
        h.inventory.setItem(15,item(Material.IRON_PICKAXE,"&6Herrero","&7Conserva los mismos intercambios.","&7Primero fija su puesto en un goal Trabajo.",work.category()==ShopWorkDefinition.Category.BLACKSMITH?"&aSeleccionado · clic para estaciones":"&eClic para elegir y configurar estaciones"));
        h.inventory.setItem(22,item(Material.ARROW,"&eVolver al NPC"));
        h.inventory.setItem(24,item(Material.CHEST,"&6Editar intercambios","&7La categoría conserva la tienda actual."));player.openInventory(h.inventory);
    }
    public void openStations(Player player,String npc){
        if(!valid(player,npc))return;
        if(plugin.definitions().get(npc).shopWork().category()==ShopWorkDefinition.Category.FISHERMAN){openFishingStations(player,npc);return;}
        cancel(player);cancelOtherInputs(player);
        var def=plugin.definitions().get(npc);
        if(def.mode()!=NpcDefinition.Mode.SHOP || def.shopWork().category()!=ShopWorkDefinition.Category.BLACKSMITH){open(player,npc);return;}
        Holder h=menu(player,npc,Screen.STATIONS,"&6Estaciones de herrero: &f"+npc);boolean point=hasWorkPoint(npc);
        h.inventory.setItem(4,item(Material.COMPASS,"&ePuesto de trabajo",point?"&aYa hay un goal Trabajo con punto.":"&cPrimero fija el punto de un goal Trabajo.","&7Clic para abrir sus rutinas.","&7Sin estaciones válidas atiende como vendedor."));
        int[] slots={10,13,16};Kind[] kinds={Kind.SMELTERY,Kind.CAULDRON,Kind.ANVIL};
        for(int i=0;i<kinds.length;i++){
            Kind kind=kinds[i];var station=kind.station(def.shopWork());
            h.inventory.setItem(slots[i],item(kind.icon,"&6"+kind.label,station==null?"&7Sin configurar":coordinates(station),
                    kind==Kind.SMELTERY?"&7Marca cualquier bloque; después puedes quitarlo.":kind==Kind.CAULDRON?"&7Selecciona un caldero que tenga agua.":"&7Selecciona un yunque.",
                    "&eClic para marcar · clic derecho para quitar"));
        }
        h.inventory.setItem(19,item(Material.BLAZE_POWDER,"&eConfigurar las tres estaciones","&7Fundición → caldero con agua → yunque.",point?"&eClic para comenzar":"&cPrimero configura el puesto de trabajo."));
        h.inventory.setItem(22,item(Material.ARROW,"&eVolver a categoría"));
        h.inventory.setItem(24,item(Material.CHEST,"&6Editar intercambios"));player.openInventory(h.inventory);
    }
    public void openFishingStations(Player player,String npc){
        if(!valid(player,npc))return;
        cancel(player);cancelOtherInputs(player);var def=plugin.definitions().get(npc);
        if(def.mode()!=NpcDefinition.Mode.SHOP || def.shopWork().category()!=ShopWorkDefinition.Category.FISHERMAN){open(player,npc);return;}
        var fishing=def.shopWork().fishing();boolean point=hasWorkPoint(npc);Holder h=menu(player,npc,Screen.FISHING,"&3Puntos de pescador: &f"+npc);
        h.inventory.setItem(4,item(Material.COMPASS,"&ePuesto de trabajo",point?"&aYa hay un goal Trabajo con punto.":"&cPrimero fija el punto de un goal Trabajo.","&7Clic para abrir sus rutinas.","&7Si no puede pescar, atiende en este puesto."));
        h.inventory.setItem(10,item(Material.FISHING_ROD,"&bPesca en tierra","&aPuntos: "+fishing.shorePoints().size(),"&7Se elige uno al azar en cada ciclo.","&eClic para listar, añadir o quitar"));
        h.inventory.setItem(13,item(Material.OAK_BOAT,"&bMuelle",fishing.dock()==null?"&7Sin configurar":coordinates(fishing.dock()),"&7Marca la orilla mirando hacia el agua.","&eClic para marcar · clic derecho para quitar"));
        h.inventory.setItem(16,item(Material.COD,"&bPesca en bote","&aPuntos: "+fishing.boatPoints().size(),"&7Marca agua mirando hacia donde lanzará la caña.","&eClic para listar, añadir o quitar"));
        h.inventory.setItem(19,item(Material.PRISMARINE_CRYSTALS,"&eConfigurar los tres pasos","&7Punto en tierra → muelle → punto en bote.",point?"&eClic para comenzar":"&cPrimero configura el puesto de trabajo."));
        h.inventory.setItem(22,item(Material.ARROW,"&eVolver a categoría"));h.inventory.setItem(24,item(Material.CHEST,"&6Editar intercambios"));player.openInventory(h.inventory);
    }
    private void openFishingPoints(Player player,String npc,Kind kind){
        if(!valid(player,npc))return;
        cancel(player);cancelOtherInputs(player);var def=plugin.definitions().get(npc);
        if(def.mode()!=NpcDefinition.Mode.SHOP || def.shopWork().category()!=ShopWorkDefinition.Category.FISHERMAN){open(player,npc);return;}
        Holder h=menu(player,npc,kind==Kind.SHORE?Screen.SHORE_POINTS:Screen.BOAT_POINTS,"&3"+(kind==Kind.SHORE?"Pesca en tierra: ":"Pesca en bote: ")+npc,54);
        h.points=points(def.shopWork().fishing(),kind);
        h.inventory.setItem(4,item(kind.icon,"&b"+kind.label,"&7Máximo "+FishingDefinition.MAX_POINTS+" puntos.","&7Cada punto guarda la dirección de tu mirada."));
        for(int i=0;i<h.points.size();i++)h.inventory.setItem(9+i,item(kind.icon,"&bPunto "+(i+1),coordinates(h.points.get(i)),"&7Dirección: "+h.points.get(i).yaw()+"°","&eClic derecho para quitar este punto"));
        h.inventory.setItem(45,item(Material.LIME_DYE,"&aAñadir punto",h.points.size()<FishingDefinition.MAX_POINTS?"&eClic para marcar":"&cSe alcanzó el máximo de puntos."));
        h.inventory.setItem(49,item(Material.ARROW,"&eVolver a pescador"));player.openInventory(h.inventory);
    }
    private Holder menu(Player player,String npc,Screen screen,String title){
        return menu(player,npc,screen,title,27);
    }
    private Holder menu(Player player,String npc,Screen screen,String title,int size){
        Holder h=new Holder(player,npc,screen);h.inventory=Bukkit.createInventory(h,size,color(title));menus.put(player.getUniqueId(),h);return h;
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void inventory(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || event.getClickedInventory()!=h.inventory
                || event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT)return;
        int slot=event.getRawSlot();boolean remove=event.getClick()==ClickType.RIGHT;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!p.isOnline() || p.getOpenInventory().getTopInventory()!=h.inventory || !valid(p,h.npc))return;
            try{
                if(h.screen==Screen.CATEGORY){
                    switch(slot){
                        case 11->{setCategory(p,h.npc,ShopWorkDefinition.Category.VENDOR);open(p,h.npc);}
                        case 13->{setCategory(p,h.npc,ShopWorkDefinition.Category.FISHERMAN);say(p,"&ePrimero fija el punto normal de un goal Trabajo; después marca los puntos de pesca y el muelle mirando hacia el agua.");openFishingStations(p,h.npc);}
                        case 15->{setCategory(p,h.npc,ShopWorkDefinition.Category.BLACKSMITH);say(p,"&ePrimero fija el punto normal de un goal Trabajo; después marca fundición, caldero y yunque.");openStations(p,h.npc);}
                        case 22->plugin.npcEditor().open(p,h.npc);
                        case 24->trades(p,h.npc);
                    }
                }else if(h.screen==Screen.STATIONS){
                    Kind kind=switch(slot){case 10->Kind.SMELTERY;case 13->Kind.CAULDRON;case 16->Kind.ANVIL;default->null;};
                    if(kind!=null){
                        if(remove){save(p,h.npc,y->y.set(path(h.npc,kind),null));say(p,"&aEstación quitada: &f"+kind.label);openStations(p,h.npc);}
                        else begin(p,h.npc,kind,false);
                    }else switch(slot){
                        case 4->{cancel(p);plugin.routineCommands().editor().openMain(p,h.npc);}
                        case 19->begin(p,h.npc,Kind.SMELTERY,true);
                        case 22->open(p,h.npc);
                        case 24->trades(p,h.npc);
                    }
                }else if(h.screen==Screen.FISHING){
                    switch(slot){
                        case 4->{cancel(p);plugin.routineCommands().editor().openMain(p,h.npc);}
                        case 10->openFishingPoints(p,h.npc,Kind.SHORE);
                        case 13->{
                            if(remove){save(p,h.npc,y->{checkFishing(y,h.npc);y.set(fishingPath(h.npc,Kind.DOCK),null);});say(p,"&aMuelle quitado.");openFishingStations(p,h.npc);}
                            else begin(p,h.npc,Kind.DOCK,false);
                        }
                        case 16->openFishingPoints(p,h.npc,Kind.BOAT);
                        case 19->begin(p,h.npc,Kind.SHORE,true);
                        case 22->open(p,h.npc);
                        case 24->trades(p,h.npc);
                    }
                }else{
                    Kind kind=h.screen==Screen.SHORE_POINTS?Kind.SHORE:Kind.BOAT;
                    if(slot==45)begin(p,h.npc,kind,false);
                    else if(slot==49)openFishingStations(p,h.npc);
                    else if(remove && slot>=9 && slot<9+h.points.size()){
                        int index=slot-9;FishingDefinition.Point previous=h.points.get(index);
                        save(p,h.npc,y->{var latest=checkFishing(y,h.npc);var list=new ArrayList<>(points(latest.shopWork().fishing(),kind));
                            if(index>=list.size() || !list.get(index).equals(previous))throw new IllegalArgumentException("Otro administrador cambió esta lista; ábrela de nuevo");
                            list.remove(index);y.set(fishingPath(h.npc,kind),list.stream().map(ShopWorkEditor::pointMap).toList());});
                        say(p,"&aPunto quitado.");openFishingPoints(p,h.npc,kind);
                    }
                }
            }catch(Exception ex){say(p,"&cNo se pudo guardar: &f"+Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()));}
        });
    }
    private void setCategory(Player player,String npc,ShopWorkDefinition.Category category)throws Exception{
        save(player,npc,y->{y.set("npcs."+npc+".mode","shop");y.set("npcs."+npc+".shop.category",category.name().toLowerCase(Locale.ROOT));});
        say(player,"&aCategoría guardada: &f"+switch(category){case BLACKSMITH->"Herrero";case FISHERMAN->"Pescador";case VENDOR->"Vendedor";});
    }
    private void trades(Player player,String npc){
        if(plugin.definitions().get(npc).mode()!=NpcDefinition.Mode.SHOP){say(player,"&ePrimero selecciona una categoría de tienda.");return;}
        cancel(player);plugin.shops().openEditor(player,npc);
    }
    private void begin(Player player,String npc,Kind kind,boolean guided){
        if(!valid(player,npc))return;
        var def=plugin.definitions().get(npc);
        if(def.mode()!=NpcDefinition.Mode.SHOP || def.shopWork().category()!=kind.category())return;
        if(!hasWorkPoint(npc)){say(player,"&eConfigura primero el punto normal de un goal Trabajo en Rutinas y horarios.");return;}
        UUID world=def.position().worldId();String worldName=def.position().worldName();
        if(world!=null?!world.equals(player.getWorld().getUID()):!worldName.equals(player.getWorld().getName())){say(player,"&cSelecciona las estaciones en el mundo del NPC.");return;}
        if(kind==Kind.SHORE || kind==Kind.BOAT){if(points(def.shopWork().fishing(),kind).size()>=FishingDefinition.MAX_POINTS){say(player,"&cYa hay "+FishingDefinition.MAX_POINTS+" puntos en esta lista.");return;}}
        cancel(player);cancelOtherInputs(player);player.closeInventory();
        selections.put(player.getUniqueId(),new Selection(npc,kind,guided,player.getWorld().getUID(),System.nanoTime()+600_000_000_000L,kind.fishing()?def.shopWork().fishing():kind.station(def.shopWork())));
        prompt(player,kind);
    }
    private static void prompt(Player player,Kind kind){
        if(kind.fishing()){
            say(player,"&eMarca &f"+kind.label+" &emirando hacia donde "+(kind==Kind.DOCK?"estará el agua y saldrá el bote":"lanzará la caña")+". Escribe &fcancelar &epara salir.");
            say(player,kind==Kind.BOAT?"&7Apunta al agua y haz clic derecho; se guarda su superficie y la dirección de tu mirada.":"&7Haz clic en el bloque del suelo; el NPC se situará encima de él.");return;
        }
        say(player,"&eMarca con clic izquierdo o derecho: &f"+kind.label+"&e. Escribe &fcancelar &epara salir.");
        if(kind==Kind.SMELTERY)say(player,"&7Se guarda el lugar del bloque; puedes quitarlo después y la fundición seguirá en ese espacio.");
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void select(PlayerInteractEvent event){
        Player player=event.getPlayer();Selection s=selections.get(player.getUniqueId());
        if(s==null || event.getHand()!=EquipmentSlot.HAND)return;
        boolean air=event.getAction()==Action.RIGHT_CLICK_AIR && s.kind()==Kind.BOAT;
        if(!air && (event.isCancelled() || event.getAction()!=Action.LEFT_CLICK_BLOCK && event.getAction()!=Action.RIGHT_CLICK_BLOCK))return;
        if(!validSelection(player,s))return;
        event.setCancelled(true);Block block=event.getClickedBlock();
        try{
            if(s.kind()==Kind.BOAT){
                var hit=player.rayTraceBlocks(16,FluidCollisionMode.ALWAYS);block=hit==null?null:hit.getHitBlock();
            }
            if(block==null){if(s.kind()==Kind.BOAT)say(player,"&eApunta al agua a menos de 16 bloques y haz clic derecho.");return;}
            if(s.kind().fishing()){saveFishingPoint(player,s,block);return;}
            if(s.kind()==Kind.CAULDRON && block.getType()!=Material.WATER_CAULDRON)throw new IllegalArgumentException("Selecciona un caldero con agua");
            if(s.kind()==Kind.ANVIL && block.getType()!=Material.ANVIL && block.getType()!=Material.CHIPPED_ANVIL && block.getType()!=Material.DAMAGED_ANVIL)throw new IllegalArgumentException("Selecciona un yunque");
            var station=new ShopWorkDefinition.Station(block.getWorld().getUID(),block.getWorld().getName(),block.getX(),block.getY(),block.getZ());
            save(player,s.npc(),y->{
                var latest=NpcParser.parse(y).get(s.npc());
                if(latest.mode()!=NpcDefinition.Mode.SHOP || latest.shopWork().category()!=ShopWorkDefinition.Category.BLACKSMITH
                        || !Objects.equals(s.kind().station(latest.shopWork()),s.previous()))throw new IllegalArgumentException("Otro administrador cambió esta estación; selecciónala de nuevo");
                String path=path(s.npc(),s.kind());y.set(path,null);y.set(path+".world",station.worldName());y.set(path+".world-uuid",station.worldId().toString());
                y.set(path+".x",station.x());y.set(path+".y",station.y());y.set(path+".z",station.z());
            });
            selections.remove(player.getUniqueId(),s);say(player,"&aEstación guardada: &f"+s.kind().label+" &7("+station.x()+", "+station.y()+", "+station.z()+")");
            if(s.guided() && s.kind()!=Kind.ANVIL)begin(player,s.npc(),Kind.values()[s.kind().ordinal()+1],true);
            else Bukkit.getScheduler().runTask(plugin,()->{if(player.isOnline() && valid(player,s.npc()))openStations(player,s.npc());});
        }catch(Exception ex){say(player,"&cNo se guardó: &f"+Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()));}
    }
    private void saveFishingPoint(Player player,Selection selection,Block block)throws Exception{
        Kind kind=selection.kind();
        if(!selection.world().equals(block.getWorld().getUID()))throw new IllegalArgumentException("Selecciona el punto en el mundo del NPC");
        if(kind==Kind.BOAT){
            if(block.getType()!=Material.WATER || block.getRelative(org.bukkit.block.BlockFace.UP).getType()==Material.WATER)
                throw new IllegalArgumentException("Selecciona la superficie del agua para la pesca en bote");
        }else if(block.getType().isAir() || block.isLiquid())throw new IllegalArgumentException("Selecciona un bloque de suelo para el punto en tierra o muelle");
        float yaw=player.getLocation().getYaw();yaw=(yaw%360+540)%360-180;
        var point=new FishingDefinition.Point(block.getWorld().getUID(),block.getWorld().getName(),block.getX()+.5,block.getY()+1,block.getZ()+.5,yaw);
        save(player,selection.npc(),yaml->{
            var latest=checkFishing(yaml,selection.npc());var fishing=latest.shopWork().fishing();
            if(!Objects.equals(fishing,selection.previous()))throw new IllegalArgumentException("Otro administrador cambió los puntos; selecciona de nuevo");
            String path=fishingPath(selection.npc(),kind);
            if(kind==Kind.DOCK){yaml.set(path,null);yaml.createSection(path,pointMap(point));}
            else{
                var list=new ArrayList<>(points(fishing,kind));
                if(list.size()>=FishingDefinition.MAX_POINTS)throw new IllegalArgumentException("Máximo "+FishingDefinition.MAX_POINTS+" puntos por lista");
                if(list.contains(point))throw new IllegalArgumentException("Este punto y dirección ya están guardados");
                list.add(point);yaml.set(path,list.stream().map(ShopWorkEditor::pointMap).toList());
            }
        });
        selections.remove(player.getUniqueId(),selection);say(player,"&aPunto guardado: &f"+kind.label+" &7("+point.x()+", "+point.y()+", "+point.z()+"; dirección "+point.yaw()+"°)");
        if(selection.guided() && kind!=Kind.BOAT)begin(player,selection.npc(),kind==Kind.SHORE?Kind.DOCK:Kind.BOAT,true);
        else Bukkit.getScheduler().runTask(plugin,()->{
            if(!player.isOnline() || !valid(player,selection.npc()))return;
            if(selection.guided() || kind==Kind.DOCK)openFishingStations(player,selection.npc());else openFishingPoints(player,selection.npc(),kind);
        });
    }
    private boolean validSelection(Player player,Selection s){
        var def=plugin.definitions().get(s.npc());
        if(!player.hasPermission("mdvnpc.admin") || System.nanoTime()>s.expires() || !s.world().equals(player.getWorld().getUID()) || def==null
                || def.mode()!=NpcDefinition.Mode.SHOP || def.shopWork().category()!=s.kind().category()){
            selections.remove(player.getUniqueId(),s);say(player,"&eSelección cancelada: cambiaron el permiso, el mundo, el NPC o venció el tiempo.");return false;
        }
        return true;
    }
    @EventHandler(priority=EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void chat(AsyncPlayerChatEvent event){
        Selection s=selections.get(event.getPlayer().getUniqueId());if(s==null)return;
        event.setCancelled(true);String text=event.getMessage().trim();UUID owner=event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin,()->{Player player=Bukkit.getPlayer(owner);if(player==null || selections.get(owner)!=s)return;
            if(text.equalsIgnoreCase("cancelar") || text.equalsIgnoreCase("cancel")){cancel(player);say(player,"&eSelección de estación cancelada.");openStations(player,s.npc());}
            else if(validSelection(player,s))prompt(player,s.kind());});
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void breakBlock(BlockBreakEvent event){if(selections.containsKey(event.getPlayer().getUniqueId()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    @EventHandler public void close(InventoryCloseEvent event){Holder h=menus.get(event.getPlayer().getUniqueId());if(h!=null && h.inventory==event.getInventory())menus.remove(event.getPlayer().getUniqueId(),h);}
    @EventHandler public void quit(PlayerQuitEvent event){cancel(event.getPlayer());}
    public void cancel(Player player){selections.remove(player.getUniqueId());menus.remove(player.getUniqueId());}
    public void closeAll(){
        selections.clear();var active=List.copyOf(menus.values());menus.clear();
        for(Holder h:active){Player player=Bukkit.getPlayer(h.owner);if(player!=null && player.getOpenInventory().getTopInventory()==h.inventory)player.closeInventory();}
    }
    private void cancelOtherInputs(Player player){
        if(plugin.routineCommands()!=null)plugin.routineCommands().cancelSelection(player);
        if(plugin.prefixEditor()!=null)plugin.prefixEditor().cancel(player);
        if(plugin.npcEditor()!=null)plugin.npcEditor().cancel(player);
    }
    private boolean hasWorkPoint(String npc){
        if(plugin.routines()==null)return false;var plan=plugin.routines().repository().snapshot().plans().get(npc);
        if(plan==null)return false;
        return plan.goals().stream().anyMatch(root->{for(int i=0;i<root.choiceCount();i++){var goal=root.choice(i);if(goal.type()==RoutineGoal.Type.WORK && !goal.points().isEmpty())return true;}return false;});
    }
    private boolean valid(Player player,String npc){if(!player.hasPermission("mdvnpc.admin"))return false;if(!plugin.definitions().containsKey(npc)){say(player,"&cEl NPC ya no existe.");return false;}return true;}
    private void save(Player player,String npc,Consumer<YamlConfiguration> mutation)throws Exception{
        if(!valid(player,npc))throw new IllegalArgumentException("El NPC ya no existe o no tienes permiso");
        plugin.shops().prepareReload();plugin.repository().edit(y->{if(y.getConfigurationSection("npcs."+npc)==null)throw new IllegalArgumentException("El NPC ya no existe");mutation.accept(y);});plugin.reloadNpcs();
    }
    private static String path(String npc,Kind kind){return "npcs."+npc+".shop.blacksmith.stations."+kind.key;}
    private static String fishingPath(String npc,Kind kind){return "npcs."+npc+".shop.fisherman."+kind.key;}
    private static List<FishingDefinition.Point> points(FishingDefinition fishing,Kind kind){return kind==Kind.SHORE?fishing.shorePoints():fishing.boatPoints();}
    private static NpcDefinition checkFishing(YamlConfiguration yaml,String npc){
        var latest=NpcParser.parse(yaml).get(npc);
        if(latest==null || latest.mode()!=NpcDefinition.Mode.SHOP || latest.shopWork().category()!=ShopWorkDefinition.Category.FISHERMAN)
            throw new IllegalArgumentException("Otro administrador cambió la categoría; abre el editor de nuevo");
        return latest;
    }
    private static Map<String,Object> pointMap(FishingDefinition.Point point){
        var row=new LinkedHashMap<String,Object>();row.put("world",point.worldName());if(point.worldId()!=null)row.put("world-uuid",point.worldId().toString());
        row.put("x",point.x());row.put("y",point.y());row.put("z",point.z());row.put("yaw",point.yaw());return row;
    }
    private static String coordinates(ShopWorkDefinition.Station s){return "&a"+s.worldName()+": "+s.x()+", "+s.y()+", "+s.z();}
    private static String coordinates(FishingDefinition.Point p){return "&a"+p.worldName()+": "+p.x()+", "+p.y()+", "+p.z();}
    private static String color(String text){return ChatColor.translateAlternateColorCodes('&',text);}
    private static void say(Player player,String text){player.sendMessage(color("&6[MDVNPC] "+text));}
    private static ItemStack item(Material material,String title,String...lore){ItemStack item=new ItemStack(material);var meta=item.getItemMeta();meta.setDisplayName(color(title));meta.setLore(Arrays.stream(lore).map(ShopWorkEditor::color).toList());item.setItemMeta(meta);return item;}
}
