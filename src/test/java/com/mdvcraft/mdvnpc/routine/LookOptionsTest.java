package com.mdvcraft.mdvnpc.routine;
import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LookOptionsTest {
    @Test void independentContextsAndFiniteLimits(){
        var yaml=new YamlConfiguration();yaml.set("routines.looking.walking.enabled",false);
        yaml.set("routines.looking.seated.player-range",2);yaml.set("routines.looking.seated.yaw-limit",999);
        yaml.set("routines.looking.seated.pitch-step",Double.NaN);
        var plugin=mock(MdvNpcPlugin.class);when(plugin.settings()).thenReturn(Settings.parse(yaml));
        assertFalse(LookOptions.read(plugin,false).enabled());var seated=LookOptions.read(plugin,true);
        assertTrue(seated.enabled());assertEquals(2,seated.range());assertEquals(85,seated.yawLimit());assertEquals(7,seated.pitchStep());
    }
    @Test void oldIntervalsSurviveNewJarDefaultsAndExplicitOverrideWins(){
        var yaml=new YamlConfiguration();yaml.set("routines.glance-min-seconds",30);yaml.set("routines.glance-max-seconds",40);
        var defaults=new YamlConfiguration();defaults.set("routines.looking.walking.interval-min-seconds",8);yaml.setDefaults(defaults);
        var plugin=mock(MdvNpcPlugin.class);when(plugin.settings()).thenReturn(Settings.parse(yaml));
        assertEquals(600,LookOptions.read(plugin,false).minDelay());
        yaml.set("routines.looking.walking.interval-min-seconds",2);assertEquals(40,LookOptions.read(plugin,false).minDelay());
    }
}
