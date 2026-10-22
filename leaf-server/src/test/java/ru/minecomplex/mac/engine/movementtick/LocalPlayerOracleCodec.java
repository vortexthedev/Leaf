package ru.minecomplex.mac.engine.movementtick;

import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerCommand;
import ru.minecomplex.mac.fixture.inbound.PlayerAbilities;

import ru.minecomplex.mac.player.state.SendState;

import java.io.DataInputStream;
import java.io.IOException;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec2f;

/** Shared state/packet payload codec for native MACL and MACD fixtures. */
public final class LocalPlayerOracleCodec {
    public record State(MovementOracleTest.Recorded player, PlayerInput input, Vec2f vector, boolean crouching,
                 String pose, boolean swimming, boolean fallFlying, boolean flying, int jumpTriggerTime,
                 int sprintTriggerTime, int autoJumpTime, boolean wasSprinting, PlayerInput lastSentInput,
                 double xLast, double yLast, double zLast, float yRotLast, float xRotLast,
                 int positionReminder, boolean lastOnGround, boolean lastHorizontalCollision,
                 boolean autoJumpEnabled, float xxa, float zza) {
        static State read(DataInputStream in) throws IOException {
            return new State(MovementOracleTest.Recorded.read(in), PlayerInput.fromMask(in.readUnsignedByte()),
                    new Vec2f(in.readFloat(), in.readFloat()), in.readBoolean(), in.readUTF(),
                    in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readInt(), in.readInt(), in.readInt(),
                    in.readBoolean(), PlayerInput.fromMask(in.readUnsignedByte()), in.readDouble(), in.readDouble(),
                    in.readDouble(), in.readFloat(), in.readFloat(), in.readInt(), in.readBoolean(), in.readBoolean(),
                    in.readBoolean(), in.readFloat(), in.readFloat());
        }

        public SendState sendState() {
            var state = new SendState();
            state.lastSentInput = lastSentInput; state.wasSprinting = wasSprinting;
            state.xLast = xLast; state.yLast = yLast; state.zLast = zLast;
            state.yRotLast = yRotLast; state.xRotLast = xRotLast;
            state.positionReminder = positionReminder;
            state.lastOnGround = lastOnGround; state.lastHorizontalCollision = lastHorizontalCollision;
            state.autoJumpEnabled = autoJumpEnabled;
            return state;
        }
    }

    public static InboundEvent packet(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> {
                boolean position = in.readBoolean(), rotation = in.readBoolean();
                Movement.Kind kind = position ? (rotation ? Movement.Kind.POS_ROT : Movement.Kind.POS)
                        : (rotation ? Movement.Kind.ROT : Movement.Kind.STATUS_ONLY);
                yield new Movement(kind, in.readDouble(), in.readDouble(), in.readDouble(),
                        in.readFloat(), in.readFloat(), in.readBoolean(), in.readBoolean());
            }
            case 1 -> new ru.minecomplex.mac.fixture.inbound.PlayerInput(in.readUnsignedByte());
            case 2 -> new PlayerCommand(PlayerCommand.Action.valueOf(in.readUTF()), 0);
            case 3 -> new PlayerAbilities(in.readBoolean());
            case 4 -> throw new IOException("Unexpected packet " + in.readUTF());
            case 5 -> new PlayerCommand(PlayerCommand.Action.START_RIDING_JUMP, in.readInt());
            case 6 -> new ru.minecomplex.mac.fixture.inbound.VehicleMovement(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readBoolean());
            case 7 -> new ru.minecomplex.mac.fixture.inbound.PaddleBoat(in.readBoolean(), in.readBoolean());
            case 8 -> {
                int id = in.readInt();
                yield in.readBoolean() ? ru.minecomplex.mac.fixture.inbound.TeleportAccept.withPosition(id, in.readDouble(),
                        in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat())
                        : ru.minecomplex.mac.fixture.inbound.TeleportAccept.idOnly(id);
            }
            case 9 -> new ru.minecomplex.mac.fixture.inbound.UseItem(in.readBoolean(), in.readInt(), in.readFloat(), in.readFloat());
            case 10 -> new ru.minecomplex.mac.fixture.inbound.PlayerAction(
                    ru.minecomplex.mac.fixture.inbound.PlayerAction.Action.valueOf(in.readUTF()), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt());
            case 11 -> new ru.minecomplex.mac.fixture.inbound.HeldSlot(in.readInt());
            case 12 -> new ru.minecomplex.mac.fixture.inbound.UseItemOn(in.readBoolean(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt());
            default -> throw new IOException("Unknown MACL packet tag");
        };
    }
}
