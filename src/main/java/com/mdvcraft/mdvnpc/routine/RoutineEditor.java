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
    private enum Screen { MAIN, GOAL, ADD, DIALOGUE, WALK_MODE, OPTIONS }
    private enum InputKind { TIME, SPEED, RADIUS, MODE_TIME, DIALOG_INTERVAL, DIALOG_DELAY, DIALOG_RANGE, DIALOG_LINES }
    private static final class Holder implements InventoryHolder {
        final String npc; final Screen screen; final int page,goal,option; Inventory inventory;
        Holder(String npc,Screen screen,int page,int goal){this(npc,screen,page,goal,0);}
        Holder(String npc,Screen screen,int page,int goal,int option){this.npc=npc;this.screen=screen;this.page=page;this.goal=goal;this.option=option;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private static final class Input {
        final String npc; final int goal,option; final InputKind kind; final RoutineGoal.WalkMode mode; final List<String> lines=new ArrayList<>();
        Input(String npc,int goal,int option,InputKind kind,RoutineGoal.WalkMode mode){this.npc=npc;this.goal=goal;this.option=option;this.kind=kind;this.mode=mode;}
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
        if(plugin.workEditor()!=null)plugin.workEditor().cancel(player);
        if(plugin.prefixEditor()!=null)plugin.prefixEditor().cancel(player);
        var plan=repo().snapshot().plans().get(npc); List<RoutineGoal> goals=plan==null?List.of():plan.goals();
        int pages=Math.max(1,(goals.size()+44)/45); page=Math.max(0,Math.min(page,pages-1));
        Holder h=new Holder(npc,Screen.MAIN,page,0); Inventory inv=Bukkit.createInventory(h,54,c("&2Rutina: &f"+npc));h.inventory=inv;
        int start=page*45;
        for(int slot=0;slot<45 && start+slot<goals.size();slot++) inv.setItem(slot,goalItem(goals.get(start+slot)));
        if(page>0)inv.setItem(45,item(Material.ARROW,"&ePágina anterior",List.of("&7Página "+(page+1)+" / "+pages)));
        inv.setItem(46,item(Material.CRAFTING_TABLE,"&bEditar NPC",List.of("&7Nombre, trabajo, instrumento y tienda.","&eClic para abrir")));
        boolean enabled=plan!=null && plan.enabled();
        inv.setItem(47,item(enabled?Material.LIME_DYE:Material.GRAY_DYE,enabled?"&aRutina activada":"&7Rutina desactivada",List.of("&eClic para cambiar")));
        inv.setItem(49,item(Material.EMERALD,"&aAgregar goal",List.of("&7Crear rápidamente un nuevo objetivo.")));
        inv.setItem(50,item(Material.BOOK,"&eEstado",List.of("&7"+plugin.routines().status(npc),"&7Goals: &f"+goals.size())));
        inv.setItem(51,item(Material.NAME_TAG,"&dRasgo del NPC",List.of("&7Actual: &f"+com.mdvcraft.mdvnpc.trait.TraitEditor.name(plugin.definitions().get(npc).traits().type()),"&eClic para asignar, cambiar o quitar")));
        inv.setItem(52,item(Material.WRITABLE_BOOK,"&dPrefijo de los diálogos",List.of("&7Personaliza cómo habla este NPC.","&eClic para editar")));
        if(page+1<pages)inv.setItem(53,item(Material.ARROW,"&ePágina siguiente",List.of("&7Página "+(page+2)+" / "+pages)));
        player.openInventory(inv);
    }
    public void openGoal(Player player,String npc,int order){
        openChoice(player,npc,order,0);
    }
    public void openChoice(Player player,String npc,int order,int option){
        RoutineGoal root=goal(npc,order); if(root==null){openMain(player,npc);return;}
        if(option<0 || option>=root.choiceCount()){openOptions(player,npc,order);return;}
        RoutineGoal g=option==0?root:root.choice(option);
        Holder h=new Holder(npc,Screen.GOAL,0,order,option); Inventory inv=Bukkit.createInventory(h,27,c("&2Goal #"+order+(option==0?" principal":" opción "+(option+1))));h.inventory=inv;
        inv.setItem(4,goalItem(g,root));
        inv.setItem(10,item(Material.CLOCK,"&eHorario",g.target()?List.of("&7Este modo es META y no usa horario."):List.of("&7Actual: &f"+RoutineSchedule.format(g.start())+" - "+RoutineSchedule.format(g.end()),option==0?"&eClic para cambiar todas las opciones":"&7Heredado del horario principal.")));
        inv.setItem(11,item(Material.COMPASS,"&eReasignar puntos",List.of("&7Actual: &f"+g.points().size()+" punto(s)","&7Al comenzar la selección se usan 0 puntos",pointHelp(g))));
        inv.setItem(12,item(Material.FEATHER,"&eVelocidad",List.of("&7Actual: &f"+g.speed()+" bloques/s","&eClic para cambiar")));
        if(g.type()==RoutineGoal.Type.WALK) inv.setItem(13,item(Material.MINECART,"&eModo de caminar",List.of("&7Actual: &f"+modeName(g.mode()),"&eClic para cambiar")));
        if(g.type()==RoutineGoal.Type.WALK && g.mode()==RoutineGoal.WalkMode.RANDOM) inv.setItem(14,item(Material.OAK_SAPLING,"&eRadio",List.of("&7Actual: &f"+g.radius()+" bloques","&eClic para cambiar")));
        var d=effectiveDialogue(npc,g);
        String dialogueState=!g.dialogue().configured() && g.type()==RoutineGoal.Type.WORK ? "&eHeredados del diálogo global" : d.enabled()?"&aActivados":"&7Desactivados";
        inv.setItem(15,item(Material.WRITABLE_BOOK,"&dDiálogos del goal",List.of(dialogueState,"&7Líneas: &f"+d.lines().size(),"&7Intervalo: &f"+d.intervalSeconds()+"s","&eClic para editar")));
        inv.setItem(16,item(Material.BARRIER,option==0?"&cEliminar goal":"&cEliminar esta opción",List.of(option==0?"&7Elimina el horario y todas sus opciones.":"&7Conserva las otras opciones.")));
        inv.setItem(18,item(Material.CHEST,"&bOpciones del horario",List.of("&7Modo: &f"+(root.randomChoice()?"Aleatorio":"Fijo (principal)"),"&7Opciones: &f"+root.choiceCount(),"&7Se elige una vez por horario / día.","&eClic para gestionar")));
        inv.setItem(19,workInteractionItem(root));
        inv.setItem(20,item(Material.CRAFTING_TABLE,"&eCambiar actividad",List.of("&7Elegir dormir, sentarse, caminar o trabajar.","&7Seleccionarás sus nuevos destinos.")));
        inv.setItem(22,item(Material.ARROW,"&eVolver",List.of()));
        player.openInventory(inv);
    }
    public void openOptions(Player player,String npc,int order){
        RoutineGoal root=goal(npc,order);if(root==null){openMain(player,npc);return;}
        Holder h=new Holder(npc,Screen.OPTIONS,0,order);Inventory inv=Bukkit.createInventory(h,27,c("&3Opciones del goal #"+order));h.inventory=inv;
        for(int option=0;option<root.choiceCount();option++){
            RoutineGoal g=root.choice(option);List<String> lore=new ArrayList<>();
            lore.add("&7Actividad: &f"+typeName(g.type()));if(g.type()==RoutineGoal.Type.WALK)lore.add("&7Caminar: &f"+modeName(g.mode()));
            var point=g.points().getFirst();lore.add("&7Destino: &f"+point.x()+", "+point.y()+", "+point.z());lore.add("&7Puntos: &f"+g.points().size());
            lore.add(workInteractionSummary(root));
            if(g.type()==RoutineGoal.Type.WORK)lore.add("&7Trabajo atiende en su puesto.");
            lore.add(option==0?"&7Se usa siempre en modo fijo.":"&7Participa en el sorteo con la principal.");lore.add("&eClic para editar");
            inv.setItem(option,item(material(g.type()),"&6Opción "+(option+1)+(option==0?" &f(principal)":""),lore));
        }
        inv.setItem(19,item(root.randomChoice()?Material.LIME_DYE:Material.GRAY_DYE,root.randomChoice()?"&aSelección ALEATORIA":"&eSelección FIJA",List.of("&7Fija: usa siempre la opción principal.","&7Aleatoria: misma probabilidad por opción.","&7Una elección al comenzar el horario / día.","&eClic para cambiar")));
        inv.setItem(21,item(Material.EMERALD,"&aAñadir opción",List.of("&7Otro destino o actividad en este horario.","&7Máximo "+RoutineGoal.MAX_CHOICES+" opciones, incluida la principal.","&eClic para elegir actividad")));
        inv.setItem(23,workInteractionItem(root));
        inv.setItem(22,item(Material.ARROW,"&eVolver",List.of()));player.openInventory(inv);
    }
    private void openAdd(Player player,String npc){
        openAdd(player,npc,0,0);
    }
    private void openAdd(Player player,String npc,int order,int option){
        RoutineGoal root=order==0?null:goal(npc,order);boolean meta=root!=null && root.target();
        Holder h=new Holder(npc,Screen.ADD,0,order,option); Inventory inv=Bukkit.createInventory(h,27,c(order==0?"&2Agregar goal: &f"+npc:"&2Actividad para opción #"+(option+1)));h.inventory=inv;
        if(!meta)inv.setItem(10,item(Material.RED_BED,"&bDormir",List.of(order==0?"&7Seleccionar cama + horario.":"&7Seleccionar cama; hereda el horario.")));
        if(root==null || meta)inv.setItem(11,item(Material.COMPASS,"&eCaminar META",List.of("&7Recorrido previo al siguiente goal.","&7Sin horario propio.")));
        if(!meta)inv.setItem(12,item(Material.GRASS_BLOCK,"&eCaminar ALEATORIO",List.of("&7Puntos posibles dentro de un horario.")));
        if(!meta)inv.setItem(13,item(Material.MINECART,"&eCaminar CICLO",List.of("&7Recorre los puntos en orden y repite.")));
        if(!meta)inv.setItem(14,item(Material.OAK_STAIRS,"&6Sentarse",List.of(order==0?"&7Seleccionar una o varias sillas + horario.":"&7Seleccionar sillas; hereda el horario.")));
        if(!meta)inv.setItem(15,item(Material.IRON_PICKAXE,"&aTrabajo",List.of(order==0?"&7Seleccionar puesto + horario.":"&7Seleccionar puesto; hereda el horario.")));
        inv.setItem(22,item(Material.ARROW,"&eVolver",List.of())); player.openInventory(inv);
    }
    private void openDialogue(Player player,String npc,int order){
        openDialogue(player,npc,order,0);
    }
    private void openDialogue(Player player,String npc,int order,int option){
        RoutineGoal g=choice(npc,order,option); if(g==null){openMain(player,npc);return;} var d=effectiveDialogue(npc,g);
        Holder h=new Holder(npc,Screen.DIALOGUE,0,order,option); Inventory inv=Bukkit.createInventory(h,36,c("&5Diálogos opción #"+(option+1)));h.inventory=inv;
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
        openWalkMode(player,npc,order,0);
    }
    private void openWalkMode(Player player,String npc,int order,int option){
        RoutineGoal root=goal(npc,order),g=choice(npc,order,option); if(g==null || g.type()!=RoutineGoal.Type.WALK){openChoice(player,npc,order,option);return;}
        Holder h=new Holder(npc,Screen.WALK_MODE,0,order,option); Inventory inv=Bukkit.createInventory(h,27,c("&3Modo caminar #"+order));h.inventory=inv;
        if(option==0 && root.alternatives().isEmpty() || root.target())inv.setItem(11,item(Material.COMPASS,"&eMETA",List.of("&7Recorre los puntos antes del siguiente goal.","&7No usa horario propio.",g.mode()==RoutineGoal.WalkMode.TARGET?"&aModo actual":"&eClic para seleccionar")));
        if(!root.target() || root.alternatives().isEmpty() && option==0)inv.setItem(13,item(Material.GRASS_BLOCK,"&eALEATORIO",List.of("&7Elige puntos dentro del radio durante su horario.",g.mode()==RoutineGoal.WalkMode.RANDOM?"&aModo actual":"&eClic para seleccionar")));
        if(!root.target() || root.alternatives().isEmpty() && option==0)inv.setItem(15,item(Material.MINECART,"&eCICLO",List.of("&7Recorre los puntos en orden y repite.",g.mode()==RoutineGoal.WalkMode.CYCLE?"&aModo actual":"&eClic para seleccionar")));
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
                case OPTIONS -> clickOptions(p,h,event.getRawSlot());
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
        if(slot==46){Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline() && p.getOpenInventory().getTopInventory()==h.inventory && validNpc(p,h.npc))plugin.npcEditor().open(p,h.npc);});return;}
        if(slot==52){
            Bukkit.getScheduler().runTask(plugin,()->{
                if(p.isOnline() && p.getOpenInventory().getTopInventory()==h.inventory && validNpc(p,h.npc))
                    plugin.prefixEditor().open(p,h.npc,h.page);
            });return;
        }
        if(slot==51){
            Bukkit.getScheduler().runTask(plugin,()->{
                if(p.isOnline() && p.getOpenInventory().getTopInventory()==h.inventory && validNpc(p,h.npc))
                    plugin.traitEditor().open(p,h.npc,h.page);
            });return;
        }
    }
    private void clickGoal(Player p,Holder h,int slot)throws Exception{
        RoutineGoal g=choice(h.npc,h.goal,h.option); if(g==null){openMain(p,h.npc);return;}
        switch(slot){
            case 10 -> { if(g.target())say(p,"&7Los goals META no tienen horario propio."); else if(h.option>0)say(p,"&7Edita el horario en la opción principal; se aplica a todas.");else input(p,h,InputKind.TIME,null,"&eEscribe &fHH:mm HH:mm &e(inicio y fin), o &fcancelar&e."); }
            case 11 -> commands.beginReselect(p,h.npc,g,h.option);
            case 12 -> input(p,h,InputKind.SPEED,null,"&eEscribe la nueva velocidad &f0.2..6&e, o &fcancelar&e.");
            case 13 -> { if(g.type()==RoutineGoal.Type.WALK)openWalkMode(p,h.npc,h.goal,h.option); }
            case 14 -> { if(g.type()==RoutineGoal.Type.WALK && g.mode()==RoutineGoal.WalkMode.RANDOM)input(p,h,InputKind.RADIUS,null,"&eEscribe el radio &f1..128&e, o &fcancelar&e."); }
            case 15 -> openDialogue(p,h.npc,h.goal,h.option);
            case 16 -> {
                if(h.option>0){save(p,h.npc,goal(h.npc,h.goal).withoutChoice(h.option),()->openOptions(p,h.npc,h.goal));}
                else{repo().edit(y->y.set("npcs."+h.npc+".goals."+h.goal,null));reload();say(p,"&aGoal eliminado.");openMain(p,h.npc);}
            }
            case 18 -> openOptions(p,h.npc,h.goal);
            case 19 -> {RoutineGoal root=goal(h.npc,h.goal);save(p,h.npc,root.withWorkInteraction(!root.workInteraction()),()->openChoice(p,h.npc,h.goal,h.option));}
            case 20 -> openAdd(p,h.npc,h.goal,h.option);
            case 22 -> {if(h.option>0)openOptions(p,h.npc,h.goal);else openMain(p,h.npc);}
        }
    }
    private void clickAdd(Player p,Holder h,int slot){
        if(slot==22){if(h.goal==0)openMain(p,h.npc);else openOptions(p,h.npc,h.goal);return;}
        if(h.inventory.getItem(slot)==null)return;
        int order=h.goal==0?nextOrder(h.npc):h.goal; if(order<0){say(p,"&cEste NPC ya tiene 100 goals.");return;}
        RoutineGoal.Type type=switch(slot){case 10->RoutineGoal.Type.SLEEP;case 11,12,13->RoutineGoal.Type.WALK;case 14->RoutineGoal.Type.SIT;case 15->RoutineGoal.Type.WORK;default->null;};if(type==null)return;
        RoutineGoal.WalkMode mode=switch(slot){case 11->RoutineGoal.WalkMode.TARGET;case 12->RoutineGoal.WalkMode.RANDOM;default->RoutineGoal.WalkMode.CYCLE;};
        if(h.goal==0)commands.beginNew(p,h.npc,order,type,mode);else commands.beginOption(p,h.npc,order,h.option,type,mode);
    }
    private void clickOptions(Player p,Holder h,int slot)throws Exception{
        RoutineGoal root=goal(h.npc,h.goal);if(root==null){openMain(p,h.npc);return;}
        if(slot>=0 && slot<root.choiceCount()){openChoice(p,h.npc,h.goal,slot);return;}
        switch(slot){
            case 19 -> {if(root.choiceCount()==1)say(p,"&7Añade otra opción para activar el sorteo.");else save(p,h.npc,root.withRandomChoice(!root.randomChoice()),()->openOptions(p,h.npc,h.goal));}
            case 21 -> {if(root.choiceCount()>=RoutineGoal.MAX_CHOICES)say(p,"&cMáximo "+RoutineGoal.MAX_CHOICES+" opciones.");else openAdd(p,h.npc,h.goal,root.choiceCount());}
            case 22 -> openGoal(p,h.npc,h.goal);
            case 23 -> save(p,h.npc,root.withWorkInteraction(!root.workInteraction()),()->openOptions(p,h.npc,h.goal));
        }
    }
    private void clickDialogue(Player p,Holder h,int slot)throws Exception{
        RoutineGoal g=choice(h.npc,h.goal,h.option); if(g==null){openMain(p,h.npc);return;} var d=effectiveDialogue(h.npc,g);
        switch(slot){
            case 10 -> save(p,h,g.withDialogue(new RoutineGoal.Dialogue(!d.enabled(),d.range(),d.intervalSeconds(),d.initialDelaySeconds(),d.random(),d.lineOfSight(),d.lines())),()->openDialogue(p,h.npc,h.goal,h.option));
            case 11 -> input(p,h,InputKind.DIALOG_INTERVAL,null,"&eEscribe el intervalo en segundos &f0.5..86400&e.");
            case 12 -> input(p,h,InputKind.DIALOG_DELAY,null,"&eEscribe la demora inicial en segundos &f0..86400&e.");
            case 13 -> input(p,h,InputKind.DIALOG_RANGE,null,"&eEscribe el rango del diálogo &f0..64&e.");
            case 14 -> save(p,h,g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),d.range(),d.intervalSeconds(),d.initialDelaySeconds(),!d.random(),d.lineOfSight(),d.lines())),()->openDialogue(p,h.npc,h.goal,h.option));
            case 15 -> save(p,h,g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),d.range(),d.intervalSeconds(),d.initialDelaySeconds(),d.random(),!d.lineOfSight(),d.lines())),()->openDialogue(p,h.npc,h.goal,h.option));
            case 16 -> input(p,h,InputKind.DIALOG_LINES,null,"&dEscribe las nuevas líneas una por una. &f'fin' &dguarda, &f'cancelar' &dcancela. Se reemplazarán las anteriores.");
            case 31 -> openChoice(p,h.npc,h.goal,h.option);
        }
    }
    private void clickWalkMode(Player p,Holder h,int slot)throws Exception{
        RoutineGoal g=choice(h.npc,h.goal,h.option); if(g==null || g.type()!=RoutineGoal.Type.WALK){openChoice(p,h.npc,h.goal,h.option);return;}
        if(slot==22){openChoice(p,h.npc,h.goal,h.option);return;}
        if(h.inventory.getItem(slot)==null)return;
        RoutineGoal.WalkMode selected=switch(slot){case 11->RoutineGoal.WalkMode.TARGET;case 13->RoutineGoal.WalkMode.RANDOM;case 15->RoutineGoal.WalkMode.CYCLE;default->null;};
        if(selected==null || selected==g.mode())return;
        if(g.mode()==RoutineGoal.WalkMode.TARGET && selected!=RoutineGoal.WalkMode.TARGET){
            input(p,h,InputKind.MODE_TIME,selected,"&eEl modo será &f"+modeName(selected)+"&e. Escribe &fHH:mm HH:mm &epara asignarle horario.");
            return;
        }
        save(p,h,g.withMode(selected),()->openChoice(p,h.npc,h.goal,h.option));
    }
    private void input(Player p,Holder h,InputKind kind,RoutineGoal.WalkMode mode,String prompt){
        inputs.put(p.getUniqueId(),new Input(h.npc,h.goal,h.option,kind,mode)); p.closeInventory(); say(p,prompt);
    }
    @EventHandler(priority=EventPriority.LOWEST) @SuppressWarnings("deprecation")
    public void chat(AsyncPlayerChatEvent event){
        Input in=inputs.get(event.getPlayer().getUniqueId()); if(in==null)return; event.setCancelled(true); String text=event.getMessage().trim(); UUID id=event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin,()->{Player p=Bukkit.getPlayer(id);if(p==null || inputs.get(id)!=in)return;handleInput(p,in,text);});
    }
    private void handleInput(Player p,Input in,String text){
        try{
            if(!validNpc(p,in.npc)){inputs.remove(p.getUniqueId(),in);return;}
            if(text.equalsIgnoreCase("cancelar")||text.equalsIgnoreCase("cancel")){inputs.remove(p.getUniqueId(),in);say(p,"&7Edición cancelada.");openChoice(p,in.npc,in.goal,in.option);return;}
            RoutineGoal root=goal(in.npc,in.goal),g=choice(in.npc,in.goal,in.option);if(g==null){inputs.remove(p.getUniqueId(),in);say(p,"&cEse goal u opción ya no existe.");openMain(p,in.npc);return;}
            if(in.kind==InputKind.DIALOG_LINES){
                if(text.equalsIgnoreCase("fin")){var d=effectiveDialogue(in.npc,g);save(p,in.npc,root.withChoice(in.option,g.withDialogue(new RoutineGoal.Dialogue(d.enabled(),d.range(),d.intervalSeconds(),d.initialDelaySeconds(),d.random(),d.lineOfSight(),in.lines))),()->openDialogue(p,in.npc,in.goal,in.option));inputs.remove(p.getUniqueId(),in);return;}
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
            save(p,in.npc,root.withChoice(in.option,updated),()->openChoice(p,in.npc,in.goal,in.option));inputs.remove(p.getUniqueId(),in);
        }catch(Exception ex){say(p,"&cValor inválido: &f"+(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()));say(p,"&7Intenta de nuevo o escribe &fcancelar&7.");}
    }
    private void save(Player p,String npc,RoutineGoal goal,Runnable after)throws Exception{plugin.shops().prepareReload();repo().put(npc,goal);reload();say(p,"&aGoal #"+goal.order()+" actualizado.");after.run();}
    private void save(Player p,Holder h,RoutineGoal selected,Runnable after)throws Exception{RoutineGoal root=goal(h.npc,h.goal);if(root==null)throw new IllegalArgumentException("El goal ya no existe");save(p,h.npc,root.withChoice(h.option,selected),after);}
    private void reload()throws Exception{plugin.reloadNpcs();}
    private RoutineGoal.Dialogue effectiveDialogue(String npc,RoutineGoal g){
        var d=g.dialogue(); if(d.configured() || g.type()!=RoutineGoal.Type.WORK)return d;
        var def=plugin.definitions().get(npc); if(def==null)return d; var legacy=def.dialogue();
        return new RoutineGoal.Dialogue(legacy.enabled(),legacy.range(),legacy.intervalSeconds(),legacy.initialDelaySeconds(),legacy.random(),legacy.lineOfSight(),legacy.lines(),false);
    }
    private RoutineGoal goal(String npc,int order){var plan=repo().snapshot().plans().get(npc);return plan==null?null:plan.goals().stream().filter(g->g.order()==order).findFirst().orElse(null);}
    private RoutineGoal choice(String npc,int order,int option){RoutineGoal root=goal(npc,order);return root==null || option<0 || option>=root.choiceCount()?null:root.choice(option);}
    private int nextOrder(String npc){var plan=repo().snapshot().plans().get(npc);Set<Integer> used=new HashSet<>();if(plan!=null)plan.goals().forEach(g->used.add(g.order()));for(int i=1;i<=100;i++)if(!used.contains(i))return i;return -1;}
    private boolean validNpc(Player p,String npc){if(!p.hasPermission("mdvnpc.admin")){return false;}if(!plugin.definitions().containsKey(npc)){say(p,"&cNPC no encontrado: &f"+npc);return false;}return true;}
    private static int[] times(String text){String[] v=text.trim().split("\\s+");if(v.length!=2)throw new IllegalArgumentException("usa HH:mm HH:mm");return new int[]{RoutineSchedule.parseHour(v[0]),RoutineSchedule.parseHour(v[1])};}
    private static double number(String text,double min,double max,String name){double n=Double.parseDouble(text.replace(',','.'));if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(name+" debe estar entre "+min+" y "+max);return n;}
    private static String typeName(RoutineGoal.Type t){return switch(t){case SLEEP->"Dormir";case WALK->"Caminar";case SIT->"Sentarse";case WORK->"Trabajo";};}
    private static String modeName(RoutineGoal.WalkMode m){return switch(m){case TARGET->"META";case RANDOM->"ALEATORIO";case CYCLE->"CICLO";};}
    private static String pointHelp(RoutineGoal g){return switch(g.type()){case SLEEP->"&8Seleccionarás nuevamente la cama.";case SIT->"&8Seleccionarás nuevamente las sillas.";case WORK->"&8Seleccionarás nuevamente el puesto.";case WALK->"&8Marcarás nuevamente el recorrido.";};}
    private static ItemStack goalItem(RoutineGoal g){
        return goalItem(g,g);
    }
    private static ItemStack goalItem(RoutineGoal g,RoutineGoal root){
        Material m=material(g.type());
        List<String> lore=new ArrayList<>();lore.add("&7Tipo: &f"+typeName(g.type()));if(g.type()==RoutineGoal.Type.WALK)lore.add("&7Modo: &f"+modeName(g.mode()));
        lore.add(g.target()?"&7Horario: &fMETA / sin horario":"&7Horario: &f"+RoutineSchedule.format(g.start())+" - "+RoutineSchedule.format(g.end()));lore.add("&7Puntos: &f"+g.points().size());lore.add("&7Velocidad: &f"+g.speed()+" b/s");
        if(!g.dialogue().configured() && g.type()==RoutineGoal.Type.WORK)lore.add("&dDiálogo: &eheredado del NPC");else lore.add(g.dialogue().enabled()?"&dDiálogo: &aactivo (&f"+g.dialogue().lines().size()+"&a líneas)":"&dDiálogo: &7inactivo");
        lore.add(workInteractionSummary(root));
        if(g.type()==RoutineGoal.Type.WORK)lore.add("&7Trabajo atiende en su puesto.");
        lore.add("&bSelección: &f"+(root.randomChoice()?"Aleatoria ("+root.choiceCount()+" opciones)":"Fija (principal)"));lore.add("&eClic para editar");
        return item(m,"&6Goal #"+g.order()+" &8- &f"+typeName(g.type()),lore);
    }
    private static String workInteractionSummary(RoutineGoal root){return root.workInteraction()?"&7Atención extra: &aactivada en todas las opciones":"&7Atención extra: &7desactivada en todas las opciones";}
    private static ItemStack workInteractionItem(RoutineGoal root){
        return item(root.workInteraction()?Material.LIME_DYE:Material.GRAY_DYE,"&eAtender durante este goal",List.of(
                root.workInteraction()?"&aAtención extra activada":"&7Atención extra desactivada",
                "&7Permite comandos y compras del NPC",
                "&7mientras realiza esta actividad.",
                "&7También al caminar hacia el destino.",
                "&7Se aplica a todas las opciones del goal.",
                "&8Trabajo sigue atendiendo en su puesto.",
                "&eClic para cambiar"));
    }
    private static Material material(RoutineGoal.Type type){return switch(type){case SLEEP->Material.RED_BED;case WALK->Material.LEATHER_BOOTS;case SIT->Material.OAK_STAIRS;case WORK->Material.IRON_PICKAXE;};}
    private static ItemStack item(Material material,String name,List<String> lore){ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();meta.setDisplayName(c(name));meta.setLore(lore.stream().map(RoutineEditor::c).toList());stack.setItemMeta(meta);return stack;}
    @EventHandler public void quit(PlayerQuitEvent event){inputs.remove(event.getPlayer().getUniqueId());}
}
