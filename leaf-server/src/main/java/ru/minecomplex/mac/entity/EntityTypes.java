package ru.minecomplex.mac.entity;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.packet.NativeRuntime;

/**
 * The client's entity type data, generated from the 26.3 jar (MAET v6, {@code tools/datagen}
 * {@code EntityTypeSamples}): every type's {@code EntityType.getDimensions()} and {@code DefaultAttributes} base values,
 * the local player's dimensions per pose, and the real {@code getDefaultDimensions(pose)}/{@code getAgeScale()} of the
 * rideable living types as adult and baby with their constant movement facts, and the synched data ids of the mob
 * items the vehicle model reads ({@link #dataId}); for every living type the client constructs, its adult and baby default
 * dimensions per pose and the ids of the synched items its dimensions read ({@link #living}).
 */
public final class EntityTypes {
    private static final int MAGIC = 0x4d414554;
    private static volatile EntityTypes loaded;

    /**
     * @param updateInterval    {@code EntityType.updateInterval()} (the steps of the 26.3 stepped interpolation)
     * @param defaultAttributes attribute key → supplier base value, {@code null} for a type without a supplier (not living)
     */
    public record Type(String key, EntityDimensions dimensions, int updateInterval, Map<String, Double> defaultAttributes) {
        public boolean living() {
            return defaultAttributes != null;
        }
    }

    /** One rideable living type as a real entity: per age (adult, baby) its {@code isBaby()}, age scale and dimensions. */
    public static final class Rideable {
        private final String key;
        private final boolean[] baby = new boolean[2];
        private final float[] ageScale = new float[2];
        private final EntityDimensions[][] dimensions = new EntityDimensions[2][];
        private int maxHeadYRot;
        private Boolean canRear;
        private boolean pushedByFluid, canFloat;

        private Rideable(String key) {
            this.key = key;
        }

        /** {@code Mob.getMaxHeadYRot()}. */
        public int maxHeadYRot() {
            return maxHeadYRot;
        }

        /** {@code AbstractHorse.canPerformRearing()}; {@code null} for types that are no horse. */
        public Boolean canRear() {
            return canRear;
        }

        /** {@code Entity.isPushedByFluid()}. */
        public boolean pushedByFluid() {
            return pushedByFluid;
        }

        /** The client entity's {@code getNavigation().canFloat()} (no goals are registered on the client). */
        public boolean canFloat() {
            return canFloat;
        }

        public String key() {
            return key;
        }

        /** {@code getAgeScale()} of the adult ({@code baby == false}) or baby entity. */
        public float ageScale(boolean baby) {
            return ageScale[baby ? 1 : 0];
        }

        /** {@code getDefaultDimensions(pose)} of the adult or baby entity. */
        public EntityDimensions defaultDimensions(boolean baby, Pose pose) {
            return dimensions[baby ? 1 : 0][pose.ordinal()];
        }

        /** Whether setting the age to a baby's made {@code isBaby()} true (types that cannot be babies stay adult). */
        public boolean canBeBaby() {
            return baby[1];
        }

        /**
         * {@code LivingEntity.getScale()} from the {@code scale} attribute value: {@code sanitizeScale((float) value)},
         * which only {@code HappyGhast} overrides ({@code Math.min(scale, 1.0F)}, in every release that has it).
         */
        public float scale(double attributeValue) {
            float scale = (float) attributeValue;
            return key.equals("minecraft:happy_ghast") ? Math.min(scale, 1.0F) : scale;
        }
    }

    /**
     * One living type as a real client entity: {@code getDefaultDimensions(pose)} and {@code getAgeScale()} as adult and
     * after {@code setBaby(true)}, and the ids of its synched items that dimensions read (by field name, as declared in
     * its class hierarchy: {@code DATA_BABY_ID}, {@code ID_SIZE}, {@code PUFF_STATE}, {@code DATA_TYPE},
     * {@code DATA_STATE}, {@code DATA_STANDING_ID}, {@code DATA_ATTACH_FACE_ID}, {@code DATA_PEEK_ID}).
     */
    public static final class Living {
        private final String key;
        private String entityClass;
        private boolean canBeBaby;
        private final float[] ageScale = new float[2];
        private final EntityDimensions[][] dimensions = new EntityDimensions[2][];
        private Map<String, Integer> items = Map.of();

        private Living(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** The simple name of the client entity class. */
        public String entityClass() {
            return entityClass.substring(entityClass.lastIndexOf('.') + 1);
        }

        /** Whether {@code setBaby(true)} made {@code isBaby()} true. */
        public boolean canBeBaby() {
            return canBeBaby;
        }

        public float ageScale(boolean baby) {
            return ageScale[baby && canBeBaby ? 1 : 0];
        }

        /** {@code getDefaultDimensions(pose)} of the adult or baby entity (with its default synched data otherwise). */
        public EntityDimensions defaultDimensions(boolean baby, Pose pose) {
            return dimensions[baby && canBeBaby ? 1 : 0][pose.ordinal()];
        }

        /** The id of a dimension item by field name, −1 when the type has none. */
        public int item(String field) {
            return items.getOrDefault(field, -1);
        }
    }

