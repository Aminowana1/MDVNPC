package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.DoorController;
import com.mdvcraft.mdvnpc.routine.RoutineNavigator;
import com.mdvcraft.mdvnpc.routine.RoutineTerrain;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Work choreography layered over the existing Paper navigator; every item is cosmetic. */
public final class BlacksmithController {
    public enum Result { RUNNING, FALLBACK }
    private enum Stage { SMELT, QUENCH_INGOT, ANVIL_FIRST, QUENCH_SWORD, ANVIL_SECOND }
    private static final double ARRIVAL_DISTANCE_SQUARED = .4 * .4;
    private static final double PROGRESS_DISTANCE_SQUARED = .1 * .1;
    private static final int NO_PROGRESS_TICKS = 160, FLIGHT_TICKS = 16;
    private final MdvNpcPlugin plugin;
    private final RoutineNavigator navigator;
    private final DoorController geometryDoors;
    private final Function<Material, ItemStack> items;
    private final Map<String, State> states = new HashMap<>();

    private static final class State {
        ActiveNpc npc;
        ShopWorkDefinition definition;
        Stage stage = Stage.SMELT;
        Location workPost, station, approach, progress;
        List<Location> approaches = List.of();
        int candidate;
        boolean traveling = true, handSaved, entityHandSaved, previousRaised, raisedApplied, dropChanceApplied, headApplied;
        long travelStarted, lastProgress, stageStarted, nextEffect, nextDrop;
        ItemStack previousHand, previousEntityHand, appliedHand;
        float previousYaw, previousPitch, previousBodyYaw, previousDropChance;
        float appliedYaw,appliedBodyYaw;
        final List<Flight> flights = new ArrayList<>();
    }
    private record Flight(ItemDisplay display, Location start, Location end, long started,
                          Material material, boolean smelting) {}

    public BlacksmithController(MdvNpcPlugin plugin, RoutineNavigator navigator,
                                BiPredicate<ActiveNpc, Location> teleport) {
        this(plugin, navigator, new DoorController(plugin), ItemStack::new);
        // Rotation is applied without teleporting. All travel goes through RoutineNavigator.
    }

    /** Item factory/geometry seam keeps tests independent of Paper's item registry. */
    BlacksmithController(MdvNpcPlugin plugin, RoutineNavigator navigator,
                         DoorController geometryDoors, Function<Material, ItemStack> items) {
        this.plugin = plugin; this.navigator = navigator;
        this.geometryDoors = geometryDoors; this.items = items;
    }

    public boolean active(String id) { return states.containsKey(id); }
    public boolean traveling(String id) { State state = states.get(id); return state != null && state.traveling; }
    /** The service can enforce this deadline while floor recovery or a hop owns movement. */
    public boolean travelExpired(String id,long tick) {
        State state=states.get(id);
        return state!=null && state.traveling && tick-state.travelStarted>=seconds("travel-timeout-seconds",30,3,120)*20L;
    }
    public String status(String id) {
        State state = states.get(id);
        if (state == null) return "";
        String station = switch (state.stage) {
            case SMELT -> "fundición";
            case QUENCH_INGOT, QUENCH_SWORD -> "caldero";
            case ANVIL_FIRST, ANVIL_SECOND -> "yunque";
        };
        return state.traveling ? "caminando a " + station : "trabajando en " + station;
    }

