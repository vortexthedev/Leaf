package ru.minecomplex.mac.entity;

import java.util.Map;

/**
 * The stored dimensions of another living entity on the client ({@code Entity.dimensions}, its box around the position)
 * and the client-side state they read. {@code refreshDimensions} recomputes them from {@code getDimensions(getPose())}
 * ({@code LivingEntity}: {@code SLEEPING_DIMENSIONS} when sleeping, else {@code getDefaultDimensions(pose)} scaled by
 * {@code getScale()}) only when vanilla calls it:
 *
 * <ul>
 *   <li>a {@code set_entity_data} item whose {@code onSyncedDataUpdated} refreshes: the pose ({@code Entity}), the age
 *   ({@code AgeableMob}, and the own {@code DATA_BABY_ID} of zombies, piglins and zoglins), a cube mob's or phantom's
 *   {@code ID_SIZE}, a pufferfish's {@code PUFF_STATE}, a salmon's {@code DATA_TYPE}, a sniffer's {@code DATA_STATE}
 *   (every assignment, equal values too: {@code SynchedEntityData.assignValues});</li>
 *   <li>the end of the entity's tick when its {@code scale} changed ({@code LivingEntity.tick}: 1.21.4 compares it with
 *   {@code appliedScale}, later releases refresh a dirty {@code scale} in {@code refreshDirtyAttributes}; both leave the
 *   same dimensions);</li>
 *   <li>a polar bear's tick after its client stand animation moved ({@code PolarBear.tick});</li>
 *   <li>construction: {@code Entity}'s constructor takes the type's dimensions, a pufferfish and a salmon refresh them.</li>
 * </ul>
 *
 * <p>{@code getDefaultDimensions(pose)} comes from the client's own entity (MAET living section: adult and baby, every pose,
 * default synched data), with the overrides that read other data ported: cube mobs ({@code getType().getDimensions()
 * .scale(getSize())}), phantoms ({@code scale(1 + 0.15 size)}), pufferfish ({@code scale} 0.5/0.7/1 by puff state),
 * salmon (0.5/1/1.5 by variant, clamped), sniffer ({@code DIGGING_DIMENSIONS.scale(getAgeScale())} while digging) and the
 * polar bear's stand animation ({@code scale(1, 1 + stand / 6)}). Players use the player pose table.
 */
public final class LivingShape {
    enum Form { PLAYER, CUBE, PHANTOM, PUFFERFISH, SALMON, SNIFFER, POLAR_BEAR, OTHER }

    /** {@code Sniffer.State.DIGGING}'s wire id. */
    private static final int SNIFFER_DIGGING = 5;

    private final String key;
    private final Form form;
    private final EntityTypes types;
    private final EntityTypes.Living living;
    /** The data ids this type's refreshing items have in the client's release (−1: the type has none). */
    private final int babyId, sizeId, puffId, salmonId, snifferId, standingId;
    EntityDimensions dimensions;
    /** {@code getPose()}: the synched pose (another player does not set its own: {@code RemotePlayer.updatePlayerPose}). */
    Pose pose = Pose.STANDING;
    /** The {@code scale} the stored dimensions were computed with. */
    float scale = 1.0F;
    /** {@code PolarBear.clientSideStandAnimation} and its previous value. */
    float stand, standO;

    LivingShape(String key, EntityTypes types) {
        this.key = key;
        this.types = types;
        this.living = types.living(key);
        this.form = switch (key) {
            case "minecraft:player" -> Form.PLAYER;
            case "minecraft:slime", "minecraft:magma_cube", "minecraft:sulfur_cube" -> Form.CUBE;
            case "minecraft:phantom" -> Form.PHANTOM;
            case "minecraft:pufferfish" -> Form.PUFFERFISH;
            case "minecraft:salmon" -> Form.SALMON;
            case "minecraft:sniffer" -> Form.SNIFFER;
            case "minecraft:polar_bear" -> Form.POLAR_BEAR;
            default -> Form.OTHER;
        };
        this.babyId = item("DATA_BABY_ID");
        this.sizeId = form == Form.CUBE || form == Form.PHANTOM ? item("ID_SIZE") : -1;
        this.puffId = form == Form.PUFFERFISH ? item("PUFF_STATE") : -1;
        this.salmonId = form == Form.SALMON ? item("DATA_TYPE") : -1;
        this.snifferId = form == Form.SNIFFER ? item("DATA_STATE") : -1;
        this.standingId = form == Form.POLAR_BEAR ? item("DATA_STANDING_ID") : -1;
        this.dimensions = types.type(key).dimensions();
    }

    private int item(String field) {
        return living == null ? -1 : living.item(field);
    }

    /** Whether this type's dimensions are modelled (the client constructs it; players have their own table). */
    static boolean modelled(String key, EntityTypes types) {
        var type = types.type(key);
        return type != null && type.living() && (key.equals("minecraft:player") || types.living(key) != null);
    }

