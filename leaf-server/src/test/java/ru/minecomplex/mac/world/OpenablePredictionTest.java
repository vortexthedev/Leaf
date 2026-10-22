package ru.minecomplex.mac.world;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.player.state.ClientPlayerData;
import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.fixture.inbound.UseItemOn;
import ru.minecomplex.mac.fixture.outbound.*;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.replica.WorldModel;
import ru.minecomplex.mac.world.piston.PistonProperties;

/** Ownership and uncertainty contracts around the vanilla opening oracle. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class OpenablePredictionTest {
    @Test
    void forksKeepLocalOpeningAndServerCorrectionWaitsForAck() {
        var registry=BlockRegistry.get(); var properties=PistonProperties.get();
        int closed=properties.defaultState("minecraft:oak_trapdoor"),open=properties.with(closed,"open","true");
        var model=new WorldModel(registry);
        model.sent(0,Wire.of(new JoinGame(1,"minecraft:overworld",0,96,2)));
        model.sent(1,Wire.of(new ChunkData(0,0,new int[6],new int[]{1,1,1,1,1,1},new int[6],new long[0],ChunkBlockEntities.encode(List.of()))));
        model.sent(2,Wire.of(new BlockUpdate(8,63,8,closed)));
        var original=model.hypothesis(new SyncWindow(2,3),2);
        original.world().startPrediction(1,new Vec3d(8.5,64,8.5));
        original.world().useOpenable(8,63,8,0F); original.world().endPrediction();
        var fork=original.fork();
        assertEquals(open,original.world().getBlock(8,63,8)); assertEquals(open,fork.world().getBlock(8,63,8));
        model.sent(3,Wire.of(new BlockUpdate(8,63,8,closed))); model.sent(4,Wire.of(new BlockChangedAck(1)));
        original.apply(new SyncWindow(3,4),3);
        assertEquals(open,original.world().getBlock(8,63,8),"Server update only replaces the retained confirmed state");
        original.apply(new SyncWindow(4,5),4);
        assertEquals(closed,original.world().getBlock(8,63,8));
        assertEquals(open,fork.world().getBlock(8,63,8),"Another hypothesis has not handled the acknowledgement yet");
        fork.apply(new SyncWindow(4,5),4); assertEquals(closed,fork.world().getBlock(8,63,8));
        assertEquals(List.of(new BlockPredictions.Restored(new BlockPosition(8,63,8),closed,new Vec3d(8.5,64,8.5))),fork.world().takeRestored());
        assertTrue(fork.world().dynamicGaps().isEmpty());
    }

    @Test
    void spectatorOffhandAndUnresolvedSecondaryUseCannotCertifyOpening() {
        var registry=BlockRegistry.get(); int state=PistonProperties.get().defaultState("minecraft:oak_trapdoor");
        var player=new PlayerState(); var data=new ClientPlayerData(); data.localMode=GameType.SURVIVAL;
        var use=new UseItemOn(false,1,8,63,8);
        assertTrue(OpenableBlocks.consumes(registry,state,player,data,Wire.of(use)));
        assertFalse(OpenableBlocks.consumes(registry,state,player,data,Wire.of(new UseItemOn(true,1,8,63,8))));
        data.localMode=GameType.SPECTATOR; assertFalse(OpenableBlocks.consumes(registry,state,player,data,Wire.of(use)));
        data.localMode=GameType.SURVIVAL; player.shiftKeyDown=true;
        assertTrue(OpenableBlocks.consumes(registry,state,player,data,Wire.of(use)),"Both empty hands permit secondary block use");
        data.inventory.setUnknown(40,true); assertFalse(OpenableBlocks.consumes(registry,state,player,data,Wire.of(use)));
        data.inventory.replace(40,FixtureItems.stack(WireItem.of("minecraft:shield",1)));
        assertFalse(OpenableBlocks.consumes(registry,state,player,data,Wire.of(use)),"A nonempty offhand suppresses main-hand block use while sneaking");
    }
}
