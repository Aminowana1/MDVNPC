package com.mdvcraft.mdvnpc.skin;

import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.util.Text;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.entity.LivingEntity;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class DisguiseService {
    public PlayerDisguise apply(LivingEntity entity, NpcDefinition npc) {
        PlayerDisguise disguise = new PlayerDisguise(Text.color(npc.name()), skinInput(npc.skin()));
        disguise.setNameVisible(npc.nameVisible());
        disguise.setDisplayedInTab(false);
        disguise.setDynamicName(false);
        disguise.setModifyBoundingBox(true);
        disguise.setReplaceSounds(false);
        disguise.setPlayIdleSounds(false);
        disguise.setVelocitySent(false);
        disguise.setEntity(entity);
        if (!disguise.startDisguise()) throw new IllegalStateException("LibsDisguises rechazó el disfraz de " + npc.id());
        return disguise;
    }

    // LibsDisguises 11.0.18 accepts a signed profile JSON through its String skin API.
    public static String skinInput(NpcDefinition.Skin skin) {
        if (skin.texture().isBlank()) return skin.name();
        UUID id = skin.profileId() != null ? skin.profileId()
                : UUID.nameUUIDFromBytes(skin.texture().getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"" + id + "\",\"name\":\"" + skin.name()
                + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + skin.texture()
                + "\",\"signature\":\"" + skin.signature() + "\"}]}";
    }
}
