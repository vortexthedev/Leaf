package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.engine.movementtick.HorseOracle;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;

import ru.minecomplex.mac.vanilla.OracleBits;
import ru.minecomplex.mac.mechanics.fluid.FluidContactOracleTest;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HorseControlOracleTest {

    static HorseState state(DataInputStream in,boolean camel)throws IOException {
        var s=new HorseState(new Object(),camel?HorseState.Kind.CAMEL:HorseState.Kind.HORSE,new ru.minecomplex.mac.player.state.PlayerState()); s.body.deltaMovement=OracleBits.vector(in);
        s.body.yRot=in.readFloat(); s.body.xRot=in.readFloat(); s.yawOld=in.readFloat(); s.headYaw=in.readFloat(); s.bodyYaw=in.readFloat(); s.pendingJump=in.readFloat();
        s.allowStandSliding=in.readBoolean(); s.jumping=in.readBoolean(); s.standing=in.readBoolean(); s.eating=in.readBoolean();
        s.standCounter=in.readInt(); s.gallopSoundCounter=in.readInt(); s.needsSync=in.readBoolean();
        if(camel){s.dashCooldown=in.readInt();s.dashing=in.readBoolean();s.lastPoseChangeTick=in.readLong();}
        return s;
    }
    static void compare(DataInputStream in,HorseState actual,boolean camel,String context)throws IOException {
        compare(in,actual,camel,context,true);
    }
    static void compare(DataInputStream in,HorseState actual,boolean camel,String context,boolean soundCounters)throws IOException {
        var expected=state(in,camel);
        for(var axis:ru.minecomplex.mac.collision.Axis.values()) FluidContactOracleTest.bits(expected.body.deltaMovement.get(axis),actual.body.deltaMovement.get(axis),context+" movement "+axis);
        float[] e={expected.body.yRot,expected.body.xRot,expected.yawOld,expected.headYaw,expected.bodyYaw,expected.pendingJump},a={actual.body.yRot,actual.body.xRot,actual.yawOld,actual.headYaw,actual.bodyYaw,actual.pendingJump};
        for(int n=0;n<e.length;n++) OracleBits.bits(e[n],a[n],context+" float "+n);
        assertEquals(expected.allowStandSliding,actual.allowStandSliding,context+" sliding"); assertEquals(expected.jumping,actual.jumping,context+" jumping");
        assertEquals(expected.standing,actual.standing,context+" standing"); assertEquals(expected.eating,actual.eating,context+" eating");
        assertEquals(expected.standCounter,actual.standCounter,context+" stand counter"); if(soundCounters)assertEquals(expected.gallopSoundCounter,actual.gallopSoundCounter,context+" gallop"); assertEquals(expected.needsSync,actual.needsSync,context+" sync");
        if(camel){assertEquals(expected.dashCooldown,actual.dashCooldown,context+" cooldown");assertEquals(expected.dashing,actual.dashing,context+" dash");assertEquals(expected.lastPoseChangeTick,actual.lastPoseChangeTick,context+" pose clock");}
    }
    @Test
    void matchesVanilla()throws Exception {
        String path="/vanilla/777/horse-controls.bin.gz";var resource=getClass().getResourceAsStream(path);assertNotNull(resource,path);
        try(var in=new DataInputStream(new GZIPInputStream(resource))) {
            assertEquals(0x4d414843,in.readInt());assertEquals(2,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());assertEquals(4096,in.readInt());

            for(int i=0;i<4096;i++) {
                var kind=HorseState.Kind.values()[in.readUnsignedByte()];boolean camel=kind==HorseState.Kind.CAMEL;
                boolean saddled=in.readBoolean(),authority=in.readBoolean(),effective=in.readBoolean(),ground=in.readBoolean(),rear=in.readBoolean();long time=in.readLong();
                var rider=new HorseOracle.Rider(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readBoolean());
                var facts=new HorseOracle.Control(kind,saddled,authority,effective,ground,rear,time,in.readDouble(),in.readDouble(),in.readFloat(),in.readFloat(),in.readFloat());
                var parsed=state(in,camel);var state=new HorseState(new Object(),kind,parsed.body);HorseOracle.assign(state,parsed);
                state.body.fixedBodyDimensions=true;state.body.bodyWidth=1.4F;state.body.bodyHeight=1.6F;
                facts.apply(state);
                int[] calls={0};Vec3d[] observedInput={null};float[] observedSpeed={0};
                var riding=new MovementTickerHorse(LocalPlayerFluidOracleTest.environment(ru.minecomplex.mac.world.BlockRegistry.get(),java.util.List.of()),state) {
                    @Override protected float getBlockJumpFactor(){return facts.blockJumpFactor();}
                    @Override protected float getBlockSpeedFactor(){return facts.blockSpeedFactor();}
                    @Override protected float getJumpBoostPower(){return facts.jumpBoost();}
                    @Override public void travelBody(Vec3d input,float speed,boolean refuses){calls[0]++;observedInput[0]=input;observedSpeed[0]=speed;}
                };
                riding.gameTime(facts.gameTime());
                var riderPlayer=rider.player();String context="26.3 "+kind+" "+i;
                riding.charge(in.readInt());compare(in,state,camel,context+" charge");
                Vec3d input=riding.riddenInput(riderPlayer);FluidTravelOracleTest.compare(in,input,context+" input");OracleBits.bits(in.readFloat(),riding.riddenSpeed(riderPlayer),context+" speed");
                riding.tickRidden(riderPlayer,input);compare(in,state,camel,context+" ridden");
                riding.jump(in.readFloat(),input);compare(in,state,camel,context+" direct jump");
                state.pendingJump=1.0F;
                if(camel)state.dashCooldown=0;
                boolean canTravel=in.readBoolean(); int expectedCalls=in.readInt();
                riding.travelRidden(riderPlayer,canTravel);
                assertEquals(expectedCalls,calls[0],context+" travel calls");
                if(expectedCalls!=0){FluidTravelOracleTest.compare(in,observedInput[0],context+" travel input");OracleBits.bits(in.readFloat(),observedSpeed[0],context+" travel speed");}
                compare(in,state,camel,context+" orchestration");
            }
            assertEquals(-1,in.read());
        }
    }
}
