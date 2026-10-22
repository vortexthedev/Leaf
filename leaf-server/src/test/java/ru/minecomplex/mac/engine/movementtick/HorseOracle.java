package ru.minecomplex.mac.engine.movementtick;

import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.engine.MovementEnvironment;

/** Test data conversion for the consolidated horse ticker. Contains no simulated mechanics. */
public final class HorseOracle {
    public record Dimensions(float width,float height,float eyeHeight) { }
    public record Rider(float xxa,float zza,float yaw,float pitch,boolean sprinting) {
        public PlayerState player() {var p=new PlayerState();p.xxa=xxa;p.zza=zza;p.yRot=yaw;p.xRot=pitch;p.sprinting=sprinting;return p;}
    }
    public record Control(HorseState.Kind kind,boolean saddled,boolean localAuthority,boolean effectiveAi,boolean onGround,boolean canRear,long gameTime,double speed,double jumpStrength,float blockJumpFactor,float blockSpeedFactor,float jumpBoost) {
        public void apply(HorseState s) {
            if(s.kind!=kind)throw new IllegalArgumentException("Wrong horse kind");
            s.saddled=saddled;s.localAuthority=localAuthority;s.effectiveAi=effectiveAi;s.canRear=canRear;s.body.onGround=onGround;
            s.body.attributes.get(Attribute.MOVEMENT_SPEED).setBaseValue(speed);
            s.body.attributes.get(Attribute.JUMP_STRENGTH).setBaseValue(jumpStrength);
        }
    }
    public static MovementTickerHorse body(MovementEnvironment env,HorseState state,float eye,boolean pushed,boolean controlled,boolean driver,boolean noGravity,Dimensions dimensions) {
        state.eyeHeight=eye;state.pushedByFluid=pushed;state.controlledByClient=controlled;state.playerController=driver;state.noGravity=noGravity;
        state.standingWidth=dimensions.width;state.standingHeight=dimensions.height;state.standingEyeHeight=dimensions.eyeHeight;
        return new MovementTickerHorse(env,state);
    }
    public static java.util.Set<ru.minecomplex.mac.engine.Unmodeled> simulate(HorsePlayerTick tick,HorseState state,Control facts,float maxHeadYaw,boolean empty,boolean exceptional,boolean canFloat) {
        facts.apply(state);state.maxHeadYaw=maxHeadYaw;state.navigationCanFloat=canFloat;
        return tick.simulateVehicleTick(facts.gameTime(),empty,exceptional);
    }
    public static float blockJumpFactor(MovementTickerHorse horse) { return horse.getBlockJumpFactor(); }
    public static float blockSpeedFactor(MovementTickerHorse horse) { return horse.getBlockSpeedFactor(); }
    public static void assign(HorseState target,HorseState source) {
        for(var field:HorseState.class.getFields()) {
            if(!java.util.Set.of("yawOld","headYaw","bodyYaw","pendingJump","allowStandSliding","jumping","standing","eating","needsSync","standCounter","gallopSoundCounter","standAnimation","oldStandAnimation","dashing","dashCooldown","lastPoseChangeTick","pose").contains(field.getName()))continue;
            try {field.set(target,field.get(source));}catch(IllegalAccessException e){throw new AssertionError(e);}
        }
        target.body.deltaMovement=source.body.deltaMovement;target.body.yRot=source.body.yRot;target.body.xRot=source.body.xRot;
    }
}
