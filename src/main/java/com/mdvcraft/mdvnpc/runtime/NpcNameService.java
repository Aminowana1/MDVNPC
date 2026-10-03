package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.util.Text;
import me.libraryaddict.disguise.DisguiseConfig;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/** Replaces only LD's armor-stand nametags; the label has no visible entity model. */
public final class NpcNameService {
    private static final long RETRY_NANOS=5_000_000_000L;
    private static final class Label {
        final ActiveNpc npc;
        TextDisplay display;
        Location location;
        String text;
        double offset;
        long nextAttempt;
        boolean warned;
        Label(ActiveNpc npc){this.npc=npc;}
    }
    private final MdvNpcPlugin plugin;
    private final NamespacedKey marker;
    private final LongSupplier clock;
    private final Map<String,Label> labels=new HashMap<>();

    public NpcNameService(MdvNpcPlugin plugin){this(plugin,System::nanoTime);}
    NpcNameService(MdvNpcPlugin plugin,LongSupplier clock){
        this.plugin=plugin;this.clock=clock;marker=new NamespacedKey(plugin,"npc-name");
    }
    /** Must be called before startDisguise: hiding a running name would already spawn its stands. */
    public static boolean usesOwnName(PlayerDisguise disguise){
        return disguise!=null && disguise.getInternals()!=null
                && disguise.getInternals().getNameDisplayType()==DisguiseConfig.PlayerNameType.ARMORSTANDS;
    }
    public boolean manages(ActiveNpc npc){return npc!=null && usesOwnName(npc.disguise());}
    public boolean isName(Entity entity){return entity.getPersistentDataContainer().has(marker,PersistentDataType.STRING);}
    public boolean liveName(Entity entity){
        String id=entity.getPersistentDataContainer().get(marker,PersistentDataType.STRING);
        Label label=labels.get(id);return label!=null && label.display==entity;
    }
    public void register(ActiveNpc npc){
        if(!manages(npc))return;
        Label previous=labels.get(npc.definition().id());
        if(previous!=null && previous.npc==npc){tick(npc);return;}
        if(previous!=null)remove(previous.npc);
        labels.put(npc.definition().id(),new Label(npc));
        tick(npc);
    }
    public double offset(ActiveNpc npc){Label label=label(npc);return label==null?0:label.offset;}
    public void offset(ActiveNpc npc,double additional){
        if(!Double.isFinite(additional))throw new IllegalArgumentException("El desplazamiento del nombre debe ser finito");
        Label label=label(npc);if(label==null || Double.compare(label.offset,additional)==0)return;
        label.offset=additional;tick(npc);
    }
    /** Called by existing manager/routine clocks, without player queries or a separate task. */
    public void tick(ActiveNpc npc){
        Label label=label(npc);if(label==null)return;
        if(!npc.entity().isValid()){remove(npc);return;}
        if(!npc.definition().nameVisible())return;
        long now=clock.getAsLong();if(label.display==null && label.nextAttempt!=0 && now<label.nextAttempt)return;
        try {
            Location location=location(label);
            if(!location.getWorld().isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4))return;
            String text=Text.color(npc.disguise().getName()==null?npc.definition().name():npc.disguise().getName());
            if(label.display!=null && !label.display.isValid())removeDisplay(label);
            if(label.display==null){
                // Capture inside the callback so configuration/spawn exceptions cannot orphan a display.
                label.display=location.getWorld().spawn(location,TextDisplay.class,display->{
                    label.display=display;
                    display.getPersistentDataContainer().set(marker,PersistentDataType.STRING,npc.definition().id());
                    display.setPersistent(false);display.setGravity(false);display.setInvulnerable(true);display.setSilent(true);
                    display.setBillboard(Display.Billboard.CENTER);display.setAlignment(TextDisplay.TextAlignment.CENTER);
                    display.setLineWidth(4096);display.setShadowed(true);display.setSeeThrough(false);display.setViewRange(1);
                    display.setDefaultBackground(false);display.setBackgroundColor(Color.fromARGB(0));
                    display.setInterpolationDuration(0);display.setTeleportDuration(0);
                    display.text(LegacyComponentSerializer.legacySection().deserialize(text));
                });
                if(label.display==null || !label.display.isValid())throw new IllegalStateException("El nombre del NPC no se pudo crear");
                label.location=location;label.text=text;label.nextAttempt=0;
            }else{
                // Rotation of the NPC does not move the label: location() normalizes yaw/pitch.
                if(!location.equals(label.location) && label.display.teleport(location))label.location=location;
                if(!text.equals(label.text)){label.display.text(LegacyComponentSerializer.legacySection().deserialize(text));label.text=text;}
            }
        }catch(RuntimeException | LinkageError ex){
            try{removeDisplay(label);}catch(RuntimeException | LinkageError cleanup){ex.addSuppressed(cleanup);}
            label.nextAttempt=now+RETRY_NANOS;
            if(!label.warned){label.warned=true;plugin.getLogger().log(Level.WARNING,"No se pudo mostrar el nombre de NPC "+npc.definition().id()+"; se reintentará",ex);}
        }
    }
    private Location location(Label label){
        var disguise=label.npc.disguise();
        double y=(disguise.getHeight()+disguise.getWatcher().getNameYModifier()+label.offset)*disguise.getDisguiseScale()+.27;
        if(!Double.isFinite(y))throw new IllegalStateException("Altura del nombre no válida");
        Location location=label.npc.position().clone().add(0,y,0);location.setYaw(0);location.setPitch(0);return location;
    }
    private Label label(ActiveNpc npc){
        if(npc==null)return null;
        Label label=labels.get(npc.definition().id());return label!=null && label.npc==npc?label:null;
    }
    public void remove(ActiveNpc npc){
        Label label=label(npc);if(label==null)return;
        labels.remove(npc.definition().id(),label);
        try{removeDisplay(label);}catch(RuntimeException | LinkageError ex){plugin.getLogger().log(Level.WARNING,"No se pudo retirar el nombre de NPC "+npc.definition().id(),ex);}
    }
    private void removeDisplay(Label label){
        try{if(label.display!=null)label.display.remove();}
        finally{label.display=null;label.location=null;label.text=null;}
    }
    public void clear(){for(Label label:List.copyOf(labels.values()))remove(label.npc);labels.clear();}
}
