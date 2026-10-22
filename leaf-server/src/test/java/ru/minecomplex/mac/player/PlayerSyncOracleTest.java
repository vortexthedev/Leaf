package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.fixture.ItemSampleCodec;
import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.ClaimedStack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.effect.EffectInstance;
import ru.minecomplex.mac.entity.effect.MobEffect;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.replica.CompensatedInventory;
import ru.minecomplex.mac.inventory.EquipmentSlot;
import ru.minecomplex.mac.fixture.ItemComponentSample;
import net.minecraft.world.item.ItemStack;
import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.fixture.inbound.ContainerClosed;
import ru.minecomplex.mac.fixture.inbound.HeldSlot;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerAction;
import ru.minecomplex.mac.fixture.inbound.Swing;
import ru.minecomplex.mac.fixture.inbound.TeleportAccept;
import ru.minecomplex.mac.fixture.outbound.Abilities;
import ru.minecomplex.mac.fixture.outbound.CloseContainer;
import ru.minecomplex.mac.fixture.outbound.ContainerContent;
import ru.minecomplex.mac.fixture.outbound.ContainerSlot;
import ru.minecomplex.mac.fixture.outbound.CursorItem;
import ru.minecomplex.mac.fixture.outbound.OpenContainer;
import ru.minecomplex.mac.fixture.outbound.PlayerInventorySlot;
import ru.minecomplex.mac.fixture.outbound.SetHeldSlot;
import ru.minecomplex.mac.fixture.outbound.EffectRemove;
import ru.minecomplex.mac.fixture.outbound.EffectUpdate;
import ru.minecomplex.mac.fixture.outbound.EntityAttributes;
import ru.minecomplex.mac.fixture.outbound.EntitySyncedData;
import ru.minecomplex.mac.fixture.outbound.EntityVelocity;
import ru.minecomplex.mac.fixture.outbound.Explosion;
import ru.minecomplex.mac.fixture.outbound.GameEvent;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.PlayerHealth;
import ru.minecomplex.mac.fixture.outbound.PlayerInfoGameMode;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.fixture.outbound.PlayerRotation;
import ru.minecomplex.mac.fixture.outbound.Respawn;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * MAPS v1 ({@code tools/datagen} {@code PlayerSyncSamples}): chains of clientbound player packets applied by the real
 * {@code ClientPacketListener} handlers to a real {@code LocalPlayer}, on vanilla 26.3. The same chain runs
 * through {@link ClientPlayerHandlers} from the initial state only, and every recorded field and reply is compared bit
 * for bit after every operation.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PlayerSyncOracleTest {

    static {
        ru.minecomplex.mac.packet.NativeRuntime.ensure();
    }

    static final String[] EFFECT_NAMES = { "JUMP_BOOST", "LEVITATION", "SLOW_FALLING", "WEAVING", "DOLPHINS_GRACE", "BLINDNESS" };

    static final MobEffect[] EFFECTS = { MobEffect.JUMP_BOOST, MobEffect.LEVITATION, MobEffect.SLOW_FALLING, MobEffect.WEAVING, MobEffect.DOLPHINS_GRACE, MobEffect.BLINDNESS };

    @Test
    void chainedHandlersMatchVanilla() throws IOException {
        verify("player-sync", false);
    }

    /**
     * Native item behaviour facts used to resolve fixture stacks.
     */
    private static ru.minecomplex.mac.inventory.item.ItemBehavior items;

    static void verify(String stem, boolean catalog) throws IOException {
        String path = "/vanilla/777/" + stem + ".bin.gz";
        var stream = PlayerSyncOracleTest.class.getResourceAsStream(path);
        if (stream == null)
            throw new IOException("Missing " + path);
        items = ru.minecomplex.mac.inventory.item.ItemBehavior.get();
        ClientPlayerHandlers handlers = new ClientPlayerHandlers();
        ClientPlayerActions actions = new ClientPlayerActions();
        ClientItemUse itemUse = new ClientItemUse();
        int operations = 0;
        int[] kinds = new int[43];
        int[] coverage = new int[2];
        try (var in = new DataInputStream(new GZIPInputStream(stream))) {
            assertEquals(0x4d415053, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int scenarios = in.readInt();
            var names = new java.util.HashSet<String>();
            for (int scenario = 0; scenario < scenarios; scenario++) {
                String scenarioName = catalog ? in.readUTF() : Integer.toString(scenario);
                if (catalog)
                    names.add(scenarioName);
                boolean filledBundle = false;
                boolean totemEffects = false, extractedBundle = false;
                State initial = State.read(in);
                PlayerState p = new PlayerState();
                initial.applyMotion(p);
                LocalPlayerState local = new LocalPlayerState();
                ClientPlayerData d = new ClientPlayerData();
                d.localMode = initial.localMode < 0 ? null : GameType.byId(initial.localMode);
                d.infoPresent = true;
                d.infoMode = GameType.byId(initial.infoMode);
                Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
                initial.check(itemUse, p, local, d, gaps, "26.3" + " scenario " + scenario + " initial");
                int count = in.readInt();
                State previous = initial;
                for (int op = 0; op < count; op++) {
                    if (previous.selectionPending()) {
                        // a hotbar key or scroll the packets have not announced yet (inherent, see inventory.md): the
                        // client's selected slot is taken from vanilla so the rest is compared exactly
                        d.inventory.setSelected(previous.selected());
                    }
                    int kind = in.readUnsignedByte();
                    kinds[kind]++;
                    OutboundEvent event = null;
                    switch(kind) {
                        case 0 ->
                            {
                                int id = in.readInt();
                                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                                double dx = in.readDouble(), dy = in.readDouble(), dz = in.readDouble();
                                float yRot = in.readFloat(), xRot = in.readFloat();
                                event = new PlayerPosition(id, x, y, z, dx, dy, dz, yRot, xRot, in.readInt());
                            }
                        case 1 ->
                            {
                                float yRot = in.readFloat();
                                boolean relativeY = in.readBoolean();
                                float xRot = in.readFloat();
                                event = new PlayerRotation(yRot, relativeY, xRot, in.readBoolean());
                            }
                        case 2 ->
                            event = new EntityVelocity(1, in.readDouble(), in.readDouble(), in.readDouble());
                        case 3 ->
                            {
                                boolean hasKnockback = in.readBoolean();
                                event = new Explosion(1.5, 70.0, -2.5, hasKnockback, in.readDouble(), in.readDouble(), in.readDouble());
                            }
                        case 4 ->
                            {
                                boolean invulnerable = in.readBoolean(), flying = in.readBoolean(), mayfly = in.readBoolean(), instabuild = in.readBoolean();
                                event = new Abilities(invulnerable, flying, mayfly, instabuild, in.readFloat(), in.readFloat());
                            }
                        case 5 ->
                            event = new GameEvent(GameEvent.CHANGE_GAME_MODE, in.readFloat());
                        case 6 ->
                            {
                                String name = in.readUTF();
                                int amplifier = in.readInt(), duration = in.readInt();
                                boolean ambient = in.readBoolean(), visible = in.readBoolean(), showIcon = in.readBoolean(), blend = in.readBoolean();
                                event = new EffectUpdate(1, key(name), amplifier, duration, ambient, visible, showIcon, blend);
                            }
                        case 7 ->
                            event = new EffectRemove(1, key(in.readUTF()));
                        case 8 ->
                            {
                                int snapshots = in.readInt();
                                List<ru.minecomplex.mac.entity.attribute.AttributeSnapshot> list = new ArrayList<>();
                                for (int i = 0; i < snapshots; i++) {
                                    String attribute = in.readUTF();
                                    double base = in.readDouble();
                                    int modifiers = in.readInt();
                                    List<ru.minecomplex.mac.entity.attribute.AttributeSnapshot.Modifier> modifierList = new ArrayList<>();
                                    for (int m = 0; m < modifiers; m++) {
                                        modifierList.add(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot.Modifier(in.readUTF(), in.readDouble(), in.readUnsignedByte()));
                                    }
                                    list.add(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot(attribute, base, modifierList));
                                }
                                event = EntityAttributes.of(1, list);
                            }
                        case 9 ->
                            event = new PlayerHealth(in.readFloat(), in.readInt(), in.readFloat());
                        case 10 ->
                            event = new PlayerInfoGameMode(false, true, in.readInt());
                        case 11 ->
                            {
                            }
                        case 12 ->
                            p.setSprinting(in.readBoolean());
                        case 13 ->
                            p.passenger = in.readBoolean();
                        case 14 ->
                            {
                                // vanilla setPos(1, y, z) then setPos(-0.0, y, z): continue from the stored vector
                                p.position = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
                                int id = in.readInt();
                                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                                double dx = in.readDouble(), dy = in.readDouble(), dz = in.readDouble();
                                float yRot = in.readFloat(), xRot = in.readFloat();
                                event = new PlayerPosition(id, x, y, z, dx, dy, dz, yRot, xRot, in.readInt());
                            }
                        case 15 ->
                            {
                                int present = in.readInt();
                                byte flags = in.readByte();
                                boolean noGravity = in.readBoolean();
                                String pose = in.readUTF();
                                byte livingFlags = in.readByte();
                                event = new EntitySyncedData(1, present, flags, noGravity, pose, livingFlags, in.readFloat());
                            }
                        case 16 ->
                            {
                                int keep = in.readInt();
                                event = new Respawn("minecraft:overworld", keep, -64, 384, in.readInt());
                            }
                        case 17 ->
                            {
                                in.readInt();
                                event = new JoinGame(1, "minecraft:overworld", -64, 384, 10, in.readInt());
                            }
                        case 19 ->
                            {
                                ClientPlayerHandlers.Result zero = handlers.apply(p, local, d, Wire.of(EntityAttributes.of(1, List.of(new ru.minecomplex.mac.entity.attribute.AttributeSnapshot("minecraft:movement_efficiency", -0.0, List.of()))), Wire.FACTS));
                                gaps.removeAll(zero.resolves());
                                gaps.addAll(zero.gaps());
                                int keep = in.readInt();
                                event = new Respawn("minecraft:overworld", keep, -64, 384, in.readInt());
                            }
                        case 20 ->
                            {
                                p.passenger = in.readBoolean();
                                int id = in.readInt();
                                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                                double dx = in.readDouble(), dy = in.readDouble(), dz = in.readDouble();
                                float yRot = in.readFloat(), xRot = in.readFloat();
                                event = new PlayerPosition(id, x, y, z, dx, dy, dz, yRot, xRot, in.readInt());
                            }
                        case 21 ->
                            {
                                int containerId = in.readInt(), stateId = in.readInt(), size = in.readInt();
                                List<WireItem> stacks = new ArrayList<>();
                                for (int i = 0; i < size; i++) stacks.add(wireItem(in));
                                event = ContainerContent.of(containerId, stateId, stacks, wireItem(in));
                            }
                        case 27 ->
                            {
                                int containerId = in.readInt();
                                if (containerId >= 0) {
                                    int stateId = in.readInt(), size = in.readInt();
                                    List<WireItem> stacks = new ArrayList<>();
                                    for (int i = 0; i < size; i++) stacks.add(wireItem(in));
                                    event = ContainerContent.of(containerId, stateId, stacks, wireItem(in));
                                } else {
                                    int container = in.readInt(), stateId = in.readInt(), slot = in.readInt();
                                    event = ContainerSlot.of(container, stateId, slot, wireItem(in));
                                }
                            }
                        case 22 ->
                            {
                                int containerId = in.readInt(), stateId = in.readInt(), slot = in.readInt();
                                event = ContainerSlot.of(containerId, stateId, slot, wireItem(in));
                            }
                        case 23, 38 ->
                            {
                                WireItem stack = wireItem(in);
                                event = PlayerInventorySlot.of(in.readInt(), stack);
                            }
                        case 24 ->
                            event = new SetHeldSlot(in.readInt());
                        case 25 ->
                            event = CursorItem.of(wireItem(in));
                        case 26 ->
                            {
                                int id = in.readInt();
                                event = new OpenContainer(id, in.readInt());
                            }
                        case 28 ->
                            event = new CloseContainer(in.readInt());
                        case 34 ->
                            event = new ru.minecomplex.mac.fixture.outbound.EntityEvent(1, 9);
                        case 42 ->
                            event = new ru.minecomplex.mac.fixture.outbound.EntityEvent(1, in.readUnsignedByte());
                        case 35 ->
                            {
                                String group = in.readUTF();
                                event = new ru.minecomplex.mac.fixture.outbound.ItemCooldown(group, in.readInt());
                            }
                        case 36 ->
                            {
                                // PlayerModel.Hypothesis.livingTickStart
                                ClientItemUse.livingTick(d, gaps);
                            }
                        case 37 ->
                            {
                                // PlayerModel.Hypothesis.playerTickEnd
                                ClientItemUse.tickCooldowns(d);
                                if (!d.useOutcomeOpen())
                                    gaps.remove(Unmodeled.ITEM_USE_OUTCOME);
                            }
                        case 29, 30, 31, 32, 33, 39, 40, 41 ->
                            {
                                // client actions: their packets are the recorded replies
                            }
                        case 18 ->
                            {
                                local.send.lastSentInput = ru.minecomplex.mac.player.state.PlayerInput.fromMask(in.readUnsignedByte());
                                local.input = ru.minecomplex.mac.player.state.PlayerInput.fromMask(in.readUnsignedByte());
                            }
                        default ->
                            throw new IOException("Unknown operation " + kind);
                    }
                    String context = "26.3" + " scenario " + scenarioName + " op " + op + " kind " + kind + " " + event;
                    List<InboundEvent> replies = List.of();
                    if (System.getenv("MAC_DEBUG_OP") != null && context.startsWith(System.getenv("MAC_DEBUG_OP"))) {
                        System.out.println("DEBUG " + context + " main=" + ClientItemUse.hand(d, false) + " off=" + ClientItemUse.hand(d, true) + " selected=" + d.inventory.selected() + " using=" + d.usingItem + " useItem=" + d.useItem + " slot/version=" + d.useItemSlot + "/" + d.useItemVersion + " unknown=" + java.util.stream.IntStream.range(0, CompensatedInventory.SIZE).filter(d.inventory::unknown).boxed().toList() + " mayLose=" + java.util.stream.IntStream.range(0, CompensatedInventory.SIZE).filter(i -> d.inventory.mayLose(i) > 0).boxed().toList() + " menu=" + d.inventory.openContainerId() + " gaps=" + gaps);
                    }
                    if (event != null) {
                        ClientPlayerHandlers.Result result = handlers.apply(p, local, d, Wire.of(event, Wire.FACTS));
                        replies = Wire.in(result.replies());
                        gaps.removeAll(result.resolves());
                        gaps.addAll(result.gaps());
                        if (result.player() != null) {
                            p = result.player();
                            local = result.local();
                        }
                        if (event instanceof JoinGame) {
                            // the server's ADD_PLAYER of the own entry in the new session
                            handlers.apply(p, local, d, Wire.of(new PlayerInfoGameMode(true, false, 0), Wire.FACTS));
                        }
                    }
                    State after = State.read(in);
                    int bundleNumerator = catalog ? in.readInt() : 0;
                    int bundleDenominator = catalog ? in.readInt() : 1;
                    int totemMask = catalog ? in.readInt() : 0;
                    totemEffects |= totemMask == 7;
                    if (catalog) {
                        assertTrue(bundleNumerator >= 0 && bundleDenominator > 0, context + " bundle fraction");
                        extractedBundle |= kind == 39 && filledBundle && bundleNumerator == 0;
                        filledBundle |= bundleNumerator > 0;
                    }
                    if (kind >= 29 && kind <= 33 || kind >= 39 && kind <= 41) {
                        // the client's own packets: what the model learns the client did
                        int sent = in.readInt();
                        if (System.getenv("MAC_DEBUG_OP") != null && context.startsWith(System.getenv("MAC_DEBUG_OP"))) {
                            System.out.println("DEBUG " + context + " main=" + ClientItemUse.hand(d, false) + " off=" + ClientItemUse.hand(d, true) + " selected=" + d.inventory.selected() + " food=" + d.food + " using=" + d.usingItem + " instabuild=" + p.abilities.instabuild + " info=" + d.infoPresent + "/" + d.infoMode + " feet=" + d.inventory.item(36) + " gaps=" + gaps);
                        }
                        for (int i = 0; i < sent; i++) {
                            ClientPlayerActions.Result result = actions.apply(p, d, Wire.of(clientPacket(in, context), Wire.FACTS));
                            assertEquals(Set.of(), result.violations(), context + " vanilla packets");
                            gaps.removeAll(result.resolves());
                            gaps.addAll(result.gaps());
                        }
                        {
                            // the slot is sent before every drop: nothing stays bounded
                            for (int slot = 0; slot < 9; slot++) assertEquals(0, d.inventory.mayLose(slot), context + " exact drops " + slot);
                        }
                        if (kind == 29) {
                            ClientPlayerActions.Result result = actions.tickEnded(p, d);
                            assertEquals(Set.of(), result.violations(), context + " vanilla tick");
                            gaps.removeAll(result.resolves());
                            gaps.addAll(result.gaps());
                        }
                        if (gaps.contains(Unmodeled.RIPTIDE)) {
                            // the riptide launch is not simulated: continue from vanilla's motion
                            after.applyMotion(p);
                            p.autoSpinAttack = after.autoSpinAttack();
                            gaps.remove(Unmodeled.RIPTIDE);
                        }
                        after.check(itemUse, p, local, d, gaps, context);
                        operations++;
                        previous = after;
                        // counted where compared: the inventory known, the use state certain
                        boolean compared = !gaps.contains(Unmodeled.INVENTORY_CONTAINER) && !d.useOutcomeInventory;
                        if (compared && after.usingItem() && !gaps.contains(Unmodeled.ITEM_USE_TIMING))
                            coverage[0]++;
                        if (compared && !after.cooldowns().isEmpty())
                            coverage[1]++;
                        continue;
                    }
                    if (kind == 16 || kind == 17 || kind == 19) {
                        assertTrue(gaps.contains(Unmodeled.RESPAWN_POSITION), context);
                        // resetPos/spawn position: continue from vanilla's, the gap stays until an absolute teleport
                        p.position = after.position();
                        p.oldPosition = after.oldPosition();
                        bits(after.health(), d.health, context + " recreated health");
                    }
                    if (kind == 11) {
                        // client-side perturbation through vanilla setters: continue from the values they stored
                        after.applyMotion(p);
                    }
                    after.check(itemUse, p, local, d, gaps, context);
                    if (event instanceof EntitySyncedData synced && synced.has(EntitySyncedData.HEALTH)) {
                        // assignValues stores the health as sent
                        bits(after.health(), d.health, context + " synced health");
                    }
                    if (kind == 9) {
                        // right after hurtTo: setHealth stored Mth.clamp(health, 0, maxHealth) with the current maximum
                        bits(after.health(), Math.min(d.health, after.maxHealth()), context + " stored health");
                    }
                    int replyCount = in.readInt();
                    assertEquals(replyCount, replies.size(), context + " replies " + replies);
                    for (InboundEvent reply : replies) checkReply(in, reply, context);
                    operations++;
                    previous = after;
                    // counted where compared: the inventory known, the use state certain
                    boolean compared = !gaps.contains(Unmodeled.INVENTORY_CONTAINER) && !d.useOutcomeInventory;
                    if (compared && after.usingItem() && !gaps.contains(Unmodeled.ITEM_USE_TIMING))
                        coverage[0]++;
                    if (compared && !after.cooldowns().isEmpty())
                        coverage[1]++;
                }
                if (catalog && scenarioName.startsWith("bundle_manipulation/")) {
                    assertTrue(filledBundle, scenarioName + " must really insert contents");
                    assertTrue(extractedBundle, scenarioName + " must really extract contents");
                }
                if (catalog && scenarioName.startsWith("totem_recovery/"))
                    assertTrue(totemEffects, scenarioName + " real default death-protection effects applied");
            }
            if (catalog)
                ItemServerOutcomeOracleTest.checkNames(names);
            assertEquals(-1, in.read(), "trailing data");
        }
        if (catalog) {
            assertTrue(operations > 500, "Named lifecycle operations");
            return;
        }
        assertEquals(80 * 120, operations);
        assertTrue(coverage[0] > 100, "states using an item " + coverage[0]);
        assertTrue(coverage[1] > 100, "states with cooldowns " + coverage[1]);
        for (int kind = 0; kind < 42; kind++) assertTrue(kinds[kind] > 100, "operation kind " + kind + " covered " + kinds[kind]);
    }

    /**
     * A stack as {@code PlayerSyncSamples.writeStack} records it: key, count, set/removed bits, set values.
     */
    private static WireItem wireItem(DataInputStream in) throws IOException {
        String item = in.readUTF();
        int count = in.readInt();
        int set = in.readInt(), removed = in.readInt();
        return new WireItem(item, count, set, removed, ru.minecomplex.mac.fixture.ItemSampleCodec.read(in, set));
    }

    /**
     * A resolved vanilla stack: {@code null} when empty.
     */
    record Resolved(String item, int count, ItemComponentSample components, boolean nextDamageWillBreak) {

        static Resolved read(DataInputStream in) throws IOException {
            if (in.readBoolean())
                return null;
            String item = in.readUTF();
            int count = in.readInt();
            ItemComponentSample components = ru.minecomplex.mac.fixture.ItemSampleCodec.read(in, in.readInt());
            return new Resolved(item, count, components, in.readBoolean());
        }

        void check(ItemStack actual, String context) {
            if (this == null)
                return;
            assertEquals(item, Stacks.key(actual), context + " item");
            assertEquals(count, Stacks.count(actual), context + " count");
            // every modelled component as vanilla resolved it (records compare floats by bits)
            assertEquals(components, FixtureItems.components(actual), context + " components");
            assertEquals(nextDamageWillBreak, ru.minecomplex.mac.inventory.item.ItemMechanics.nextDamageWillBreak(actual), context + " nextDamageWillBreak");
        }
    }

    /**
     * A hotbar slot an unknown drop may have reduced: same stack, count within the model's bounds.
     */
    static void checkBounded(Resolved expected, CompensatedInventory inventory, int slot, String context) {
        ItemStack bound = inventory.item(slot);
        int count = expected == null ? 0 : expected.count();
        assertTrue(count >= inventory.minCount(slot) && count <= Stacks.count(bound), context + " count " + count + " outside [" + inventory.minCount(slot) + ", " + Stacks.count(bound) + "]");
        if (expected != null)
            expected.check(Stacks.withCount(bound, count), context);
    }

    static void checkStack(Resolved expected, ItemStack actual, String context) {
        assertEquals(expected == null, actual.isEmpty(), context + " empty");
        if (expected != null)
            expected.check(actual, context);
    }

    private static String key(String name) {
        return "minecraft:" + name.toLowerCase();
    }

    private static void checkReply(DataInputStream in, InboundEvent reply, String context) throws IOException {
        int tag = in.readUnsignedByte();
        switch(tag) {
            case 10 ->
                {
                    TeleportAccept accept = assertInstanceOf(TeleportAccept.class, reply, context);
                    assertEquals(in.readInt(), accept.id(), context);
                    assertEquals(in.readBoolean(), accept.hasPosition(), context);
                    bits(in.readDouble(), accept.x(), context + " accept x");
                    bits(in.readDouble(), accept.y(), context + " accept y");
                    bits(in.readDouble(), accept.z(), context + " accept z");
                    bits(in.readFloat(), accept.yRot(), context + " accept yRot");
                    bits(in.readFloat(), accept.xRot(), context + " accept xRot");
                }
            case 0 ->
                {
                    Movement move = assertInstanceOf(Movement.class, reply, context);
                    assertEquals(in.readBoolean(), move.hasPosition(), context);
                    assertEquals(in.readBoolean(), move.hasRotation(), context);
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float yRot = in.readFloat(), xRot = in.readFloat();
                    if (move.hasPosition()) {
                        bits(x, move.x(), context + " reply x");
                        bits(y, move.y(), context + " reply y");
                        bits(z, move.z(), context + " reply z");
                    }
                    if (move.hasRotation()) {
                        bits(yRot, move.yRot(), context + " reply yRot");
                        bits(xRot, move.xRot(), context + " reply xRot");
                    }
                    assertEquals(in.readBoolean(), move.onGround(), context);
                    assertEquals(in.readBoolean(), move.horizontalCollision(), context);
                }
            case 13 ->
                assertEquals(new ContainerClosed(in.readInt()), assertInstanceOf(ContainerClosed.class, reply, context), context);
            default ->
                throw new AssertionError(context + " unexpected reply tag " + tag + (tag == 4 ? " " + in.readUTF() : ""));
        }
    }

    /**
     * A serverbound packet the vanilla client sent for a client action ({@code PlayerSyncSamples.writeReply}).
     */
    private static InboundEvent clientPacket(DataInputStream in, String context) throws IOException {
        int tag = in.readUnsignedByte();
        return switch(tag) {
            case 11 ->
                new HeldSlot(in.readInt());
            case 12 ->
                PlayerAction.of(PlayerAction.Action.valueOf(in.readUTF()));
            case 13 ->
                new ContainerClosed(in.readInt());
            case 14 ->
                new Swing(Swing.Hand.valueOf(in.readUTF()));
            case 16 ->
                {
                    int containerId = in.readInt(), stateId = in.readInt(), slot = in.readInt(), button = in.readInt(), type = in.readInt();
                    int count = in.readInt();
                    Map<Integer, ru.minecomplex.mac.fixture.ClaimedStack> changed = new java.util.HashMap<>();
                    for (int i = 0; i < count; i++) {
                        int index = in.readInt();
                        changed.put(index, claim(in));
                    }
                    yield ru.minecomplex.mac.fixture.inbound.ContainerClick.of(containerId, stateId, slot, button, type, changed, claim(in));
                }
            case 17 ->
                {
                    int slot = in.readInt();
                    yield ru.minecomplex.mac.fixture.inbound.CreativeSlot.of(slot, wireItem(in));
                }
            case 15 ->
                {
                    boolean offHand = in.readUTF().equals("OFF_HAND");
                    int sequence = in.readInt();
                    yield new ru.minecomplex.mac.fixture.inbound.UseItem(offHand, sequence, in.readFloat(), in.readFloat());
                }
            default ->
                throw new AssertionError(context + " unexpected client packet tag " + tag + (tag == 4 ? " " + in.readUTF() : ""));
        };
    }

    /**
     * A click's stack ({@code PlayerSyncSamples.writeClaim}).
     */
    private static ru.minecomplex.mac.fixture.ClaimedStack claim(DataInputStream in) throws IOException {
        if (!in.readBoolean())
            return ru.minecomplex.mac.fixture.ClaimedStack.exact(wireItem(in));
        if (!in.readBoolean())
            return ru.minecomplex.mac.fixture.ClaimedStack.EMPTY;
        String item = in.readUTF();
        int count = in.readInt();
        return ru.minecomplex.mac.fixture.ClaimedStack.hashed(item, count, in.readInt(), in.readInt());
    }

    static void bits(double expected, double actual, String context) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual), context + ": " + expected + " vs " + actual);
    }

    static void bits(float expected, float actual, String context) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), context + ": " + expected + " vs " + actual);
    }

    /**
     * One recorded vanilla player state ({@code PlayerSyncSamples.writeState}).
     */
    record State(Vec3d position, Vec3d oldPosition, Vec3d delta, float yRot, float xRot, boolean sprinting, boolean swimming, boolean fallFlying, String pose, float width, float height, boolean noGravity, boolean autoSpinAttack, boolean onGround, double fallDistance, boolean firstTick, boolean wasSprinting, boolean wasShiftKeyDown, int lastSentInput, Vec3d last, float yRotLast, float xRotLast, int positionReminder, boolean lastOnGround, boolean lastHorizontalCollision, boolean autoJumpEnabled, boolean crouching, boolean wasFallFlying, int sprintTriggerTime, int jumpTriggerTime, int autoJumpTime, int waterVisionTime, int jumpRidingTicks, float jumpRidingScale, int input, boolean invulnerable, boolean flying, boolean mayfly, boolean instabuild, float flyingSpeed, float walkingSpeed, int localMode, float health, float maxHealth, boolean alive, boolean deadOrDying, int food, float saturation, int infoMode, Effect[] effects, Resolved[] slots, int selected, int carriedIndex, boolean selectionPending, Resolved carried, boolean usingItem, boolean usedOffHand, Resolved useItem, int ticksUsing, int cooldownClock, Map<String, List<Integer>> cooldowns, int stateId, int containerId, int openStateId, Resolved[] menu, Map<String, Boolean> canGlide, boolean walksOnPowderSnow, List<AttributeValue> attributes) {

        record Effect(boolean present, int duration, int amplifier, boolean ambient, boolean visible, boolean showIcon, boolean hidden) {
        }

        record AttributeValue(String name, boolean present, double base, double value) {
        }

        static State read(DataInputStream in) throws IOException {
            Vec3d position = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
            Vec3d old = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
            Vec3d delta = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
            float yRot = in.readFloat(), xRot = in.readFloat();
            boolean sprinting = in.readBoolean(), swimming = in.readBoolean(), fallFlying = in.readBoolean();
            String pose = in.readUTF();
            float width = in.readFloat(), height = in.readFloat();
            boolean noGravity = in.readBoolean(), autoSpinAttack = in.readBoolean();
            boolean onGround = in.readBoolean();
            double fallDistance = in.readDouble();
            boolean firstTick = in.readBoolean(), wasSprinting = in.readBoolean(), wasShiftKeyDown = in.readBoolean();
            int lastSentInput = in.readUnsignedByte();
            Vec3d last = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
            float yRotLast = in.readFloat(), xRotLast = in.readFloat();
            int positionReminder = in.readInt();
            boolean lastOnGround = in.readBoolean(), lastHorizontalCollision = in.readBoolean(), autoJumpEnabled = in.readBoolean();
            boolean crouching = in.readBoolean(), wasFallFlying = in.readBoolean();
            int sprintTriggerTime = in.readInt(), jumpTriggerTime = in.readInt(), autoJumpTime = in.readInt();
            int waterVisionTime = in.readInt(), jumpRidingTicks = in.readInt();
            float jumpRidingScale = in.readFloat();
            int input = in.readUnsignedByte();
            boolean invulnerable = in.readBoolean(), flying = in.readBoolean(), mayfly = in.readBoolean(), instabuild = in.readBoolean();
            float flyingSpeed = in.readFloat(), walkingSpeed = in.readFloat();
            int localMode = in.readInt();
            float health = in.readFloat();
            float maxHealth = in.readFloat();
            boolean alive = in.readBoolean(), deadOrDying = in.readBoolean();
            int food = in.readInt();
            float saturation = in.readFloat();
            int infoMode = in.readInt();
            Effect[] effects = new Effect[EFFECT_NAMES.length];
            for (int i = 0; i < effects.length; i++) {
                boolean present = in.readBoolean();
                effects[i] = present ? new Effect(true, in.readInt(), in.readInt(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean()) : new Effect(false, 0, 0, false, false, false, false);
            }
            Resolved[] slots = new Resolved[CompensatedInventory.SIZE];
            for (int i = 0; i < slots.length; i++) slots[i] = Resolved.read(in);
            int selected = in.readInt();
            int carriedIndex = in.readInt();
            boolean selectionPending = in.readBoolean();
            Resolved carried = Resolved.read(in);
            boolean usingItem = in.readBoolean(), usedOffHand = in.readBoolean();
            Resolved useItem = Resolved.read(in);
            int ticksUsing = in.readInt(), cooldownClock = in.readInt(), cooldownCount = in.readInt();
            Map<String, List<Integer>> cooldowns = new java.util.TreeMap<>();
            for (int i = 0; i < cooldownCount; i++) cooldowns.put(in.readUTF(), List.of(in.readInt(), in.readInt()));
            int stateId = in.readInt(), containerId = in.readInt(), openStateId = in.readInt();
            Resolved[] menu = new Resolved[in.readInt()];
            for (int i = 0; i < menu.length; i++) menu[i] = Resolved.read(in);
            int equipment = in.readInt();
            Map<String, Boolean> canGlide = new LinkedHashMap<>();
            for (int i = 0; i < equipment; i++) canGlide.put(in.readUTF(), in.readBoolean());
            boolean walksOnPowderSnow = in.readBoolean();
            int count = in.readInt();
            List<AttributeValue> attributes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                boolean present = in.readBoolean();
                attributes.add(present ? new AttributeValue(name, true, in.readDouble(), in.readDouble()) : new AttributeValue(name, false, 0, 0));
            }
            return new State(position, old, delta, yRot, xRot, sprinting, swimming, fallFlying, pose, width, height, noGravity, autoSpinAttack, onGround, fallDistance, firstTick, wasSprinting, wasShiftKeyDown, lastSentInput, last, yRotLast, xRotLast, positionReminder, lastOnGround, lastHorizontalCollision, autoJumpEnabled, crouching, wasFallFlying, sprintTriggerTime, jumpTriggerTime, autoJumpTime, waterVisionTime, jumpRidingTicks, jumpRidingScale, input, invulnerable, flying, mayfly, instabuild, flyingSpeed, walkingSpeed, localMode, health, maxHealth, alive, deadOrDying, food, saturation, infoMode, effects, slots, selected, carriedIndex, selectionPending, carried, usingItem, usedOffHand, useItem, ticksUsing, cooldownClock, cooldowns, stateId, containerId, openStateId, menu, canGlide, walksOnPowderSnow, attributes);
        }

        void applyMotion(PlayerState p) {
            p.position = position;
            p.oldPosition = oldPosition;
            p.deltaMovement = delta;
            p.yRot = yRot;
            p.xRot = xRot;
        }

        void check(ClientItemUse itemUse, PlayerState p, LocalPlayerState local, ClientPlayerData d, Set<Unmodeled> gaps, String context) {
            assertEquals(onGround, p.onGround, context + " onGround");
            bits(fallDistance, p.fallDistance, context + " fallDistance");
            assertEquals(firstTick, p.firstTick, context + " firstTick");
            assertEquals(wasSprinting, local.send.wasSprinting, context + " wasSprinting");
            assertEquals(wasShiftKeyDown, local.send.wasShiftKeyDown, context + " wasShiftKeyDown");
            assertEquals(lastSentInput, local.send.lastSentInput.mask(), context + " lastSentInput");
            bits(last.x(), local.send.xLast, context + " xLast");
            bits(last.y(), local.send.yLast, context + " yLast");
            bits(last.z(), local.send.zLast, context + " zLast");
            bits(yRotLast, local.send.yRotLast, context + " yRotLast");
            bits(xRotLast, local.send.xRotLast, context + " xRotLast");
            assertEquals(positionReminder, local.send.positionReminder, context + " positionReminder");
            assertEquals(lastOnGround, local.send.lastOnGround, context + " lastOnGround");
            assertEquals(lastHorizontalCollision, local.send.lastHorizontalCollision, context + " lastHorizontalCollision");
            assertEquals(autoJumpEnabled, local.send.autoJumpEnabled, context + " autoJumpEnabled");
            assertEquals(crouching, local.crouching, context + " crouching");
            assertEquals(wasFallFlying, local.wasFallFlying, context + " wasFallFlying");
            assertEquals(sprintTriggerTime, local.sprintTriggerTime, context + " sprintTriggerTime");
            assertEquals(jumpTriggerTime, local.jumpTriggerTime, context + " jumpTriggerTime");
            assertEquals(autoJumpTime, local.autoJumpTime, context + " autoJumpTime");
            assertEquals(waterVisionTime, local.waterVisionTime, context + " waterVisionTime");
            assertEquals(jumpRidingTicks, local.jumpRidingTicks, context + " jumpRidingTicks");
            bits(jumpRidingScale, local.jumpRidingScale, context + " jumpRidingScale");
            assertEquals(input, local.input.mask(), context + " input keys");
            assertEquals(sprinting, p.sprinting, context + " sprinting flag");
            assertEquals(swimming, p.swimming, context + " swimming flag");
            assertEquals(fallFlying, p.fallFlying, context + " fall flying flag");
            assertEquals(autoSpinAttack, p.autoSpinAttack, context + " auto spin attack");
            assertEquals(noGravity, p.noGravity, context + " no gravity");
            assertEquals(alive, p.alive, context + " isAlive");
            assertEquals(deadOrDying, p.deadOrDying, context + " isDeadOrDying");
            if (!gaps.contains(Unmodeled.ENTITY_POSE)) {
                assertEquals(pose, p.pose.name(), context + " pose");
                // the dimensions vanilla cached at its last refreshDimensions
                bits(width, p.width(), context + " width");
                bits(height, p.height(), context + " height");
            }
            bits(position.x(), p.position.x(), context + " x");
            bits(position.y(), p.position.y(), context + " y");
            bits(position.z(), p.position.z(), context + " z");
            bits(oldPosition.x(), p.oldPosition.x(), context + " xOld");
            bits(oldPosition.y(), p.oldPosition.y(), context + " yOld");
            bits(oldPosition.z(), p.oldPosition.z(), context + " zOld");
            bits(delta.x(), p.deltaMovement.x(), context + " dx");
            bits(delta.y(), p.deltaMovement.y(), context + " dy");
            bits(delta.z(), p.deltaMovement.z(), context + " dz");
            bits(yRot, p.yRot, context + " yRot");
            bits(xRot, p.xRot, context + " xRot");
            assertEquals(invulnerable, p.abilities.invulnerable, context + " invulnerable");
            assertEquals(flying, p.abilities.flying, context + " flying");
            assertEquals(mayfly, p.abilities.mayfly, context + " mayfly");
            assertEquals(instabuild, p.abilities.instabuild, context + " instabuild");
            bits(flyingSpeed, p.abilities.flyingSpeed, context + " flyingSpeed");
            bits(walkingSpeed, p.abilities.walkingSpeed, context + " walkingSpeed");
            assertEquals(localMode, d.localMode == null ? -1 : d.localMode.ordinal(), context + " local game mode");
            // the upper clamp to max health is not modelled: only death and NaN are read by movement
            assertEquals(health <= 0.0F, d.health <= 0.0F, context + " dead " + health + " vs " + d.health);
            assertEquals(Float.isNaN(health), Float.isNaN(d.health), context + " NaN health");
            if (!d.useOutcomeFood)
                assertEquals(food, d.food, context + " food");
            if (!d.useOutcomeFood)
                bits(saturation, d.saturation, context + " saturation");
            assertEquals(infoMode, d.infoMode.ordinal(), context + " PlayerInfo game mode");
            assertEquals(infoMode == GameType.SPECTATOR.ordinal(), p.spectator, context + " spectator");
            for (int i = 0; i < EFFECTS.length; i++) {
                Effect expected = effects[i];
                assertEquals(expected.present(), p.effects.has(EFFECTS[i]), context + " " + EFFECTS[i]);
                if (expected.present()) {
                    EffectInstance actual = p.effects.instance(EFFECTS[i]);
                    String effect = context + " " + EFFECTS[i];
                    assertEquals(expected.duration(), actual.duration(), effect + " duration");
                    assertEquals(expected.amplifier(), actual.amplifier(), effect + " amplifier");
                    assertEquals(expected.ambient(), actual.ambient(), effect + " ambient");
                    assertEquals(expected.visible(), actual.visible(), effect + " visible");
                    assertEquals(expected.showIcon(), actual.showIcon(), effect + " showIcon");
                    assertEquals(expected.hidden(), actual.hidden() != null, effect + " hidden");
                }
            }
            assertEquals(containerId, d.inventory.openContainerId(), context + " open container");
            if (containerId != 0)
                assertEquals(openStateId, d.inventory.openStateId(), context + " open container state id");
            if (d.inventory.openMenuSize() >= 0 && !d.useOutcomeInventory) {
                assertEquals(menu.length, d.inventory.openMenuSize(), context + " open menu size");
                for (int i = 0; i < menu.length; i++) {
                    int index = d.inventory.openMenuIndex(i);
                    if (d.inventory.openMenuUnknown(i) || index >= 0 && d.inventory.mayLose(index) > 0)
                        continue;
                    ItemStack modelled = d.inventory.openMenuStack(i);
                    if (d.inventory.openMenuCountUnknown(i) && menu[i] != null) {
                        // the container cut the count to the stack's maximum size: at most the sent count
                        assertTrue(menu[i].count() <= Stacks.count(modelled), context + " menu slot " + i + " count");
                        modelled = Stacks.withCount(modelled, menu[i].count());
                    }
                    checkStack(menu[i], modelled, context + " menu " + d.inventory.openMenuKey() + " slot " + i);
                }
            }
            assertEquals(d.inventory.uncertain(), gaps.contains(Unmodeled.INVENTORY_CLIENT_ACTION), context + " inventory gap");
            if (!gaps.contains(Unmodeled.INVENTORY_CONTAINER) && !d.useOutcomeInventory) {
                for (int i = 0; i < slots.length; i++) {
                    // a slot the model only guesses (INVENTORY_CLIENT_ACTION) is not compared
                    if (d.inventory.unknown(i))
                        continue;
                    if (d.inventory.mayLose(i) == 0) {
                        checkStack(slots[i], d.inventory.item(i), context + " slot " + i);
                    } else {
                        checkBounded(slots[i], d.inventory, i, context + " bounded slot " + i);
                    }
                }
                if (!selectionPending)
                    assertEquals(selected, d.inventory.selected(), context + " selected slot");
                assertEquals(carriedIndex, d.carriedIndex, context + " carried index");
                if (!d.inventory.carriedUnknown())
                    checkStack(carried, d.inventory.carried(), context + " carried");
                if (!gaps.contains(Unmodeled.ITEM_USE_TIMING)) {
                    assertEquals(usingItem, d.usingItem, context + " isUsingItem");
                    if (usingItem) {
                        assertEquals(usedOffHand, d.usingOffHand, context + " used hand");
                        int useSlot = d.useItemSlot;
                        boolean bounded = useSlot >= 0 && d.inventory.version(useSlot) == d.useItemVersion && d.inventory.mayLose(useSlot) > 0;
                        if (bounded) {
                            // the very stack of a slot an unplaced drop may have shrunk (DROP_SLOT): within its bounds; an
                            // emptied stack has no use duration
                            checkBounded(useItem, d.inventory, useSlot, context + " use item");
                            assertEquals(useItem == null ? -d.useRemaining : itemUse.ticksUsing(d), ticksUsing, context + " ticks using");
                        } else {
                            checkStack(useItem, ClientItemUse.useItem(d), context + " use item");
                        }
                        if (!bounded)
                            assertEquals(ticksUsing, itemUse.ticksUsing(d), context + " ticks using");
                    }
                }
                assertEquals(cooldownClock, d.cooldownClock, context + " cooldown clock");
                Map<String, List<Integer>> modelled = new java.util.TreeMap<>();
                d.cooldowns.forEach((group, times) -> modelled.put(group, List.of(times[0], times[1])));
                // a group an undecided use may have started is reported (ITEM_USE_OUTCOME), not compared
                Map<String, List<Integer>> expectedCooldowns = new java.util.TreeMap<>(cooldowns);
                expectedCooldowns.keySet().removeAll(d.uncertainCooldowns.keySet());
                modelled.keySet().removeAll(d.uncertainCooldowns.keySet());
                assertEquals(!d.useOutcomeOpen(), !gaps.contains(Unmodeled.ITEM_USE_OUTCOME), context + " outcome gap");
                assertEquals(expectedCooldowns, modelled, context + " cooldowns");
                assertEquals(stateId, d.inventory.stateId(), context + " state id");
                for (Map.Entry<String, Boolean> entry : canGlide.entrySet()) {
                    EquipmentSlot slot = EquipmentSlot.byName(entry.getKey());
                    if (slot == EquipmentSlot.MAINHAND && (selectionPending || d.inventory.mayLose(selected) > 0))
                        continue;
                    int index = switch(slot) {
                        case MAINHAND ->
                            selected;
                        case OFFHAND ->
                            CompensatedInventory.OFFHAND;
                        case FEET ->
                            CompensatedInventory.FEET;
                        case LEGS ->
                            CompensatedInventory.LEGS;
                        case CHEST ->
                            CompensatedInventory.CHEST;
                        case HEAD ->
                            CompensatedInventory.HEAD;
                        default ->
                            -1;
                    };
                    if (index >= 0 && d.inventory.unknown(index))
                        continue;
                    assertEquals(entry.getValue(), ru.minecomplex.mac.inventory.item.ItemMechanics.canGlideUsing(d.inventory.equipment(slot), slot), context + " canGlideUsing " + slot);
                }
                if (!d.inventory.unknown(CompensatedInventory.FEET)) {
                    assertEquals(walksOnPowderSnow, p.wearingLeatherBoots, context + " leather boots on powder snow");
                }
            }
            for (AttributeValue expected : attributes) {
                Attribute attribute = ClientPlayerHandlers.attribute("minecraft:" + expected.name());
                assertTrue(expected.present(), context + " " + expected.name() + " missing on the vanilla player");
                bits(expected.base(), p.attributes.get(attribute).baseValue(), context + " " + expected.name() + " base");
                bits(expected.value(), p.attributes.value(attribute), context + " " + expected.name() + " value");
            }
        }
    }
}
