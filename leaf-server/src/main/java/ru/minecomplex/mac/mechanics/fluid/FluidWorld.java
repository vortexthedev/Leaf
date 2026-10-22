package ru.minecomplex.mac.mechanics.fluid;

import java.util.function.Consumer;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.BlockTag;
import ru.minecomplex.mac.world.FluidProperties;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Client fluid height, flow and entity contact, including resolved boat passenger interaction boxes.
 */
public final class FluidWorld {

    private static final int[] DX = { 0, 1, 0, -1 }, DZ = { -1, 0, 1, 0 };

    private final MovementEnvironment env;

    private final BlockRegistry registry;

    private final FluidProperties properties;

    private final Consumer<Unmodeled> uncertainty;

    /**
     * Entity-specific facts; null selects the ordinary player profile.
     */
    public record EntityFacts(boolean player, float eyeHeight, boolean pushedByFluid) {
    }

    /**
     * Facts from the passenger's current vehicle in the same client hypothesis and tick phase.
     */
    public record BoatPassenger(Aabb box, boolean underwater) {
    }

    private EntityFacts entity;

    public FluidWorld(MovementEnvironment env, Consumer<Unmodeled> uncertainty) {
        this(env, uncertainty, null);
    }

    public FluidWorld(MovementEnvironment env, Consumer<Unmodeled> uncertainty, EntityFacts entity) {
        this.env = env;
        this.registry = env.registry();
        properties = FluidProperties.get();
        this.uncertainty = uncertainty;
        this.entity = entity;
    }

    public int state(int x, int y, int z) {
        return env.blocks().getBlock(x, y, z);
    }

    /**
     * Canonical, disjoint vanilla WATER/LAVA tags, only usable when env.fluidsResolved().
     */
    public int kind(int state) {
        String fluid = registry.fluid(state);
        return fluid.endsWith("water") ? 1 : fluid.endsWith("lava") ? 2 : 0;
    }

    public int kind(int x, int y, int z) {
        return kind(state(x, y, z));
    }

    public float height(int x, int y, int z) {
        int state = state(x, y, z), kind = kind(state);
        return kind != 0 && kind(x, y + 1, z) == kind ? 1.0F : properties.ownHeight(state);
    }

    private boolean sturdy(int state, int direction) {
        String name = registry.block(state).name();
        if (name.endsWith("shulker_box") || name.equals("minecraft:moving_piston")) {
            uncertainty.accept(Unmodeled.CONTEXT_SHAPE);
        }
        return properties.sturdy(state, direction);
    }

    private boolean solidFace(int type, int x, int y, int z, int direction) {
        int state = state(x, y, z);
        return kind(state) != type && !properties.ice(state) && sturdy(state, direction);
    }

