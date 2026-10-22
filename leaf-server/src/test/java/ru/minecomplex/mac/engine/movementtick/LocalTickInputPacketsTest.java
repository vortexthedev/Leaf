package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.inbound.PlayerAbilities;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec2f;

/**
 * Input and chained send-state oracles for vanilla 26.3; this does not certify movement.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalTickInputPacketsTest {

    @Test
    void inputAndPackets() throws IOException {
        var trace = LocalTickOracle.read();
        var inputPort = SendPositions.inputPort();
        int count = 0;
        for (var scenario : trace.scenarios()) {
            int rawPrevious = 0, effective = 0;
            var previous = scenario.initial().base();
            var send = previous.sendState();
            send.wasShiftKeyDown = scenario.initial().wasShift();
            for (var tick : scenario.ticks()) {
                int raw = tick.rawKeys();
                for (int key = 0; key < 7; key++) {
                    int bit = 1 << key;
                    boolean toggle = key == 5 && scenario.toggleCrouch() || key == 6 && scenario.toggleSprint();
                    if (toggle) {
                        if ((raw & bit) != 0 && (rawPrevious & bit) == 0)
                            effective ^= bit;
                    } else
                        effective = effective & ~bit | raw & bit;
                }
                rawPrevious = raw;
                PlayerInput keys = PlayerInput.fromMask(effective);
                Vec2f vector = inputPort.keyboardVector(keys);
                if (previous.autoJumpTime() > 0)
                    keys = keys.withJump();
                var after = tick.after().base();
                String context = "26.3 " + scenario.family() + " tick " + count;
                boolean slowly = after.crouching() || previous.pose().equals("SWIMMING");
                Vec2f modified = inputPort.modifyInput(vector, false, false, 1.0F, slowly, 0.3);
                Vec2f recordedVector = vector;
                assertEquals(after.input(), keys, context);
                bits(after.vector().x(), recordedVector.x(), context + " input x");
                bits(after.vector().y(), recordedVector.y(), context + " input y");
                bits(after.xxa(), modified.x(), context + " xxa");
                bits(after.zza(), modified.y(), context + " zza");
                var result = SendPositions.send(after.player().toState(), send, keys, true, true, scenario.autoJump(), null);
                var expected = tick.packets().stream().filter(p -> !(p instanceof PlayerAbilities)).toList();
                assertEquals(expected, Wire.in(result.packets()), context + " packets");
                assertTrue(result.unmodeled().isEmpty(), context);
                assertEquals(after.lastSentInput(), send.lastSentInput, context);
                assertEquals(after.wasSprinting(), send.wasSprinting, context);
                assertEquals(tick.after().wasShift(), send.wasShiftKeyDown, context);
                assertEquals(after.positionReminder(), send.positionReminder, context);
                assertEquals(Double.doubleToLongBits(after.xLast()), Double.doubleToLongBits(send.xLast), context);
                assertEquals(Double.doubleToLongBits(after.yLast()), Double.doubleToLongBits(send.yLast), context);
                assertEquals(Double.doubleToLongBits(after.zLast()), Double.doubleToLongBits(send.zLast), context);
                bits(after.yRotLast(), send.yRotLast, context);
                bits(after.xRotLast(), send.xRotLast, context);
                assertEquals(after.lastOnGround(), send.lastOnGround, context);
                assertEquals(after.lastHorizontalCollision(), send.lastHorizontalCollision, context);
                assertEquals(after.autoJumpEnabled(), send.autoJumpEnabled, context);
                previous = after;
                count++;
            }
        }
        assertEquals(10500, count);
    }

    private static void bits(float expected, float actual, String context) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), context);
    }
}
