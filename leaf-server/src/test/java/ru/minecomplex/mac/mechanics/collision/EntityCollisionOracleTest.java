package ru.minecomplex.mac.mechanics.collision;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.replica.CompensatedEntities;
import ru.minecomplex.mac.entity.EntityMotions;
import ru.minecomplex.mac.entity.EntityTypes;
import ru.minecomplex.mac.fixture.outbound.EntityAttributes;
import ru.minecomplex.mac.fixture.outbound.EntityPassengers;
import ru.minecomplex.mac.fixture.outbound.EntityPositionSync;
import ru.minecomplex.mac.fixture.outbound.EntitySpawn;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.engine.Unmodeled;

import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MAEC (EntityCollisionSamples): the real {@code getEntityCollisions(source, box)} over a real entity section storage of
 * boats, rafts and happy ghasts. {@link EntityMotions} must give every entity's box and, whenever it reports no gap,
 * exactly the client's boxes in the client's order; with a gap its boxes are a part of the client's.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class EntityCollisionOracleTest {

    static final String[] KINDS = { "minecraft:oak_boat", "minecraft:bamboo_raft", "minecraft:oak_chest_boat", "minecraft:happy_ghast" };

    static final int PLAYER = 1;

    static Aabb box(DataInputStream in) throws Exception {
        return new Aabb(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
    }

    static void bits(Aabb expected, Aabb actual, String context) {
        double[] e = { expected.minX(), expected.minY(), expected.minZ(), expected.maxX(), expected.maxY(), expected.maxZ() };
        double[] a = { actual.minX(), actual.minY(), actual.minZ(), actual.maxX(), actual.maxY(), actual.maxZ() };
        for (int i = 0; i < 6; i++) {
            assertEquals(Double.doubleToLongBits(e[i]), Double.doubleToLongBits(a[i]), context + " " + expected + " vs " + actual);
        }
    }

    @Test
    void collisionQueriesSeeTheClientsEntities() throws Exception {
        var types = EntityTypes.get();
        String path = "/vanilla/777/entity-collisions.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        int exact = 0, exactHits = 0, gapped = 0;
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414543, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            for (int scenarios = in.readInt(), s = 0; s < scenarios; s++) {
                String context0 = "26.3 scenario " + s;
                var entities = new CompensatedEntities(PLAYER);
                int count = in.readInt();
                String[] typeOf = new String[count];
                for (int i = 0; i < count; i++) {
                    int kind = in.readUnsignedByte();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    int flags = in.readUnsignedByte();
                    double scale = in.readDouble();
                    int id = 100 + i;
                    typeOf[i] = KINDS[kind];
                    entities.spawn(Wire.of(new EntitySpawn(id, KINDS[kind], x, y, z, 0.0F, 0.0F, 0.0F, 0, false, 0.0, 0.0, 0.0)), chunk -> false);
                    if (kind == 3 && flags != 0) {
                        Map<Integer, Object> items = new HashMap<>();
                        if ((flags & 1) != 0)
                            items.put(16, true);
                        if ((flags & 4) != 0)
                            items.put(types.dataId("HappyGhast.STAYS_STILL"), true);
                        boolean dead = (flags & 2) != 0;
                        entities.syncedData(Wire.of(new EntitySyncedData(id, dead ? EntitySyncedData.HEALTH : 0, (byte) 0, false, "", (byte) 0, 0.0F, EntitySyncedData.encodeMobItems(items))));
                        if ((flags & 8) != 0) {
                            entities.attributes(Wire.of(EntityAttributes.of(id, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:scale", scale, List.of())))));
                            // the fixture refreshes the dimensions at once (a client does at the end of the entity's tick)
                            entities.motions().refreshDimensions(id);
                        }
                    }
                }
                for (int moves = in.readInt(), m = 0; m < moves; m++) {
                    int index = in.readInt();
                    // a position sync to an entity that does not tick snaps it (setPos)
                    entities.motion(Wire.of(EntityPositionSync.linear(100 + index, in.readDouble(), in.readDouble(), in.readDouble(), 0.0F, 0.0F, false)), chunk -> false);
                }
                Map<Integer, List<Integer>> riders = new HashMap<>();
                for (int links = in.readInt(), l = 0; l < links; l++) {
                    int passenger = in.readInt(), vehicle = in.readInt();
                    int passengerId = passenger < 0 ? PLAYER : 100 + passenger, vehicleId = 100 + vehicle;
                    var list = riders.computeIfAbsent(vehicleId, ignored -> new ArrayList<>());
                    list.add(passengerId);
                    entities.apply(Wire.of(new EntityPassengers(vehicleId, list.stream().mapToInt(Integer::intValue).toArray())));
                }
                var motions = entities.motions();
                for (int i = 0; i < count; i++) bits(box(in), motions.boxOf(100 + i), context0 + " entity " + i + " " + typeOf[i]);
                var playerPosition = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                Aabb playerBox = box(in);
                for (int queries = in.readInt(), q = 0; q < queries; q++) {
                    int source = in.readInt();
                    Aabb query = box(in);
                    var expected = new ArrayList<Aabb>();
                    for (int n = in.readInt(), i = 0; i < n; i++) expected.add(box(in));
                    var collider = source < 0 ? EntityMotions.Collider.player(PLAYER, playerPosition) : motions.vehicleCollider(100 + source, typeOf[source], Vec3d.ZERO);
                    var gaps = EnumSet.noneOf(Unmodeled.class);
                    var actual = motions.collisionBoxes(query, collider, playerBox, gaps);
                    String context = context0 + " query " + q + " source " + source;
                    if (gaps.isEmpty()) {
                        assertEquals(expected.size(), actual.size(), context + " " + expected + " vs " + actual);
                        for (int i = 0; i < expected.size(); i++) bits(expected.get(i), actual.get(i), context + " shape " + i);
                        exact++;
                        if (!expected.isEmpty())
                            exactHits++;
                    } else {
                        // what is reported is a part of the client's answer, in its order
                        int at = 0;
                        for (Aabb hit : actual) {
                            while (at < expected.size() && !expected.get(at).equals(hit)) at++;
                            assertTrue(at < expected.size(), context + " reported " + hit + " not in " + expected);
                            at++;
                        }
                        gapped++;
                    }
                }
            }
            assertEquals(-1, in.read());
        }
        assertTrue(exactHits > 300, "26.3 exact non-empty " + exactHits + " of " + exact + ", gapped " + gapped);
        assertTrue(gapped < exact, "26.3 gapped " + gapped + " exact " + exact);
    }
}