    /**
     * FlowingFluid.getFlow, preserving NORTH/EAST/SOUTH/WEST order and float height subtraction.
     */
    public Vec3d flow(int x, int y, int z) {
        int s = state(x, y, z), type = kind(s);
        if (type == 0)
            return Vec3d.ZERO;
        double fx = 0.0, fz = 0.0;
        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d], nz = z + DZ[d], n = state(nx, y, nz), k = kind(n);
            if (k == 0 || k == type) {
                float h = properties.ownHeight(n), distance = 0.0F;
                if (h == 0.0F) {
                    boolean blocked = env.tags().contains(BlockTag.BLOCKS_FLUID_FLOW, registry.block(n));
                    if (!blocked) {
                        n = state(nx, y - 1, nz);
                        k = kind(n);
                        if (k == 0 || k == type) {
                            h = properties.ownHeight(n);
                            if (h > 0.0F)
                                distance = properties.ownHeight(s) - (h - 0.8888889F);
                        }
                    }
                } else if (h > 0.0F)
                    distance = properties.ownHeight(s) - h;
                if (distance != 0.0F) {
                    fx += DX[d] * distance;
                    fz += DZ[d] * distance;
                }
            }
        }
        Vec3d result = new Vec3d(fx, 0.0, fz);
        if (registry.has(s, BlockRegistry.FLAG_FLUID_FALLING)) {
            for (int d = 0; d < 4; d++) {
                if (solidFace(type, x + DX[d], y, z + DZ[d], d) || solidFace(type, x + DX[d], y + 1, z + DZ[d], d)) {
                    result = result.normalize().add(0.0, -6.0, 0.0);
                    break;
                }
            }
        }
        return result.normalize();
    }

    /**
     * A non-player's client pose/dimension refresh also changes the eye used by subsequent fluid queries.
     */
    public void refreshEntityEyeHeight(float height) {
        if (entity == null)
            throw new IllegalStateException("Ordinary player eyes are resolved from pose/scale");
        entity = new EntityFacts(entity.player(), height, entity.pushedByFluid());
    }

    public double jumpThreshold(PlayerState p) {
        return eyeHeight(p) < 0.4 ? 0.0 : 0.4;
    }

    public float eyeHeight(PlayerState p) {
        return entity == null ? properties.eyeHeight(p.pose, p.dimensionScale) : entity.eyeHeight();
    }

    private boolean pushed(PlayerState p) {
        return entity == null ? !p.abilities.flying : entity.pushedByFluid();
    }

    /**
     * Base-tick refresh, or LivingEntity.checkFallDamage refresh when eyes=false.
     */
    public void refresh(PlayerState p, boolean eyes) {
        refresh(p, eyes, null);
    }

    public void refresh(PlayerState p, boolean eyes, BoatPassenger boat) {
        refresh(p, eyes, boat, false);
    }

    /**
     * A verified non-boat vehicle leaves the passenger fluid interaction box unchanged.
     */
    public void refresh(PlayerState p, boolean eyes, BoatPassenger boat, boolean nonBoatVehicleResolved) {
        if (!env.fluidsResolved()) {
            Aabb box = p.boundingBox();
            for (int x = VanillaMath.floor(box.minX()); x <= VanillaMath.floor(box.maxX()); x++) {
                for (int y = VanillaMath.floor(box.minY()); y <= VanillaMath.floor(box.maxY()); y++) {
                    for (int z = VanillaMath.floor(box.minZ()); z <= VanillaMath.floor(box.maxZ()); z++) {
                        if (kind(x, y, z) != 0) {
                            uncertainty.accept(Unmodeled.FLUID);
                            return;
                        }
                    }
                }
            }
            return;
        }
        if (p.passenger && boat == null && !nonBoatVehicleResolved) {
            uncertainty.accept(Unmodeled.RIDING);
            return;
        }
        Aabb b = p.boundingBox().deflate(0.001);
        boolean sheltered = p.passenger && boat != null && !boat.underwater();
        if (sheltered) {
            if (boat.box().maxY() >= b.maxY()) {
                if (eyes)
                    p.wasEyeInWater = p.eyeInWater;
                p.waterHeight = 0.0;
                p.lavaHeight = 0.0;
                p.wasTouchingWater = false;
                p.isInLava = false;
                p.eyeInWater = false;
                p.eyeInLava = false;
                return;
            }
            b = new Aabb(b.minX(), Math.max(b.minY(), boat.box().maxY()), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        }
        Aabb load = b.inflate(1.0, 0.0, 1.0);
        int chunkX0 = VanillaMath.floor(load.minX()) >> 4, chunkZ0 = VanillaMath.floor(load.minZ()) >> 4;
        int chunkX1 = ((int) Math.ceil(load.maxX()) - 1) >> 4;
        int chunkZ1 = ((int) Math.ceil(load.maxZ()) - 1) >> 4;
        boolean loaded = true;
        for (int x = chunkX0; x <= chunkX1; x++) {
            for (int z = chunkZ0; z <= chunkZ1; z++) loaded &= env.hasChunkAt(x << 4, z << 4);
        }
        if (eyes)
            p.wasEyeInWater = p.eyeInWater;
        double eye = p.y() + eyeHeight(p);
        boolean[] eyeInside = { false, false, false };
        double[] depths = { 0, 0, 0 }, maxima = { 0, 0, 0 };
        int[] counts = { 0, 0, 0 };
        Vec3d[] currents = { Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO };
        if (loaded) {
            for (int x = VanillaMath.floor(b.minX()); x < Math.ceil(b.maxX()); x++) {
                for (int y = VanillaMath.floor(b.minY()); y < Math.ceil(b.maxY()); y++) {
                    for (int z = VanillaMath.floor(b.minZ()); z < Math.ceil(b.maxZ()); z++) {
                        int k = kind(x, y, z);
                        if (k == 0)
                            continue;
                        double top = y + (double) height(x, y, z);
                        if (top < b.minY())
                            continue;
                        if (x == VanillaMath.floor(p.x()) && z == VanillaMath.floor(p.z()) && eye >= y) {
                            int s = state(x, y, z);
                            float cameraHeight = height(x, y, z);
                            if (registry.has(s, BlockRegistry.FLAG_FLUID_SOURCE) && sturdy(state(x, y + 1, z), 4)) {
                                cameraHeight = 1.0F;
                            }
                            if (eye <= y + (double) cameraHeight)
                                eyeInside[k] = true;
                        }
                        depths[k] = Math.max(top - p.boundingBox().minY(), depths[k]);
                        if (pushed(p)) {
                            Vec3d f = flow(x, y, z);
                            maxima[k] = Math.max(depths[k], maxima[k]);
                            if (maxima[k] < 0.4)
                                f = f.scale(maxima[k]);
                            currents[k] = currents[k].add(f);
                            counts[k]++;
                        }
                    }
                }
            }
        }
        p.waterHeight = depths[1];
        p.lavaHeight = depths[2];
        p.wasTouchingWater = depths[1] > 0.0;
        p.isInLava = depths[2] > 0.0;
        if (p.wasTouchingWater)
            p.fallDistance = 0.0;
        if (pushed(p)) {
            for (int k = 1; k <= 2; k++) {
                Vec3d current = currents[k];
                if (counts[k] == 0)
                    continue;
                if (current.lengthSqr() < 1.0E-5F)
                    continue;
                double scale = 0.014;
                if (k == 2) {
                    Tri fast = env.fastLava();
                    if (fast == Tri.EITHER) {
                        uncertainty.accept(Unmodeled.FLUID);
                        continue;
                    }
                    scale = fast == Tri.TRUE ? 0.007 : 0.0023333333333333335;
                }
                Vec3d impulse;
                if (entity != null && !entity.player())
                    impulse = current.normalize();
                else
                    impulse = current.scale(1.0 / counts[k]);
                impulse = impulse.scale(scale);
                Vec3d previous = p.deltaMovement;
                if (Math.abs(previous.x()) < 0.003 && Math.abs(previous.z()) < 0.003 && impulse.length() < 0.0045000000000000005) {
                    impulse = impulse.normalize().scale(0.0045000000000000005);
                }
                p.deltaMovement = p.deltaMovement.add(impulse);
            }
        }
        p.eyeInWater = eyeInside[1];
        p.eyeInLava = eyeInside[2];
    }
}