    private final Map<String, Type> types;
    private final EntityDimensions[] player;
    private final EntityDimensions sleeping;
    private final Map<String, Rideable> rideable;
    private final Pose[] vanillaPoses;
    private final Map<String, Integer> dataIds;
    private final Map<String, Living> living;

    private EntityTypes(Map<String, Type> types, EntityDimensions[] player, EntityDimensions sleeping, Map<String, Rideable> rideable,
                        Pose[] vanillaPoses, Map<String, Integer> dataIds, Map<String, Living> living) {
        this.dataIds = dataIds;
        this.living = living;
        this.types = types;
        this.player = player;
        this.sleeping = sleeping;
        this.rideable = rideable;
        this.vanillaPoses = vanillaPoses;
    }

    /**
     * The release's id of a synched data accessor by its vanilla field name ({@code DATA_MOB_FLAGS_ID},
     * {@code DATA_BABY_ID}, {@code DATA_ID_FLAGS} of {@code AbstractHorse}, {@code DASH} and {@code LAST_POSE_CHANGE_TICK}
     * of {@code Camel}; {@code Pig.DATA_SADDLE_ID}, {@code Pig.DATA_BOOST_TIME}, {@code Strider.DATA_SADDLE_ID},
     * {@code Strider.DATA_BOOST_TIME}, {@code Strider.DATA_SUFFOCATING}, {@code HappyGhast.STAYS_STILL}, {@code AbstractNautilus.DASH},
     * {@code TamableAnimal.DATA_FLAGS_ID}, −1 where the release has no such item); evidence for {@link MobData}.
     */
    public int dataId(String accessor) {
        Integer id = dataIds.get(accessor);
        if (id == null) throw new IllegalArgumentException("No data id recorded for " + accessor);
        return id;
    }

    /** The pose with vanilla ordinal {@code ordinal} (the core enum orders its poses differently). */
    public Pose vanillaPose(int ordinal) {
        return vanillaPoses[ordinal];
    }

    public static EntityTypes get() {
        EntityTypes types = loaded;
        if (types != null) return types;
        synchronized (EntityTypes.class) {
            if (loaded == null) loaded = load();
            return loaded;
        }
    }

    /** The type registered under {@code key}, or {@code null} when the protocol has none. */
    public Type type(String key) {
        return types.get(key);
    }

    /** The living type registered under {@code key} as the client constructs it, or {@code null} (not living or not built). */
    public Living living(String key) {
        return living.get(key);
    }

    /** {@code LivingEntity.SLEEPING_DIMENSIONS}. */
    public EntityDimensions sleepingDimensions() {
        return sleeping;
    }

    /** The rideable living type registered under {@code key}, or {@code null}. */
    public Rideable rideable(String key) {
        return rideable.get(key);
    }

    /** {@code Player.getDefaultDimensions(pose)} (from 1.21.9 {@code Avatar}). */
    public EntityDimensions playerDefaultDimensions(Pose pose) {
        return player[pose.ordinal()];
    }

    /**
     * The local player's {@code LivingEntity.getDimensions(pose)}: {@code SLEEPING_DIMENSIONS} when sleeping, else its
     * default dimensions scaled by {@code getScale()}.
     */
    public EntityDimensions playerDimensions(Pose pose, float scale) {
        return pose == Pose.SLEEPING ? sleeping : playerDefaultDimensions(pose).scale(scale);
    }

    /** {@code LivingEntity.getDimensions(pose)} of a rideable living entity: sleeping dimensions, else scaled defaults. */
    public EntityDimensions livingDimensions(Rideable type, boolean baby, Pose pose, float scale) {
        return pose == Pose.SLEEPING ? sleeping : type.defaultDimensions(baby, pose).scale(scale);
    }

    /** Every registered type key. */
    public java.util.Set<String> keys() {
        return types.keySet();
    }

    public int size() {
        return types.size();
    }

