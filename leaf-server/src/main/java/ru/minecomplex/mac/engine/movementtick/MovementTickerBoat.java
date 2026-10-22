package ru.minecomplex.mac.engine.movementtick;
import ru.minecomplex.mac.util.math.Trig;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.engine.MovementEnvironment;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.entity.BoatState.Status;
import ru.minecomplex.mac.mechanics.collision.BlockCollisions;
import ru.minecomplex.mac.mechanics.entity.BoatPush;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.replica.CompensatedWorld;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.world.FluidProperties;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;

/**
 * The client's {@code AbstractBoat} tick: interpolation, the status of the water around it ({@code getStatus}),
 * {@code floatBoat} and {@code controlBoat} while the client drives it, {@code Entity.move} with the boat's own overrides,
 * the block effects and the pushes of the entities in its box. The boat's fields are a {@link BoatState} of the client
 * hypothesis; its body is the {@code Entity} part ({@link MovementTicker}).
 */
public final class MovementTickerBoat extends MovementTicker {

    /**
     * An entity in the boat's push box, as {@code EntitySelector.pushableBy} lists it (mutable, same hypothesis).
     */
    public record Contact(BoatPush.Actor actor, boolean hasBoatAsPassenger, boolean sameVehicle) {
    }

    /**
     * The entities in a box, in the client's query order.
     */
    public interface Contacts {

        List<Contact> query(Aabb box);
    }

    /**
     * {@code getWaterLevelAbove} and the collision test of the surface snap of {@link #floatBoat}.
     */
    public interface Surface {

        float waterLevelAbove();

        boolean canSnapTo(double y);
    }

    private final BoatState boat;

    /**
     * {@code null}: the entities around are not known ({@link Unmodeled#VEHICLE_ENTITIES}).
     */
    private final Contacts contacts;

    private final BlockCollisions.ShapeContext emptyContext;

    /**
     * This tick's {@code isControlledByLocalInstance()} and {@code isLocalInstanceAuthoritative()}.
     */
    private boolean controlledByClient, authority;

    public MovementTickerBoat(MovementEnvironment env, BoatState boat, Contacts contacts) {
        super(env, boat.body, new FluidWorld.EntityFacts(false, boat.eyeHeight, true));
        if (!boat.body.fixedBodyDimensions)
            throw new IllegalArgumentException("Boat needs resolved entity dimensions");
        this.boat = boat;
        this.contacts = contacts;
        this.emptyContext = env.blocks() instanceof CompensatedWorld world ? BlockCollisions.emptyContext(env.registry(), world, this::unmodeled) : BlockCollisions.emptyContext(env.registry());
    }

    /**
     * The controller {@link #tick} sets, for moves of the body outside a tick (the body oracle).
     */
    void controls(boolean controlledByClient, boolean authority) {
        this.controlledByClient = controlledByClient;
        this.authority = authority;
    }

    // ------------------------------------------------------------------------------------------------------------
    // The tick
    /**
     * One client tick of the boat ({@code AbstractBoat.tick}), never a marker acknowledgement. The keys are
     * {@link BoatState#input}, as its driver's last {@code rideTick} left them.
     *
     * @param vehicle                {@code isVehicle()}: it has passengers
     * @param controllerIsPlayer     {@code getControllingPassenger() instanceof Player}
     * @param firstPassengerIsPlayer {@code getFirstPassenger() instanceof Player}: otherwise the paddles stop
     * @param controlledByClient     {@code isControlledByLocalInstance()}
     * @param authority              {@code isLocalInstanceAuthoritative()}, {@code EITHER} when not known
     * @return the paddle packet the driver sends, if any
     */
    public TickPackets tick(boolean vehicle, boolean controllerIsPlayer, boolean firstPassengerIsPlayer, boolean controlledByClient, Tri authority) {
        clearUnmodeled();
        this.controlledByClient = controlledByClient;
        this.authority = authority == Tri.TRUE;
        var body = boat.body;
        List<ServerboundPaddleBoatPacket> packets = List.of();
        body.oldPosition = body.position;
        boat.tickCount++;
        boat.interpolation.tick(body, this::noCollision);
        boat.oldStatus = boat.status;
        boat.status = getStatus(body.boundingBox());
        if (!boat.underwater())
            boat.outOfControlTicks = 0.0F;
        else
            boat.outOfControlTicks++;
        // Entity.baseTick
        body.wasInPowderSnow = body.isInPowderSnow;
        body.isInPowderSnow = false;
        fluids.refresh(body, true);
        if (body.isInLava && !body.firstTick)
            body.fallDistance *= 0.5;
        body.firstTick = false;
        if (authority == Tri.EITHER)
            unmodeled(Unmodeled.RIDING);
        if (authority == Tri.TRUE) {
            if (!firstPassengerIsPlayer) {
                boat.leftPaddle = false;
                boat.rightPaddle = false;
            }
            floatBoat(boat.noGravity ? 0.0 : 0.04, 0.04, body.height(), controllerIsPlayer, new Surface() {

                public float waterLevelAbove() {
                    return getWaterLevelAbove(body.boundingBox(), boat.lastYd);
                }

                public boolean canSnapTo(double y) {
                    return noCollision(body.boundingBox().move(0.0, y - body.y(), 0.0));
                }
            });
            var keys = boat.input;
            controlBoat(vehicle, keys.left(), keys.right(), keys.forward(), keys.backward());
            packets = List.of(paddles(controllerIsPlayer));
            move(MoverType.SELF, body.deltaMovement);
        } else if (authority == Tri.FALSE) {
            body.deltaMovement = Vec3d.ZERO;
            boat.deltaRotation = 0.0F;
        }
        // Entity.tick's block effects run twice for a boat: once in move, once in AbstractBoat.tick
        applyEffectsFromBlocks();
        applyEffectsFromBlocks();
        pushEntities(vehicle);
        return new TickPackets(List.copyOf(packets), Set.copyOf(unmodeled()));
    }

