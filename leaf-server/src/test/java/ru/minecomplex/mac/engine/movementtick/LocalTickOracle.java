package ru.minecomplex.mac.engine.movementtick;

import ru.minecomplex.mac.fixture.inbound.InboundEvent;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.player.state.PlayerState;

/** MACL v3 world/hidden state, v4 fluid state and v5 actual camera/screen/focus facts. */
public final class LocalTickOracle {

    public record Block(int x, int y, int z, int state) {}
    public record State(LocalPlayerOracleCodec.State base, boolean wasShift, float ridingScale, int ridingTicks,
                 int waterVision, boolean wasFallFlying, int food, Fluid fluid) {
        public static State read(DataInputStream in) throws IOException {
            return new State(LocalPlayerOracleCodec.State.read(in), in.readBoolean(), in.readFloat(), in.readInt(),
                    in.readInt(), in.readBoolean(), in.readInt(), null);
        }
    }
    record Fluid(double waterHeight,double lavaHeight,boolean water,boolean lava,boolean eyeWater,
                 boolean eyeLava,boolean wasEyeWater,boolean underwater) {
        public static Fluid read(DataInputStream in) throws IOException {
            return new Fluid(in.readDouble(),in.readDouble(),in.readBoolean(),in.readBoolean(),
                    in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean());
        }
        void apply(PlayerState p) {
            p.waterHeight=waterHeight;p.lavaHeight=lavaHeight;p.wasTouchingWater=water;p.isInLava=lava;
            p.eyeInWater=eyeWater;p.eyeInLava=eyeLava;p.wasEyeInWater=wasEyeWater;p.wasUnderwater=underwater;
        }
    }
    private static State state(DataInputStream in, boolean wet) throws IOException {
        State s=State.read(in);
        return wet?new State(s.base(),s.wasShift(),s.ridingScale(),s.ridingTicks(),s.waterVision(),s.wasFallFlying(),s.food(),Fluid.read(in)):s;
    }
    record Control(int flags) {
        boolean controlled(){return (flags&1)!=0;}boolean focused(){return (flags&2)!=0;}
        boolean loading(){return (flags&4)!=0;}boolean blocked(){return (flags&8)!=0;}
        int screenOp(){return flags>>4;}
    }
    record Tick(int rawKeys, float yaw, float pitch, State after, List<InboundEvent> packets, Control control) {}
    record Scenario(String family, boolean autoJump, boolean mayfly, boolean flying, boolean toggleCrouch,
                    boolean toggleSprint, int sprintWindow, List<Block> blocks, State initial, List<Tick> ticks) {}
    record Trace(List<Scenario> scenarios) {}
    public static Trace read() throws IOException { return read(false); }
    public static Trace read(boolean wet) throws IOException {
        return read(wet, wet?"local-fluid-ticks":"local-ticks");
    }
    public static Trace read(boolean wet, String stem) throws IOException {

        String path = "/vanilla/777/" + stem + ".bin.gz";
        var stream = LocalTickOracle.class.getResourceAsStream(path);
        if (stream == null) throw new IOException("Missing " + path);
        try (var in = new DataInputStream(new GZIPInputStream(stream))) {
            boolean camera=stem.equals("local-camera-ticks");
            if (in.readInt() != 0x4d41434c || in.readUnsignedShort() != (camera ? 5 : wet ? 4 : 3) || in.readInt() != ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL)
                throw new IOException("Unexpected MACL header for " + stem);
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int count = in.readInt();
            var scenarios = new ArrayList<Scenario>();
            for (int s = 0; s < count; s++) scenarios.add(readScenario(in, wet, camera));
            if (in.read() != -1) throw new IOException("Trailing MACL data for " + stem);
            return new Trace(List.copyOf(scenarios));
        }
    }

    /** One MACL scenario (also the body of other families' scenarios, e.g. MAES). */
    static Scenario readScenario(DataInputStream in, boolean wet, boolean camera) throws IOException {
        String family = in.readUTF();
        boolean auto = in.readBoolean(), mayfly = in.readBoolean(), flying = in.readBoolean();
        boolean crouch = in.readBoolean(), sprint = in.readBoolean();
        int window = in.readInt(), blockCount = in.readInt();
        var blocks = new ArrayList<Block>();
        for (int b = 0; b < blockCount; b++)
            blocks.add(new Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
        State initial = state(in,wet);
        int tickCount = in.readInt();
        var ticks = new ArrayList<Tick>();
        for (int t = 0; t < tickCount; t++) {
            int keys = in.readUnsignedByte();
            float yaw = in.readFloat(), pitch = in.readFloat();
            Control control=camera?new Control(in.readInt()):null;
            State after = state(in,wet);
            int packetCount = in.readInt();
            var packets = new ArrayList<InboundEvent>();
            for (int p = 0; p < packetCount; p++) packets.add(LocalPlayerOracleCodec.packet(in));
            ticks.add(new Tick(keys, yaw, pitch, after, List.copyOf(packets),control));
        }
        return new Scenario(family, auto, mayfly, flying, crouch, sprint, window,
                List.copyOf(blocks), initial, List.copyOf(ticks));
    }
}
