package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.frame.ClientTickFrame;

import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.player.PlayerModel;
import ru.minecomplex.mac.fixture.inbound.*;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.world.*;
import ru.minecomplex.mac.engine.movementtick.LocalPlayerOracleCodec;
import ru.minecomplex.mac.engine.movementtick.LocalTickOracle;

import ru.minecomplex.mac.engine.movementtick.MovementOracleTest;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.world.piston.PistonProperties;

/** Real block interaction and real prediction rollback feed complete MovementCheckRunner frames, without expected-state resets. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class OpenableProbeOracleTest {
    @org.junit.jupiter.api.BeforeAll static void initializeNativePackets() { ru.minecomplex.mac.packet.NativeRuntime.ensure(); }

    @Test
    void openCloseAndCorrectionMatchVanilla() throws Exception {
        String local=System.getProperty("mac.openables.input","");
        var raw=local.isEmpty()?getClass().getResourceAsStream("/vanilla/777/openables.bin.gz")
                :java.nio.file.Files.newInputStream(java.nio.file.Path.of(local));
        assertNotNull(raw,"26.3");
        var registry=BlockRegistry.get(); var properties=PistonProperties.get();
        int ticks=0,falls=0,rollbacks=0;
        var lead=MovementCheckRunner.class.getDeclaredMethod("lead"); lead.setAccessible(true);
        try(var in=new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414f50,in.readInt()); assertEquals(1,in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt()); org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            var directModel=new WorldModel(registry);
            directModel.sent(0,Wire.of(new JoinGame(7,"minecraft:overworld",0,96,2,0)));
            directModel.sent(1,Wire.of(new ChunkData(0,0,new int[6],new int[]{1,1,1,1,1,1},new int[6],new long[0],ChunkBlockEntities.encode(List.of()))));
            var direct=directModel.hypothesis(new SyncWindow(1,2),1).world();
            int count=in.readInt(); assertTrue(count>1000,"All openable states and five hand/sneak modes");
            var types=new java.util.HashSet<String>();
            for(int i=0;i<count;i++) {
                int state=in.readInt(),mode=in.readUnsignedByte(); float yaw=in.readFloat();
                boolean expectedConsumes=in.readBoolean(); int expected=in.readInt(),below=in.readInt(),above=in.readInt();
                int mainCount=in.readInt(),offCount=in.readInt();
                int stone=registry.block("minecraft:stone").firstStateId();
                direct.setBlock(8,62,8,stone); direct.setBlock(8,63,8,stone); direct.setBlock(8,65,8,0); direct.setBlock(8,64,8,state);
                if(registry.block(state).isInstance("DoorBlock")) {
                    boolean upper="upper".equals(properties.value(state,"half"));
                    direct.setBlock(8,upper?63:65,8,properties.with(state,"half",upper?"lower":"upper"));
                }
                var player=new PlayerState(); player.shiftKeyDown=mode==2||mode==3;
                var data=new ClientPlayerData(); data.localMode=GameType.SURVIVAL;
                var shield=FixtureItems.stack(WireItem.of("minecraft:shield",1));
                if(mode==4) data.inventory.replace(0,shield);
                if(mode==2) data.inventory.replace(40,shield);
                var use=new UseItemOn(mode==1,i+1,8,64,8);
                String context="26.3"+" state="+state+" "+registry.block(state).name()+" mode="+mode;
                boolean consumes=OpenableBlocks.consumes(registry,state,player,data,Wire.of(use));
                assertEquals(expectedConsumes,consumes,context+" consumes");
                if(consumes) {
                    var actions=new ru.minecomplex.mac.listener.ClientPlayerActions();
                    var actionResult=actions.apply(player,data,Wire.of(use),new ru.minecomplex.mac.listener.ClientPlayerActions.Target() {
                        public String block(int x,int y,int z) { return registry.block(state).name(); }
                        public String entityType(int entity) { return null; }
                        public boolean blockUseConsumes(net.minecraft.network.protocol.game.ServerboundUseItemOnPacket action) { return consumes; }
                    });
                    assertEquals(mainCount,data.inventory.minCount(0),context+" main-hand count");
                    assertEquals(offCount,data.inventory.minCount(40),context+" offhand count");
                    assertTrue(actionResult.gaps().isEmpty(),context+" no ItemStack.useOn fallback");
                }
                direct.startPrediction(i+1,new Vec3d(8.5,64,8.5));
                if(consumes) direct.useOpenable(8,64,8,yaw);
                direct.endPrediction();
                assertEquals(expected,direct.getBlock(8,64,8),context+" clicked");
                assertEquals(below,direct.getBlock(8,63,8),context+" below");
                assertEquals(above,direct.getBlock(8,65,8),context+" above");
                types.add(registry.block(state).name());
                // End each independent case without sharing a predicted state's confirmed baseline with another case.
                direct.blockChangedAck(i+1); direct.takeRestored();
            }
            assertTrue(types.containsAll(Set.of("minecraft:oak_trapdoor","minecraft:iron_trapdoor","minecraft:oak_door",
                    "minecraft:iron_door","minecraft:oak_fence_gate","minecraft:chest","minecraft:barrel","minecraft:shulker_box")));
            assertEquals(56,in.readInt());
            for(int s=0;s<56;s++) {
                String name=in.readUTF(); boolean refused=in.readBoolean(); int clickY=in.readInt(),n=in.readInt();
                var blocks=new ArrayList<LocalTickOracle.Block>();
                for(int b=0;b<n;b++) blocks.add(new LocalTickOracle.Block(in.readInt(),in.readInt(),in.readInt(),in.readInt()));
                var initial=LocalTickOracle.State.read(in); var seed=initial.base().player();
                var world=new WorldModel(registry); var players=new PlayerModel();
                var probe=new MovementCheckRunner(world,players,new SyncWindow(-1,0));
                long seq=0;
                var join=new JoinGame(7,"minecraft:overworld",0,96,2,0); world.sent(seq,Wire.of(join)); players.sent(seq,Wire.of(join));
                world.sent(++seq,Wire.of(new ChunkData(0,0,new int[6],new int[]{1,1,1,1,1,1},new int[6],new long[0],ChunkBlockEntities.encode(List.of()))));
                for(var b:blocks) world.sent(++seq,Wire.of(new BlockUpdate(b.x(),b.y(),b.z(),b.state())));
                players.sent(++seq,Wire.of(new PlayerPosition(1,seed.x(),seed.y(),seed.z(),0,0,0,seed.yRot(),seed.xRot(),0)));
                int by=name.startsWith("TRAP")?63:64;
                int before=blocks.stream().filter(b->b.x()==8&&b.y()==by&&b.z()==8).findFirst().orElseThrow().state();
                int beforeUpper=blocks.stream().filter(b->b.x()==8&&b.y()==65&&b.z()==8).mapToInt(LocalTickOracle.Block::state).findFirst().orElse(0);
                assertEquals(32,in.readInt());
                for(int tick=0;tick<32;tick++) {
                    int op=in.readUnsignedByte(),mask=in.readUnsignedByte(),expectedBlock=in.readInt(),expectedUpper=in.readInt();
                    var after=LocalTickOracle.State.read(in); int packets=in.readInt();
                    var input=new ArrayList<InboundEvent>(); if(tick==0) input.add(PlayerLoaded.INSTANCE);
                    if(op==1) input.add(new UseItemOn(false,1,8,clickY,8));
                    if(op==2) {
                        world.sent(++seq,Wire.of(new BlockUpdate(8,by,8,refused?before:expectedBlock)));
                        if(name.startsWith("DOOR")) world.sent(++seq,Wire.of(new BlockUpdate(8,65,8,refused?beforeUpper:expectedUpper)));
                    }
                    if(op==3) world.sent(++seq,Wire.of(new BlockChangedAck(1)));
                    Movement movement=null;
                    for(int j=0;j<packets;j++) {var p=LocalPlayerOracleCodec.packet(in);input.add(p);if(p instanceof Movement m)movement=m;}
                    var frame=new ClientTickFrame(tick,tick,tick,new SyncWindow(seq,seq+1),Wire.ofAll(input),(new ru.minecomplex.mac.fixture.inbound.PlayerInput(mask)).flags(),tick==0,Wire.of(movement),Set.of());
                    var report=probe.frame(frame); String context="26.3"+" "+name+" tick="+tick;
                    assertEquals(MovementCheckRunner.Status.COMPARED,report.status(),context+report);
                    assertTrue(report.gaps().isEmpty(),context+report.gaps()); assertEquals(0.0,report.offset(),context+report);
                    var hypothesis=(PlayerModel.Hypothesis)lead.invoke(probe);
                    assertEquals(List.of(),after.base().player().differences(MovementOracleTest.Recorded.of(hypothesis.player())),context+" complete physics state");
                    assertEquals(after.base().pose(),hypothesis.player().pose.name(),context+" pose");
                    assertEquals(Double.doubleToLongBits(after.base().player().x()),Double.doubleToLongBits(report.predicted().x()),context+" x");
                    assertEquals(Double.doubleToLongBits(after.base().player().y()),Double.doubleToLongBits(report.predicted().y()),context+" y");
                    assertEquals(Double.doubleToLongBits(after.base().player().z()),Double.doubleToLongBits(report.predicted().z()),context+" z");
                    assertEquals(expectedBlock,probe.clientWorld().getBlock(8,by,8),context+" block");
                    assertEquals(expectedUpper,probe.clientWorld().getBlock(8,65,8),context+" upper");
                    if(name.startsWith("TRAP")&&tick>8&&tick<16&&after.base().player().y()<seed.y()-.1) falls++;
                    if(refused&&op==3) rollbacks++;
                    ticks++;
                }
            }
            assertEquals(-1,in.read());
        }
        assertEquals(1792,ticks); assertTrue(falls>0,"Actual support-loss falls"); assertEquals(28,rollbacks);
    }
}
