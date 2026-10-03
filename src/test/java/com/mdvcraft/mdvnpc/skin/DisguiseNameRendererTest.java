package com.mdvcraft.mdvnpc.skin;

import com.mdvcraft.mdvnpc.config.NpcParser;
import me.libraryaddict.disguise.DisguiseConfig;
import me.libraryaddict.disguise.disguisetypes.DisguiseInternals;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedConstruction;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisguiseNameRendererTest {
    @BeforeEach void setup(){MockBukkit.mock();}
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void armorStandNameIsSuppressedBeforeFirstDisguiseSpawn(){check(DisguiseConfig.PlayerNameType.ARMORSTANDS,true,false);}
    @Test void otherNameModesKeepTheirVisibility(){
        for(var mode:DisguiseConfig.PlayerNameType.values())if(mode!=DisguiseConfig.PlayerNameType.ARMORSTANDS)check(mode,true,true);
    }
    @Test void hiddenNamesStayHiddenInEveryMode(){for(var mode:DisguiseConfig.PlayerNameType.values())check(mode,false,false);}
    private void check(DisguiseConfig.PlayerNameType mode,boolean configuredVisible,boolean expectedVisible){
        var config=new YamlConfiguration();config.set("npcs.actor.location.world","world");config.set("npcs.actor.name-visible",configuredVisible);
        var definition=NpcParser.parse(config).get("actor");LivingEntity entity=mock(LivingEntity.class);
        try(MockedConstruction<PlayerDisguise> construction=mockConstruction(PlayerDisguise.class,(mock,context)->{
            var internals=mock(DisguiseInternals.class);when(mock.getInternals()).thenReturn(internals);when(internals.getNameDisplayType()).thenReturn(mode);when(mock.startDisguise()).thenReturn(true);
        })){
            PlayerDisguise disguise=new DisguiseService().apply(entity,definition);
            assertSame(construction.constructed().getFirst(),disguise);var order=inOrder(disguise);
            order.verify(disguise).setNameVisible(expectedVisible);order.verify(disguise).setEntity(entity);order.verify(disguise).startDisguise();
        }
    }
}