    /**
     * {@code AbstractBoat.tick}'s pushes of the entities in its inflated box ({@code AbstractBoat.push}).
     */
    private void pushEntities(boolean vehicle) {
        var body = boat.body;
        if (contacts == null) {
            unmodeled(Unmodeled.VEHICLE_ENTITIES);
            return;
        }
        var own = new BoatPush.Actor();
        own.position = body.position;
        own.velocity = body.deltaMovement;
        own.box = body.boundingBox();
        own.boat = true;
        own.noPhysics = body.noPhysics;
        own.vehicle = vehicle;
        own.pushable = true;
        for (var contact : contacts.query(own.box.inflate(0.2F, -0.01F, 0.2F))) {
            if (!contact.hasBoatAsPassenger())
                BoatPush.apply(own, contact.actor(), contact.sameVehicle());
        }
        body.deltaMovement = own.velocity;
    }

    /**
     * {@code Entity.handleEntityEvent} 71/72 (26.1+ bubble columns launch boats on the client): applied at once, it never
     * advances the tick, the interpolation or the bubble countdowns.
     */
    public static Set<Unmodeled> bubbleLaunchEvent(BoatState boat, int id, Tri canSimulateMovement) {
        if (id != 71 && id != 72)
            return Set.of();
        if (canSimulateMovement == Tri.EITHER)
            return Set.of(Unmodeled.RIDING);
        if (canSimulateMovement == Tri.TRUE) {
            var velocity = boat.body.deltaMovement;
            boat.body.deltaMovement = id == 71 ? velocity.add(0.0, -0.7, 0.0) : new Vec3d(velocity.x(), 0.6, velocity.z());
        }
        return Set.of();
    }

    // ------------------------------------------------------------------------------------------------------------
    // floatBoat, controlBoat, paddles
    /**
     * {@code AbstractBoat.floatBoat}: buoyancy and friction by status, or the snap onto a surface just landed on.
     */
    public void floatBoat(double gravity, double defaultGravity, float height, boolean controllingPassengerIsPlayer, Surface surface) {
        var body = boat.body;
        double verticalSpeed = -gravity;
        double buoyancy = 0.0;
        float friction = 0.05F;
        if (boat.oldStatus == Status.IN_AIR && boat.status != Status.IN_AIR && boat.status != Status.ON_LAND) {
            boat.waterLevel = body.position.y() + height * 1.0;
            double targetY = surface.waterLevelAbove() - height + 0.101;
            if (surface.canSnapTo(targetY)) {
                body.position = new Vec3d(body.position.x(), targetY, body.position.z());
                body.deltaMovement = body.deltaMovement.multiply(1.0, 0.0, 1.0);
                boat.lastYd = 0.0;
            }
            boat.status = Status.IN_WATER;
            return;
        }
        switch(boat.status) {
            case IN_WATER ->
                {
                    buoyancy = (boat.waterLevel - body.position.y()) / height;
                    friction = 0.9F;
                }
            case UNDER_FLOWING_WATER ->
                {
                    verticalSpeed = -7.0E-4;
                    friction = 0.9F;
                }
            case UNDER_WATER ->
                {
                    buoyancy = 0.01F;
                    friction = 0.45F;
                }
            case IN_AIR ->
                friction = 0.9F;
            case ON_LAND ->
                {
                    friction = boat.landFriction;
                    if (controllingPassengerIsPlayer)
                        boat.landFriction /= 2.0F;
                }
        }
        var velocity = body.deltaMovement;
        body.deltaMovement = new Vec3d(velocity.x() * friction, velocity.y() + verticalSpeed, velocity.z() * friction);
        boat.deltaRotation *= friction;
        if (buoyancy > 0.0) {
            velocity = body.deltaMovement;
            body.deltaMovement = new Vec3d(velocity.x(), (velocity.y() + buoyancy * (defaultGravity / 0.65)) * 0.75, velocity.z());
        }
    }

