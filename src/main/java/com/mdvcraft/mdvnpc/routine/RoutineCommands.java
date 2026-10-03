package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Chat only transports input: all world access and mutations run on the server thread. */
public final class RoutineCommands implements Listener {
    private final MdvNpcPlugin plugin;
    private final RoutineEditor editor;
    private final Map<UUID,Selection> selections=new ConcurrentHashMap<>();
    private static final class Selection {
        String npc; UUID world; int order,start,end,stage,option;
        RoutineGoal.Type type; RoutineGoal.WalkMode mode; double speed,radius;
        RoutineGoal.Dialogue dialogue=RoutineGoal.Dialogue.disabled();
        RoutineGoal original; long expires; boolean reopenEditor,optionSelection;
        final List<RoutineGoal.Point> points=new ArrayList<>();
    }
    public RoutineCommands(MdvNpcPlugin plugin) { this.plugin=plugin; this.editor=new RoutineEditor(plugin,this); }
    public RoutineEditor editor() { return editor; }
    private RoutineRepository repo() {return plugin.routines().repository();}
    public void cancelSelection(Player player){selections.remove(player.getUniqueId());editor.cancelInput(player);}
    private static void say(CommandSender player,String message) {player.sendMessage(ChatColor.GOLD+"[MDVNPC] "+ChatColor.RESET+message);}
    public void command(CommandSender sender,String[] a) throws Exception {
        if(!sender.hasPermission("mdvnpc.admin")) return;
        if(sender instanceof Player player && plugin.prefixEditor()!=null)plugin.prefixEditor().cancel(player);
        if(a.length<2 || a[1].equalsIgnoreCase("help")) {help(sender);return;}
        if(a[1].equalsIgnoreCase("cancelar") || a[1].equalsIgnoreCase("cancel")) {
            if(sender instanceof Player p) { selections.remove(p.getUniqueId()); editor.cancelInput(p); }say(sender,"Selección cancelada.");return;
        }
        String id=a[1]; var def=plugin.definitions().get(id);
        if(def==null) throw new IllegalArgumentException("NPC no encontrado: "+id);
        if(a.length==2 && sender instanceof Player p) { editor.openMain(p,id); return; }
        String action=a.length>2?a[2].toLowerCase(Locale.ROOT):"list";
        var plan=repo().snapshot().plans().get(id);
        switch(action) {
            case "list","lista","status" -> {
                say(sender,id+": "+plugin.routines().status(id));
                if(plan!=null) for(var goal:plan.goals()) say(sender,goal.order()+" - "+goal.type()+" "+goal.mode()+" "+
                        (goal.target()?"sin horario (meta)":RoutineSchedule.format(goal.start())+"–"+RoutineSchedule.format(goal.end()))+"; "+goal.points().size()+" puntos; "+goal.speed()+" bloques/s; "+(goal.randomChoice()?"aleatorio: "+goal.choiceCount()+" opciones":"fijo: principal"));
                say(sender,plugin.routines().metrics());
            }
            case "enable","activar" -> {
                if(a.length!=4 || !Set.of("true","false").contains(a[3].toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("routine "+id+" enable true|false");
                repo().edit(y -> y.set("npcs."+id+".enabled",Boolean.parseBoolean(a[3])));plugin.reloadNpcs();say(sender,"Rutina actualizada.");
            }
            case "delete","borrar" -> {
                if(a.length!=4) throw new IllegalArgumentException("routine "+id+" delete <número>");int order=Integer.parseInt(a[3]);
                if(order<1 || order>100) throw new IllegalArgumentException("Goal 1..100");
                repo().edit(y -> y.set("npcs."+id+".goals."+order,null));plugin.reloadNpcs();say(sender,"Goal eliminado.");
            }
            case "goal" -> {
                if(!(sender instanceof Player p)) throw new IllegalArgumentException("Selecciona los lugares dentro del juego");
                if(a.length<5) throw new IllegalArgumentException("routine <npc> goal <1..100> dormir|caminar|sentarse|trabajo [modo] [desde hasta] [velocidad] [radio]");
                Selection s=new Selection();s.npc=id;s.order=Integer.parseInt(a[3]);s.type=parseType(a[4]);s.mode=RoutineGoal.WalkMode.CYCLE;
                if(s.order<1 || s.order>100) throw new IllegalArgumentException("Goal 1..100");
                World world=plugin.manager().resolveWorld(def); if(world==null || p.getWorld()!=world) throw new IllegalArgumentException("Debes estar en el mundo del NPC");
                s.world=world.getUID();s.expires=System.nanoTime()+600_000_000_000L;
                s.original=plan==null?null:plan.goals().stream().filter(g -> g.order()==s.order).findFirst().orElse(null);
                if(s.original!=null)s.dialogue=s.original.dialogue();
                editor.cancelInput(p); p.closeInventory();
                int index=5;
                if(s.type==RoutineGoal.Type.WALK) {if(a.length<=index) throw new IllegalArgumentException("Modo de caminar: meta, aleatorio o ciclo");s.mode=parseMode(a[index++]);}
                s.speed=plugin.settings().messages().getDouble("routines.default-speed",2.4);s.radius=plugin.settings().messages().getDouble("routines.random-radius",20);
                boolean target=s.type==RoutineGoal.Type.WALK && s.mode==RoutineGoal.WalkMode.TARGET;
                if(!target && a.length>=index+2) {s.start=RoutineSchedule.parseHour(a[index++]);s.end=RoutineSchedule.parseHour(a[index++]);s.stage=2;}
                else if(target) s.stage=2;
                if(a.length>index) s.speed=Double.parseDouble(a[index++]);
                if(a.length>index) s.radius=Double.parseDouble(a[index++]);
                if(a.length>index || !Double.isFinite(s.speed) || s.speed<.2 || s.speed>6 || !Double.isFinite(s.radius) || s.radius<1 || s.radius>128) throw new IllegalArgumentException("Velocidad 0.2..6; radio 1..128. Revisa los argumentos.");
                selections.put(p.getUniqueId(),s);prompt(p,s);
            }
            default -> help(sender);
        }
    }
    public void beginNew(Player p,String id,int order,RoutineGoal.Type type,RoutineGoal.WalkMode mode) {
        var def=plugin.definitions().get(id); if(def==null){say(p,"NPC no encontrado: "+id);return;}
        try {
            World world=plugin.manager().resolveWorld(def); if(world==null || p.getWorld()!=world) throw new IllegalArgumentException("Debes estar en el mundo del NPC");
            Selection s=new Selection();s.npc=id;s.order=order;s.type=type;s.mode=mode;s.world=world.getUID();s.expires=System.nanoTime()+600_000_000_000L;s.reopenEditor=true;
            var plan=repo().snapshot().plans().get(id);s.original=plan==null?null:plan.goals().stream().filter(g->g.order()==order).findFirst().orElse(null);
            if(s.original!=null)s.dialogue=s.original.dialogue();
            s.speed=plugin.settings().messages().getDouble("routines.default-speed",2.4);s.radius=plugin.settings().messages().getDouble("routines.random-radius",20);
            s.stage=type==RoutineGoal.Type.WALK && mode==RoutineGoal.WalkMode.TARGET?2:0;
            editor.cancelInput(p);p.closeInventory();selections.put(p.getUniqueId(),s);prompt(p,s);
        } catch(Exception ex){say(p,"No se pudo iniciar: "+ex.getMessage());}
    }
    public void beginReselect(Player p,String id,RoutineGoal goal) {
        beginReselect(p,id,goal,0);
    }
    public void beginReselect(Player p,String id,RoutineGoal goal,int option) {
        var def=plugin.definitions().get(id); if(def==null){say(p,"NPC no encontrado: "+id);return;}
        try {
            World world=plugin.manager().resolveWorld(def);if(world==null || p.getWorld()!=world)throw new IllegalArgumentException("Debes estar en el mundo del NPC");
            var plan=repo().snapshot().plans().get(id);RoutineGoal root=plan==null?null:plan.goals().stream().filter(g->g.order()==goal.order()).findFirst().orElse(null);
            if(root==null || option<0 || option>=root.choiceCount())throw new IllegalArgumentException("Ese goal u opción ya no existe");
            Selection s=new Selection();s.npc=id;s.order=goal.order();s.type=goal.type();s.mode=goal.mode();s.start=root.start();s.end=root.end();s.speed=goal.speed();s.radius=goal.radius();s.dialogue=goal.dialogue();s.original=root;s.option=option;s.optionSelection=true;
            s.world=world.getUID();s.expires=System.nanoTime()+600_000_000_000L;s.stage=2;s.reopenEditor=true;
            editor.cancelInput(p);p.closeInventory();selections.put(p.getUniqueId(),s);say(p,"Puntos reiniciados a 0 para la nueva selección.");prompt(p,s);
        } catch(Exception ex){say(p,"No se pudo iniciar: "+ex.getMessage());}
    }
    public void beginOption(Player p,String id,int order,int option,RoutineGoal.Type type,RoutineGoal.WalkMode mode) {
        var def=plugin.definitions().get(id);if(def==null){say(p,"NPC no encontrado: "+id);return;}
        try {
            World world=plugin.manager().resolveWorld(def);if(world==null || p.getWorld()!=world)throw new IllegalArgumentException("Debes estar en el mundo del NPC");
            var plan=repo().snapshot().plans().get(id);RoutineGoal root=plan==null?null:plan.goals().stream().filter(g->g.order()==order).findFirst().orElse(null);
            if(root==null || option<0 || option>root.choiceCount())throw new IllegalArgumentException("Ese goal u opción ya no existe");
            if(option==root.choiceCount() && root.choiceCount()>=RoutineGoal.MAX_CHOICES)throw new IllegalArgumentException("Máximo "+RoutineGoal.MAX_CHOICES+" opciones");
            if(root.target()!=(type==RoutineGoal.Type.WALK && mode==RoutineGoal.WalkMode.TARGET))throw new IllegalArgumentException("Las opciones META deben ser recorridos META; las demás heredan el horario");
            Selection s=new Selection();s.npc=id;s.order=order;s.option=option;s.optionSelection=true;s.original=root;s.type=type;s.mode=mode;s.start=root.start();s.end=root.end();
            s.world=world.getUID();s.expires=System.nanoTime()+600_000_000_000L;s.stage=2;s.reopenEditor=true;
            s.speed=plugin.settings().messages().getDouble("routines.default-speed",2.4);s.radius=plugin.settings().messages().getDouble("routines.random-radius",20);
            if(option<root.choiceCount())s.dialogue=root.choice(option).dialogue();
            editor.cancelInput(p);p.closeInventory();selections.put(p.getUniqueId(),s);
            say(p,"Opción "+(option+1)+": "+(root.target()?"recorrido META":RoutineSchedule.format(s.start)+"–"+RoutineSchedule.format(s.end))+". Selecciona su destino.");prompt(p,s);
        } catch(Exception ex){say(p,"No se pudo iniciar: "+ex.getMessage());}
    }
    public void clock(CommandSender sender,String[] a) throws Exception {
        if(!sender.hasPermission("mdvnpc.admin"))return;
        if(a.length<2) {say(sender,"/mdvnpc clock <mundo> <minutos-día> <minutos-noche> | off | status");return;}
        World world=Bukkit.getWorld(a[1]); if(world==null) throw new IllegalArgumentException("Mundo no cargado");
        if(world.getName().contains(".")) throw new IllegalArgumentException("El gestor de reloj no admite nombres de mundo con puntos");
        if(a.length==2 || a[2].equalsIgnoreCase("status")) {say(sender,world.getName()+": "+RoutineSchedule.format(RoutineSchedule.minute(world.getFullTime()))+"; reloj "+repo().snapshot().clocks().getOrDefault(world.getName(),null));return;}
        if(a[2].equalsIgnoreCase("off")) repo().edit(y -> y.set("clocks."+world.getName(),null));
        else {
            if(a.length!=4) throw new IllegalArgumentException("clock <mundo> <minutos-día> <minutos-noche>");
            var clock=new RoutineRepository.Clock(Double.parseDouble(a[2]),Double.parseDouble(a[3]));
            repo().edit(y -> {y.set("clocks."+world.getName()+".day-minutes",clock.dayMinutes());y.set("clocks."+world.getName()+".night-minutes",clock.nightMinutes());});
        }
        plugin.reloadNpcs();say(sender,"Reloj actualizado. Día 06:00–18:00, noche 18:00–06:00.");
    }
    public static RoutineGoal.Type parseType(String text) {
        return switch(text.toLowerCase(Locale.ROOT)) {case "dormir","sleep" -> RoutineGoal.Type.SLEEP;case "caminar","walk" -> RoutineGoal.Type.WALK;case "sentarse","sit" -> RoutineGoal.Type.SIT;case "trabajo","work" -> RoutineGoal.Type.WORK;default -> throw new IllegalArgumentException("Tipo: dormir, caminar, sentarse, trabajo");};
    }
    public static RoutineGoal.WalkMode parseMode(String text) {
        return switch(text.toLowerCase(Locale.ROOT)) {case "meta","target" -> RoutineGoal.WalkMode.TARGET;case "aleatorio","random" -> RoutineGoal.WalkMode.RANDOM;case "ciclo","cycle" -> RoutineGoal.WalkMode.CYCLE;default -> throw new IllegalArgumentException("Modo: meta, aleatorio, ciclo");};
    }
    private void prompt(Player p,Selection s) {
        say(p,s.stage==0?"Escribe la hora de inicio (ej. 22:00). O cancelar.":s.stage==1?"Escribe la hora de fin (ej. 07:00). O cancelar.":
                switch(s.type){case SLEEP -> "Haz clic en la cama. O escribe cancelar.";case WORK -> "Haz clic en el suelo del puesto (se usa tu orientación). O cancelar.";case WALK -> "Clic IZQUIERDO en el suelo: añade punto. Clic DERECHO: guardar recorrido. O cancelar.";case SIT -> "Clic IZQUIERDO en stairs: añade silla. Clic DERECHO: guardar sillas. O cancelar.";});
    }
    @EventHandler(priority=EventPriority.LOWEST) @SuppressWarnings("deprecation")
    public void chat(AsyncPlayerChatEvent event) {
        UUID id=event.getPlayer().getUniqueId();Selection s=selections.get(id);if(s==null)return;
        event.setCancelled(true);String text=event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin,() -> {
            Player p=Bukkit.getPlayer(id);if(p==null || selections.get(id)!=s)return;
            if(!valid(p,s))return;
            try {
                if(text.equalsIgnoreCase("cancelar") || text.equalsIgnoreCase("cancel")) {selections.remove(id,s);say(p,"Selección cancelada.");return;}
                if(s.stage==0) {s.start=RoutineSchedule.parseHour(text);s.stage=1;}
                else if(s.stage==1) {s.end=RoutineSchedule.parseHour(text);s.stage=2;}
                prompt(p,s);
            } catch(RuntimeException ex) {say(p,ex.getMessage());}
        });
    }
    private boolean valid(Player p,Selection s) {
        if(!p.hasPermission("mdvnpc.admin") || System.nanoTime()>s.expires || !p.getWorld().getUID().equals(s.world) || !plugin.definitions().containsKey(s.npc)) {
            selections.remove(p.getUniqueId(),s);say(p,"Selección cancelada: permisos, mundo, NPC o tiempo cambiaron.");return false;
        }
        return true;
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(PlayerInteractEvent event) {
        Selection s=selections.get(event.getPlayer().getUniqueId());if(s==null)return;
        event.setCancelled(true);if(event.getHand()!=EquipmentSlot.HAND || !valid(event.getPlayer(),s))return;
        Player player=event.getPlayer();if(s.stage!=2) {prompt(player,s);return;}
        try {
            boolean many=s.type==RoutineGoal.Type.WALK || s.type==RoutineGoal.Type.SIT;
            if(many && (event.getAction()==Action.RIGHT_CLICK_AIR || event.getAction()==Action.RIGHT_CLICK_BLOCK)) {save(player,s);return;}
            Block block=event.getClickedBlock();if(block==null || event.getAction()==Action.PHYSICAL)return;
            if(many && event.getAction()!=Action.LEFT_CLICK_BLOCK)return;
            if(s.type==RoutineGoal.Type.SLEEP) {
                if(!(block.getBlockData() instanceof Bed bed))throw new IllegalArgumentException("Selecciona una cama");
                if(bed.getPart()==Bed.Part.FOOT) {
                    int x=block.getX()+bed.getFacing().getModX(),z=block.getZ()+bed.getFacing().getModZ();
                    if(!block.getWorld().isChunkLoaded(x>>4,z>>4))throw new IllegalArgumentException("La cama no está cargada");
                    block=block.getRelative(bed.getFacing());
                }
            } else if(s.type==RoutineGoal.Type.SIT) {
                if(!(block.getBlockData() instanceof Stairs stairs) || stairs.getHalf()!=org.bukkit.block.data.Bisected.Half.BOTTOM)throw new IllegalArgumentException("Selecciona stairs normales, no invertidas");
            } else if(!block.getType().isSolid() || RoutineTerrain.hazard(block.getType()))throw new IllegalArgumentException("Selecciona suelo sólido y seguro");
            int y=block.getY()+((s.type==RoutineGoal.Type.WALK || s.type==RoutineGoal.Type.WORK)?1:0);
            var point=new RoutineGoal.Point(s.world,block.getX(),y,block.getZ(),player.getLocation().getYaw());
            if(!many)s.points.clear();
            if(s.points.stream().anyMatch(p -> p.x()==point.x() && p.y()==point.y() && p.z()==point.z())) {say(player,"Ese punto ya está marcado.");return;}
            if(s.points.size()>=128)throw new IllegalArgumentException("Máximo 128 puntos por goal");
            s.points.add(point);say(player,"Punto "+s.points.size()+": "+point.x()+", "+point.y()+", "+point.z());
            player.spawnParticle(Particle.HAPPY_VILLAGER,block.getLocation().add(.5,1.1,.5),5,.2,.1,.2);
            if(!many)save(player,s);
        } catch(Exception ex) {say(player,"No se guardó: "+ex.getMessage());}
    }
    private void save(Player p,Selection s) throws Exception {
        var plan=repo().read().plans().get(s.npc);
        RoutineGoal current=plan==null?null:plan.goals().stream().filter(g -> g.order()==s.order).findFirst().orElse(null);
        if(!Objects.equals(current,s.original))throw new IllegalArgumentException("Otro administrador cambió este goal. Cancela y vuelve a seleccionarlo.");
        var definition=plugin.definitions().get(s.npc);
        World world=plugin.manager().resolveWorld(definition);
        if(world==null || !world.getUID().equals(s.world))throw new IllegalArgumentException("El NPC cambió de mundo");
        RoutineGoal goal=new RoutineGoal(s.order,s.type,s.mode,s.start,s.end,s.speed,s.radius,s.points,s.dialogue);
        if(s.optionSelection && current!=null) {
            if(s.option==current.choiceCount()){List<RoutineGoal> next=new ArrayList<>(current.alternatives());next.add(goal);goal=current.withAlternatives(next);}
            else goal=current.withChoice(s.option,goal);
        } else if(current!=null) goal=current.withChoice(0,goal);
        plugin.shops().prepareReload();repo().put(s.npc,goal);selections.remove(p.getUniqueId(),s);plugin.reloadNpcs();
        say(p,(s.optionSelection?"Opción "+(s.option+1)+" del goal ":"Goal ")+s.order+" guardado para "+s.npc+".");
        if(s.reopenEditor) Bukkit.getScheduler().runTask(plugin,()->editor.openChoice(p,s.npc,s.order,s.optionSelection?s.option:0));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e) {if(selections.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler public void quit(PlayerQuitEvent e) {selections.remove(e.getPlayer().getUniqueId());}
    public void clear() {selections.clear();editor.clear();}
    public void prune() {
        long now=System.nanoTime();
        selections.forEach((id,s) -> {if(now>s.expires && selections.remove(id,s)) {Player p=Bukkit.getPlayer(id);if(p!=null)say(p,"La selección venció después de 10 minutos.");}});
    }
    public static void help(CommandSender s) {
        say(s,"/mdvnpc routine <npc> - abrir editor gráfico de rutinas");
        say(s,"/mdvnpc routine <npc> goal <número> dormir|sentarse|trabajo [desde hasta] [velocidad]");
        say(s,"/mdvnpc routine <npc> goal <número> caminar meta [velocidad]");
        say(s,"/mdvnpc routine <npc> goal <número> caminar aleatorio|ciclo [desde hasta] [velocidad] [radio]");
        say(s,"/mdvnpc routine <npc> list | delete <número> | enable true|false");
        say(s,"/mdvnpc routine cancelar; /mdvnpc clock <mundo> <minutos-día> <minutos-noche> | off");
    }
}
