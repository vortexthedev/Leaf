package ru.minecomplex.mac.entity.effect;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EffectLifecycleOracleTest {

    private static void check(DataInputStream in, EffectInstance instance, String context) throws IOException {
        if (!in.readBoolean()) { assertNull(instance, context); return; }
        assertNotNull(instance, context);
        assertEquals(in.readInt(), instance.duration(), context);
        assertEquals(in.readInt(), instance.amplifier(), context);
        assertEquals(in.readBoolean(), instance.ambient(), context);
        assertEquals(in.readBoolean(), instance.visible(), context);
        assertEquals(in.readBoolean(), instance.showIcon(), context);
        check(in, instance.hidden(), context + " hidden");
    }

    @Test
    void matchesVanilla() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/effect-lifecycle.bin.gz");
        assertNotNull(raw, "26.3");
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414546, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(160, in.readInt());
            var unresolved = new ActiveEffects();
            unresolved.set(MobEffect.JUMP_BOOST, 2);
            assertFalse(unresolved.timingResolved());
            var unresolvedCopy = unresolved.copy();
            unresolvedCopy.replace(MobEffect.JUMP_BOOST, new EffectInstance(3, 2, false, true, true));
            assertFalse(unresolved.timingResolved()); assertTrue(unresolvedCopy.timingResolved());
            assertThrows(IllegalStateException.class, () -> unresolved.add(MobEffect.JUMP_BOOST,
                    new EffectInstance(3, 5, false, true, true)));
            unresolved.tickClient(); assertEquals(2, unresolved.amplifier(MobEffect.JUMP_BOOST));
            unresolved.remove(MobEffect.JUMP_BOOST); assertTrue(unresolved.timingResolved());
            for (int scenario = 0; scenario < 160; scenario++) {
                ActiveEffects effects = new ActiveEffects(); assertEquals(100, in.readInt());
                for (int step = 0; step < 100; step++) {
                    int action = in.readUnsignedByte();
                    var incoming = new EffectInstance(in.readInt(), in.readInt(), in.readBoolean(), in.readBoolean(), in.readBoolean());
                    ActiveEffects fork = effects.copy();
                    boolean changed = apply(effects, action, incoming), expected = in.readBoolean();
                    assertEquals(expected, changed, "26.3");
                    assertEquals(changed, apply(fork, action, incoming));
                    String context = "26.3 scenario " + scenario + " tick " + step;
                    check(in, effects.instance(MobEffect.JUMP_BOOST), context);
                    String snapshot = snapshot(effects.instance(MobEffect.JUMP_BOOST));
                    fork.tickClient();
                    assertEquals(snapshot, snapshot(effects.instance(MobEffect.JUMP_BOOST)), context + " fork isolation");
                    assertEquals(effects.has(MobEffect.JUMP_BOOST), fork.has(MobEffect.JUMP_BOOST));
                    assertTrue(effects.timingResolved());
                }
            }
            assertEquals(-1, in.read());
        }
    }

    private static String snapshot(EffectInstance instance) {
        if (instance == null) return "null";
        return instance.duration() + ":" + instance.amplifier() + ":" + instance.ambient() + ":"
                + instance.visible() + ":" + instance.showIcon() + ":" + snapshot(instance.hidden());
    }

    private static boolean apply(ActiveEffects effects, int action, EffectInstance incoming) {
        return switch (action) {
            case 0 -> { effects.replace(MobEffect.JUMP_BOOST, incoming); yield true; }
            case 1 -> effects.add(MobEffect.JUMP_BOOST, incoming);
            case 2 -> { effects.tickClient(); yield false; }
            case 3 -> { effects.remove(MobEffect.JUMP_BOOST); yield false; }
            default -> throw new AssertionError(action);
        };
    }
}
