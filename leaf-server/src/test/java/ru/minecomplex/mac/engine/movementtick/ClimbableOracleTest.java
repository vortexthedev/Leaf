package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.fixture.FixturePlayers;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.*;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;

/** Independent resolved tag, spectator, glide, shift and creative factors against the real LocalPlayer. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ClimbableOracleTest {

    @Test
    void matchesVanillaClimbing()throws Exception {
        var registry=BlockRegistry.get();
        var raw=getClass().getResourceAsStream("/vanilla/777/climbable.bin.gz");assertNotNull(raw);
        var on=MovementTicker.class.getDeclaredMethod("onClimbable");on.setAccessible(true);
        var handle=MovementTicker.class.getDeclaredMethod("handleOnClimbable",Vec3d.class);handle.setAccessible(true);
        try(var in=new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41434b,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(4096,in.readInt());
            for(int i=0;i<4096;i++) {
                int flags=in.readInt(),state=in.readInt(),below=in.readInt();
                Vec3d delta=new Vec3d(in.readDouble(),in.readDouble(),in.readDouble());
                var p=new PlayerState();p.position=new Vec3d(0.5,64,0.5);p.abilities.flying=(flags&4)!=0;p.shiftKeyDown=(flags&8)!=0;
                p.spectator=(flags&16)!=0;p.fallFlying=(flags&32)!=0;p.fallDistance=7.5;
                var ids=new EnumMap<BlockTag,int[]>(BlockTag.class);
                if((flags&1)!=0)ids.put(BlockTag.CLIMBABLE,new int[]{registry.block(state).registryId()});
                if((flags&2)!=0)ids.put(BlockTag.CAN_GLIDE_THROUGH,new int[]{registry.block(state).registryId()});
                var tags=new ResolvedBlockTags(ids);
                MovementEnvironment env=new MovementEnvironment() {
                    public BlockSource blocks(){return (x,y,z)->x==0&&z==0?(y==64?state:y==63?below:0):0;}
                    public BlockRegistry registry(){return registry;}public BlockTags tags(){return tags;}
                    public boolean hasChunkAt(int x,int z){return true;}public int minY(){return -64;}
                    public List<VoxelShape> entityCollisions(Aabb box){return List.of();}public VoxelShape borderShape(Aabb box){return null;}
                };
                var physics=new MovementTickerPlayer(env,p,new LocalPlayerState(), FixturePlayers.loaded());
                String context="26.3 climb "+i;
                assertEquals(in.readBoolean(),(boolean)on.invoke(physics),context);
                FluidTravelOracleTest.compare(in,(Vec3d)handle.invoke(physics,delta),context+" clamp");
                FluidContactOracleTest.bits(in.readDouble(),p.fallDistance,context+" fall");
                assertTrue(physics.unmodeled().isEmpty(),context);
                // A registry-id payload is copied, and cannot mutate an already resolved hypothesis.
                ids.values().forEach(array->Arrays.fill(array,0));
                assertEquals((flags&1)!=0,tags.contains(BlockTag.CLIMBABLE,registry.block(state)),context+" immutable tag");
            }
            assertEquals(-1,in.read());
        }
    }
}
