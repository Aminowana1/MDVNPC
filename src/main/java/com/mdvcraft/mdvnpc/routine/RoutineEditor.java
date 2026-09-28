package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Inventory-driven routine editor. World point selection is delegated to RoutineCommands. */
public final class RoutineEditor implements Listener {
    private enum Screen { MAIN, GOAL, ADD, DIALOGUE, WALK_MODE }
    private enum InputKind { TIME, SPEED, RADIUS, MODE_TIME, DIALOG_INTERVAL, DIALOG_DELAY, DIALOG_RANGE, DIALOG_LINES }
    private static final class Holder implements InventoryHolder {
        final String npc; final Screen screen; final int page,goal; Inventory inventory;
        Holder(String npc,Screen screen,int page,int goal){this.npc=npc;this.screen=screen;this.page=page;this.goal=goal;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private static final class Input {
        final String npc; final int goal; final InputKind kind; final RoutineGoal.WalkMode mode; final List<String> lines=new ArrayList<>();
        Input(String npc,int goal,InputKind kind,RoutineGoal.WalkMode mode){this.npc=npc;this.goal=goal;this.kind=kind;this.mode=mode;}
    }
    private final MdvNpcPlugin plugin;
    private final RoutineCommands commands;
    private final Map<UUID,Input> inputs=new ConcurrentHashMap<>();
    RoutineEditor(MdvNpcPlugin plugin,RoutineCommands commands){this.plugin=plugin;this.commands=commands;}
    private RoutineRepository repo(){return plugin.routines().repository();}
    private static String c(String s){return ChatColor.translateAlternateColorCodes('&',s);}
    private static void say(Player p,String s){p.sendMessage(c("&6[MDVNPC] &f"+s));}

    public void cancelInput(Player player){inputs.remove(player.getUniqueId());}
    public void clear(){inputs.clear();}

    public void openMain(Player player,String npc){openMain(player,npc,0);}
    public void openMain(Player player,String npc,int page){
        if(!validNpc(player,npc))return;
        var plan=repo().snapshot().plans().get(npc); List<RoutineGoal> goals=plan==null?List.of():plan.goals();
        int pages=Math.max(1,(goals.size()+44)/45); page=Math.max(0,Math.min(page,pages-1));
        Holder h=new Holder(npc,Screen.MAIN,page,0); Inventory inv=Bukkit.createInventory(h,54,c("&2Rutina: &f"+npc));h.inventory=inv;
        int start=page*45;
        for(int slot=0;slot<45 && start+slot<goals.size();slot++) inv.setItem(slot,goalItem(goals.get(start+slot)));
        if(page>0)inv.setItem(45,item(Material.ARROW,"&ePágina anterior",List.of("&7Página "+(page+1)+" / "+pages)));
        boolean enabled=plan!=null && plan.enabled();
        inv.setItem(47,item(enabled?Material.LIME_DYE:Material.GRAY_DYE,enabled?"&aRutina activada":"&7Rutina desactivada",List.of("&eClic para cambiar")));
        inv.setItem(49,item(Material.EMERALD,"&aAgregar goal",List.of("&7Crear rápidamente un nuevo objetivo.")));
        inv.setItem(50,item(Material.BOOK,"&eEstado",List.of("&7"+plugin.routines().status(npc),"&7Goals: &f"+goals.size())));
        inv.setItem(51,item(Material.NAME_TAG,"&dRasgo del NPC",List.of("&7Actual: &f"+com.mdvcraft.mdvnpc.trait.TraitEditor.name(plugin.definitions().get(npc).traits().type()),"&eClic para asignar, cambiar o quitar")));
        if(page+1<pages)inv.setItem(53,item(Material.ARROW,"&ePágina siguiente",List.of("&7Página "+(page+2)+" / "+pages)));
        player.openInventory(inv);
    }
    public void openGoal(Player player,String npc,int order){
        RoutineGoal g=goal(npc,order); if(g==null){openMain(player,npc);return;}
        Holder h=new Holder(npc,Screen.GOAL,0,order); Inventory inv=Bukkit.createInventory(h,27,c("&2Goal #"+order+": &f"+typeName(g.type())));h.inventory=inv;
        inv.setItem(4,goalItem(g));
        inv.setItem(10,item(Material.CLOCK,"&eHorario",g.target()?List.of("&7Este modo es META y no usa horario."):List.of("&7Actual: &f"+RoutineSchedule.format(g.start())+" - "+RoutineSchedule.format(g.end()),"&eClic para cambiar")));
        inv.setItem(11,item(Material.COMPASS,"&eReasignar puntos",List.of("&7Actual: &f"+g.points().size()+" punto(s)","&7Al comenzar la selección se usan 0 puntos",pointHelp(g))));
        inv.setItem(12,item(Material.FEATHER,"&eVelocidad",List.of("&7Actual: &f"+g.speed()+" bloques/s","&eClic para cambiar")));
        if(g.type()==RoutineGoal.Type.WALK) inv.setItem(13,item(Material.MINECART,"&eModo de caminar",List.of("&7Actual: &f"+modeName(g.mode()),"&eClic para cambiar")));
        if(g.type()==RoutineGoal.Type.WALK && g.mode()==RoutineGoal.WalkMode.RANDOM) inv.setItem(14,item(Material.OAK_SAPLING,"&eRadio",List.of("&7Actual: &f"+g.radius()+" bloques","&eClic para cambiar")));
        var d=effectiveDialogue(npc,g);
        String dialogueState=!g.dialogue().configured() && g.type()==RoutineGoal.Type.WORK ? "&eHeredados del diálogo global" : d.enabled()?"&aActivados":"&7Desactivados";
        inv.setItem(15,item(Material.WRITABLE_BOOK,"&dDiálogos del goal",List.of(dialogueState,"&7Líneas: &f"+d.lines().size(),"&7Intervalo: &f"+d.intervalSeconds()+"s","&eClic para editar")));
        inv.setItem(16,item(Material.BARRIER,"&cEliminar goal",List.of("&7Elimina únicamente este goal.")));
        inv.setItem(22,item(Material.ARROW,"&eVolver",List.of()));
        player.openInventory(inv);
    }
    private void openAdd(Player player,String npc){
        Holder h=new Holder(npc,Screen.ADD,0,0); Inventory inv=Bukkit.createInventory(h,27,c("&2Agregar goal: &f"+npc));h.inventory=inv;
        inv.setItem(10,item(Material.RED_BED,"&bDormir",List.of("&7Seleccionar cama + horario.")));
        inv.setItem(11,item(Material.COMPASS,"&eCaminar META",List.of("&7Recorrido previo al siguiente goal.","&7Sin horario propio.")));
        inv.setItem(12,item(Material.GRASS_BLOCK,"&eCaminar ALEATORIO",List.of("&7Puntos posibles dentro de un horario.")));
        inv.setItem(13,item(Material.MINECART,"&eCaminar CICLO",List.of("&7Recorre los puntos en orden y repite.")));
        inv.setItem(14,item(Material.OAK_STAIRS,"&6Sentarse",List.of("&7Seleccionar una o varias sillas + horario.")));
        inv.setItem(15,item(Material.IRON_PICKAXE,"&aTrabajo",List.of("&7Seleccionar puesto + horario.")));
        inv.setItem(22,item(Material.ARROW,"&eVolver",List.of())); player.openInventory(inv);
    }
    private void openDialogue(Player player,String npc,int order){
        RoutineGoal g=goal(npc,order); if(g==null){openMain(player,npc);return;} var d=effectiveDialogue(npc,g);
        Holder h=new Holder(npc,Screen.DIALOGUE,0,order); Inventory inv=Bukkit.createInventory(h,36,c("&5Diálogos goal #"+order));h.inventory=inv;
        inv.setItem(10,item(d.enabled()?Material.LIME_DYE:Material.GRAY_DYE,d.enabled()?"&aDiálogos activados":"&7Diálogos desactivados",List.of("&eClic para cambiar")));
        inv.setItem(11,item(Material.CLOCK,"&eIntervalo",List.of("&7Cada: &f"+d.intervalSeconds()+"s","&eClic para cambiar")));
        inv.setItem(12,item(Material.REPEATER,"&eDemora inicial",List.of("&7Actual: &f"+d.initialDelaySeconds()+"s","&eClic para cambiar")));
        inv.setItem(13,item(Material.SPYGLASS,"&eRango",List.of("&7Actual: &f"+d.range()+" bloques","&eClic para cambiar")));
        inv.setItem(14,item(Material.FIREWORK_STAR,"&eOrden",List.of(d.random()?"&7Modo: &fAleatorio":"&7Modo: &fSecuencial","&eClic para cambiar")));
        inv.setItem(15,item(Material.ENDER_EYE,"&eLínea de visión",List.of(d.lineOfSight()?"&aRequerida":"&7No requerida","&eClic para cambiar")));
        List<String> lore=new ArrayList<>(); lore.add("&7Líneas actuales: &f"+d.lines().size());
        for(String line:d.lines().stream().limit(4).toList()) lore.add("&8- &7"+ChatColor.stripColor(c(line)).substring(0,Math.min(45,ChatColor.stripColor(c(line)).length())));
        lore.add("&eClic para reemplazarlas por chat");
        inv.setItem(16,item(Material.WRITABLE_BOOK,"&dEditar líneas",lore));
        inv.setItem(31,item(Material.ARROW,"&eVolver",List.of())); player.openInventory(inv);
    }
    private void openWalkMode(Player player,String npc,int order){
        RoutineGoal g=goal(npc,order); if(g==null || g.type()!=RoutineGoal.Type.WALK){openGoal(player,npc,order);return;}
        Holder h=new Holder(npc,Screen.WALK_MODE,0,order); Inventory inv=Bukkit.createInventory(h,27,c("&3Modo caminar #"+order));h.inventory=inv;
        inv.setItem(11,item(Material.COMPASS,"&eMETA",List.of("&7Recorre los puntos antes del siguiente goal.","&7No usa horario propio.",g.mode()==RoutineGoal.WalkMode.TARGET?"&aModo actual":"&eClic para seleccionar")));
        inv.setItem(13,item(Material.GRASS_BLOCK,"&eALEATORIO",List.of("&7Elige puntos dentro del radio durante su horario.",g.mode()==RoutineGoal.WalkMode.RANDOM?"&aModo actual":"&eClic para seleccionar")));
        inv.setItem(15,item(Material.MINECART,"&eCICLO",List.of("&7Recorre los puntos en orden y repite.",g.mode()==RoutineGoal.WalkMode.CYCLE?"&aModo actual":"&eClic para seleccionar")));
        inv.setItem(22,item(Material.ARROW,"&eVolver",List.of())); player.openInventory(inv);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void inventory(InventoryClickEvent event){
        if(!(event.getInventory().getHolder() instanceof Holder h))return; event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory()!=event.getInventory())return;
        if(!validNpc(p,h.npc))return;
        try {
            switch(h.screen){
                case MAIN -> clickMain(p,h,event.getRawSlot());
                case GOAL -> clickGoal(p,h,event.getRawSlot());
                case ADD -> clickAdd(p,h,event.getRawSlot());
                case DIALOGUE -> clickDialogue(p,h,event.getRawSlot());
                case WALK_MODE -> clickWalkMode(p,h,event.getRawSlot());
            }
        } catch(Exception ex){say(p,"&cNo se pudo editar: &f"+(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()));}
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event){
        if(event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    private void clickMain(Player p,Holder h,int slot)throws Exception{
        var plan=repo().snapshot().plans().get(h.npc); List<RoutineGoal> goals=plan==null?List.of():plan.goals(); int index=h.page*45+slot;
        if(slot>=0 && slot<45 && index<goals.size()){openGoal(p,h.npc,goals.get(index).order());return;}
        if(slot==45 && h.page>0){openMain(p,h.npc,h.page-1);return;}
        if(slot==53){openMain(p,h.npc,h.page+1);return;}
        if(slot==47){boolean enabled=plan==null || !plan.enabled();repo().edit(y->y.set("npcs."+h.npc+".enabled",enabled));reload();openMain(p,h.npc,h.page);return;}
        if(slot==49){openAdd(p,h.npc);return;}
        if(slot==51){
            Bukkit.getScheduler().runTask(plugin,()->{
                if(p.isOnline() && p.getOpenInventory().getTopInventory()==h.inventory && validNpc(p,h.npc))
                    plugin.traitEditor().open(p,h.npc,h.page);
            });return;
        }
    }
    private void clickGoal(Player p,Holder h,int slot)throws Exception{
        RoutineGoal g=goal(h.npc,h.goal); if(g==null){openMain(p,h.npc);return;}
        switch(slot){
            case 10 -> { if(g.target())say(p,"&7Los goals META no tienen horario propio."); else input(p,h.npc,h.goal,InputKind.TIME,null,"&eEscribe &fHH:mm HH:mm &e(inicio y fin), o &fcancelar&e."); }
            case 11 -> commands.beginReselect(p,h.npc,g);
            case 12 -> input(p,h.npc,h.goal,InputKind.SPEED,null,"&eEscribe la nueva velocidad &f0.2..6&e, o &fcancelar&e.");
            case 13 -> { if(g.type()==RoutineGoal.Type.WALK)openWalkMode(p,h.npc,h.goal); }
            case 14 -> { if(g.type()==RoutineGoal.Type.WALK && g.mode()==RoutineGoal.WalkMode.RANDOM)input(p,h.npc,h.goal,InputKind.RADIUS,null,"&eEscribe el radio &f1..128&e, o &fcancelar&e."); }
            case 15 -> openDialogue(p,h.npc,h.goal);
            case 16 -> { repo().edit(y->y.set("npcs."+h.npc+".goals."+h.goal,null));reload();say(p,"&aGoal eliminado.");openMain(p,h.npc); }
            case 22 -> openMain(p,h.npc);
        }
    }
    private void clickAdd(Player p,Holder h,int slot){
        int order=nextOrder(h.npc); if(order<0){say(p,"&cEste NPC ya tiene 100 goals.");return;}
        switch(slot){
            case 10 -> commands.beginNew(p,h.npc,order,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE);
            case 11 -> commands.beginNew(p,h.npc,order,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.TARGET);
            case 12 -> commands.beginNew(p,h.npc,order,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.RANDOM);
            case 13 -> commands.beginNew(p,h.npc,order,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.CYCLE);
            case 14 -> commands.beginNew(p,h.npc,order,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE);
            case 15 -> commands.beginNew(p,h.npc,order,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE);
            case 22 -> openMain(p,h.npc);
        }
    }
    private void clickDialogue(Player p,Holder h,int slot)throws Exception{
        RoutineGoal g=goal(h.npc,h.goal); if(g==null){openMain(p,h.npc);return;} var d=effectiveDialogue(h.npc,g);
        switch(slot){
            case 10 -> save(p,h.npc,g.withDialogue(new RoutineGoal.Dialogue(!d.enabled(),d.range(),d.intervalSeconds(),d.initialDelaySeconds(),d.random(),d.lineOfSight(),d.lines())),()->openDialogue(p,h.npc,h.goal));
            case 11 -> input(p,h.npc,h.goal,InputKind.DIALOG_INTERVAL,null,"&eEscribe el intervalo en segundos &f0.5..86400&e.");
            case 12 -> input(p,h.npc,h.goal,InputKind.DIALOG_DELAY,null,"&eEscribe la demora inicial en segundos &f0..86400&e.");
            case 13 -> input(p,h.npc,h.goal,InputKind.DIALOG_RANGE,null,"&eEscribe el rango del diálogo &f0..64&e.");
            case 14 -> save(p,h.npc,g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),d.range(),d.intervalSeconds(),d.initialDelaySeconds(),!d.random(),d.lineOfSight(),d.lines())),()->openDialogue(p,h.npc,h.goal));
            case 15 -> save(p,h.npc,g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),d.range(),d.intervalSeconds(),d.initialDelaySeconds(),d.random(),!d.lineOfSight(),d.lines())),()->openDialogue(p,h.npc,h.goal));
            case 16 -> input(p,h.npc,h.goal,InputKind.DIALOG_LINES,null,"&dEscribe las nuevas líneas una por una. &f'fin' &dguarda, &f'cancelar' &dcancela. Se reemplazarán las anteriores.");
            case 31 -> openGoal(p,h.npc,h.goal);
        }
    }
    private void clickWalkMode(Player p,Holder h,int slot)throws Exception{
        RoutineGoal g=goal(h.npc,h.goal); if(g==null || g.type()!=RoutineGoal.Type.WALK){openGoal(p,h.npc,h.goal);return;}
        if(slot==22){openGoal(p,h.npc,h.goal);return;}
        RoutineGoal.WalkMode selected=switch(slot){case 11->RoutineGoal.WalkMode.TARGET;case 13->RoutineGoal.WalkMode.RANDOM;case 15->RoutineGoal.WalkMode.CYCLE;default->null;};
        if(selected==null || selected==g.mode())return;
        if(g.mode()==RoutineGoal.WalkMode.TARGET && selected!=RoutineGoal.WalkMode.TARGET){
            input(p,h.npc,h.goal,InputKind.MODE_TIME,selected,"&eEl modo será &f"+modeName(selected)+"&e. Escribe &fHH:mm HH:mm &epara asignarle horario.");
            return;
        }
        save(p,h.npc,g.withMode(selected),()->openGoal(p,h.npc,h.goal));
    }
    private void input(Player p,String npc,int goal,InputKind kind,RoutineGoal.WalkMode mode,String prompt){
        inputs.put(p.getUniqueId(),new Input(npc,goal,kind,mode)); p.closeInventory(); say(p,prompt);
    }
    @EventHandler(priority=EventPriority.LOWEST) @SuppressWarnings("deprecation")
    public void chat(AsyncPlayerChatEvent event){
        Input in=inputs.get(event.getPlayer().getUniqueId()); if(in==null)return; event.setCancelled(true); String text=event.getMessage().trim(); UUID id=event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin,()->{Player p=Bukkit.getPlayer(id);if(p==null || inputs.get(id)!=in)return;handleInput(p,in,text);});
    }
    private void handleInput(Player p,Input in,String text){
        try{
            if(text.equalsIgnoreCase("cancelar")||text.equalsIgnoreCase("cancel")){inputs.remove(p.getUniqueId(),in);say(p,"&7Edición cancelada.");openGoal(p,in.npc,in.goal);return;}
            RoutineGoal g=goal(in.npc,in.goal);if(g==null){inputs.remove(p.getUniqueId(),in);say(p,"&cEse goal ya no existe.");openMain(p,in.npc);return;}
            if(in.kind==InputKind.DIALOG_LINES){
                if(text.equalsIgnoreCase("fin")){var d=effectiveDialogue(in.npc,g);save(p,in.npc,g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),d.range(),d.intervalSeconds(),d.initialDelaySeconds(),d.random(),d.lineOfSight(),in.lines)),()->openDialogue(p,in.npc,in.goal));inputs.remove(p.getUniqueId(),in);return;}
                if(in.lines.size()>=128)throw new IllegalArgumentException("Máximo 128 líneas"); in.lines.add(text);say(p,"&aLínea "+in.lines.size()+" agregada. &7Escribe otra o &ffin&7.");return;
            }
            RoutineGoal updated;
            switch(in.kind){
                case TIME -> {int[] t=times(text);updated=g.withTimes(t[0],t[1]);}
                case SPEED -> updated=g.withSpeed(number(text,.2,6,"velocidad"));
                case RADIUS -> updated=g.withRadius(number(text,1,128,"radio"));
                case MODE_TIME -> {int[] t=times(text);updated=new RoutineGoal(g.order(),g.type(),in.mode,t[0],t[1],g.speed(),g.radius(),g.points(),g.dialogue());}
                case DIALOG_INTERVAL -> {var d=effectiveDialogue(in.npc,g);updated=g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),d.range(),number(text,.5,86400,"intervalo"),d.initialDelaySeconds(),d.random(),d.lineOfSight(),d.lines()));}
                case DIALOG_DELAY -> {var d=effectiveDialogue(in.npc,g);updated=g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),d.range(),d.intervalSeconds(),number(text,0,86400,"demora"),d.random(),d.lineOfSight(),d.lines()));}
                case DIALOG_RANGE -> {var d=effectiveDialogue(in.npc,g);updated=g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),number(text,0,64,"rango"),d.intervalSeconds(),d.initialDelaySeconds(),d.random(),d.lineOfSight(),d.lines()));}
                default -> throw new IllegalStateException("Entrada no soportada");
            }
            save(p,in.npc,updated,()->openGoal(p,in.npc,in.goal));inputs.remove(p.getUniqueId(),in);
        }catch(Exception ex){say(p,"&cValor inválido: &f"+(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()));say(p,"&7Intenta de nuevo o escribe &fcancelar&7.");}
    }
    private void save(Player p,String npc,RoutineGoal goal,Runnable after)throws Exception{plugin.shops().prepareReload();repo().put(npc,goal);reload();say(p,"&aGoal #"+goal.order()+" actualizado.");after.run();}
    private void reload()throws Exception{plugin.reloadNpcs();}
    private RoutineGoal.Dialogue effectiveDialogue(String npc,RoutineGoal g){
        var d=g.dialogue(); if(d.configured() || g.type()!=RoutineGoal.Type.WORK)return d;
        var def=plugin.definitions().get(npc); if(def==null)return d; var legacy=def.dialogue();
        return new RoutineGoal.Dialogue(legacy.enabled(),legacy.range(),legacy.intervalSeconds(),legacy.initialDelaySeconds(),legacy.random(),legacy.lineOfSight(),legacy.lines(),false);
    }
    private RoutineGoal goal(String npc,int order){var plan=repo().snapshot().plans().get(npc);return plan==null?null:plan.goals().stream().filter(g->g.order()==order).findFirst().orElse(null);}
    private int nextOrder(String npc){var plan=repo().snapshot().plans().get(npc);Set<Integer> used=new HashSet<>();if(plan!=null)plan.goals().forEach(g->used.add(g.order()));for(int i=1;i<=100;i++)if(!used.contains(i))return i;return -1;}
    private boolean validNpc(Player p,String npc){if(!p.hasPermission("mdvnpc.admin")){return false;}if(!plugin.definitions().containsKey(npc)){say(p,"&cNPC no encontrado: &f"+npc);return false;}return true;}
    private static int[] times(String text){String[] v=text.trim().split("\\s+");if(v.length!=2)throw new IllegalArgumentException("usa HH:mm HH:mm");return new int[]{RoutineSchedule.parseHour(v[0]),RoutineSchedule.parseHour(v[1])};}
    private static double number(String text,double min,double max,String name){double n=Double.parseDouble(text.replace(',','.'));if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(name+" debe estar entre "+min+" y "+max);return n;}
    private static String typeName(RoutineGoal.Type t){return switch(t){case SLEEP->"Dormir";case WALK->"Caminar";case SIT->"Sentarse";case WORK->"Trabajo";};}
    private static String modeName(RoutineGoal.WalkMode m){return switch(m){case TARGET->"META";case RANDOM->"ALEATORIO";case CYCLE->"CICLO";};}
    private static String pointHelp(RoutineGoal g){return switch(g.type()){case SLEEP->"&8Seleccionarás nuevamente la cama.";case SIT->"&8Seleccionarás nuevamente las sillas.";case WORK->"&8Seleccionarás nuevamente el puesto.";case WALK->"&8Marcarás nuevamente el recorrido.";};}
    private static ItemStack goalItem(RoutineGoal g){
        Material m=switch(g.type()){case SLEEP->Material.RED_BED;case WALK->Material.LEATHER_BOOTS;case SIT->Material.OAK_STAIRS;case WORK->Material.IRON_PICKAXE;};
        List<String> lore=new ArrayList<>();lore.add("&7Tipo: &f"+typeName(g.type()));if(g.type()==RoutineGoal.Type.WALK)lore.add("&7Modo: &f"+modeName(g.mode()));
        lore.add(g.target()?"&7Horario: &fMETA / sin horario":"&7Horario: &f"+RoutineSchedule.format(g.start())+" - "+RoutineSchedule.format(g.end()));lore.add("&7Puntos: &f"+g.points().size());lore.add("&7Velocidad: &f"+g.speed()+" b/s");
        if(!g.dialogue().configured() && g.type()==RoutineGoal.Type.WORK)lore.add("&dDiálogo: &eheredado del NPC");else lore.add(g.dialogue().enabled()?"&dDiálogo: &aactivo (&f"+g.dialogue().lines().size()+"&a líneas)":"&dDiálogo: &7inactivo");lore.add("&eClic para editar");
        return item(m,"&6Goal #"+g.order()+" &8- &f"+typeName(g.type()),lore);
    }
    private static ItemStack item(Material material,String name,List<String> lore){ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();meta.setDisplayName(c(name));meta.setLore(lore.stream().map(RoutineEditor::c).toList());stack.setItemMeta(meta);return stack;}
    @EventHandler public void quit(PlayerQuitEvent event){inputs.remove(event.getPlayer().getUniqueId());}
}