    public Result tick(ActiveNpc npc, Location workPost, double speed, long tick, int cadence) {
        String id = npc.definition().id();
        State state = states.get(id);
        try {
            ShopWorkDefinition definition = npc.definition().shopWork();
            if (!npc.definition().enabled() || npc.definition().mode() != NpcDefinition.Mode.SHOP
                    || !npc.entity().isValid() || npc.entity().isDead() || npc.disguise() == null || definition == null
                    || definition.category() != ShopWorkDefinition.Category.BLACKSMITH
                    || !stationsValid(npc.entity().getWorld(), definition)
                    || !loaded(workPost) || workPost.getWorld() != npc.entity().getWorld()) return fallback(id);
            if (state != null && (state.npc != npc || !state.definition.equals(definition)
                    || !samePost(state.workPost, workPost))) { stop(id); state = null; }
            if (state == null) {
                if (!arrived(npc.position(), workPost)) return Result.FALLBACK;
                state = new State(); state.npc = npc; state.definition = definition; state.workPost = workPost.clone();
                state.previousYaw = npc.position().getYaw(); state.previousPitch = npc.position().getPitch();
                state.previousBodyYaw = npc.entity().getBodyYaw();
                states.put(id, state);
                saveHand(state);
                if (!beginTravel(state, tick)) return fallback(id);
            }
            if (!loaded(npc.position()) || !loaded(state.approach)) return fallback(id);
            updateFlights(state, tick);
            if (state.traveling) return travel(state, speed, tick, cadence);
            // Knockback or another plugin moving the entity requires a real walking approach again.
            if (!arrived(npc.position(), state.approach)) {
                cleanupFlights(state); state.traveling = true; state.travelStarted = state.lastProgress = tick;
                state.progress = npc.position().clone(); navigator.cancel(id);
                return Result.RUNNING;
            }
            if(!navigator.activeHop(id) && !supported(state.npc.position()))return fallback(id);
            faceStation(state);
            boolean ending=tick-state.stageStarted>=duration(state.stage);
            if(!ending || state.stage!=Stage.ANVIL_FIRST && state.stage!=Stage.ANVIL_SECOND)effects(state,tick);
            if (ending) {
                cleanupFlights(state);
                state.stage = switch (state.stage) {
                    case SMELT -> Stage.QUENCH_INGOT;
                    case QUENCH_INGOT -> Stage.ANVIL_FIRST;
                    case ANVIL_FIRST -> Stage.QUENCH_SWORD;
                    case QUENCH_SWORD -> Stage.ANVIL_SECOND;
                    case ANVIL_SECOND -> Stage.SMELT;
                };
                hand(state, switch (state.stage) {
                    case QUENCH_INGOT -> Material.IRON_INGOT;
                    case QUENCH_SWORD -> Material.IRON_SWORD;
                    case ANVIL_FIRST, ANVIL_SECOND -> Material.MACE;
                    case SMELT -> Material.RAW_IRON;
                });
                if (!beginTravel(state, tick)) return fallback(id);
            }
            return Result.RUNNING;
        } catch (RuntimeException | LinkageError error) {
            // Cosmetics never prevent the NPC from returning to its ordinary shop post.
            stop(id);
            return Result.FALLBACK;
        }
    }

    private boolean stationsValid(World world, ShopWorkDefinition definition) {
        if (!stationLoaded(world, definition.smeltery()) || !stationLoaded(world, definition.cauldron())
                || !stationLoaded(world, definition.anvil())) return false;
        Block water = world.getBlockAt(definition.cauldron().x(), definition.cauldron().y(), definition.cauldron().z());
        if (water.getType() != Material.WATER_CAULDRON || !(water.getBlockData() instanceof Levelled level)
                || level.getLevel() <= 0) return false;
        Material anvil = world.getBlockAt(definition.anvil().x(), definition.anvil().y(), definition.anvil().z()).getType();
        return anvil == Material.ANVIL || anvil == Material.CHIPPED_ANVIL || anvil == Material.DAMAGED_ANVIL;
    }