    private static EntityTypes load() {
        String path = "/mac/entities/" + NativeRuntime.PROTOCOL + ".bin.gz";
        try (InputStream raw = EntityTypes.class.getResourceAsStream(path)) {
            if (raw == null) throw new IOException("Missing " + path);
            var in = new DataInputStream(new GZIPInputStream(raw));
            if (in.readInt() != MAGIC || in.readUnsignedShort() != 6 || in.readInt() != NativeRuntime.PROTOCOL) {
                throw new IOException("Unexpected entity data header in " + path);
            }
            in.readUTF();
            EntityAttachment[] kinds = EntityAttachment.values();
            if (in.readInt() != kinds.length) throw new IOException("Unexpected attachment kinds in " + path);
            for (EntityAttachment kind : kinds) {
                if (!in.readUTF().equals(kind.name())) throw new IOException("Unexpected attachment kind order in " + path);
            }
            // poses in vanilla order, which differs from the core enum's
            Pose[] poses = new Pose[in.readInt()];
            if (poses.length != Pose.values().length) throw new IOException("Unexpected poses in " + path);
            for (int i = 0; i < poses.length; i++) poses[i] = Pose.valueOf(in.readUTF());
            if (java.util.EnumSet.copyOf(java.util.Arrays.asList(poses)).size() != poses.length) throw new IOException("Repeated pose in " + path);
            int count = in.readInt();
            Map<String, Type> types = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                EntityDimensions dimensions = dimensions(in);
                int updateInterval = in.readInt();
                Map<String, Double> defaults = null;
                if (in.readBoolean()) {
                    var attributes = new LinkedHashMap<String, Double>();
                    for (int n = in.readInt(); n > 0; n--) attributes.put(in.readUTF(), in.readDouble());
                    defaults = Collections.unmodifiableMap(attributes);
                }
                types.put(key, new Type(key, dimensions, updateInterval, defaults));
            }
            EntityDimensions[] player = new EntityDimensions[poses.length];
            for (Pose pose : poses) player[pose.ordinal()] = dimensions(in);
            EntityDimensions sleeping = dimensions(in);
            Map<String, Rideable> rideable = new HashMap<>();
            for (int n = in.readInt(); n > 0; n--) {
                Rideable entry = new Rideable(in.readUTF());
                entry.maxHeadYRot = in.readInt();
                int rearing = in.readUnsignedByte();
                entry.canRear = rearing == 2 ? null : rearing == 1;
                entry.pushedByFluid = in.readBoolean();
                entry.canFloat = in.readBoolean();
                for (int age = 0; age < 2; age++) {
                    entry.baby[age] = in.readBoolean();
                    entry.ageScale[age] = in.readFloat();
                    entry.dimensions[age] = new EntityDimensions[poses.length];
                    for (Pose pose : poses) entry.dimensions[age][pose.ordinal()] = dimensions(in);
                }
                rideable.put(entry.key, entry);
            }
            Map<String, Integer> dataIds = new HashMap<>();
            for (int n = in.readInt(); n > 0; n--) dataIds.put(in.readUTF(), in.readInt());
            Map<String, Living> living = new HashMap<>();
            for (int n = in.readInt(); n > 0; n--) {
                Living entry = new Living(in.readUTF());
                if (!in.readBoolean()) continue;
                entry.entityClass = in.readUTF();
                entry.canBeBaby = in.readBoolean();
                entry.ageScale[0] = in.readFloat();
                entry.ageScale[1] = in.readFloat();
                for (int age = 0; age < (entry.canBeBaby ? 2 : 1); age++) {
                    entry.dimensions[age] = new EntityDimensions[poses.length];
                    for (Pose pose : poses) entry.dimensions[age][pose.ordinal()] = shortDimensions(in);
                }
                var items = new HashMap<String, Integer>();
                for (int k = in.readInt(); k > 0; k--) items.put(in.readUTF(), in.readInt());
                entry.items = Map.copyOf(items);
                living.put(entry.key, entry);
            }
            if (in.read() != -1) throw new IOException("Trailing entity data in " + path);
            return new EntityTypes(Map.copyOf(types), player, sleeping, Map.copyOf(rideable), poses, Map.copyOf(dataIds), Map.copyOf(living));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load bundled vanilla data " + path, failure);
        }
    }

    /** Size, eye height and fixed flag (no attachment points). */
    private static EntityDimensions shortDimensions(DataInputStream in) throws IOException {
        float width = in.readFloat(), height = in.readFloat(), eyeHeight = in.readFloat();
        return new EntityDimensions(width, height, eyeHeight, new EntityAttachments(new EnumMap<>(EntityAttachment.class)), in.readBoolean());
    }

    private static EntityDimensions dimensions(DataInputStream in) throws IOException {
        float width = in.readFloat(), height = in.readFloat(), eyeHeight = in.readFloat();
        boolean fixed = in.readBoolean();
        var points = new EnumMap<EntityAttachment, List<Vec3d>>(EntityAttachment.class);
        for (EntityAttachment kind : EntityAttachment.values()) {
            int n = in.readInt();
            var list = new ArrayList<Vec3d>(n);
            for (int i = 0; i < n; i++) list.add(new Vec3d(in.readDouble(), in.readDouble(), in.readDouble()));
            points.put(kind, list);
        }
        return new EntityDimensions(width, height, eyeHeight, new EntityAttachments(points), fixed);
    }
}