    /**
     * {@code AbstractBoat.controlBoat}: the keys turn and accelerate the boat and set the paddles.
     */
    public void controlBoat(boolean isVehicle, boolean left, boolean right, boolean up, boolean down) {
        if (!isVehicle)
            return;
        var body = boat.body;
        float acceleration = 0.0F;
        if (left)
            boat.deltaRotation--;
        if (right)
            boat.deltaRotation++;
        if (right != left && !up && !down)
            acceleration += 0.005F;
        body.yRot += boat.deltaRotation;
        if (up)
            acceleration += 0.04F;
        if (down)
            acceleration -= 0.005F;
        body.deltaMovement = body.deltaMovement.add(Trig.sin(-body.yRot * (float) (Math.PI / 180.0)) * acceleration, 0.0, Trig.cos(body.yRot * (float) (Math.PI / 180.0)) * acceleration);
        boat.leftPaddle = right && !left || up;
        boat.rightPaddle = left && !right || up;
    }

    /**
     * The {@code paddle_boat} packet a controlling player sends for the paddles.
     */
    public ServerboundPaddleBoatPacket paddles(boolean hasControllingPassenger) {
        return new ServerboundPaddleBoatPacket(boat.leftPaddle && hasControllingPassenger, boat.rightPaddle && hasControllingPassenger);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The water around the boat (getStatus and its queries)
    private int state(int x, int y, int z) {
        int state = env.blocks().getBlock(x, y, z);
        if (state < 0) {
            unmodeled(Unmodeled.VEHICLE_WORLD);
            return CompensatedWorld.AIR;
        }
        return state;
    }

    private boolean water(int x, int y, int z) {
        if (!env.fluidsResolved())
            unmodeled(Unmodeled.FLUID);
        return fluids.kind(state(x, y, z)) == 1;
    }

    /**
     * {@code FluidState.getHeight}: a full block under water, else the fluid's own height.
     */
    private float height(int x, int y, int z) {
        int state = state(x, y, z);
        return water(x, y + 1, z) ? 1.0F : FluidProperties.get().ownHeight(state);
    }

    /**
     * {@link #getStatus} of a boat outside its tick (the status a ride starts from): only what these queries cannot resolve
     * is added to {@code gaps}.
     */
    public static Status status(MovementEnvironment env, BoatState boat, Aabb box, Set<Unmodeled> gaps) {
        var ticker = new MovementTickerBoat(env, boat, null);
        ticker.unmodeled().clear();
        var status = ticker.getStatus(box);
        gaps.addAll(ticker.unmodeled());
        return status;
    }

    /**
     * {@code AbstractBoat.getStatus}; sets {@code waterLevel} and, on land, {@code landFriction}.
     */
    public Status getStatus(Aabb box) {
        var submerged = isUnderwater(box);
        if (submerged != null) {
            boat.waterLevel = box.maxY();
            return submerged;
        }
        if (checkInWater(box))
            return Status.IN_WATER;
        float friction = getGroundFriction(box);
        if (friction > 0.0F) {
            boat.landFriction = friction;
            return Status.ON_LAND;
        }
        return Status.IN_AIR;
    }

    /**
     * {@code AbstractBoat.checkInWater}: water reaching the bottom of the box; sets {@code waterLevel}.
     */
    public boolean checkInWater(Aabb box) {
        int minX = VanillaMath.floor(box.minX()), maxX = (int) Math.ceil(box.maxX());
        int minY = VanillaMath.floor(box.minY()), maxY = (int) Math.ceil(box.minY() + 0.001);
        int minZ = VanillaMath.floor(box.minZ()), maxZ = (int) Math.ceil(box.maxZ());
        boolean inWater = false;
        boat.waterLevel = -Double.MAX_VALUE;
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    if (water(x, y, z)) {
                        float surface = y + height(x, y, z);
                        boat.waterLevel = Math.max(surface, boat.waterLevel);
                        inWater |= box.minY() < surface;
                    }
                }
            }
        }
        return inWater;
    }

    /**
     * {@code AbstractBoat.isUnderwater}: under a source or flowing water, else {@code null}.
     */
    public Status isUnderwater(Aabb box) {
        double maxY = box.maxY() + 0.001;
        boolean underwater = false;
        for (int x = VanillaMath.floor(box.minX()); x < (int) Math.ceil(box.maxX()); x++) {
            for (int y = VanillaMath.floor(box.maxY()); y < (int) Math.ceil(maxY); y++) {
                for (int z = VanillaMath.floor(box.minZ()); z < (int) Math.ceil(box.maxZ()); z++) {
                    if (water(x, y, z) && maxY < y + height(x, y, z)) {
                        // Falling water is not a source, even when its own height is full.
                        int state = state(x, y, z);
                        if (!env.registry().has(state, BlockRegistry.FLAG_FLUID_SOURCE))
                            return Status.UNDER_FLOWING_WATER;
                        underwater = true;
                    }
                }
            }
        }
        return underwater ? Status.UNDER_WATER : null;
    }

    /**
     * {@code AbstractBoat.getWaterLevelAbove}.
     */
    public float getWaterLevelAbove(Aabb box, double lastYd) {
        int minX = VanillaMath.floor(box.minX()), maxX = (int) Math.ceil(box.maxX());
        int minY = VanillaMath.floor(box.maxY()), maxY = (int) Math.ceil(box.maxY() - lastYd);
        int minZ = VanillaMath.floor(box.minZ()), maxZ = (int) Math.ceil(box.maxZ());
        int posY = 0;
        layers: for (int y = minY; y < maxY; y++) {
            float blockHeight = 0.0F;
            for (int x = minX; x < maxX; x++) {
                for (int z = minZ; z < maxZ; z++) {
                    posY = y;
                    if (water(x, y, z))
                        blockHeight = Math.max(blockHeight, height(x, y, z));
                    if (blockHeight >= 1.0F)
                        continue layers;
                }
            }
            if (blockHeight < 1.0F)
                return posY + blockHeight;
        }
        return maxY + 1;
    }

    /**
     * {@code AbstractBoat.getGroundFriction}: the mean friction of the blocks under the box (lily pads excluded).
     */
    public float getGroundFriction(Aabb box) {
        var probe = new Aabb(box.minX(), box.minY() - 0.001, box.minZ(), box.maxX(), box.minY(), box.maxZ());
        int x0 = VanillaMath.floor(probe.minX()) - 1, x1 = (int) Math.ceil(probe.maxX()) + 1;
        int y0 = VanillaMath.floor(probe.minY()) - 1, y1 = (int) Math.ceil(probe.maxY()) + 1;
        int z0 = VanillaMath.floor(probe.minZ()) - 1, z1 = (int) Math.ceil(probe.maxZ()) + 1;
        var boatShape = Shapes.create(probe);
        float friction = 0.0F;
        int count = 0;
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                int edges = (x != x0 && x != x1 - 1 ? 0 : 1) + (z != z0 && z != z1 - 1 ? 0 : 1);
                if (edges == 2)
                    continue;
                for (int y = y0; y < y1; y++) {
                    if (edges > 0 && (y == y0 || y == y1 - 1))
                        continue;
                    int state = state(x, y, z);
                    if (!env.registry().block(state).name().equals("minecraft:lily_pad") && Shapes.intersect(emptyContext.shape(state, x, y, z).move(x, y, z), boatShape)) {
                        friction += env.registry().block(state).friction();
                        count++;
                    }
                }
            }
        }
        return friction / count;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Entity.move overrides of a boat (it is no LivingEntity)
    @Override
    protected Vec3d maybeBackOffFromEdge(Vec3d delta, MoverType type) {
        return delta;
    }

    @Override
    protected float maxUpStep() {
        return 0.0F;
    }

    @Override
    protected boolean livingBody() {
        return false;
    }

    @Override
    protected boolean fallResettingPortalShapes() {
        return false;
    }

    @Override
    protected double blockBounceRestitution(int state) {
        return registry.block(state).bounceRestitution() * 0.8F;
    }

    @Override
    protected double getEffectiveGravity() {
        return boat.noGravity ? 0.0 : 0.04;
    }

    @Override
    protected float getAirDrag() {
        return 1.0F;
    }

    @Override
    protected float getBlockSpeedFactor() {
        return entityBlockSpeedFactor();
    }

    @Override
    protected boolean tracksClientFallDamage() {
        return true;
    }

    /**
     * {@code AbstractBoat.checkFallDamage}: remembers the vertical speed; falls in water do not count.
     */
    @Override
    protected void checkFallDamage(double dy, boolean onGround, int onState, BlockPos pos) {
        boat.lastYd = p.deltaMovement.y();
        if (p.passenger)
            return;
        if (onGround) {
            resetFallDistance();
            return;
        }
        if (!env.fluidsResolved())
            unmodeled(Unmodeled.FLUID);
        if (fluids.kind(VanillaMath.floor(p.x()), VanillaMath.floor(p.y()) - 1, VanillaMath.floor(p.z())) != 1 && dy < 0.0) {
            p.fallDistance -= (float) dy;
        }
    }

    @Override
    protected void bubbleColumn(boolean above, boolean down) {
        if (above)
            return;
        if (!authority)
            resetFallDistance();
        else
            super.bubbleColumn(false, down);
    }
}
