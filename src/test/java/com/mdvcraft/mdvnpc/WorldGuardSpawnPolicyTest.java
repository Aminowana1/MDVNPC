package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.integration.SpawnBypassPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldGuardSpawnPolicyTest {
    @Test void releasesOnlyNewlyCancelledSpawnsInDenyRegion() {
        assertTrue(SpawnBypassPolicy.shouldOverride(false, true, true));
        assertFalse(SpawnBypassPolicy.shouldOverride(true, true, true));
        assertFalse(SpawnBypassPolicy.shouldOverride(false, false, true));
        assertFalse(SpawnBypassPolicy.shouldOverride(false, true, false));
    }
}
