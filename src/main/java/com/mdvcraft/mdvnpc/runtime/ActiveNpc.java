package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.model.NpcDefinition;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.Location;
import org.bukkit.entity.Villager;

public record ActiveNpc(NpcDefinition definition, Location anchor, Villager entity, PlayerDisguise disguise) {}
