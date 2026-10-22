package ru.minecomplex.mac.entity;

import java.util.Map;

/**
 * Native 26.3 synced mob data (VehicleDataProbe): mob flags 15, baby 16, horse flags 18, camel dash 19 and pose clock 20.
 * Unreceived items retain the exact defineSynchedData defaults.
 */
public final class MobData {

    private static final int MOB_FLAGS = 15;

    private static final int BABY = 16;

    private final Map<Integer, Object> items;

    /**
     * The entity ({@code null}: none, or the local player's own id).
     */
    private final PacketEntity base;


    private final EntityTypes types;

    public MobData(ru.minecomplex.mac.replica.CompensatedEntities entities, int id) {
        this.items = entities.mobItems(id);
        var entity = entities.entity(id);
        this.base = entity == null || entity.spawn() == null || id == entities.localPlayerId() ? null : entity;
        this.types = EntityTypes.get();
    }

    /**
     * The release's id of one of the living mounts' own items, by {@code Class.FIELD} as MAET records it ({@code
     * Pig.DATA_BOOST_TIME}, …); −1 where the release has none.
     */
    public int id(String accessor) {
        return types.dataId(accessor);
    }

    /**
     * A boolean item by MAET accessor name, its default {@code false} when never received or absent.
     */
    public boolean flag(String accessor) {
        int id = id(accessor);
        return id >= 0 && items.get(id) instanceof Boolean value && value;
    }

    /**
     * An int item by MAET accessor name, its default 0 when never received or absent.
     */
    public int integer(String accessor) {
        int id = id(accessor);
        return id >= 0 && items.get(id) instanceof Integer value ? value : 0;
    }

    /**
     * {@code TamableAnimal.isTame()}: its flags bit 4.
     */
    public boolean tame() {
        int id = id("TamableAnimal.DATA_FLAGS_ID");
        return id >= 0 && (flags(id) & 4) != 0;
    }

    /**
     * The data id of {@code AbstractHorse.DATA_ID_FLAGS}.
     */
    public int horseFlagsId() {
        return 18;
    }

    /**
     * The data id of {@code Camel.DASH}.
     */
    public int camelDashId() {
        return 19;
    }

    /**
     * The data id of {@code Camel.LAST_POSE_CHANGE_TICK}.
     */
    public int camelPoseTickId() {
        return 20;
    }

    /**
     * {@code Mob.isNoAi()}: mob flags bit 1.
     */
    public boolean noAi() {
        return (flags(MOB_FLAGS) & 1) != 0;
    }

    /**
     * {@code AgeableMob.isBaby()}.
     */
    public boolean baby() {
        return items.get(BABY) instanceof Boolean value && value;
    }

    /**
     * {@code AbstractHorse.getFlag(bit)}: 2 tame, 4 saddle (before 1.21.5), 8 bred, 16 eating, 32 standing.
     */
    public boolean horseFlag(int bit) {
        return (flags(horseFlagsId()) & bit) != 0;
    }

    /**
     * {@code Camel.isDashing()}.
     */
    public boolean camelDash() {
        return items.get(camelDashId()) instanceof Boolean value && value;
    }

    /**
     * {@code Camel.getPoseTime} anchor: {@code LAST_POSE_CHANGE_TICK} (negative while sitting).
     */
    public long camelLastPoseChangeTick() {
        return items.get(camelPoseTickId()) instanceof Long value ? value : 0L;
    }

    /**
     * {@code Entity.getPose()}.
     */
    public Pose pose() {
        return base != null ? base.syncedPose() : Pose.STANDING;
    }

    /**
     * {@code LivingEntity.isAlive()} as far as the data tells: a health item that is not positive is dying.
     */
    public boolean alive() {
        return base == null || base.alive();
    }

    /**
     * {@code Entity.isNoGravity()}.
     */
    public boolean noGravity() {
        return base != null && base.noGravity();
    }

    private byte flags(int id) {
        return items.get(id) instanceof Byte value ? value : 0;
    }
}
