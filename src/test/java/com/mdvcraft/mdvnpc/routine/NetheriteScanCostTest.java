package com.mdvcraft.mdvnpc.routine;
import org.bukkit.*; import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.*; import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*; import static org.mockito.Mockito.*;
class NetheriteScanCostTest {
    World w; NetheriteNodeIndex index; int snapshots;
    @AfterEach void down(){MockBukkit.unmock();}
    @BeforeEach void up(){
        MockBukkit.mock(); w=mock(World.class); var ch=mock(Chunk.class); var s=mock(ChunkSnapshot.class,withSettings().stubOnly());
        when(w.getMinHeight()).thenReturn(-64); when(w.getMaxHeight()).thenReturn(320); when(w.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(w.getChunkAt(anyInt(),anyInt())).thenReturn(ch); when(ch.getChunkSnapshot(anyBoolean(),anyBoolean(),anyBoolean())).thenAnswer(i->{snapshots++;return s;});
        when(s.getHighestBlockYAt(anyInt(),anyInt())).thenReturn(70);
        when(s.contains(any(BlockData.class))).thenReturn(true); when(s.getBlockType(anyInt(),anyInt(),anyInt())).thenReturn(Material.AIR);
        index=new NetheriteNodeIndex();
    }
    NetheriteNodeIndex.Scan scan(){ return index.discover(w,new Location(w,1,64,1),new Location(w,10,64,10),0,256); }
    @Test void skyAboveTheHighestBlockIsNotScanned(){
        var scan=scan(); int updates=0;
        while(!scan.done() && updates<1000){scan.advance(8192);updates++;}
        assertTrue(scan.done()); assertTrue(updates<=6*snapshots,updates+" updates for "+snapshots+" chunks");
    }
    @Test void editsInOneChunkDoNotStarveTheScan(){
        var scan=scan();
        for(int i=0;i<200 && !scan.done();i++){ scan.advance(8192); if(i%10==9) index.invalidate(w,3,3); }
        assertTrue(scan.done());
    }
}
