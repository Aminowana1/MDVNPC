package com.mdvcraft.mdvnpc.companion;
import com.mdvcraft.mdvnpc.api.*;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.*; import org.bukkit.entity.Player;
import org.junit.jupiter.api.*; import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*; import java.util.concurrent.*; import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*; import static org.mockito.Mockito.*;
class CompanionOrderTest {
    CompanionRuntime c; World w;
    @BeforeEach void up(){
        var server=MockBukkit.mock(); w=server.addSimpleWorld("world"); var plugin=MockBukkit.createMockPlugin();
        var service=mock(CompanionService.class); when(service.settings()).thenReturn(CompanionSettings.defaults());
        var spec=mock(CompanionSpec.class); when(spec.owner()).thenReturn(plugin); when(spec.viewers()).thenReturn(Set.of());
        c=new CompanionRuntime(service,UUID.randomUUID(),spec,mock(NpcDefinition.class),null);
    }
    @AfterEach void down(){MockBukkit.unmock();}
    @Test void orderGivenFromACancellationCallbackReplacesTheOneThatCancelledIt() {
        var inner = new AtomicReference<CompletableFuture<MoveResult>>();
        var first = c.moveTo(new Location(w, 1, 5, 1));
        first.whenComplete((result, error) -> { if (inner.get() == null) inner.set(c.moveTo(new Location(w, 2, 5, 2))); });
        var outer = c.moveTo(new Location(w, 3, 5, 3));
        assertEquals(MoveResult.CANCELLED, outer.getNow(null));
        assertFalse(inner.get().isDone());
        c.stop();
        assertEquals(MoveResult.CANCELLED, inner.get().getNow(null));
    }
}