    LivingShape copy() {
        var copy = new LivingShape(key, types);
        copy.dimensions = dimensions;
        copy.pose = pose;
        copy.scale = scale;
        copy.stand = stand;
        copy.standO = standO;
        return copy;
    }

    /** The constructor's dimensions: the type's, refreshed by a pufferfish's and a salmon's constructor. */
    void construct(ru.minecomplex.mac.replica.CompensatedEntities owner, int id, float scale) {
        dimensions = types.type(key).dimensions();
        if (form == Form.PUFFERFISH || form == Form.SALMON) refresh(owner, id, scale);
    }

    /**
     * {@code assignValues} then {@code onSyncedDataUpdated} for each item of a {@code set_entity_data}: the pose is stored;
     * whether one of the items refreshes the dimensions.
     */
    boolean refreshes(Map<Integer, Object> items) {
        if (items.get(EntityDataItems.POSE) instanceof Pose value) {
            pose = value;
            return true;
        }
        for (int id : new int[]{babyId, sizeId, puffId, salmonId, snifferId}) {
            if (id >= 0 && items.containsKey(id)) return true;
        }
        return false;
    }

    /** {@code refreshDimensions()}. */
    void refresh(ru.minecomplex.mac.replica.CompensatedEntities owner, int id, float scale) {
        this.scale = scale;
        dimensions = pose == Pose.SLEEPING ? types.sleepingDimensions() : defaultDimensions(owner, id, pose).scale(scale);
    }

    /** The end of the entity's own tick: a changed {@code scale}, then a polar bear's stand animation. */
    void tickEnd(ru.minecomplex.mac.replica.CompensatedEntities owner, int id, float currentScale) {
        if (Float.floatToIntBits(currentScale) != Float.floatToIntBits(scale)) refresh(owner, id, currentScale);
        if (form == Form.POLAR_BEAR) {
            if (stand != standO) refresh(owner, id, currentScale);
            standO = stand;
            boolean standing = owner.mobItems(id).get(standingId) instanceof Boolean value && value;
            stand = Math.clamp(stand + (standing ? 1.0F : -1.0F), 0.0F, 6.0F);
        }
    }

    /** {@code getDefaultDimensions(pose)}. */
    private EntityDimensions defaultDimensions(ru.minecomplex.mac.replica.CompensatedEntities owner, int id, Pose pose) {
        Map<Integer, Object> items = owner.mobItems(id);
        boolean baby = babyId >= 0 && items.get(babyId) instanceof Boolean value && value;
        return switch (form) {
            case PLAYER -> types.playerDefaultDimensions(pose);
            case CUBE -> types.type(key).dimensions().scale((float) integer(items, sizeId, 1));
            case PHANTOM -> living.defaultDimensions(false, pose).scale(1.0F + 0.15F * integer(items, sizeId, 0));
            case PUFFERFISH -> types.type(key).dimensions().scale(switch (integer(items, puffId, 0)) {
                case 0 -> 0.5F;
                case 1 -> 0.7F;
                default -> 1.0F;
            });
            // Salmon.Variant.BY_ID clamps: SMALL 0.5, MEDIUM 1, LARGE 1.5
            case SALMON -> types.type(key).dimensions().scale(new float[]{0.5F, 1.0F, 1.5F}[Math.clamp(integer(items, salmonId, 1), 0, 2)]);
            case SNIFFER -> integer(items, snifferId, 0) == SNIFFER_DIGGING
                    ? digging().scale(living.ageScale(baby)) : living.defaultDimensions(baby, pose);
            case POLAR_BEAR -> {
                EntityDimensions dimension = living.defaultDimensions(baby, pose);
                yield stand > 0.0F ? dimension.scale(1.0F, 1.0F + stand / 6.0F) : dimension;
            }
            case OTHER -> living.defaultDimensions(baby, pose);
        };
    }

    /** {@code Sniffer.DIGGING_DIMENSIONS}: {@code scalable(SNIFFER.getWidth(), SNIFFER.getHeight() - 0.4F).withEyeHeight(0.81F)}. */
    private EntityDimensions digging() {
        var type = types.type(key).dimensions();
        return new EntityDimensions(type.width(), type.height() - 0.4F, 0.81F, type.attachments(), false);
    }

    /** {@code Entity.setPose} by the entity itself: a changed pose refreshes the dimensions ({@code onSyncedDataUpdated}). */
    void setPose(ru.minecomplex.mac.replica.CompensatedEntities owner, int id, Pose value, float scale) {
        if (value == pose) return;
        pose = value;
        refresh(owner, id, scale);
    }

    public Pose pose() {
        return pose;
    }

    private static int integer(Map<Integer, Object> items, int id, int fallback) {
        return id >= 0 && items.get(id) instanceof Integer value ? value : fallback;
    }

    public EntityDimensions dimensions() {
        return dimensions;
    }
}
