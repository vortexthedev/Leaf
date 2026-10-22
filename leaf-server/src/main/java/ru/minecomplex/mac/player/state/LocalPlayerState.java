package ru.minecomplex.mac.player.state;

import ru.minecomplex.mac.engine.movementtick.LocalPlayerControls;
import ru.minecomplex.mac.engine.movementtick.VehiclePacketState;
import ru.minecomplex.mac.util.math.Vec2f;

/** Hidden LocalPlayer state. Retain and fork together with PlayerState and the client-world hypothesis. */
public final class LocalPlayerState {
    public PlayerInput input = PlayerInput.EMPTY;
    public LocalPlayerControls controls = new LocalPlayerControls();
    public Vec2f vector = new Vec2f(0.0F, 0.0F);
    public boolean crouching, wasFallFlying;
    /** Passenger look state, retained across ride ticks and hypothesis forks. */
    public boolean handsBusy;
    public float yawOld, headYaw, bodyYaw;
    public int sprintTriggerTime, jumpTriggerTime, autoJumpTime, waterVisionTime;
    public float jumpRidingScale;
    public int jumpRidingTicks;
    public SendState send = new SendState();
    /** Null means unresolved root vehicle; refresh this snapshot at the actual version-specific send phase. */
    public VehiclePacketState rootVehicle;

    // Client options and camera, as the model assumes them (not sent by the client)
    /** The camera entity is the player ({@code isControlledCamera}). */
    public boolean controlledCamera = true;
    /** The auto-jump option. */
    public boolean autoJumpOption;
    /** The sprint double-tap window option (from 1.21.9; 7 ticks before). */
    public int sprintWindow = 7;

    public LocalPlayerState copy() {
        var copy = new LocalPlayerState();
        copy.input = input;
        copy.vector = vector;
        copy.controls = controls.copy();
        copy.crouching = crouching;
        copy.wasFallFlying = wasFallFlying;
        copy.handsBusy = handsBusy;
        copy.yawOld = yawOld;
        copy.headYaw = headYaw;
        copy.bodyYaw = bodyYaw;
        copy.sprintTriggerTime = sprintTriggerTime;
        copy.jumpTriggerTime = jumpTriggerTime;
        copy.autoJumpTime = autoJumpTime;
        copy.waterVisionTime = waterVisionTime;
        copy.jumpRidingScale = jumpRidingScale;
        copy.jumpRidingTicks = jumpRidingTicks;
        copy.send = send.copy();
        copy.rootVehicle = rootVehicle;
        copy.controlledCamera = controlledCamera;
        copy.autoJumpOption = autoJumpOption;
        copy.sprintWindow = sprintWindow;
        return copy;
    }
}
