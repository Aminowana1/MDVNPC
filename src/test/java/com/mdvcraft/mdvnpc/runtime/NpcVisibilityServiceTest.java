package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.config.Settings;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NpcVisibilityServiceTest {
    private ServerMock server;
    private Plugin mdvnpc;
    private Plugin tutorial;
    private Plugin other;
    private NpcVisibilityService visibility;
    private PlayerMock viewer;
    private PlayerMock bystander;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        mdvnpc = MockBukkit.createMockPlugin("MDVNPC");
        tutorial = MockBukkit.createMockPlugin("MDVTutorial");
        other = MockBukkit.createMockPlugin("Other");
        visibility = new NpcVisibilityService(mdvnpc);
        viewer = server.addPlayer("Novato");
        bystander = server.addPlayer("Veterano");
    }

    @AfterEach
    void tearDown() {
        PlayerFilter.hiddenViewers(null);
        MockBukkit.unmock();
    }

    /** A valid NPC entity stand-in; PlayerMock honours hideEntity/canSee(Entity). */
    private Entity npc(String name) {
        return server.addPlayer(name);
    }

    @Test
    void hidesExistingAndLaterTrackedEntitiesOnlyFromHiddenViewers() {
        Entity before = npc("Herrero");
        visibility.track(before);

        assertTrue(visibility.hide(tutorial, viewer));
        assertFalse(viewer.canSee(before));
        assertTrue(bystander.canSee(before));

        // Respawn/reload/chunk load: a new entity inherits the hidden state.
        Entity after = npc("Pescador");
        visibility.track(after);
        assertFalse(viewer.canSee(after));
        assertTrue(bystander.canSee(after));
    }

    @Test
    void npcStayHiddenUntilEveryOwnerShowsThem() {
        Entity entity = npc("Guardia");
        visibility.track(entity);

        assertTrue(visibility.hide(tutorial, viewer));
        assertFalse(visibility.hide(other, viewer), "second owner does not change the state");

        assertFalse(visibility.show(tutorial, viewer));
        assertFalse(viewer.canSee(entity));
        assertTrue(visibility.isHidden(viewer));

        assertTrue(visibility.show(other, viewer));
        assertTrue(viewer.canSee(entity));
        assertFalse(visibility.isHidden(viewer));
    }

    @Test
    void showWithoutHideIsANoOp() {
        assertFalse(visibility.show(tutorial, viewer));
        assertFalse(visibility.isHidden(viewer));
    }

    @Test
    void ownerDisableRevealsOnlyViewersNobodyElseHides() {
        PlayerMock second = server.addPlayer("Otro");
        Entity entity = npc("Bardo");
        visibility.track(entity);
        visibility.hide(tutorial, viewer);
        visibility.hide(tutorial, second);
        visibility.hide(other, second);

        visibility.ownerDisabled(tutorial);

        assertFalse(visibility.isHidden(viewer));
        assertTrue(viewer.canSee(entity));
        assertTrue(visibility.isHidden(second));
        assertFalse(second.canSee(entity));
    }

    @Test
    void quitForgetsTheViewer() {
        visibility.hide(tutorial, viewer);
        visibility.playerQuit(viewer.getUniqueId());
        assertFalse(visibility.isHidden(viewer));
    }

    @Test
    void closeRevealsEverythingAndForgets() {
        Entity entity = npc("Mercader");
        visibility.track(entity);
        visibility.hide(tutorial, viewer);

        visibility.close();

        assertTrue(viewer.canSee(entity));
        assertFalse(visibility.isHidden(viewer));
        assertEquals(0, visibility.trackedCount());
    }

    @Test
    void invalidEntitiesArePrunedAndNeverTracked() {
        Entity dead = mock(Entity.class);
        when(dead.isValid()).thenReturn(false);
        visibility.track(dead);
        visibility.track(null);
        assertEquals(0, visibility.trackedCount());

        Entity gone = mock(Entity.class);
        when(gone.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(gone.isValid()).thenReturn(true);
        visibility.track(gone);
        assertEquals(1, visibility.trackedCount());
        when(gone.isValid()).thenReturn(false); // the entity was removed from the world
        visibility.hide(tutorial, viewer); // hiding prunes before applying
        assertEquals(0, visibility.trackedCount());
    }

    @Test
    void playerFilterIgnoresHiddenViewers() {
        Settings settings = mock(Settings.class);
        PlayerFilter.hiddenViewers(visibility::isHidden);

        assertTrue(PlayerFilter.accepts(viewer, settings));
        visibility.hide(tutorial, viewer);
        assertFalse(PlayerFilter.accepts(viewer, settings));
        assertTrue(PlayerFilter.hidden(viewer));
        assertTrue(PlayerFilter.accepts(bystander, settings));

        PlayerFilter.hiddenViewers(null);
        assertTrue(PlayerFilter.accepts(viewer, settings));
    }
}