    private static boolean stationLoaded(World world, ShopWorkDefinition.Station station) {
        if (station == null || station.y() < world.getMinHeight() || station.y() >= world.getMaxHeight()) return false;
        if (station.worldId() != null ? !station.worldId().equals(world.getUID()) : !world.getName().equals(station.worldName())) return false;
        return world.isChunkLoaded(station.x() >> 4, station.z() >> 4);
    }
    private static boolean loaded(Location location) {
        return location != null && location.getWorld() != null && Double.isFinite(location.getX())
                && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
    private static boolean samePost(Location first, Location second) {
        return first.getWorld() == second.getWorld() && first.distanceSquared(second) < 1e-8;
    }
    private static boolean arrived(Location current, Location target) {
        if (current == null || target == null || current.getWorld() != target.getWorld()) return false;
        double x = current.getX() - target.getX(), z = current.getZ() - target.getZ();
        return x*x + z*z <= ARRIVAL_DISTANCE_SQUARED && Math.abs(current.getY() - target.getY()) <= .16;
    }

    private boolean beginTravel(State state, long tick) {
        ShopWorkDefinition.Station station = switch (state.stage) {
            case SMELT -> state.definition.smeltery();
            case QUENCH_INGOT, QUENCH_SWORD -> state.definition.cauldron();
            case ANVIL_FIRST, ANVIL_SECOND -> state.definition.anvil();
        };
        World world = state.npc.entity().getWorld();
        state.station = new Location(world, station.x()+.5, station.y()+.6, station.z()+.5);
        RoutineTerrain terrain = new RoutineTerrain(world, geometryDoors);
        int offset = state.stage == Stage.SMELT ? 2 : 1;
        ArrayList<Location> approaches = new ArrayList<>(4);
        try (var update = terrain.beginUpdate()) {
            for (int[] direction : new int[][] {{0,1},{1,0},{0,-1},{-1,0}}) {
                Location center = new Location(world, station.x()+.5+direction[0]*offset,
                        station.y(), station.z()+.5+direction[1]*offset);
                var node = terrain.near(center);
                if (node == null) continue;
                Location target = terrain.location(node);
                if (Math.abs(target.getY()-station.y()) > 1.25 || !terrain.fits(target.getX(),target.getY(),target.getZ(),false)) continue;
                // The gap before a smeltery must be open; a wall cannot become an animation target.
                if (offset == 2 && !terrain.fits(station.x()+.5+direction[0],target.getY(),station.z()+.5+direction[1],false)) continue;
                approaches.add(target);
            }
        }
        approaches.sort(Comparator.comparingDouble(at -> state.npc.position().distanceSquared(at)));
        state.approaches = List.copyOf(approaches); state.candidate = 0;
        if (approaches.isEmpty()) return false;
        state.approach = approaches.getFirst(); state.traveling = true;
        state.travelStarted = state.lastProgress = tick; state.progress = state.npc.position().clone();
        navigator.cancel(state.npc.definition().id());
        return true;
    }

    private Result travel(State state, double speed, long tick, int cadence) {
        String id = state.npc.definition().id();
        Location current = state.npc.position();
        if (current.getWorld() != state.approach.getWorld()) return fallback(id);
        double progressX=current.getX()-state.progress.getX(),progressZ=current.getZ()-state.progress.getZ();
        if (progressX*progressX+progressZ*progressZ >= PROGRESS_DISTANCE_SQUARED) {
            state.progress = current.clone(); state.lastProgress = tick;
        }
        if (travelExpired(id,tick)) return fallback(id);
        if (tick - state.lastProgress >= NO_PROGRESS_TICKS) {
            if (++state.candidate >= state.approaches.size()) return fallback(id);
            state.approach = state.approaches.get(state.candidate); state.lastProgress = tick;
            state.progress = current.clone(); navigator.cancel(id);
        }
        RoutineNavigator.Result result = navigator.move(state.npc,state.approach,speed,tick,cadence);
        if (result != RoutineNavigator.Result.ARRIVED || !arrived(state.npc.position(),state.approach)) return Result.RUNNING;
        if (!supported(state.npc.position())) return fallback(id);
        navigator.cancel(id); state.traveling = false;
        state.stageStarted = state.nextEffect = state.nextDrop = tick;
        hand(state,switch (state.stage) {
            case SMELT -> Material.RAW_IRON;
            case QUENCH_INGOT -> Material.IRON_INGOT;
            case QUENCH_SWORD -> Material.IRON_SWORD;
            case ANVIL_FIRST, ANVIL_SECOND -> Material.MACE;
        });
        faceStation(state);
        effects(state,tick);
        return Result.RUNNING;
    }

    private long duration(Stage stage) {
        return switch (stage) {
            case SMELT -> seconds("smelt-seconds",8,1,600)*20L;
            case QUENCH_INGOT, QUENCH_SWORD -> seconds("quench-seconds",5,1,600)*20L;
            case ANVIL_FIRST, ANVIL_SECOND -> seconds("anvil-seconds",120,1,3600)*20L;
        };
    }
    private int seconds(String key,int fallback,int minimum,int maximum) {
        var settings = plugin.settings();
        return Math.max(minimum,Math.min(maximum,settings==null?fallback:settings.messages().getInt("blacksmith."+key,fallback)));
    }
    private double decimal(String key,double fallback,double minimum,double maximum) {
        var settings=plugin.settings();double value=settings==null?fallback:settings.messages().getDouble("blacksmith."+key,fallback);
        return Double.isFinite(value)?Math.max(minimum,Math.min(maximum,value)):fallback;
    }
    private boolean supported(Location position) {
        RoutineTerrain terrain=new RoutineTerrain(position.getWorld(),geometryDoors);
        try(var update=terrain.beginUpdate()) {
            var floor=terrain.fallColumn(position.getX(),position.getY(),position.getZ(),.02);
            return floor.safe() && Double.isFinite(floor.support()) && Math.abs(position.getY()-floor.support())<=.02;
        }
    }

    private void saveHand(State state) {
        var watcher = state.npc.disguise().getWatcher();
        state.previousHand = cloneItem(watcher.getItemInMainHand()); state.previousRaised = watcher.isMainHandRaised();
        state.handSaved = true;
        EntityEquipment equipment = state.npc.entity().getEquipment();
        if (equipment != null) {
            state.previousEntityHand=cloneItem(equipment.getItemInMainHand());state.entityHandSaved=true;
            state.previousDropChance=equipment.getItemInMainHandDropChance();
        }
    }
    private static ItemStack cloneItem(ItemStack item) { return item==null?null:item.clone(); }
    private void hand(State state, Material material) {
        ItemStack item = items.apply(material);
        state.appliedHand=item.clone();state.raisedApplied=true;
        state.npc.disguise().getWatcher().setMainHandRaised(false);
        state.npc.disguise().getWatcher().setItemInMainHand(item.clone());
        EntityEquipment equipment=state.npc.entity().getEquipment();
        if(equipment!=null) {
            state.dropChanceApplied=true;equipment.setItemInMainHandDropChance(0);
            equipment.setItemInMainHand(item.clone(),true);
        }
    }
    private static void faceStation(State state) {
        Location current=state.npc.position();
        Location direction=current.clone();direction.setDirection(state.station.toVector().subtract(current.toVector()).setY(0));
        state.headApplied=true;state.appliedYaw=state.appliedBodyYaw=direction.getYaw();
        state.npc.entity().setRotation(direction.getYaw(),0);state.npc.entity().setBodyYaw(direction.getYaw());
    }

    private void effects(State state,long tick) {
        World world=state.station.getWorld();
        boolean anvil=state.stage==Stage.ANVIL_FIRST || state.stage==Stage.ANVIL_SECOND;
        if(tick>=state.nextEffect) {
            state.nextEffect=tick+(anvil?20:12);
            if(anvil) {
                state.npc.entity().swingMainHand();
                // Each hammer swing uses the vanilla anvil-placement sound; volume/pitch are configurable.
                world.playSound(state.station,Sound.BLOCK_ANVIL_PLACE,
                        (float)decimal("anvil-hit-volume",.6,0,4),
                        (float)decimal("anvil-hit-pitch",1,0.5,2));
                world.spawnParticle(Particle.CRIT,state.station.clone().add(0,.4,0),5,.15,.05,.15,.05);
            } else if(state.stage==Stage.SMELT) {
                world.playSound(state.station,Sound.BLOCK_FURNACE_FIRE_CRACKLE,.4f,1.05f);
                world.spawnParticle(Particle.FLAME,state.station,5,.2,.12,.2,.015);
                world.spawnParticle(Particle.SMOKE,state.station.clone().add(0,.25,0),4,.15,.1,.15,.01);
            } else {
                world.playSound(state.station,Sound.BLOCK_FIRE_EXTINGUISH,.25f,1.3f);
                world.spawnParticle(Particle.CLOUD,state.station.clone().add(0,.25,0),5,.2,.05,.2,.02);
                // A water cauldron has no client fluid state, so plain BUBBLE vanishes immediately.
                world.spawnParticle(Particle.BUBBLE_POP,state.station,8,.15,.08,.15,.01);
                world.spawnParticle(Particle.SPLASH,state.station,4,.15,.08,.15,.02);
            }
        }
        if(anvil || tick<state.nextDrop)return;
        if(state.stage==Stage.SMELT) {
            state.nextDrop=tick+20;
            drop(state,Material.RAW_IRON,tick,true);
        } else if(state.nextDrop==state.stageStarted) {
            state.nextDrop=Long.MAX_VALUE;
            drop(state,state.stage==Stage.QUENCH_INGOT?Material.IRON_INGOT:Material.IRON_SWORD,tick,false);
            hand(state,Material.AIR);
        }
    }

    private void drop(State state,Material material,long tick,boolean smelting) {
        if(state.flights.size()>=6)return;
        Location start=state.npc.position().clone().add(0,1.1,0),end=state.station.clone();
        ItemDisplay display=start.getWorld().spawn(start,ItemDisplay.class,entity->{
            // ItemDisplay is never an Item entity: players/hoppers cannot collect or merge it.
            state.flights.add(new Flight(entity,start,end,tick,material,smelting));
            entity.setItemStack(items.apply(material));entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            entity.setGravity(false);entity.setPersistent(false);entity.setInvulnerable(true);entity.setSilent(true);
            entity.setBillboard(Display.Billboard.FIXED);entity.setViewRange(.75f);
            entity.setTeleportDuration(1);entity.setInterpolationDuration(1);
            entity.setTransformation(new Transformation(new Vector3f(),new Quaternionf(),new Vector3f(.4f),new Quaternionf()));
        });
        if(display==null || !display.isValid())throw new IllegalStateException("No se pudo crear el objeto visual del herrero");
        if(state.flights.stream().noneMatch(flight->flight.display()==display))
            state.flights.add(new Flight(display,start,end,tick,material,smelting));
        state.npc.entity().swingMainHand();
    }
    private void updateFlights(State state,long tick) {
        var iterator=state.flights.iterator();
        while(iterator.hasNext()) {
            Flight flight=iterator.next();
            if(!flight.display().isValid()) {flight.display().remove();iterator.remove();continue;}
            if(!loaded(flight.start()) || !loaded(flight.end())) {flight.display().remove();iterator.remove();continue;}
            double elapsed=Math.max(0,(tick-flight.started())/(double)FLIGHT_TICKS);
            if(elapsed>=1) {
                flight.display().remove();iterator.remove();
                World world=flight.end().getWorld();
                world.spawnParticle(flight.smelting()?Particle.FLAME:Particle.CLOUD,flight.end(),8,.15,.1,.15,.015);
                world.playSound(flight.end(),flight.smelting()?Sound.BLOCK_LAVA_POP:Sound.ENTITY_GENERIC_SPLASH,.3f,1.1f);
                continue;
            }
            Location current=flight.start().clone().add(flight.end().toVector().subtract(flight.start().toVector()).multiply(elapsed));
            current.add(0,4*.25*elapsed*(1-elapsed),0);
            if(!loaded(current) || !flight.display().teleport(current)) {flight.display().remove();iterator.remove();}
        }
    }
    private static void cleanupFlights(State state) {
        for(Flight flight:state.flights)try{flight.display().remove();}catch(RuntimeException ignored){}
        state.flights.clear();
    }
    private static boolean sameItem(ItemStack first,ItemStack second) {
        boolean firstEmpty=first==null || first.getType().isAir(),secondEmpty=second==null || second.getType().isAir();
        return firstEmpty || secondEmpty ? firstEmpty && secondEmpty : first.equals(second);
    }
    private Result fallback(String id) {stop(id);return Result.FALLBACK;}
    public void stop(String id) {
        State state=states.remove(id);if(state==null)return;
        try{navigator.cancel(id);}catch(RuntimeException | LinkageError ignored){}
        cleanupFlights(state);
        try {
            if(state.handSaved && state.npc.disguise()!=null) {
                var watcher=state.npc.disguise().getWatcher();
                try {
                    if(state.appliedHand!=null && sameItem(watcher.getItemInMainHand(),state.appliedHand))
                        watcher.setItemInMainHand(cloneItem(state.previousHand));
                } finally {
                    if(state.raisedApplied && !watcher.isMainHandRaised())watcher.setMainHandRaised(state.previousRaised);
                }
            }
        } catch(RuntimeException | LinkageError ignored) {}
        try {
            EntityEquipment equipment=state.npc.entity().getEquipment();
            if(state.entityHandSaved && equipment!=null) {
                if(state.appliedHand!=null && sameItem(equipment.getItemInMainHand(),state.appliedHand))
                    equipment.setItemInMainHand(cloneItem(state.previousEntityHand),true);
                if(state.dropChanceApplied && equipment.getItemInMainHandDropChance()==0)
                    equipment.setItemInMainHandDropChance(state.previousDropChance);
            }
        } catch(RuntimeException | LinkageError ignored) {}
        try {
            if(state.headApplied && state.npc.entity().isValid()) {
                Location current=state.npc.position();
                if(sameAngle(current.getYaw(),state.appliedYaw) && Math.abs(current.getPitch())<.001)
                    state.npc.entity().setRotation(state.previousYaw,state.previousPitch);
                if(sameAngle(state.npc.entity().getBodyYaw(),state.appliedBodyYaw))
                    state.npc.entity().setBodyYaw(state.previousBodyYaw);
            }
        } catch(RuntimeException | LinkageError ignored) {}
    }
    private static boolean sameAngle(float first,float second) {
        double delta=(first-second)%360;
        if(delta>180)delta-=360;if(delta<-180)delta+=360;
        return Math.abs(delta)<.001;
    }
    public void clear() {for(String id:List.copyOf(states.keySet()))stop(id);}
}
