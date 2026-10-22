package ru.minecomplex.mac.entity;
import ru.minecomplex.mac.util.math.Trig;

import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;
import ru.minecomplex.mac.engine.movementtick.MovementTickerHorse;
import net.minecraft.core.component.DataComponents;
import ru.minecomplex.mac.inventory.item.ItemMechanics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.Shapes;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.entity.PacketEntityFollowed.Kind;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.attribute.AttributeMap;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.packet.Packets;
import net.minecraft.world.entity.Relative;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.replica.CompensatedEntities;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockPosition;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

/**
 * Other entities' client transforms as far as the local player's movement depends on them, and their place in the
 * client's entity tick list relative to the local player: the behaviour of the level's {@link PacketEntityFollowed}
 * entities, whose state they keep themselves.
 *
 * <p>Boats, rafts and happy ghasts (the entities a player collides with) are simulated exactly by {@link EntityMotion}
 * (MAEM oracle): a remote one moves on the client only by its packets and interpolation. Shulkers and every entity that
 * may be pushable (living entities, minecarts) keep only the positions their packets named, a conservative reach used to
 * report what is not modelled ({@link Unmodeled#ENTITY_COLLISION}, {@link Unmodeled#ENTITY_PUSH},
 * {@link Unmodeled#VEHICLE_ENTITIES}).
 *
 * <p>Tick order ({@code ClientLevel.tickEntities}): the tick list keeps insertion order. An entity enters it when it is
 * added in a ticking chunk or its chunk starts ticking, leaves it when its chunk stops ticking or it moves into a chunk
 * that does not tick ({@code TransientEntitySectionManager}); players are always ticking. The local player enters at
 * login and at a same-level respawn ({@code ClientLevel.addEntity}), so entities that were present before a same-level
 * respawn tick before it. A passenger is ticked by its vehicle and placed by it ({@code positionRider}): not simulated.
 *
 * <p>Collision queries follow {@code EntityGetter.getEntityCollisions}: only entities whose section ({@code blockPosition})
 * lies within the query box widened by 2 blocks horizontally and 4 blocks down ({@code EntitySectionStorage
 * .forEachAccessibleNonEmptySection}) are seen, in section and section-insertion order.
 */
public final class EntityMotions {

    /**
     * The moving entity of {@code getEntityCollisions(source, box)}.
     */
    public record Collider(int id, boolean player, boolean vehicleRule, boolean happyGhast, Vec3d position) {

        /**
         * The local player moving itself.
         */
        public static Collider player(int id, Vec3d position) {
            return new Collider(id, true, false, false, position);
        }
    }

    /**
     * The local player as entities push it on the client ({@code EntitySelector.pushableBy}, {@code Entity.push}): the only
     * entity a client pushes.
     */
    public interface LocalPlayerTarget {

        Vec3d position();

        Aabb box();

        /**
         * {@code LivingEntity.isPushable()}: alive, not a spectator, not on a climbable block.
         */
        boolean pushable();

        boolean spectator();

        boolean noPhysics();

        /**
         * {@code isVehicle()}: it carries a passenger.
         */
        boolean vehicle();

        /**
         * {@code isSleeping()}: {@code LivingEntity.push} leaves a sleeping entity alone.
         */
        boolean sleeping();

        Vec3d velocity();

        void velocity(Vec3d value);

        /**
         * {@code move(MoverType.SHULKER, delta)}: a shulker entity's opening moves it; adds to {@code gaps} what the move
         * could not resolve.
         */
        default void moveByShulker(Vec3d delta, Set<Unmodeled> gaps) {
            gaps.add(Unmodeled.ENTITY_COLLISION);
        }

        /**
         * The client players this target stands for, each pushed and moved on its own: this one, or every client state a
         * prediction still considers possible (they share the level, not their velocity or state).
         */
        default List<LocalPlayerTarget> possible() {
            return List.of(this);
        }

        /**
         * {@code FireworkRocketEntity.tick} of rocket {@code id}, attached to this player: while the player glides
         * ({@code isFallFlying}) the rocket pushes it ({@link ru.minecomplex.mac.mechanics.movement.FireworkBoost}). A client state
         * that handled the rocket's removal ahead of this model does not have it.
         */
        default void rocketBoost(int id) {
        }
    }

    /**
     * {@code level.noCollision(entity, box)} of a simulated entity, for its interpolation's adjustment.
     */
    public interface BodyQueries {

        /**
         * Adds to {@code gaps} what the query could not resolve.
         */
        Predicate<Aabb> of(int id, ru.minecomplex.mac.player.state.PlayerState body, Collider self, Set<Unmodeled> gaps);
    }

    /**
     * The sleep height of the bed block at a position.
     */
    @FunctionalInterface
    public interface Beds {

        /**
         * {@code AbstractBedBlock.getSleepHeight}: the bed's outline height; {@code null} when no bed is there,
         * {@code NaN} when not known.
         */
        Double sleepHeight(int x, int y, int z);
    }

    /**
     * Added around a not simulated entity's packet positions for interpolation lag.
     */
    static final double REACH_MARGIN = 1.0;

    /**
     * The firework rocket type.
     */
    public static final String ROCKET = "minecraft:firework_rocket";

    private final CompensatedEntities owner;

    private final EntityTypes types;

    private long sequence;

    private long playerOrder;

    /**
     * Counts the LocalPlayers this level had: a respawn adds a new one, which a rocket attached before does not push.
     */
    private int playerGeneration;

    private List<Integer> tickSnapshot = List.of();

    /**
     * Whether a position is in a climbable block, for this tick ({@link #tick}; {@code null}: not known).
     */
    private java.util.function.Function<Vec3d, Tri> climbable;

    public EntityMotions(CompensatedEntities owner) {
        this.owner = owner;
        this.types = EntityTypes.get();
        playerOrder = ++sequence;
    }

    /**
     * The behaviour of {@code owner}, a copy of this level's entities, continuing this tick list's order.
     */
    public EntityMotions copy(CompensatedEntities owner) {
        var copy = new EntityMotions(owner);
        copy.sequence = sequence;
        copy.playerOrder = playerOrder;
        copy.playerGeneration = playerGeneration;
        copy.tickSnapshot = tickSnapshot;
        return copy;
    }

    /**
     * Which entities this model follows, and how; {@code null}: never pushable nor collidable.
     */
    Kind kind(String type) {
        if (type.endsWith("_boat") || type.endsWith("_raft"))
            return Kind.BOAT;
        if (type.equals("minecraft:happy_ghast"))
            return Kind.HAPPY_GHAST;
        if (type.equals("minecraft:shulker"))
            return Kind.SHULKER;
        if (type.equals("minecraft:minecart") || type.endsWith("_minecart"))
            return Kind.PUSHABLE;
        var known = types.type(type);
        if (known == null)
            return Kind.PUSHABLE;
        if (!known.living())
            return null;
        // the ender dragon ticks its own way (EnderDragon.aiStep) and pushes nothing
        return !type.equals("minecraft:ender_dragon") && LivingShape.modelled(type, types) ? Kind.LIVING : Kind.PUSHABLE;
    }

    /**
     * Whether a living entity's {@code pushEntities} can push the local player: not a bat ({@code Bat.pushEntities} does
     * nothing), a parrot ({@code Parrot.doPush} skips players), an armor stand (it pushes minecarts only) or a vex
     * ({@code Vex.tick} sets {@code noPhysics} around its tick, {@code Entity.push} then does nothing).
     */
    static boolean pushesPlayers(String type) {
        return switch(type) {
            case "minecraft:bat", "minecraft:parrot", "minecraft:armor_stand", "minecraft:vex", "minecraft:ender_dragon" ->
                false;
            default ->
                true;
        };
    }

