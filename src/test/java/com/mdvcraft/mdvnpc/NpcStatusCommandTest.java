package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.command.NpcCommand;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.music.MusicService;
import com.mdvcraft.mdvnpc.routine.RoutineService;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcStatusCommandTest {
    MdvNpcPlugin plugin;CommandSender sender;Messages messages;NpcCommand command;Command bukkitCommand;
    @BeforeEach void setup(){
        MockBukkit.mock();plugin=mock(MdvNpcPlugin.class);messages=mock(Messages.class);
        sender=mock(CommandSender.class);when(sender.hasPermission("mdvnpc.admin")).thenReturn(true);
        when(plugin.messages()).thenReturn(messages);when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        var npc=mock(NpcDefinition.class);when(npc.mode()).thenReturn(NpcDefinition.Mode.MUSICIAN_FLUTE);
        when(plugin.definitions()).thenReturn(Map.of("flautista",npc));
        var manager=mock(NpcManager.class);when(manager.activeCount()).thenReturn(1);when(plugin.manager()).thenReturn(manager);
        var routines=mock(RoutineService.class);when(routines.status("flautista")).thenReturn("trabajando; goal 2");when(plugin.routines()).thenReturn(routines);
        var music=mock(MusicService.class);when(music.status("flautista")).thenReturn("Flauta: tocando Tourdion; oyentes a 14 bloques: 2");when(plugin.music()).thenReturn(music);
        command=new NpcCommand(plugin);bukkitCommand=mock(Command.class);
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void idStatusExplainsMusicAndRoutineWithoutChangingAnyNpc() throws Exception {
        assertTrue(command.onCommand(sender,bukkitCommand,"mdvnpc",new String[]{"status","flautista"}));
        verify(sender).sendMessage("flautista: trabajando; goal 2");
        verify(sender).sendMessage("Música: Flauta: tocando Tourdion; oyentes a 14 bloques: 2");
        verify(plugin,never()).repository();verify(plugin,never()).reloadNpcs();
    }
    @Test void globalStatusKeepsItsOriginalSummary(){
        command.onCommand(sender,bukkitCommand,"mdvnpc",new String[]{"status"});
        verify(messages).send(sender,"status","count","1","active","1","ticks","10");
        verifyNoInteractions(plugin.music());
    }
    @Test void unknownIdIsRejectedAndStatusOffersNpcAutocomplete(){
        command.onCommand(sender,bukkitCommand,"mdvnpc",new String[]{"status","missing"});
        verify(messages).send(sender,"not-found");
        assertEquals(java.util.List.of("flautista"),command.onTabComplete(sender,bukkitCommand,"mdvnpc",new String[]{"status","fla"}));
        verifyNoInteractions(plugin.music());
    }
}
