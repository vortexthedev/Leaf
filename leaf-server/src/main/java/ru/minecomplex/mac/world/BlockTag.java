package ru.minecomplex.mac.world;

/**
 * Block tags read by client movement. Tags are data: the client receives them from the server
 * ({@code update_tags}), so their contents come from {@link BlockTags}, never from code.
 */
public enum BlockTag {
    BLOCKS_FLUID_FLOW("blocks_fluid_flow"),
    CLIMBABLE("climbable"),
    FENCES("fences"),
    WALLS("walls"),
    CAN_GLIDE_THROUGH("can_glide_through"),
    SUPPRESSES_BOUNCE("suppresses_bounce"),
    FALL_DAMAGE_RESETTING("fall_damage_resetting"),
    CAUSES_SUFFOCATION("causes_suffocation"),
    /**
     * {@code Strider.tick}: a strider in or on one of these blocks is warm.
     */
    STRIDER_WARM_BLOCKS("strider_warm_blocks"),
    /**
     * {@code Heightmap.Types.MOTION_BLOCKING} from 26.3: blocks in it (or holding a fluid) raise the heightmap.
     */
    BLOCKS_MOTION_IN_HEIGHTMAP("blocks_motion_in_heightmap"),
    /**
     * {@code NodeEvaluator.isBurningBlock} (a bed's stand-up search avoids it).
     */
    FIRE("fire"),
    /**
     * {@code CampfireBlock.isLitCampfire}.
     */
    CAMPFIRES("campfires"),
    /**
     * {@code DismountHelper.findSafeDismountLocation}: a player does not stand up inside these.
     */
    INVALID_SPAWN_INSIDE("invalid_spawn_inside");

    private final String key;

    BlockTag(String path) {
        this.key = "minecraft:" + path;
    }

    /**
     * Registry key of the tag, e.g. {@code minecraft:climbable}.
     */
    public String key() {
        return key;
    }
}