    /**
     * The key {@link ru.minecomplex.mac.replica.CompensatedWorld} keeps ticking chunks under.
     */
    public static long chunkKeyOf(int chunkX, int chunkZ) {
        return chunkKey(chunkX, chunkZ);
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    static long chunkKey(Vec3d position) {
        return chunkKey(floor(position.x()) >> 4, floor(position.z()) >> 4);
    }

    /**
     * {@code SectionPos.asLong} of a block position's section.
     */
    static long sectionKey(int x, int y, int z) {
        return ((long) x & 4194303L) << 42 | (long) y & 1048575L | ((long) z & 4194303L) << 20;
    }

    static long sectionKey(Vec3d position) {
        return sectionKey(floor(position.x()) >> 4, floor(position.y()) >> 4, floor(position.z()) >> 4);
    }

    /** Native add_entity getters already apply the client's Mth.unpackDegrees. */
    public static float wireAngle(float degrees) { return degrees; }

    private PacketEntityFollowed entry(int id) {
        return owner.followed(id);
    }

    private PacketEntityRocket rocketEntity(int id) {
        return owner.entity(id) instanceof PacketEntityRocket rocket ? rocket : null;
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------
    /**
     * The entity {@code add_entity} creates for this level: its class by what the client simulates of its type (Grim's
     * {@code CompensatedEntities.addEntity}).
     */
    public PacketEntity create(ClientboundAddEntityPacket spawn, String type) {
        int id = spawn.getId();
        if (type.equals(ROCKET))
            return new PacketEntityRocket(owner, id, type, spawn);
        Kind kind = kind(type);
        boolean player = type.equals("minecraft:player");
        if (kind == null)
            return new PacketEntity(owner, id, type, player, spawn);
        return switch(kind) {
            case BOAT ->
                new PacketEntityBoat(owner, id, type, spawn);
            case HAPPY_GHAST ->
                new PacketEntityHappyGhast(owner, id, type, spawn);
            case SHULKER ->
                new PacketEntityShulker(owner, id, type, spawn);
            case PUSHABLE ->
                new PacketEntityPositions(owner, id, type, player, spawn);
            case LIVING ->
                switch(type) {
                    case "minecraft:player" ->
                        new PacketEntityPlayer(owner, id, type, spawn);
                    case "minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:skeleton_horse", "minecraft:zombie_horse" ->
                        new PacketEntityHorse(owner, id, type, spawn);
                    case "minecraft:camel", "minecraft:camel_husk" ->
                        new PacketEntityCamel(owner, id, type, spawn);
                    case "minecraft:pig" ->
                        new PacketEntityRideable(owner, id, type, spawn);
                    case "minecraft:strider" ->
                        new PacketEntityStrider(owner, id, type, spawn);
                    case "minecraft:nautilus", "minecraft:zombie_nautilus" ->
                        new PacketEntityNautilus(owner, id, type, spawn);
                    default ->
                        new PacketEntityLiving(owner, id, type, player, spawn);
                };
        };
    }

    /**
     * {@code recreateFromPacket} of a created entity and its place in the tick list and sections.
     */
    public void spawned(PacketEntity entity, LongPredicate tickingChunk) {
        var spawn = entity.spawn();
        if (entity instanceof PacketEntityRocket rocket) {
            rocket.position = new Vec3d(new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ()).x(), new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ()).y(), new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ()).z());
            rocket.sectionKey = sectionKey(rocket.position);
            rocket.sectionOrder = ++sequence;
            if (tickingChunk.test(chunkKey(rocket.position))) {
                rocket.ticking = true;
                rocket.order = ++sequence;
            }
            return;
        }
        if (!(entity instanceof PacketEntityFollowed followed))
            return;
        double x = new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ()).x(), y = new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ()).y(), z = new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ()).z();
        int id = entity.id();
        if (followed instanceof PacketEntityMoving moving) {
            var type = types.type(entity.type());
            moving.motion = new EntityMotion(followed.kind() != Kind.BOAT, type.updateInterval(), type.dimensions().width(), type.dimensions().height());
            moving.motion.spawn(x, y, z, wireAngle(spawn.getYRot()), wireAngle(spawn.getXRot()));
            if (moving instanceof PacketEntityLiving living) {
                living.shape = new LivingShape(entity.type(), types);
                living.shape.construct(owner, id, livingScale(id, entity.type()));
                applyShape(living);
                // Entity.recreateFromPacket: setDeltaMovement(packet.getMovement())
                var velocity = spawn.getMovement();
                living.velocity = new Vec3d(velocity.x(), velocity.y(), velocity.z());
                living.velocityKnown = !entity.type().equals("minecraft:player");
            }
        } else if (followed instanceof PacketEntityShulker shulker) {
            // recreateFromPacket: the codec base, then snapTo
            shulker.shulker.codecBase = new Vec3d(x, y, z);
            shulker.shulker.snapTo(shulker.shulker.codecBase, false, shulkerScale(id));
        } else {
            var positions = (PacketEntityPositions) followed;
            positions.codecBase = new Vec3d(x, y, z);
            positions.targets = new Vec3d[] { positions.codecBase };
        }
        followed.sectionKey = sectionKey(followed.position());
        followed.sectionOrder = ++sequence;
        if (followed.alwaysTicking || tickingChunk.test(chunkKey(followed.position()))) {
            followed.ticking = true;
            followed.order = ++sequence;
        }
    }

    /**
     * {@code handleRespawn} in the same level: the new LocalPlayer is added after every present entity.
     */
    public void respawnLocalPlayer() {
        playerOrder = ++sequence;
        playerGeneration++;
    }

    /**
     * Whether entity {@code id} is a firework rocket of this level.
     */
    public boolean rocket(int id) {
        return rocketEntity(id) != null;
    }

    /**
     * Whether rocket {@code id} pushes the local player in its ticks: attached to it ({@code DATA_ATTACHED_TO_TARGET}),
     * not to a LocalPlayer a respawn replaced.
     */
    public boolean rocketOnLocalPlayer(int id) {
        var rocket = rocketEntity(id);
        if (rocket == null)
            return false;
        if (rocket.target >= 0)
            return rocket.target == owner.localPlayerId() && rocket.playerGeneration == playerGeneration;
        var attached = rocketAttachment(rocket);
        return attached.isPresent() && attached.getAsInt() == owner.localPlayerId();
    }

    /**
     * Whether rocket {@code id}'s tick has not found the entity it is attached to yet ({@code attachedToEntity} is null).
     */
    public boolean rocketUnresolved(int id) {
        var rocket = rocketEntity(id);
        return rocket != null && rocket.target < 0;
    }

    /**
     * Whether rocket {@code id} is in the tick list.
     */
    public boolean rocketTicking(int id) {
        var rocket = rocketEntity(id);
        return rocket != null && rocket.ticking;
    }

    /**
     * A rocket's {@code DATA_ATTACHED_TO_TARGET} as the client stores it (empty by default).
     */
    private java.util.OptionalInt rocketAttachment(PacketEntityRocket rocket) {
        return rocket.optionalInt(types.dataId("FireworkRocketEntity.DATA_ATTACHED_TO_TARGET"));
    }

    /**
     * {@code startTicking}/{@code stopTicking} of a chunk ({@code ClientLevel.onChunkLoaded}/{@code unload}).
     */
    public void chunkTicking(int chunkX, int chunkZ, boolean ticking) {
        long key = chunkKey(chunkX, chunkZ);
        var ids = new ArrayList<PacketEntity>();
        for (var entity : owner.all()) {
            if (entity instanceof PacketEntityFollowed entry) {
                if (!entry.alwaysTicking && entry.ticking != ticking && chunkKey(entry.position()) == key)
                    ids.add(entry);
            } else if (entity instanceof PacketEntityRocket rocket) {
                if (rocket.ticking != ticking && chunkKey(rocket.position) == key)
                    ids.add(rocket);
            }
        }
        ids.sort(Comparator.comparingLong(entity -> entity instanceof PacketEntityFollowed entry ? entry.sectionOrder : ((PacketEntityRocket) entity).sectionOrder));
        for (var entity : ids) {
            if (entity instanceof PacketEntityRocket rocket) {
                rocket.ticking = ticking;
                if (ticking)
                    rocket.order = ++sequence;
                continue;
            }
            var entry = (PacketEntityFollowed) entity;
            entry.ticking = ticking;
            if (ticking)
                entry.order = ++sequence;
        }
    }

    /**
     * {@code FireworkRocketEntity.tick} of a rocket in the tick list: its first tick with {@code DATA_ATTACHED_TO_TARGET}
     * finds the living entity it names ({@code attachedToEntity}, kept from then on); attached to the local player, it
     * pushes the player while it glides and moves to it ({@code setPos} at the player, the hand's offset at most half a
     * block from it), which decides whether it keeps ticking. Not attached, it flies on its own: nothing the client feels.
     */
    private void tickRocket(int id, PacketEntityRocket rocket, LocalPlayerTarget player, LongPredicate tickingChunk, Set<Unmodeled> gaps) {
        var attached = rocketAttachment(rocket);
        if (attached.isEmpty())
            return;
        if (rocket.target < 0) {
            int target = attached.getAsInt();
            var spawn = owner.spawnInput(target);
            var type = spawn == null ? null : types.type(owner.typeOf(target));
            if (target == owner.localPlayerId())
                rocket.playerGeneration = playerGeneration;
            else if (type == null || !type.living())
                return;
            rocket.target = target;
        }
        if (rocket.target != owner.localPlayerId() || rocket.playerGeneration != playerGeneration || player == null)
            return;
        for (var one : player.possible()) one.rocketBoost(id);
        rocket.position = player.position();
        long section = sectionKey(rocket.position);
        boolean ticking = tickingChunk.test(chunkKey(rocket.position));
        if (section != rocket.sectionKey) {
            rocket.sectionKey = section;
            rocket.sectionOrder = ++sequence;
            if (ticking && !rocket.ticking)
                rocket.order = ++sequence;
            rocket.ticking = ticking;
        }
        for (double dx : new double[] { -0.5, 0.5 }) {
            for (double dz : new double[] { -0.5, 0.5 }) {
                if (tickingChunk.test(chunkKey(rocket.position.add(dx, 0.0, dz))) != ticking)
                    gaps.add(Unmodeled.ENTITY_PUSH);
            }
        }
    }

    /**
     * {@code Callback.onMove}: a section change may start or stop the entity's ticking.
     */
    private void moved(PacketEntityFollowed entry, LongPredicate tickingChunk) {
        long section = sectionKey(entry.position());
        if (section == entry.sectionKey)
            return;
        entry.sectionKey = section;
        entry.sectionOrder = ++sequence;
        if (entry.alwaysTicking)
            return;
        boolean ticking = tickingChunk.test(chunkKey(entry.position()));
        if (ticking && !entry.ticking)
            entry.order = ++sequence;
        entry.ticking = ticking;
    }

    // ---- packets --------------------------------------------------------------------------------------------------
    /**
     * {@code assignValues} for a shulker: the peek it is to reach, and its attach face, whose change stores a new box
     * ({@code Shulker.onSyncedDataUpdated}: {@code setBoundingBox(makeBoundingBox())}).
     */
    private void shulkerData(int id, Map<Integer, Object> items, ShulkerBody shulker) {
        var living = types.living("minecraft:shulker");
        int peekId = living == null ? -1 : living.item("DATA_PEEK_ID"), faceId = living == null ? -1 : living.item("DATA_ATTACH_FACE_ID");
        if (items.get(peekId) instanceof Byte peek)
            shulker.rawPeek = peek;
        if (items.get(faceId) instanceof Integer face) {
            // Direction.BY_ID wraps
            shulker.attachFace = ru.minecomplex.mac.world.BlockDirection.values()[Math.floorMod(face, 6)];
            shulker.restoreBox(shulkerScale(id));
        }
    }

    /**
     * {@code Shulker.getScale()}: {@code sanitizeScale} keeps it at most 3.
     */
    private float shulkerScale(int id) {
        return Math.min((float) scale(id, "minecraft:shulker"), 3.0F);
    }

    /**
     * {@code handleSetEntityData} after the entity stored it ({@code items}: the packet's items): the items that refresh a
     * living entity's dimensions, then a sleeping position ({@code LivingEntity.onSyncedDataUpdated}: a client puts the
     * sleeper on its bed).
     */
    public void syncedData(PacketEntity entity, Map<Integer, Object> items, Beds beds, LongPredicate tickingChunk) {
        int id = entity.id();
        boolean sleeps = items.get(EntityDataItems.SLEEPING_POS) instanceof java.util.Optional<?> position && position.orElse(null) instanceof BlockPosition;
        BlockPosition bed = sleeps ? (BlockPosition) ((java.util.Optional<?>) items.get(EntityDataItems.SLEEPING_POS)).get() : null;
        if (entity instanceof PacketEntityShulker shulker) {
            shulkerData(id, items, shulker.shulker);
            // Entity.onSyncedDataUpdated(DATA_POSE) calls refreshDimensions even when the pose is unchanged.
            // Shulker.makeBoundingBox reads the current scale, before LivingEntity.tick refreshes attributes.
            if (items.containsKey(EntityDataItems.POSE))
                shulker.shulker.restoreBox(shulkerScale(id));
            if (sleeps) {
                setPosToBed(id, shulker, bed.x(), bed.y(), bed.z(), beds);
                moved(shulker, tickingChunk);
            }
            return;
        }
        if (!(entity instanceof PacketEntityLiving living))
            return;
        if (living instanceof PacketEntityRideable rideable) {
            int boostId = owner.mob(id).id(rideable.dataPrefix() + "DATA_BOOST_TIME");
            if (items.containsKey(boostId)) {
                if (items.get(boostId) instanceof Integer duration) {
                    new ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding(rideable.steeringKind()).boostSynced(rideable.steering, duration);
                    rideable.steeringKnown = true;
                } else
                    rideable.steeringKnown = false;
            }
        }
        if (living.shape.refreshes(items)) {
            living.shape.refresh(owner, id, livingScale(id, living.type()));
            applyShape(living);
        }
        if (sleeps) {
            setPosToBed(id, living, bed.x(), bed.y(), bed.z(), beds);
            moved(living, tickingChunk);
        }
    }

    /**
     * {@code LivingEntity.baseTick} while {@code firstTick}: a sleeping entity is put on its bed; from 26.3 one whose bed is
     * no bed stops sleeping ({@code stopSleeping}: standing pose, the position kept, the sleeping position cleared).
     */
    private void firstTickBed(int id, PacketEntityFollowed entry, Beds beds) {
        var bed = entry.sleepingPosition();
        if (bed == null)
            return;
        // a shulker's pose does not shape it
        if (!setPosToBed(id, entry, bed.x(), bed.y(), bed.z(), beds) && !entry.unknown && entry instanceof PacketEntityLiving living) {
            living.shape.setPose(owner, id, Pose.STANDING, livingScale(id, living.type()));
            applyShape(living);
        }
    }

    /**
     * {@code LivingEntity.setPosToBed}: {@code setPos} to the bed's centre at 0.6875 above its block; from 26.3
     * ({@code BED_SLEEP_HEIGHT}) only onto a bed, at its sleep height plus 0.125.
     */
    private boolean setPosToBed(int id, PacketEntityFollowed entry, int x, int y, int z, Beds beds) {
        double height;
        {
            Double sleepHeight = beds == null ? Double.valueOf(Double.NaN) : beds.sleepHeight(x, y, z);
            if (sleepHeight == null)
                return false;
            if (sleepHeight.isNaN()) {
                entry.unknown = true;
                return false;
            }
            height = y + sleepHeight + 0.125;
        }
        Vec3d bed = new Vec3d(x + 0.5, height, z + 0.5);
        if (entry instanceof PacketEntityShulker shulker)
            shulker.shulker.setPos(bed, passenger(id), shulkerScale(id));
        else
            ((PacketEntityMoving) entry).motion.body.position = bed;
        return true;
    }

    /**
     * {@code Entity.refreshDimensions()} of a followed living entity outside the triggers this model follows (fixtures
     * that call it directly).
     */
    public void refreshDimensions(int id) {
        if (!(owner.entity(id) instanceof PacketEntityLiving living))
            return;
        living.shape.refresh(owner, id, livingScale(id, living.type()));
        applyShape(living);
    }

    /**
     * The body takes the stored dimensions ({@code makeBoundingBox}).
     */
    private static void applyShape(PacketEntityLiving entry) {
        entry.motion.body.bodyWidth = entry.shape.dimensions().width();
        entry.motion.body.bodyHeight = entry.shape.dimensions().height();
    }

    /**
     * Whether the local player controls entity {@code id} ({@code isLocalInstanceAuthoritative}): a boat's controlling
     * passenger is its first (living, from 26.x player) passenger, a happy ghast's its first passenger when it is a player
     * and the ghast wears a harness and is not on its still timeout ({@code getControllingPassenger}).
     */
    Tri controlled(int id, PacketEntityFollowed entry) {
        var view = owner.passengers().get(id);
        if (view == null || view.passengers().isEmpty() || view.passengers().getFirst() != owner.localPlayerId())
            return Tri.FALSE;
        if (entry.kind() == Kind.BOAT)
            return Tri.TRUE;
        var mob = owner.mob(id);
        if (entry instanceof PacketEntityHorse) {
            boolean tame = entry instanceof PacketEntityCamel || mob.horseFlag(2);
            return equipped(owner.saddle(id), mob.alive() && !mob.baby() && tame, "SADDLE", "minecraft:saddle");
        }
        if (entry.kind() == Kind.LIVING)
            return ((PacketEntityLiving) entry).localController;
        if (entry.kind() != Kind.HAPPY_GHAST)
            return Tri.EITHER;
        if (mob.flag("HappyGhast.STAYS_STILL"))
            return Tri.FALSE;
        return equipped(owner.body(id), mob.alive() && !mob.baby(), "BODY", null);
    }

    /**
     * Resolve a living mount's controller before its wire handlers or tick; generation lifetime belongs to the entity.
     */
    public void localController(int id, Tri controller) {
        if (entry(id) instanceof PacketEntityLiving living && living.kind() == Kind.LIVING) {
            living.localController = controller;
        }
    }

    /**
     * Start a bounded handler-order alternative; wheel selection is not announced until gameMode.tick.
     */
    public void beginEarlyController(Integer id) {
        for (var candidate : owner.all()) {
            if (!(candidate instanceof PacketEntityFollowed entry))
                continue;
            if (entry instanceof PacketEntityLiving living) {
                living.earlyController = null;
                living.earlyControllerHandled = false;
            }
        }
        var entry = id == null ? null : entry(id);
        if (entry instanceof PacketEntityLiving living && living.kind() == Kind.LIVING && living.motion != null)
            living.earlyController = living.motion.copy();
    }

    /**
     * Detached pre-tick root under selection-before-handlers, only if a transform handler actually ran.
     */
    public EntityMotion earlyControllerStart(int id) {
        return entry(id) instanceof PacketEntityLiving living && living.earlyControllerHandled ? living.earlyController.copy() : null;
    }

    /**
     * The exact packet selected this handler order; the next handler must decode from its codec too.
     */
    public void acceptEarlyController(int id) {
        if (!(entry(id) instanceof PacketEntityLiving living) || !living.earlyControllerHandled)
            throw new IllegalStateException("No handler alternative");
        living.motion = living.earlyController.copy();
        living.rideStart = living.unknown || living.phaseUncertain ? null : living.motion.copy();
        living.rideStartEstimate = living.motion.copy();
        living.earlyController = null;
        living.earlyControllerHandled = false;
    }

    /**
     * Current resolved transform/interpolation, after this entity's tick; a detached snapshot.
     */
    public EntityMotion passengerVehicle(int id) {
        return entry(id) instanceof PacketEntityMoving moving && moving.motion != null && !moving.unknown && !moving.phaseUncertain ? moving.motion.copy() : null;
    }

    /**
     * The ground flag the followed body keeps, used again when the player regains control.
     */
    public void rideOnGround(int id, boolean onGround) {
        if (entry(id) instanceof PacketEntityMoving moving && moving.motion != null && moving.owned)
            moving.motion.body.onGround = onGround;
    }

    /**
     * An unannounced steering selection may have changed how a wire movement was applied.
     */
    public void uncertainController(int id) {
        var entry = entry(id);
        if (entry != null) {
            entry.unknown = true;
            if (entry instanceof PacketEntityMoving moving)
                moving.velocityKnown = false;
        }
    }

    /**
     * {@code hasValidEquippableItemForSlot}: a stack whose {@code equippable} slot is {@code slot} on a mob that may use the
     * slot; that the stack may equip this type is resolved only for the vanilla item ({@code vanillaItem}, or any harness
     * for the body slot) without an equippable patch.
     */
    public static Tri equipped(ItemStack stack, boolean canUseSlot, String slot, String vanillaItem) {
        if (stack == null || !canUseSlot)
            return Tri.FALSE;
        if (!slot.equals(ItemMechanics.equippableSlot(stack)))
            return Tri.FALSE;
        boolean vanilla = vanillaItem == null ? Stacks.key(stack).endsWith("_harness") : Stacks.key(stack).equals(vanillaItem);
        boolean unpatched = (!stack.getComponentsPatch().map.containsKey(DataComponents.EQUIPPABLE));
        return vanilla && unpatched ? Tri.TRUE : Tri.EITHER;
    }

    /**
     * Ownership follows the passenger graph: an entity the local player stops controlling keeps the ride's last transform
     * when the ride gave it and no interpolation of the owned time is pending unless the ride followed it too, else its
     * transform becomes unknown. Run before a handler acts on the entity (the passengers may have changed since the last
     * tick) and at the start of a tick.
     */
    private void updateOwnership(int id, PacketEntityMoving entry) {
        boolean owned = controlled(id, entry) == Tri.TRUE;
        if (entry.owned && !owned && (!entry.ownedKnown || entry.ownedTeleport || entry.motion.interpolating() && !entry.ownedInterpolationKnown)) {
            entry.unknown = true;
            entry.velocityKnown = false;
        } else if (entry.owned && !owned && !entry.ownedExact) {
            // the ride's estimate of the mount: a collider with gaps until the server's move brings both to one target
            entry.phaseUncertain = true;
            entry.estimate = true;
            entry.estimateSynced = false;
        }
        if (owned && !entry.owned) {
            entry.ownedTeleport = false;
            entry.ownedKnown = false;
            entry.ownedInterpolationKnown = false;
        }
        entry.owned = owned;
    }

    /**
     * The authority the handlers act on; an unresolved one leaves the transform unknown.
     */
    private boolean authority(int id, PacketEntityMoving entry) {
        updateOwnership(id, entry);
        Tri controlled = controlled(id, entry);
        if (controlled == Tri.EITHER)
            entry.unknown = true;
        return controlled == Tri.TRUE;
    }

    private static Vec3d decode(Vec3d base, long xa, long ya, long za) {
        if (xa == 0L && ya == 0L && za == 0L)
            return base;
        return new Vec3d(xa == 0L ? base.x() : (Math.round(base.x() * 4096.0) + xa) / 4096.0, ya == 0L ? base.y() : (Math.round(base.y() * 4096.0) + ya) / 4096.0, za == 0L ? base.z() : (Math.round(base.z() * 4096.0) + za) / 4096.0);
    }

    /**
     * The codec base after a {@code move_entity_*}'s deltas ({@code null}: a rotation only).
     */
    private static Vec3d decode(Vec3d base, net.minecraft.network.protocol.game.VecDelta delta) {
        if (delta == null)
            return decode(base, 0, 0, 0);
        if (delta instanceof net.minecraft.network.protocol.game.VecDelta.Linear linear) {
            return decode(base, linear.xa(), linear.ya(), linear.za());
        }
        for (var step : ((net.minecraft.network.protocol.game.VecDelta.Stepped) delta).steps()) {
            base = decode(base, step.xa(), step.ya(), step.za());
        }
        return base;
    }

    /**
     * {@code handleMoveEntity}.
     */
    public void move(Object packet, LongPredicate tickingChunk) {
        int id = Packets.entityId(packet);
        var entry = entry(id);
        if (entry == null)
            return;
        var delta = Packets.delta(packet);
        if (entry instanceof PacketEntityMoving moving) {
            if (moving instanceof PacketEntityLiving living && living.earlyController != null) {
                living.earlyController.move(packet, true);
                living.earlyControllerHandled = true;
            }
            moving.motion.move(packet, authority(id, moving));
            if (delta != null && !moving.owned)
                moving.estimateSynced = true;
        } else if (entry instanceof PacketEntityShulker shulker) {
            // no interpolation: moveOrInterpolateTo sets the position (a rotation alone moves nothing)
            if (delta != null) {
                Vec3d base = decode(shulker.shulker.codecBase, delta);
                shulker.shulker.codecBase = base;
                shulker.shulker.setPos(base, passenger(id), shulkerScale(id));
            }
        } else if (delta != null) {
            var positions = (PacketEntityPositions) entry;
            Vec3d base = decode(positions.codecBase, delta);
            positions.codecBase = base;
            positions.push(base);
        }
        moved(entry, tickingChunk);
    }

    /**
     * {@code handleTeleportEntity}.
     */
    public void teleport(ClientboundTeleportEntityPacket packet, LongPredicate tickingChunk) {
        int id = packet.id();
        // the local player's own teleport is the player model's (ClientPlayerHandlers)
        if (id == owner.localPlayerId())
            return;
        var entry = entry(id);
        if (entry == null)
            return;
        int relatives = net.minecraft.world.entity.Relative.pack(packet.relatives());
        var values = packet.change();
        double x = values.position().x(), y = values.position().y(), z = values.position().z();
        if (entry instanceof PacketEntityMoving moving) {
            boolean controlled = authority(id, moving);
            if (controlled)
                moving.ownedTeleport = true;
            // setValuesFromPositionPacket updates delta movement even for a locally authoritative root.
            teleportVelocity(moving, packet);
            if (controlled && moving.velocityKnown)
                moving.rideVelocity = moving.velocity;
            if (moving instanceof PacketEntityLiving living && living.earlyController != null) {
                living.earlyController.teleport(packet, true, living.ticking);
                living.earlyControllerHandled = true;
            }
            moving.motion.teleport(packet, controlled, moving.ticking);
            if (!controlled)
                moving.estimateSynced = true;
        } else if (entry instanceof PacketEntityShulker shulker) {
            // setValuesFromPositionPacket: relative to its position; moveOrInterpolateTo or setPos, both through Shulker.setPos
            Vec3d current = shulker.shulker.position;
            Vec3d target = new Vec3d((ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.X) ? current.x() : 0.0) + x, (ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Y) ? current.y() : 0.0) + y, (ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Z) ? current.z() : 0.0) + z);
            shulker.shulker.setPos(target, passenger(id), shulkerScale(id));
        } else {
            var positions = (PacketEntityPositions) entry;
            Vec3d last = positions.position();
            positions.push(new Vec3d((ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.X) ? last.x() : 0.0) + x, (ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Y) ? last.y() : 0.0) + y, (ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.Z) ? last.z() : 0.0) + z));
        }
        moved(entry, tickingChunk);
    }

    /**
     * {@code setValuesFromPositionPacket}'s delta movement, including a locally driven entity: each component
     * relative ({@code Relative.DELTA_*}) adds to the current one, else replaces it. A {@code ROTATE_DELTA} turn of the
     * current movement is not followed.
     */
    private static void teleportVelocity(PacketEntityMoving entry, ClientboundTeleportEntityPacket packet) {
        if (!(entry instanceof PacketEntityLiving))
            return;
        int relatives = net.minecraft.world.entity.Relative.pack(packet.relatives());
        if (ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.ROTATE_DELTA)) {
            entry.velocityKnown = false;
            return;
        }
        var delta = packet.change().deltaMovement();
        Vec3d v = entry.velocity;
        boolean rx = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_X), ry = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_Y), rz = ru.minecomplex.mac.packet.Packets.relative(relatives, Relative.DELTA_Z);
        entry.velocity = new Vec3d(rx ? v.x() + delta.x() : delta.x(), ry ? v.y() + delta.y() : delta.y(), rz ? v.z() + delta.z() : delta.z());
        if (!rx && !ry && !rz)
            entry.velocityKnown = true;
    }

    /**
     * {@code handleSetEntityMotion} → {@code lerpMotion} ({@code Entity}'s: {@code setDeltaMovement}; the vehicle types do not
     * override it). An entity the local player drives keeps it for the ride ({@link #takeRideVelocity}).
     */
    public void velocity(ClientboundSetEntityMotionPacket packet) {
        int id = packet.id();
        if (!(entry(id) instanceof PacketEntityMoving entry))
            return;
        var velocity = packet.movement();
        Vec3d value = new Vec3d(velocity.x(), velocity.y(), velocity.z());
        updateOwnership(id, entry);
        // a mount the local player drives, or may (the ride decides): applied by its next ride tick
        if (controlled(id, entry) != Tri.FALSE)
            entry.rideVelocity = value;
        entry.velocity = value;
        entry.velocityKnown = !entry.type().equals("minecraft:player");
    }

    /**
     * {@code handleEntityPositionSync}.
     */
    public void sync(ClientboundEntityPositionSyncPacket packet, LongPredicate tickingChunk) {
        int id = packet.id();
        var entry = entry(id);
        if (entry == null)
            return;
        var end = packet.position().endPosition();
        if (entry instanceof PacketEntityMoving moving) {
            updateOwnership(id, moving);
            Tri controlled = controlled(id, moving);
            if (controlled == Tri.EITHER)
                moving.unknown = true;
            if (moving instanceof PacketEntityLiving living && living.earlyController != null) {
                living.earlyController.sync(packet, true, living.ticking);
                living.earlyControllerHandled = true;
            }
            moving.motion.sync(packet, controlled == Tri.TRUE, moving.ticking);
            if (controlled == Tri.FALSE)
                moving.estimateSynced = true;
            // a sync to an entity that does not tick snaps it (a ticking one interpolates from where it is, or snaps when
            // it is more than 64 blocks away from it): that makes an unknown transform known again
            if (controlled == Tri.FALSE && !moving.ticking)
                moving.unknown = false;
        } else if (entry instanceof PacketEntityShulker shulker) {
            Vec3d position = new Vec3d(end.x(), end.y(), end.z());
            shulker.shulker.codecBase = position;
            boolean passenger = passenger(id);
            float scale = shulkerScale(id);
            // interpolated (setPos) when ticking and near, else snapTo; a passenger is placed by its vehicle
            if (shulker.ticking && !(shulker.shulker.position.distanceToSqr(position) > 4096.0))
                shulker.shulker.setPos(position, passenger, scale);
            else
                shulker.shulker.snapTo(position, passenger, scale);
        } else {
            var positions = (PacketEntityPositions) entry;
            positions.codecBase = new Vec3d(end.x(), end.y(), end.z());
            positions.push(positions.codecBase);
        }
        moved(entry, tickingChunk);
    }

    // ---- ticks ----------------------------------------------------------------------------------------------------
    /**
     * The local player's place in the tick list: its own, or its root vehicle's while riding ({@code tickPassenger}).
     */
    private long playerTickOrder() {
        Integer root = owner.passengers().root(owner.localPlayerId());
        if (root == null || root == owner.localPlayerId())
            return playerOrder;
        var entry = entry(root);
        // a vehicle this model does not follow: unknown place, every simulated entity counts as ticking after
        return entry == null ? Long.MAX_VALUE : entry.order;
    }

    /**
     * Starts a client tick: the entities in the tick list at {@code tickEntities}, in order, with their ownership
     * ({@link #updateOwnership}).
     */
    public void beginTick() {
        var ticking = new ArrayList<PacketEntity>();
        for (var entity : owner.all()) {
            if (entity instanceof PacketEntityFollowed entry) {
                if (!entry.hasBody())
                    continue;
                if (entry instanceof PacketEntityMoving moving)
                    updateOwnership(entry.id(), moving);
                if (entry.ticking)
                    ticking.add(entry);
            } else if (entity instanceof PacketEntityRocket rocket) {
                if (rocket.ticking)
                    ticking.add(rocket);
            }
        }
        ticking.sort(Comparator.comparingLong(entity -> entity instanceof PacketEntityFollowed entry ? entry.order : ((PacketEntityRocket) entity).order));
        tickSnapshot = ticking.stream().map(PacketEntity::id).toList();
    }

    /**
     * Ticks the simulated entities of the snapshot on one side of the local player ({@code tickNonPassenger}); an owned
     * entity is the ride's, a passenger is placed by its vehicle (unknown).
     *
     * @param queries the entity's {@code level.noCollision(entity, box)}; a query it cannot resolve leaves the transform
     *                unknown
     */
    public void tick(boolean beforePlayer, LongPredicate tickingChunk, BodyQueries queries) {
        tick(beforePlayer, tickingChunk, queries, null, java.util.EnumSet.noneOf(Unmodeled.class));
    }

    /**
     * {@link #tick(boolean, LongPredicate, BodyQueries)} with the pushes of the local player: a remote boat's tick ends with
     * {@code AbstractBoat.push} of the entities in its push box ({@link #pushByBoat}).
     *
     * @param player    the local player, {@code null} when it is not in the level
     * @param pushGaps  collects what a push could not resolve
     */
    public void tick(boolean beforePlayer, LongPredicate tickingChunk, BodyQueries queries, LocalPlayerTarget player, Set<Unmodeled> pushGaps) {
        tick(beforePlayer, tickingChunk, queries, player, pushGaps, null);
    }

    /**
     * {@link #tick(boolean, LongPredicate, BodyQueries, LocalPlayerTarget, Set)} with the level's beds (a sleeping entity's
     * first tick puts it back on its bed).
     */
    public void tick(boolean beforePlayer, LongPredicate tickingChunk, BodyQueries queries, LocalPlayerTarget player, Set<Unmodeled> pushGaps, Beds beds) {
        tick(beforePlayer, tickingChunk, queries, player, pushGaps, beds, null);
    }

    /**
     * {@link #tick(boolean, LongPredicate, BodyQueries, LocalPlayerTarget, Set, Beds)} with the level's fluids ({@code fluid}:
     * whether a box touches a fluid; {@code null}: not known, a living entity's delta movement is then not followed).
     */
    public void tick(boolean beforePlayer, LongPredicate tickingChunk, BodyQueries queries, LocalPlayerTarget player, Set<Unmodeled> pushGaps, Beds beds, Predicate<Aabb> fluid) {
        tick(beforePlayer, tickingChunk, queries, player, pushGaps, beds, fluid, null);
    }

    /**
     * {@link #tick(boolean, LongPredicate, BodyQueries, LocalPlayerTarget, Set, Beds, Predicate)} knowing whether a remote
     * entity stands in a climbable block ({@code onClimbable}: not pushable; {@code null}: not known), so the push it gets
     * back from the local player can be followed.
     */
    public void tick(boolean beforePlayer, LongPredicate tickingChunk, BodyQueries queries, LocalPlayerTarget player, Set<Unmodeled> pushGaps, Beds beds, Predicate<Aabb> fluid, java.util.function.Function<Vec3d, Tri> climbable) {
        this.climbable = climbable;
        long playerOrder = playerTickOrder();
        for (int id : tickSnapshot) {
            var rocket = rocketEntity(id);
            if (rocket != null) {
                if ((rocket.order <= playerOrder) == beforePlayer)
                    tickRocket(id, rocket, player, tickingChunk, pushGaps);
                continue;
            }
            var entry = entry(id);
            // a riding player ticks inside its root vehicle's tick, after the vehicle
            if (entry == null || (entry.order <= playerOrder) != beforePlayer)
                continue;
            var view = owner.passengers().get(id);
            if (entry instanceof PacketEntityShulker shulker) {
                tickShulker(id, shulker, view != null && view.vehicle() != null, player, pushGaps, beds);
                continue;
            }
            var moving = (PacketEntityMoving) entry;
            if (view != null && view.vehicle() != null) {
                // ClientLevel.tickPassenger: ticked by its vehicle's tick, which places it
                if (seats(view.vehicle()))
                    continue;
                moving.seated = false;
                moving.unknown = true;
                moving.drivable = false;
                moving.rideStart = null;
                moving.rideStartEstimate = null;
                moving.rideStartVelocity = null;
                moving.rideStartVelocityEstimate = null;
                tickEnd(id, moving);
                continue;
            }
            // a dismount does not move a remote passenger on the client (dismountVehicle is the server's)
            moving.seated = false;
            // the client's state a ride of the local player starts from: the remote one on the first tick it may drive the
            // entity, then the one the ride gave while it owns it
            boolean drivable = controlled(id, moving) != Tri.FALSE;
            if (moving instanceof PacketEntityRideable rideable && rideable.steering.boosting && rideable.owned && rideable.drivable && !rideable.ownedKnown)
                rideable.steeringKnown = false;
            boolean startKnown = drivable && !moving.unknown && (!moving.drivable || moving.owned && moving.ownedKnown);
            boolean shape = moving instanceof PacketEntityLiving;
            moving.rideStart = startKnown ? moving.motion.copy() : null;
            moving.rideStartEstimate = drivable && moving.motion != null ? moving.motion.copy() : null;
            moving.rideStartVelocity = startKnown && shape && moving.velocityKnown && !moving.phaseUncertain ? moving.velocity : null;
            moving.rideStartVelocityEstimate = drivable && shape ? moving.velocity : null;
            moving.rideStartPhaseUncertain = startKnown && moving.phaseUncertain;
            moving.drivable = drivable;
            if (moving.owned) {
                // its tick advances the interpolation as vanilla does; the ride then sets the transform
                moving.motion.tick(box -> false);
                moving.ownedKnown = false;
                moving.ownedInterpolationKnown = false;
                tickEnd(id, moving);
                continue;
            }
            simulate(id, moving, drivable, tickingChunk, queries, player, pushGaps, beds, fluid);
            tickRiders(id, moving, tickingChunk, queries, player, pushGaps, beds, fluid);
        }
    }

    /**
     * A followed entity's own tick (not owned by the local player's ride), its pushes, the end of its tick.
     */
    private void simulate(int id, PacketEntityMoving entry, boolean drivable, LongPredicate tickingChunk, BodyQueries queries, LocalPlayerTarget player, Set<Unmodeled> pushGaps, Beds beds, Predicate<Aabb> fluid) {
        var gaps = java.util.EnumSet.noneOf(Unmodeled.class);
        boolean playerType = entry.type().equals("minecraft:player");
        // Player.tick: noPhysics = isSpectator(), its PlayerInfo looked up by the first tick
        if (playerType && !entry.ticked)
            owner.lookUpPlayerInfo(id);
        Tri noPhysics = playerType ? owner.spectator(id) : Tri.FALSE;
        // LivingEntity.baseTick on the first tick: a sleeper goes back onto its bed (26.3: after the commonTick step)
        Runnable firstTick = !(entry instanceof PacketEntityLiving) || entry.ticked ? null : () -> firstTickBed(id, entry, beds);
        boolean commonTick = true;
        entry.ticked = true;
        var self = new Collider(id, playerType, entry.kind() == Kind.BOAT, entry.kind() == Kind.HAPPY_GHAST, entry.motion.body.position);
        boolean interpolatingBefore = entry.motion.interpolating();
        // an unresolved answer matters only where it could move the target
        if (entry.motion.tick(queries.of(id, entry.motion.body, self, gaps), commonTick ? null : firstTick, commonTick ? firstTick : null) && !gaps.isEmpty()) {
            entry.unknown = true;
        }
        if (entry instanceof PacketEntityLiving living) {
            remoteVelocity(living, commonTick ? living.motion.interpolating() : interpolatingBefore, fluid);
        }
        if (entry instanceof PacketEntityHorse horse && horse.seatAnimated()) {
            var data = owner.mob(id);
            long assigned = owner.assigned(id, data.horseFlagsId());
            if (horse.seatFlags != assigned) {
                horse.seatFlags = assigned;
                horse.seatStanding = data.horseFlag(32);
            }
            // AbstractHorse.tick: the standing countdown (a remotely controlled horse has no effective AI on this client, so
            // before the countdown nothing ends its standing) and the stand animation
            if (horse.seatStandCounter > 0 && --horse.seatStandCounter <= 0) {
                horse.seatStanding = false;
                horse.seatStandCounter = 0;
            }
            horse.oldStandAnimation = horse.standAnimation;
            horse.standAnimation = MovementTickerHorse.nextStandAnimation(horse.seatStanding, horse.standAnimation);
        }
        // a mount the local player may drive moved on as a remote entity or under the player's control: the ride gives
        // the delta movement back when the client reports the mount
        if (drivable)
            entry.velocityKnown = false;
        moved(entry, tickingChunk);
        if (entry.kind() == Kind.BOAT && player != null) {
            for (var one : player.possible()) pushByBoat(id, entry, one, pushGaps);
        }
        if ((entry.kind() == Kind.HAPPY_GHAST || entry.kind() == Kind.LIVING) && player != null && pushesPlayers(entry.type())) {
            for (var one : player.possible()) pushByLiving(id, entry, one, pushGaps, noPhysics);
        }
        // RemotePlayer.updatePlayerPose does nothing: another player's pose is the synched one
        tickEnd(id, entry);
    }

    /**
     * The client classes extending {@code Animal} (the same in all 13 releases), which a two-seat boat moves 0.2 back.
     */
    private static final Set<String> ANIMALS = Set.of("Armadillo", "Axolotl", "Bee", "Camel", "CamelHusk", "Cat", "Chicken", "Cow", "Donkey", "Fox", "Frog", "Goat", "HappyGhast", "Hoglin", "Horse", "Llama", "Mule", "MushroomCow", "Nautilus", "Ocelot", "Panda", "Parrot", "Pig", "PolarBear", "Rabbit", "Sheep", "SkeletonHorse", "Sniffer", "Strider", "TraderLlama", "Turtle", "Wolf", "ZombieHorse", "ZombieNautilus");

    /**
     * Whether the vehicle {@code id} places its passengers here: a followed boat, raft or happy ghast no ride owns.
     */
    private boolean seats(int id) {
        return entry(id) instanceof PacketEntityMoving vehicle && vehicle.motion != null && (vehicle.kind() == Kind.BOAT || vehicle.kind() == Kind.HAPPY_GHAST) && !vehicle.owned && !passenger(id);
    }

    /**
     * {@code ClientLevel.tickPassenger} for each passenger of a seating vehicle after its tick, in passenger order: a
     * player or a ticking entity runs {@code rideTick} ({@code setDeltaMovement(ZERO)}, its own tick with its pushes,
     * then {@code vehicle.positionRider}); its own passengers are not placed here (a living vehicle's seats are not
     * modelled: they become unknown).
     */
    private void tickRiders(int vehicleId, PacketEntityMoving vehicle, LongPredicate tickingChunk, BodyQueries queries, LocalPlayerTarget player, Set<Unmodeled> pushGaps, Beds beds, Predicate<Aabb> fluid) {
        if (!seats(vehicleId))
            return;
        var view = owner.passengers().get(vehicleId);
        if (view == null)
            return;
        List<Integer> riders = view.passengers();
        int me = owner.localPlayerId();
        for (int index = 0; index < riders.size(); index++) {
            int id = riders.get(index);
            var rider = entry(id);
            if (id == me || rider == null || rider instanceof PacketEntityShulker)
                continue;
            boolean playerType = rider.type().equals("minecraft:player");
            if (!playerType && !rider.ticking)
                continue;
            if (!(rider instanceof PacketEntityLiving living) || living.motion == null) {
                rider.seated = false;
                rider.unknown = true;
                continue;
            }
            living.velocity = Vec3d.ZERO;
            simulate(id, living, false, tickingChunk, queries, player, pushGaps, beds, fluid);
            Vec3d seat = vehicle.unknown || vehicle.phaseUncertain || vehicle.estimate ? null : seat(vehicleId, vehicle, id, living, index, riders.size());
            if (seat == null) {
                living.seated = false;
                living.unknown = true;
            } else {
                living.motion.body.position = ru.minecomplex.mac.util.math.VanillaMath.position(living.motion.body.position, seat);
                living.seated = true;
                living.unknown = false;
                living.phaseUncertain = false;
                moved(living, tickingChunk);
            }
            var riding = owner.passengers().get(id);
            if (riding != null) {
                for (int nested : riding.passengers()) {
                    var other = entry(nested);
                    if (other != null && nested != me) {
                        other.seated = false;
                        other.unknown = true;
                    }
                }
            }
        }
    }

    /**
     * {@code positionRider}: {@code vehicle.position().add(getPassengerAttachmentPoint(passenger, dimensions, 1.0F))} less
     * {@code passenger.getVehicleAttachmentPoint(vehicle)}. A boat's point is {@code (0, rideHeight, offset)} turned by
     * {@code -yRot} (offset {@code getSinglePassengerXOffset}: 0.15 for a chest boat; with two passengers 0.2 / -0.6, an
     * {@code Animal} 0.2 more; {@code rideHeight}: a raft 0.8888889 of its height, a boat a third); a happy ghast's
     * {@code getClamped(PASSENGER, index, yRot)}. A passenger's point is its {@code VEHICLE} attachment (no vanilla type
     * has one off the vertical, so its yaw does not matter), a spider's and a cave spider's 0.3125 and 0.21875 times its
     * scale up on a vehicle no wider than it. {@code null}: not known.
     */
    private Vec3d seat(int vehicleId, PacketEntityMoving vehicle, int riderId, PacketEntityLiving rider, int index, int count) {
        Vec3d point;
        float vehicleWidth;
        if (vehicle instanceof PacketEntityBoat boat) {
            float offset = boat.chest() ? 0.15F : 0.0F;
            if (count > 1) {
                offset = index == 0 ? 0.2F : -0.6F;
                var living = types.living(rider.type());
                if (living != null && ANIMALS.contains(living.entityClass()))
                    offset += 0.2F;
            }
            var dimensions = types.type(boat.type()).dimensions();
            vehicleWidth = dimensions.width();
            double height = boat.raft() ? dimensions.height() * 0.8888889F : dimensions.height() / 3.0F;
            float angle = -boat.motion.body.yRot * (float) (Math.PI / 180.0);
            float cos = Trig.cos(angle), sin = Trig.sin(angle);
            point = new Vec3d(0.0 * cos + (double) offset * sin, height, (double) offset * cos - 0.0 * sin);
        } else {
            EntityDimensions dimensions = ghastDimensions(vehicleId);
            vehicleWidth = dimensions.width();
            point = dimensions.attachments().getClamped(EntityAttachment.PASSENGER, index, vehicle.motion.body.yRot);
        }
        Vec3d at = vehicle.motion.body.position;
        Vec3d riding = new Vec3d(at.x() + point.x(), at.y() + point.y(), at.z() + point.z());
        var living = types.living(rider.type());
        String cls = living == null ? null : living.entityClass();
        Vec3d offset;
        EntityDimensions own = rider.shape.dimensions();
        if (("Spider".equals(cls) || "CaveSpider".equals(cls)) && vehicleWidth <= own.width()) {
            offset = new Vec3d(0.0, ("Spider".equals(cls) ? 0.3125 : 0.21875) * livingScale(riderId, rider.type()), 0.0);
        } else {
            offset = vehicleAttachment(riderId, rider.type(), own);
            if (offset == null)
                return null;
        }
        return new Vec3d(riding.x() - offset.x(), riding.y() - offset.y(), riding.z() - offset.z());
    }

    /**
     * {@code getAttachments().get(VEHICLE, 0, yRot)} of the passenger's dimensions {@code own}: their own points, else
     * (the living data's derived dimensions carry none) those of the type's dimensions scaled as vanilla derives them
     * ({@code getDefaultDimensions}: a baby's by its age scale, then {@code getDimensions}: by {@code getScale()}), when
     * these are {@code own}; {@code null}: not known. No vanilla vehicle point leaves the vertical: the yaw does not turn it.
     */
    private Vec3d vehicleAttachment(int id, String type, EntityDimensions own) {
        var points = own.attachments().points(EntityAttachment.VEHICLE);
        if (!points.isEmpty())
            return points.getFirst();
        var known = types.type(type);
        var living = types.living(type);
        if (known == null || living == null)
            return null;
        boolean baby = owner.mob(id).baby();
        EntityDimensions expected = baby ? known.dimensions().scale(living.ageScale(true)) : known.dimensions();
        expected = expected.scale(livingScale(id, type));
        if (expected.width() != own.width() || expected.height() != own.height())
            return null;
        var derived = expected.attachments().points(EntityAttachment.VEHICLE);
        return derived.isEmpty() ? Vec3d.ZERO : derived.getFirst();
    }

    /**
     * A remote living entity's delta movement in its tick ({@code LivingEntity.aiStep}): while it does not interpolate it
     * slows (x0.98; 1.21.4 {@code lerpSteps == 0}, 1.21.5–26.2 checked before the interpolation step, 26.3 after the
     * {@code commonTick} step), then each component under 0.003 becomes 0 (a remote player's horizontal pair by length:
     * not followed). Fluid pushing in its {@code baseTick} is not modelled: in a fluid the movement becomes unknown.
     */
    private static void remoteVelocity(PacketEntityLiving entry, boolean interpolating, Predicate<Aabb> fluid) {
        // whether the client interpolated this tick (and so kept the velocity) may differ a step
        if (entry.phaseUncertain && moving(entry.velocity))
            entry.velocityKnown = false;
        if (entry.type().equals("minecraft:player")) {
            entry.velocityKnown = false;
            return;
        }
        if (fluid == null || fluid.test(entry.motion.body.boundingBox().inflate(1.0E-3)))
            entry.velocityKnown = false;
        Vec3d v = interpolating ? entry.velocity : entry.velocity.scale(0.98);
        entry.velocity = new Vec3d(Math.abs(v.x()) < 0.003 ? 0.0 : v.x(), Math.abs(v.y()) < 0.003 ? 0.0 : v.y(), Math.abs(v.z()) < 0.003 ? 0.0 : v.z());
    }

    /**
     * A shulker's client tick: {@code tickCount} grows, its {@code pushEntities} (with the box it has), a changed
     * {@code scale} refreshes its box at the end of {@code LivingEntity.tick}, then {@code updatePeekAmount} and
     * {@code onPeekAmountChange}: the box is reapplied and, while it opens, what the opening sweeps is moved
     * ({@code entity.move(MoverType.SHULKER, …)}: not another shulker, not a spectator, nothing without physics, nothing
     * riding with it). A passenger is placed by its vehicle: its transform becomes unknown.
     */
    private void tickShulker(int id, PacketEntityShulker entry, boolean passenger, LocalPlayerTarget player, Set<Unmodeled> gaps, Beds beds) {
        var shulker = entry.shulker;
        shulker.tickCount++;
        // a shulker is placed at once (setPos), never interpolated
        entry.phaseUncertain = false;
        if (passenger)
            entry.unknown = true;
        // LivingEntity.baseTick on the first tick: a sleeper goes back onto its bed
        if (!entry.ticked && !entry.unknown)
            firstTickBed(id, entry, beds);
        entry.ticked = true;
        if (!entry.unknown && player != null) {
            for (var one : player.possible()) pushByLiving(id, entry, one, gaps, Tri.FALSE);
        }
        float scale = shulkerScale(id);
        if (Float.floatToIntBits(scale) != Float.floatToIntBits(shulker.boxScale))
            shulker.restoreBox(scale);
        shulker.peekO = shulker.peek;
        float target = shulker.rawPeek * 0.01F;
        if (shulker.peek == target)
            return;
        shulker.peek = shulker.peek > target ? ru.minecomplex.mac.util.math.VanillaMath.clamp(shulker.peek - 0.05F, target, 1.0F) : ru.minecomplex.mac.util.math.VanillaMath.clamp(shulker.peek + 0.05F, 0.0F, target);
        // onPeekAmountChange: reapplyPosition, refreshDimensions
        shulker.restoreBox(scale);
        if (entry.unknown || player == null)
            return;
        float physical = ShulkerBody.physicalPeek(shulker.peek), physicalOld = ShulkerBody.physicalPeek(shulker.peekO);
        var direction = shulker.attachFace.opposite();
        float push = (physical - physicalOld) * scale;
        if (push <= 0.0F)
            return;
        Aabb swept = ShulkerBody.progressDelta(scale, direction, physicalOld, physical, shulker.position);
        // other entities it sweeps move too (Entity.move of their own): not modelled, their transforms become unknown
        for (var candidate : owner.all()) {
            if (!(candidate instanceof PacketEntityFollowed other))
                continue;
            if (other == entry || other instanceof PacketEntityShulker || !other.hasBody() || other.unknown || sameRoot(id, other.id()))
                continue;
            if (found(swept, other.position()) && intersects(other.box(), swept))
                other.unknown = true;
        }
        if (sameRoot(id, owner.localPlayerId()))
            return;
        for (var one : player.possible()) {
            if (!found(swept, one.position()) || !intersects(one.box(), swept))
                continue;
            if (one.spectator() || one.noPhysics())
                continue;
            one.moveByShulker(new Vec3d(push * direction.x, push * direction.y, push * direction.z), gaps);
        }
    }

    /**
     * The client classes of {@code AbstractHorse} (the same in all 13 releases; {@code CamelHusk} from 1.21.11).
     */
    private static final Set<String> HORSES = Set.of("Horse", "Donkey", "Mule", "SkeletonHorse", "ZombieHorse", "Llama", "TraderLlama", "Camel", "CamelHusk");

    /**
     * A living entity's {@code isPushable()}: {@code LivingEntity}'s alive, no spectator, not on a climbable block (the
     * 26.x entity-ticking test is the server's); {@code AbstractHorse} only {@code !isVehicle()}, which
     * {@code Entity.push} tests anyway; a {@code Warden} not digging or emerging (its synched pose); a {@code Creaking} only
     * where it can move ({@code CAN_MOVE}, not followed).
     */
    private Tri pushable(int id, PacketEntityFollowed entry) {
        var living = types.living(entry.type());
        String cls = living == null ? null : living.entityClass();
        if (cls != null && HORSES.contains(cls))
            return Tri.TRUE;
        if ("Creaking".equals(cls))
            return Tri.EITHER;
        LivingShape shape = entry instanceof PacketEntityLiving withShape ? withShape.shape : null;
        if ("Warden".equals(cls) && shape != null && (shape.pose() == Pose.DIGGING || shape.pose() == Pose.EMERGING)) {
            return Tri.FALSE;
        }
        Tri climbing = shape == null || entry.type().equals("minecraft:player") || climbable == null ? Tri.EITHER : climbable.apply(entry.position());
        if (!owner.mob(id).alive())
            return Tri.FALSE;
        return climbing == Tri.FALSE ? Tri.TRUE : climbing == Tri.TRUE ? Tri.FALSE : Tri.EITHER;
    }

    /**
     * The end of a living entity's tick ({@code LivingEntity.tick} after {@code aiStep}): a changed {@code scale} refreshes
     * its dimensions, a polar bear's stand animation moves.
     */
    private void tickEnd(int id, PacketEntityMoving entry) {
        // both interpolations reached the same target once this model's ended (the client is never behind it); an
        // estimate only once a move from the server gave them that target
        if (entry.phaseUncertain && (entry.motion == null || !entry.motion.interpolating()) && (!entry.estimate || entry.estimateSynced)) {
            entry.phaseUncertain = false;
            entry.estimate = false;
        }
        if (!(entry instanceof PacketEntityLiving living))
            return;
        living.shape.tickEnd(owner, id, livingScale(id, living.type()));
        applyShape(living);
    }

    /**
     * The end of a remote boat's tick: {@code level.getEntities(this, getBoundingBox().inflate(0.2F, -0.01F, 0.2F),
     * EntitySelector.pushableBy(this))} (on a client only the local player passes), then for one that does not carry the
     * boat {@code this.push(entity)} ({@code AbstractBoat.push}: feet not above the boat's bottom; {@code Entity.push}).
     */
    private void pushByBoat(int id, PacketEntityMoving entry, LocalPlayerTarget player, Set<Unmodeled> gaps) {
        if (entry.unknown)
            return;
        Aabb boatBox = entry.box();
        Aabb pushBox = boatBox.inflate(0.2F, -0.01F, 0.2F);
        // a step behind the client's: whether and how it pushes is not exact
        if (entry.phaseUncertain && !restsAtTarget(entry) && intersects(player.box(), uncertainBox(entry, pushBox)))
            gaps.add(Unmodeled.ENTITY_PUSH);
        if (!found(pushBox, player.position()) || !intersects(player.box(), pushBox))
            return;
        Tri pushable = pushableBy(owner.scoreboardName(id), player);
        if (pushable == Tri.EITHER) {
            gaps.add(Unmodeled.ENTITY_PUSH);
            return;
        }
        if (pushable == Tri.FALSE)
            return;
        var boat = new ru.minecomplex.mac.mechanics.entity.BoatPush.Actor();
        boat.position = entry.motion.body.position;
        boat.velocity = Vec3d.ZERO;
        boat.box = boatBox;
        boat.boat = true;
        boat.vehicle = !owner.passengers().get(id).passengers().isEmpty();
        boat.pushable = true;
        var target = new ru.minecomplex.mac.mechanics.entity.BoatPush.Actor();
        target.position = player.position();
        target.velocity = player.velocity();
        target.box = player.box();
        target.noPhysics = player.noPhysics();
        target.vehicle = player.vehicle();
        target.pushable = player.pushable();
        ru.minecomplex.mac.mechanics.entity.BoatPush.apply(boat, target, sameRoot(id, owner.localPlayerId()));
        player.velocity(target.velocity);
    }

    /**
     * The end of a remote living entity's {@code aiStep}: {@code pushEntities} finds the local player
     * ({@code level.getPushableEntities(this, getBoundingBox())}: from 1.21.5 {@code ClientLevel} tests the local player's
     * box directly, {@code CLIENT_PUSHABLE_LOCAL_PLAYER}; 1.21.4 queries the players
     * in the sections) when {@code EntitySelector.pushableBy(this)} lets it, then {@code doPush} → {@code player.push(this)}
     * ({@code LivingEntity.push}: not while sleeping; {@code Entity.push}).
     */
    private void pushByLiving(int id, PacketEntityFollowed entry, LocalPlayerTarget player, Set<Unmodeled> gaps, Tri noPhysics) {
        if (entry.unknown)
            return;
        Aabb box = entry.box();
        if (entry.phaseUncertain && !restsAtTarget(entry) && intersects(player.box(), uncertainBox(entry, box)))
            gaps.add(Unmodeled.ENTITY_PUSH);
        boolean direct = true;
        if (!(direct || found(box, player.position())) || !intersects(player.box(), box))
            return;
        Tri pushable = pushableBy(owner.scoreboardName(id), player);
        // Entity.push: nothing when either side has no physics
        if (pushable == Tri.FALSE || player.sleeping() || noPhysics == Tri.TRUE)
            return;
        if (pushable == Tri.EITHER || noPhysics == Tri.EITHER) {
            gaps.add(Unmodeled.ENTITY_PUSH);
            return;
        }
        var self = new ru.minecomplex.mac.mechanics.entity.BoatPush.Actor();
        self.position = player.position();
        self.velocity = player.velocity();
        self.box = player.box();
        self.noPhysics = player.noPhysics();
        self.vehicle = player.vehicle();
        self.pushable = player.pushable();
        var pusher = new ru.minecomplex.mac.mechanics.entity.BoatPush.Actor();
        pusher.position = entry.position();
        pusher.velocity = Vec3d.ZERO;
        pusher.box = box;
        // Entity.push also pushes the mob itself (its velocity: a remote entity does not move by it) unless it carries a
        // passenger or is not pushable
        var moving = entry instanceof PacketEntityMoving withVelocity ? withVelocity : null;
        pusher.velocity = moving == null ? Vec3d.ZERO : moving.velocity;
        pusher.vehicle = !owner.passengers().get(id).passengers().isEmpty();
        Tri pushableItself = pushable(id, entry);
        pusher.pushable = pushableItself == Tri.TRUE;
        Vec3d before = self.velocity;
        ru.minecomplex.mac.mechanics.entity.EntityPush.pushedBy(self, pusher, sameRoot(id, owner.localPlayerId()));
        if (!self.velocity.equals(before) && moving != null) {
            if (pushableItself == Tri.EITHER && !pusher.vehicle)
                moving.velocityKnown = false;
            else
                moving.velocity = pusher.velocity;
        }
        player.velocity(self.velocity);
    }

    /**
     * Whether an entity at {@code position} is in a section {@code EntitySectionStorage.forEachAccessibleNonEmptySection}
     * visits for {@code box}: within the box widened by 2 horizontally and 4 down.
     */
    private static boolean found(Aabb box, Vec3d position) {
        int sx = floor(position.x()) >> 4, sy = floor(position.y()) >> 4, sz = floor(position.z()) >> 4;
        return sx >= floor(box.minX() - 2.0) >> 4 && sx <= floor(box.maxX() + 2.0) >> 4 && sy >= floor(box.minY() - 4.0) >> 4 && sy <= floor(box.maxY() + 0.0) >> 4 && sz >= floor(box.minZ() - 2.0) >> 4 && sz <= floor(box.maxZ() + 2.0) >> 4;
    }

    /**
     * {@code EntitySelector.pushableBy(pusher).test(localPlayer)}: no spectator, {@code isPushable()}, and the team rules of
     * both ({@link ClientTeams#pushes}).
     */
    private Tri pushableBy(String pusher, LocalPlayerTarget player) {
        if (player.spectator() || !player.pushable())
            return Tri.FALSE;
        return owner.teams().pushes(pusher, owner.scoreboardName(owner.localPlayerId()));
    }

    /**
     * The ride's transform of the owned root vehicle this tick ({@code null}: the client did not report it).
     */
    public void ride(int id, Vec3d position, float yaw, float pitch, LongPredicate tickingChunk) {
        ride(id, position, yaw, pitch, null, tickingChunk);
    }

    /**
     * {@link #ride(int, Vec3d, float, float, LongPredicate)} with the vehicle's delta movement after the ride ({@code null}: not known).
     */
    public void ride(int id, Vec3d position, float yaw, float pitch, Vec3d velocity, LongPredicate tickingChunk) {
        ride(id, position, yaw, pitch, velocity, null, true, tickingChunk);
    }

    /**
     * {@link #ride(int, Vec3d, float, float, Vec3d, LongPredicate)} with the interpolation the ride followed
     * ({@code null}: the ride did not follow one, the entity's own steps stand).
     */
    public void ride(int id, Vec3d position, float yaw, float pitch, Vec3d velocity, ru.minecomplex.mac.entity.interpolation.EntityInterpolation interpolation, boolean exact, LongPredicate tickingChunk) {
        ride(id, position, yaw, pitch, velocity, velocity != null, interpolation, exact, tickingChunk);
    }

    /**
     * A followed velocity estimate may remain useful without certifying the client's actual velocity.
     */
    public void ride(int id, Vec3d position, float yaw, float pitch, Vec3d velocity, boolean velocityKnown, ru.minecomplex.mac.entity.interpolation.EntityInterpolation interpolation, boolean exact, LongPredicate tickingChunk) {
        var entry = entry(id);
        if (entry == null)
            return;
        if (entry instanceof PacketEntityHorse horse && horse.seatAnimated() && position != null && horse.state != null) {
            horse.seatStanding = horse.state.standing;
            horse.seatStandCounter = horse.state.standCounter;
            horse.standAnimation = horse.state.standAnimation;
            horse.oldStandAnimation = horse.state.oldStandAnimation;
            horse.seatFlags = owner.assigned(id, owner.mob(id).horseFlagsId());
        }
        if (entry instanceof PacketEntityMoving moving) {
            // a mount the client reports is one it controls ({@code LocalPlayer.tick} sends move_vehicle only then)
            if (moving instanceof PacketEntityLiving && (moving.owned || position != null && controlled(id, moving) != Tri.FALSE)) {
                moving.velocity = velocity == null ? Vec3d.ZERO : velocity;
                moving.velocityKnown = velocity != null && velocityKnown;
            }
            if (moving.owned || position != null && controlled(id, moving) != Tri.FALSE) {
                // move_vehicle proves local control even when this entity layer cannot resolve the mount's controller
                // (horse saddle, pig steering item, etc.). Keep the ride's actual client transform for the remote tick after
                // a dismount; retaining the last server transform would push the player from the wrong location.
                moving.ownedKnown = position != null;
                moving.ownedInterpolationKnown = position != null && interpolation != null;
                moving.ownedExact = exact;
                if (position != null) {
                    if (exact)
                        moving.unknown = false;
                    moving.motion.body.position = position;
                    moving.motion.body.yRot = yaw;
                    moving.motion.body.xRot = pitch;
                    if (interpolation != null)
                        moving.motion.interpolation = interpolation.copy();
                    // The ride followed the handler's interpolation and movement; handing control back no longer
                    // loses a teleport merely because it was received during the owned phase.
                    if (exact && interpolation != null && moving.kind() == Kind.LIVING)
                        moving.ownedTeleport = false;
                    if (!moving.owned && !exact) {
                        moving.phaseUncertain = true;
                        moving.estimate = true;
                        moving.estimateSynced = false;
                    }
                }
            }
        } else if (entry instanceof PacketEntityPositions positions) {
            if (position != null)
                positions.targets = new Vec3d[] { position };
        }
        if (position != null)
            moved(entry, tickingChunk);
    }

    /**
     * Copy of the constructor/onSynced/ridden-tick steering clock; remote ticks do not call tickRidden.
     */
    public ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding.State steeringBoost(int id) {
        return entry(id) instanceof PacketEntityRideable rideable ? rideable.steering() : null;
    }

    /**
     * The verified ridden tick advances this generation's clock, independently of movement certainty.
     */
    public void steeringBoost(int id, ru.minecomplex.mac.engine.movementtick.OtherVehicleRiding.State state, boolean known) {
        if (!(entry(id) instanceof PacketEntityRideable rideable))
            return;
        rideable.steering.boosting = state.boosting;
        rideable.steering.boostTime = state.boostTime;
        rideable.steering.boostDuration = state.boostDuration;
        rideable.steeringKnown = known;
    }

    // ---- queries --------------------------------------------------------------------------------------------------
    /**
     * Where a box of a phase-uncertain entity may be on the client: from this model's transform towards its interpolation
     * target (the client is at most that far ahead).
     */
    private static Aabb uncertainBox(PacketEntityFollowed entry, Aabb box) {
        if (!(entry instanceof PacketEntityMoving moving) || moving.motion == null)
            return box;
        Vec3d ahead = moving.motion.target().subtract(moving.motion.body.position);
        return box.expandTowards(ahead.x(), ahead.y(), ahead.z()).inflate(1.0E-7);
    }

    /**
     * A move of entity {@code id} (relative move, sync, teleport) that was in flight during a client tick became certain:
     * the client may have handled it before that tick, so its interpolation may be a step ahead of this model's until it
     * ends. Its collisions and pushes use this model's transform and report a gap meanwhile.
     */
    public void phaseUncertain(int id) {
        if (!(entry(id) instanceof PacketEntityMoving entry) || entry.motion == null)
            return;
        // even a move to where it rests restarts its interpolation a tick early: how far it has run matters once the
        // entity moves itself (a ride), and when it ends a kept velocity starts to slow
        entry.phaseUncertain = true;
        if (entry instanceof PacketEntityLiving && moving(entry.velocity))
            entry.velocityKnown = false;
    }

    /**
     * It rests exactly at its interpolation target: its box is the same whatever step its interpolation is at.
     */
    private static boolean restsAtTarget(PacketEntityFollowed entry) {
        return !entry.estimate && (!(entry instanceof PacketEntityMoving moving) || moving.motion == null || moving.motion.body.position.equals(moving.motion.target()));
    }

    /**
     * A {@code set_entity_motion} of entity {@code id} the client may have handled a tick before this model: the velocity it kept is not known.
     */
    public void velocityLate(int id) {
        if (entry(id) instanceof PacketEntityLiving entry && moving(entry.velocity))
            entry.velocityKnown = false;
    }

    private static boolean moving(Vec3d velocity) {
        return velocity.x() != 0.0 || velocity.y() != 0.0 || velocity.z() != 0.0;
    }

    /**
     * Entity {@code id} is followed and rests at {@code position} ({@code null}: wherever it is) with nothing left to
     * interpolate: a move there changes nothing of where it is.
     */
    public boolean restsAt(int id, Vec3d position) {
        var entry = entry(id);
        if (entry == null || !entry.hasBody() || entry.unknown || entry.phaseUncertain || !atTarget(entry))
            return false;
        Vec3d at = entry instanceof PacketEntityMoving moving ? moving.motion.body.position : entry.position();
        return position == null || at.equals(position);
    }

    /**
     * Whether entity {@code id} is phase-uncertain ({@link #phaseUncertain}) and whether its interpolation ended (diagnostics, tests).
     */
    public boolean[] phase(int id) {
        var entry = entry(id);
        if (entry == null)
            return new boolean[] { false, false, false };
        boolean ended = !(entry instanceof PacketEntityMoving moving) || moving.motion == null || !moving.motion.interpolating();
        return new boolean[] { entry.phaseUncertain, ended, entry.estimate };
    }

    /**
     * No interpolation is left to run: it ended, or the entity already rests at its target.
     */
    private static boolean atTarget(PacketEntityFollowed entry) {
        return !(entry instanceof PacketEntityMoving moving) || moving.motion == null || !moving.motion.interpolating() || moving.motion.body.position.equals(moving.motion.target());
    }

    /**
     * The client's transform and interpolation of an entity the local player drives or may drive (its first passenger) as
     * they were before this tick's step: the remote ones on the first such tick, then the ride's while the model owns the
     * entity ({@code null}: not known).
     */
    public EntityMotion rideStart(int id) {
        return entry(id) instanceof PacketEntityMoving moving ? moving.rideStart : null;
    }

    /**
     * Pre-tick estimate; unlike {@link #rideStart(int)}, this does not certify the client's transform.
     */
    public EntityMotion rideStartEstimate(int id) {
        return entry(id) instanceof PacketEntityMoving moving ? moving.rideStartEstimate : null;
    }

    /**
     * The remote vehicle after its entity tick, before its local passenger ticks.
     */
    public EntityMotion remotePassengerVehicle(int id) {
        return entry(id) instanceof PacketEntityMoving moving && moving.motion != null && moving.ticking && !moving.unknown && !moving.phaseUncertain && !moving.owned ? moving.motion.copy() : null;
    }

    public float horseSeatAnimation(int id) {
        return entry(id) instanceof PacketEntityHorse horse ? horse.seatAnimation() : 0F;
    }

    /**
     * Whether the {@link #rideStart} transform of entity {@code id} may be a step behind the client's ({@link #phaseUncertain}).
     */
    public boolean rideStartUncertain(int id) {
        return entry(id) instanceof PacketEntityMoving moving && moving.rideStartPhaseUncertain;
    }

    /**
     * The client's delta movement of such an entity before this tick's step ({@link #rideStart}; {@code null}: not known).
     */
    public Vec3d rideStartVelocity(int id) {
        return entry(id) instanceof PacketEntityMoving moving ? moving.rideStartVelocity : null;
    }

    /**
     * Pre-tick movement estimate; callers must use {@link #rideStartVelocity(int)} for certainty.
     */
    public Vec3d rideStartVelocityEstimate(int id) {
        return entry(id) instanceof PacketEntityMoving moving ? moving.rideStartVelocityEstimate : null;
    }

    private EntityDimensions ghastDimensions(int id) {
        var rideable = types.rideable("minecraft:happy_ghast");
        var mob = owner.mob(id);
        return types.livingDimensions(rideable, mob.baby(), mob.pose(), rideable.scale(scale(id, "minecraft:happy_ghast")));
    }

    /**
     * {@code LivingEntity.getScale()}: {@code sanitizeScale((float) scale)}, the identity for every type followed as
     * {@link Kind#LIVING}, at most 1 for a happy ghast.
     */
    private float livingScale(int id, String type) {
        var rideable = type.equals("minecraft:happy_ghast") ? types.rideable(type) : null;
        return rideable != null ? rideable.scale(scale(id, type)) : (float) scale(id, type);
    }

    private double scale(int id, String type) {
        var known = types.type(type);
        var defaults = known == null ? null : known.defaultAttributes();
        if (defaults == null)
            return 1.0;
        return AttributeMap.value(Attribute.SCALE, defaults, owner.attributeSnapshots(id));
    }

    /**
     * A box around every place the entity may be at: its packet positions (or transform) with its size and margin.
     */
    private Aabb reach(int id, PacketEntityFollowed entry) {
        double size;
        List<Vec3d> points = new ArrayList<>();
        if (entry instanceof PacketEntityShulker shulker) {
            // at most 3 wide and, open, twice as long
            size = 6.0;
            points.add(shulker.shulker.position);
            points.add(shulker.shulker.codecBase);
            if (passenger(id)) {
                return new Aabb(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            }
        } else if (entry instanceof PacketEntityMoving moving) {
            size = Math.max(moving.motion.body.bodyWidth, moving.motion.body.bodyHeight);
            if (moving.kind() == Kind.HAPPY_GHAST) {
                var dimensions = ghastDimensions(id);
                size = Math.max(size, Math.max(dimensions.width(), dimensions.height()));
            }
            points.add(moving.motion.body.position);
            points.add(moving.motion.codecBase);
            if (moving.motion.interpolating())
                points.add(moving.motion.target());
            // a passenger sits at one of its root vehicle's passenger points, inside the vehicle's box
            Integer root = owner.passengers().root(id);
            var rootEntry = root == null || root == id ? null : entry(root);
            if (passenger(id) && rootEntry != null) {
                boolean simulated = rootEntry.hasBody() && !rootEntry.unknown && !(rootEntry instanceof PacketEntityMoving rootMoving && rootMoving.owned);
                Aabb rootBox = simulated ? rootEntry.box() : reach(root, rootEntry);
                points.add(new Vec3d(rootBox.minX(), rootBox.minY(), rootBox.minZ()));
                points.add(new Vec3d(rootBox.maxX(), rootBox.maxY(), rootBox.maxZ()));
            } else if (passenger(id)) {
                return new Aabb(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            }
        } else {
            var known = types.type(entry.type());
            double scale = Math.max(1.0, scale(id, entry.type()));
            size = known == null ? 2.0 : Math.max(known.dimensions().width(), known.dimensions().height()) * scale;
            points.addAll(List.of(((PacketEntityPositions) entry).targets));
        }
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (Vec3d point : points) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
            maxZ = Math.max(maxZ, point.z());
        }
        double margin = size + REACH_MARGIN;
        return new Aabb(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
    }

    private static boolean intersects(Aabb a, Aabb b) {
        return a.intersects(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    private boolean sameRoot(int source, int other) {
        return owner.passengers().rootId(source) == owner.passengers().rootId(other);
    }

    private boolean passenger(int id) {
        var entity = owner.entity(id);
        return entity != null && entity.vehicle() != null;
    }

    /**
     * Whether a followed entity's transform is simulated and known here (not the ride's, not a passenger elsewhere).
     */
    private boolean simulated(PacketEntityFollowed entry) {
        return entry.hasBody() && !entry.unknown && !(entry instanceof PacketEntityMoving moving && moving.owned) && (entry.vehicle() == null || entry.seated);
    }

    /**
     * {@code getEntityCollisions(source, box)}: the boxes of the simulated entities the source can collide with
     * ({@code Entity.canCollideWith}, or a boat's/minecart's {@code canVehicleCollide}), in the client's query order.
     * Entities whose box is not known add {@link Unmodeled#ENTITY_COLLISION} (for a vehicle {@link Unmodeled#VEHICLE_ENTITIES})
     * when they may be inside the query.
     *
     * @param localPlayerBox the local player's box, a pushable entity for a vehicle source not carrying it
     */
    public List<VoxelShape> collisions(Aabb box, Collider source, Aabb localPlayerBox, Set<Unmodeled> gaps) {
        return collisions(box, source, localPlayerBox, Tri.EITHER, gaps);
    }

    /**
     * {@link #collisions(Aabb, Collider, Aabb, Set)} knowing whether the local player at {@code localPlayerBox} is pushable
     * ({@code isPushable}: alive, not a spectator, not on a climbable block), which makes it an obstacle for a vehicle
     * ({@code canVehicleCollide}).
     */
    public List<VoxelShape> collisions(Aabb box, Collider source, Aabb localPlayerBox, Tri localPlayerPushable, Set<Unmodeled> gaps) {
        var boxes = collisionBoxes(box, source, localPlayerBox, localPlayerPushable, gaps);
        if (boxes.isEmpty())
            return List.of();
        var shapes = new ArrayList<VoxelShape>(boxes.size());
        for (Aabb hit : boxes) shapes.add(Shapes.create(hit));
        return shapes;
    }

    /**
     * {@link #collisions}' entity boxes, before {@code Shapes.create}.
     */
    public List<Aabb> collisionBoxes(Aabb box, Collider source, Aabb localPlayerBox, Set<Unmodeled> gaps) {
        return collisionBoxes(box, source, localPlayerBox, Tri.EITHER, gaps);
    }

    /**
     * {@link #collisions}' entity boxes, before {@code Shapes.create}.
     */
    public List<Aabb> collisionBoxes(Aabb box, Collider source, Aabb localPlayerBox, Tri localPlayerPushable, Set<Unmodeled> gaps) {
        double size = ((box.maxX() - box.minX()) + (box.maxY() - box.minY()) + (box.maxZ() - box.minZ())) / 3.0;
        if (size < 1.0E-7)
            return List.of();
        Aabb search = box.inflate(1.0E-7);
        Unmodeled gap = source.vehicleRule() || !source.player() ? Unmodeled.VEHICLE_ENTITIES : Unmodeled.ENTITY_COLLISION;
        int x0 = floor(search.minX() - 2.0) >> 4, y0 = floor(search.minY() - 4.0) >> 4, z0 = floor(search.minZ() - 2.0) >> 4;
        int x1 = floor(search.maxX() + 2.0) >> 4, y1 = floor(search.maxY() + 0.0) >> 4, z1 = floor(search.maxZ() + 2.0) >> 4;
        record Hit(int sectionX, long rest, long sectionOrder, Aabb box) {
        }
        var hits = new ArrayList<Hit>();
        var graph = owner.passengers();
        int sourceRoot = graph.rootId(source.id());
        for (var candidate : owner.all()) {
            if (!(candidate instanceof PacketEntityFollowed entry))
                continue;
            int id = entry.id();
            if (id == source.id() || graph.rootId(entry) == sourceRoot)
                continue;
            if (!simulated(entry)) {
                // a living entity or minecart is no obstacle for a player; a boat or minecart may collide with it
                boolean collidable = entry.kind() == Kind.BOAT || entry.kind() == Kind.HAPPY_GHAST || entry.kind() == Kind.SHULKER || source.vehicleRule();
                if (collidable && intersects(reach(id, entry), search))
                    gaps.add(gap);
                continue;
            }
            Vec3d position = entry.position();
            int sx = floor(position.x()) >> 4, sy = floor(position.y()) >> 4, sz = floor(position.z()) >> 4;
            if (sx < x0 || sx > x1 || sy < y0 || sy > y1 || sz < z0 || sz > z1)
                continue;
            Aabb entityBox = entry.box();
            // a step behind the client's, which is between it and its interpolation target: not exact where they may meet
            if (entry.phaseUncertain && !restsAtTarget(entry) && intersects(uncertainBox(entry, entityBox), search))
                gaps.add(gap);
            if (!intersects(entityBox, search))
                continue;
            boolean collides;
            if (entry.kind() == Kind.BOAT) {
                collides = true;
            } else if (entry.kind() == Kind.SHULKER) {
                // Shulker.canBeCollidedWith: alive
                collides = owner.mob(id).alive();
            } else if (entry.kind() == Kind.LIVING) {
                // not collidable; a boat or minecart also collides with a pushable one (alive, not a spectator, not on a
                // climbable block; class overrides): not modelled
                if (source.vehicleRule())
                    gaps.add(gap);
                continue;
            } else {
                var mob = owner.mob(id);
                boolean vehicle = !entry.passengers().isEmpty();
                collides = !mob.baby() && mob.alive() && (source.player() && source.position().y() >= entityBox.maxY() || vehicle && source.happyGhast() || mob.flag("HappyGhast.STAYS_STILL"));
                // a vehicle also collides with a pushable one: alive, not on a climbable block (not modelled)
                if (!collides && source.vehicleRule() && mob.alive())
                    gaps.add(gap);
            }
            if (collides)
                hits.add(new Hit(sx, sectionKey(sx, sy, sz) & ((1L << 42) - 1), entry.sectionOrder, entityBox));
        }
        // canVehicleCollide: the local player is an obstacle when it is pushable and not a passenger of the same vehicle
        boolean playerHit = false;
        if (source.vehicleRule() && localPlayerBox != null && !sameRoot(source.id(), owner.localPlayerId()) && intersects(localPlayerBox, search)) {
            if (localPlayerPushable == Tri.TRUE)
                playerHit = true;
            else if (localPlayerPushable == Tri.EITHER)
                gaps.add(gap);
        }
        if (hits.isEmpty() && !playerHit)
            return List.of();
        hits.sort(Comparator.comparingInt(Hit::sectionX).thenComparing(Hit::rest, Long::compareUnsigned).thenComparingLong(Hit::sectionOrder));
        var boxes = new ArrayList<Aabb>(hits.size() + 1);
        for (Hit hit : hits) boxes.add(hit.box());
        // collisions take the least clamp over the boxes and a free check any of them: their order does not matter
        if (playerHit)
            boxes.add(localPlayerBox);
        return boxes;
    }

    /**
     * {@code getBoundingBox()} of a simulated entity ({@code null}: not simulated).
     */
    public Aabb boxOf(int id) {
        var entry = entry(id);
        return entry == null || !entry.hasBody() ? null : entry.box();
    }

    /**
     * Whether an entity whose pushes are not modelled may push the local player at this transform: a simulated happy
     * ghast whose box touches it ({@code pushEntities}) when their centres are 0.01 apart ({@code Entity.push}), or any
     * other pushable entity that may be near. A simulated boat's pushes are modelled ({@link #tick}).
     */
    public boolean mayPush(Vec3d playerPosition, Aabb playerBox) {
        var graph = owner.passengers();
        int myRoot = graph.rootId(owner.localPlayerId());
        for (var candidate : owner.all()) {
            if (!(candidate instanceof PacketEntityFollowed entry))
                continue;
            int id = entry.id();
            if (graph.rootId(entry) == myRoot)
                continue;
            // a minecart pushes only on the server (AbstractMinecart.push); these living types never push a player
            if (entry.type().equals("minecraft:minecart") || entry.type().endsWith("_minecart") || !pushesPlayers(entry.type()))
                continue;
            // a simulated boat's, happy ghast's, living entity's and shulker's pushes are modelled (their tick)
            if (simulated(entry))
                continue;
            if (intersects(reach(id, entry), playerBox.inflate(0.2)))
                return true;
        }
        return false;
    }

    /**
     * Whether an entity outside {@code id}'s own riders and vehicles may be inside {@code box}: a simulated one whose box
     * intersects it, any other whose reach does (pushes are not modelled). The local player is not counted.
     */
    public boolean othersNear(int id, Aabb box) {
        var graph = owner.passengers();
        int root = graph.rootId(id);
        for (var candidate : owner.all()) {
            if (!(candidate instanceof PacketEntityFollowed entry))
                continue;
            int other = entry.id();
            if (other == id || graph.rootId(entry) == root)
                continue;
            if (simulated(entry) ? intersects(entry.box(), box) : intersects(reach(other, entry), box))
                return true;
        }
        return false;
    }

    /**
     * The collider of entity {@code id} moving itself: a boat's or minecart's {@code canVehicleCollide}.
     */
    public Collider vehicleCollider(int id, String type, Vec3d position) {
        boolean vehicleRule = type != null && (type.endsWith("_boat") || type.endsWith("_raft") || type.equals("minecraft:minecart") || type.endsWith("_minecart"));
        return new Collider(id, false, vehicleRule, "minecraft:happy_ghast".equals(type), position);
    }

    /**
     * Whether {@code level.getEntitiesOfClass(Entity.class, area, ...)} lists the local player's own vehicle, which the
     * client simulates (a geyser lifts it, {@code canSimulateMovement}); remote entities do not move by such a push.
     * {@code EITHER}: an owned entity whose transform is not known.
     */
    public Tri ownedIn(Aabb area) {
        Tri result = Tri.FALSE;
        for (var candidate : owner.all()) {
            if (!(candidate instanceof PacketEntityFollowed entry))
                continue;
            if (!(entry instanceof PacketEntityMoving moving) || !moving.owned)
                continue;
            if (!moving.hasBody() || moving.unknown || !moving.ownedKnown)
                result = Tri.EITHER;
            else if (listed(area, moving.position(), moving.box()))
                return Tri.TRUE;
        }
        return result;
    }

    /**
     * The local player's own living mount's velocity after a block entity pushed it (a geyser).
     */
    public void ownedVelocity(int id, Vec3d velocity) {
        if (entry(id) instanceof PacketEntityLiving living && living.owned && living.velocityKnown)
            living.velocity = velocity;
    }

    /**
     * A block entity moved what {@code level.getEntities(null, area)} lists (an opening shulker box, a moving piston):
     * each simulated entity there moves by its own {@code Entity.move}, which is not modelled, so its transform becomes
     * unknown. Spectator players are not listed ({@code EntitySelector.NO_SPECTATORS}); the local player is the caller's.
     *
     * @return whether the local player's own vehicle was there (its transform is the ride's, not marked here)
     */
    public boolean displacedBy(Aabb area) {
        boolean ride = false;
        for (var candidate : owner.all()) {
            if (!(candidate instanceof PacketEntityFollowed entry))
                continue;
            int id = entry.id();
            if (!entry.hasBody() || entry.unknown || !listed(area, entry.position(), entry.box()))
                continue;
            if (entry.type().equals("minecraft:player") && owner.spectator(id) == Tri.TRUE)
                continue;
            if (entry instanceof PacketEntityMoving moving && moving.owned) {
                ride = true;
                continue;
            }
            entry.unknown = true;
        }
        return ride;
    }

    /**
     * Whether {@code level.getEntities(…, area)} lists an entity at {@code position} with {@code box}: its section is one the
     * lookup visits and the boxes intersect.
     */
    public static boolean listed(Aabb area, Vec3d position, Aabb box) {
        return found(area, position) && intersects(box, area);
    }

    /**
     * Whether entity {@code id} is a simulated entity near the box (a change to it concerns a body there).
     */
    public boolean near(int id, Aabb box) {
        var entry = entry(id);
        if (entry == null)
            return false;
        return intersects(reach(id, entry), box);
    }

    /**
     * A {@code set_entity_motion} that reached the vehicle the local player drives since the last call, or {@code null}.
     */
    public Vec3d takeRideVelocity(int id) {
        if (!(entry(id) instanceof PacketEntityMoving entry) || entry.rideVelocity == null)
            return null;
        Vec3d value = entry.rideVelocity;
        entry.rideVelocity = null;
        return value;
    }

    /**
     * The simulated transform of entity {@code id} ({@code null}: not simulated or not known).
     */
    public Vec3d position(int id) {
        var entry = entry(id);
        return entry == null || !entry.hasBody() || entry.unknown ? null : entry.position();
    }

    /**
     * Whether entity {@code id} is in the client's tick list ({@code null}: not followed).
     */
    public Boolean ticking(int id) {
        var entry = entry(id);
        return entry == null ? null : entry.ticking;
    }

    /**
     * Whether entity {@code id} ticks before the local player this tick (it is in the tick list ahead of it).
     */
    public boolean ticksBeforePlayer(int id) {
        var entry = entry(id);
        return entry != null && entry.ticking && entry.order <= playerTickOrder();
    }

    /**
     * Whether the transform of simulated entity {@code id} is unknown.
     */
    public boolean unknown(int id) {
        var entry = entry(id);
        return entry != null && entry.unknown;
    }

    /**
     * The model's state of entity {@code id} (diagnostics).
     */
    public String describe(int id) {
        var entry = entry(id);
        if (entry == null)
            return "not followed";
        var moving = entry instanceof PacketEntityMoving m ? m : null;
        var living = entry instanceof PacketEntityLiving l ? l : null;
        var shulker = entry instanceof PacketEntityShulker s ? s.shulker : null;
        boolean owned = moving != null && moving.owned, ownedExact = moving == null || moving.ownedExact, ownedKnown = moving != null && moving.ownedKnown, ownedTeleport = moving != null && moving.ownedTeleport;
        boolean steeringKnown = !(entry instanceof PacketEntityRideable rideable) || rideable.steeringKnown;
        Vec3d velocity = moving == null ? Vec3d.ZERO : moving.velocity;
        boolean velocityKnown = moving != null && moving.velocityKnown;
        EntityMotion motion = moving == null ? null : moving.motion;
        return entry.kind() + " ticking=" + entry.ticking + " order=" + entry.order + " unknown=" + entry.unknown + " owned=" + owned + " ownedExact=" + ownedExact + " steeringKnown=" + steeringKnown + " ownedKnown=" + ownedKnown + " ownedTeleport=" + ownedTeleport + " velocity=" + velocity + (velocityKnown ? "" : "?") + " phaseUncertain=" + entry.phaseUncertain + (motion == null ? "" : " yRot=" + motion.body.yRot + " targetYaw=" + motion.interpolation.targetYaw(motion.body) + " target=" + motion.target()) + (shulker != null ? " position=" + shulker.position + " face=" + shulker.attachFace + " peek=" + shulker.peek + "/" + shulker.rawPeek + " ticks=" + shulker.tickCount : motion == null ? " targets=" + java.util.Arrays.toString(((PacketEntityPositions) entry).targets) : " position=" + motion.body.position + " interpolating=" + motion.interpolating() + " codec=" + motion.codecBase) + (living == null ? "" : " pose=" + living.shape.pose + " dimensions=" + living.shape.dimensions.width() + "x" + living.shape.dimensions.height());
    }

    /**
     * Whether {@code level.getEntities(entity, area)} of entity {@code except} lists a living entity that a riptide spin
     * attack touches with a known outcome ({@code checkAutoSpinAttack}: {@code doAutoAttackOnTouch} → {@code Player.attack},
     * whose client-side {@code hurtClient} is false for every living entity but a {@code RemotePlayer}, so the attack
     * changes nothing then; the spin ends and the velocity turns ×−0.2). {@code TRUE}: a living entity that is not a
     * player, followed exactly (not a passenger, its interpolation phase known), surely intersects the area and no
     * player can be listed (its attack may knock back and slow the local player); {@code FALSE}: no living entity can
     * be there; {@code EITHER} otherwise (a spectator is not listed, which is not resolved). The local player is the
     * caller's.
     */
    public Tri livingIn(Aabb area, int except) {
        int me = owner.localPlayerId();
        boolean sure = false, possible = false, player = false;
        for (var candidate : owner.all()) {
            if (!(candidate instanceof PacketEntityFollowed entry))
                continue;
            int id = entry.id();
            if (id == except || id == me)
                continue;
            var known = types.type(entry.type());
            if (known != null && !known.living())
                continue;
            boolean simulated = simulated(entry);
            if (!(simulated ? intersects(entry.box(), area) : intersects(reach(id, entry), area)))
                continue;
            possible = true;
            if (known == null || entry.type().equals("minecraft:player"))
                player = true;
            else if (simulated && !entry.phaseUncertain && entry instanceof PacketEntityLiving)
                sure = true;
        }
        if (!possible)
            return Tri.FALSE;
        return sure && !player ? Tri.TRUE : Tri.EITHER;
    }

    /**
     * A followed living entity's delta movement where the model knows it ({@code null}: not known; tests).
     */
    Vec3d knownVelocity(int id) {
        return entry(id) instanceof PacketEntityMoving moving && moving.velocityKnown ? moving.velocity : null;
    }

    /**
     * Whether entities of {@code type} are followed.
     */
    public boolean follows(String type) {
        return kind(type) != null;
    }

    /**
     * Whether entity {@code id} is followed exactly ({@code null}: not at all).
     */
    public Kind kindOf(int id) {
        var entry = entry(id);
        return entry == null ? null : entry.kind();
    }
}
