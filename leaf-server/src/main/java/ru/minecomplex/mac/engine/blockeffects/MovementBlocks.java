package ru.minecomplex.mac.engine.blockeffects;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockType;

/**
 * Movement-related facts about the blocks of one registry, derived once from the generated data: which vanilla
 * classes a block belongs to ({@code instanceof} tests in movement code) and per-state property values the ported
 * block methods read.
 *
 * <p>{@code entityInside} overrides that change movement (26.3, every block class checked): {@code WebBlock},
 * {@code HoneyBlock}, {@code PowderSnowBlock}, {@code SweetBerryBushBlock}, {@code BubbleColumnBlock}. The others
 * (fire, cactus, portals, pressure plates, cauldrons …) only hurt, ignite or act on the server.
 */
public final class MovementBlocks {
    private static final Map<BlockRegistry, MovementBlocks> CACHE = new ConcurrentHashMap<>();

    /** {@code ScaffoldingBlock.SHAPE_UNSTABLE_BOTTOM}: {@code Block.box(0, 0, 0, 16, 2, 16)}. */
    public static final VoxelShape SCAFFOLDING_UNSTABLE_BOTTOM = Shapes.create(0.0, 0.0, 0.0, 1.0, 0.125, 1.0);
    /** {@code PowderSnowBlock.FALLING_COLLISION_SHAPE}: {@code Shapes.box(0, 0, 0, 1, 0.9F, 1)}. */
    public static final VoxelShape POWDER_SNOW_FALLING = Shapes.create(0.0, 0.0, 0.0, 1.0, 0.9F, 1.0);

    public enum Inside {
        NONE,
        COBWEB,
        HONEY,
        POWDER_SNOW,
        SWEET_BERRY_BUSH,
        BUBBLE_COLUMN
    }

    public final BlockRegistry registry;
    public final int voidAir;
    private final Inside[] inside;
    private final boolean[] slime;
    private final boolean[] bed;
    private final boolean[] fenceGate;
    private final boolean[] trapdoor;
    private final boolean[] scaffolding;
    private final boolean[] powderSnow;
    private final boolean[] liquid;
    private final boolean[] ladder;
    /** Scaffolding states with {@code distance != 0 && bottom}. */
    private final boolean[] scaffoldingUnstableBottom;
    /** {@code open} of trapdoor states. */
    private final boolean[] open;
    /** {@code facing} of trapdoor and ladder states (a {@code Direction} name), else null. */
    private final String[] facing;

    private MovementBlocks(BlockRegistry registry) {
        this.registry = registry;
        this.voidAir = registry.block("minecraft:void_air").firstStateId();
        int blocks = registry.blocks().size();
        inside = new Inside[blocks];
        slime = new boolean[blocks];
        bed = new boolean[blocks];
        fenceGate = new boolean[blocks];
        trapdoor = new boolean[blocks];
        scaffolding = new boolean[blocks];
        powderSnow = new boolean[blocks];
        liquid = new boolean[blocks];
        ladder = new boolean[blocks];
        for (BlockType block : registry.blocks()) {
            int i = block.index();
            inside[i] = block.isInstance("WebBlock") ? Inside.COBWEB
                    : block.isInstance("HoneyBlock") ? Inside.HONEY
                    : block.isInstance("PowderSnowBlock") ? Inside.POWDER_SNOW
                    : block.isInstance("SweetBerryBushBlock") ? Inside.SWEET_BERRY_BUSH
                    : block.isInstance("BubbleColumnBlock") ? Inside.BUBBLE_COLUMN
                    : Inside.NONE;
            slime[i] = block.isInstance("SlimeBlock");
            bed[i] = block.isInstance("BedBlock");
            fenceGate[i] = block.isInstance("FenceGateBlock");
            trapdoor[i] = block.isInstance("TrapDoorBlock");
            scaffolding[i] = block.isInstance("ScaffoldingBlock");
            powderSnow[i] = block.isInstance("PowderSnowBlock");
            liquid[i] = block.isInstance("LiquidBlock");
            ladder[i] = block.name().equals("minecraft:ladder");
        }
        int states = registry.stateCount();
        scaffoldingUnstableBottom = new boolean[states];
        open = new boolean[states];
        facing = new String[states];
        for (int state = 0; state < states; state++) {
            int block = registry.block(state).index();
            if (!scaffolding[block] && !trapdoor[block] && !ladder[block]) {
                continue;
            }
            String distance = null;
            String bottom = null;
            for (String property : registry.properties(state).split(",")) {
                int eq = property.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String value = property.substring(eq + 1);
                switch (property.substring(0, eq)) {
                    case "distance" -> distance = value;
                    case "bottom" -> bottom = value;
                    case "open" -> open[state] = value.equals("true");
                    case "facing" -> facing[state] = value;
                    default -> {
                    }
                }
            }
            if (scaffolding[block]) {
                scaffoldingUnstableBottom[state] = !"0".equals(distance) && "true".equals(bottom);
            }
        }
    }

    public static MovementBlocks of(BlockRegistry registry) {
        return CACHE.computeIfAbsent(registry, MovementBlocks::new);
    }

    public Inside inside(int state) {
        return inside[registry.block(state).index()];
    }

    public boolean isSlime(int state) {
        return slime[registry.block(state).index()];
    }

    public boolean isBed(int state) {
        return bed[registry.block(state).index()];
    }

    public boolean isFenceGate(int state) {
        return fenceGate[registry.block(state).index()];
    }

    public boolean isTrapdoor(int state) {
        return trapdoor[registry.block(state).index()];
    }

    public boolean isScaffolding(int state) {
        return scaffolding[registry.block(state).index()];
    }

    public boolean isPowderSnow(int state) {
        return powderSnow[registry.block(state).index()];
    }

    public boolean isLiquidBlock(int state) {
        return liquid[registry.block(state).index()];
    }

    public boolean isLadder(int state) {
        return ladder[registry.block(state).index()];
    }

    public boolean scaffoldingUnstableBottom(int state) {
        return scaffoldingUnstableBottom[state];
    }

    public boolean isOpen(int state) {
        return open[state];
    }

    public String facing(int state) {
        return facing[state];
    }

    public boolean hasFluid(int state) {
        return !registry.fluid(state).equals("minecraft:empty");
    }
}
