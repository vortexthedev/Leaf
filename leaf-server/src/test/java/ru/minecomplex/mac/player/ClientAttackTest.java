package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.GameType;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.inventory.item.ItemBehavior;

import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.fixture.inbound.Attack;

import ru.minecomplex.mac.fixture.inbound.PlayerAction;
import ru.minecomplex.mac.fixture.inbound.Swing;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * The client's own attack ({@code MultiPlayerGameMode.attack}, {@code Player.attack}): what slows the attacker, and
 * {@code attackStrengthTicker} ({@code Player.tick}, {@code Minecraft.startAttack}, {@code stopDestroyBlock}).
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ClientAttackTest {
    private static final int TARGET = 42;

    private static ClientPlayerActions.Target target(String type) {
        return new ClientPlayerActions.Target() {
            @Override public String block(int x, int y, int z) { return null; }
            @Override public String entityType(int entityId) { return entityId == TARGET ? type : null; }
        };
    }

    private static PlayerState sprinting() {
        PlayerState p = new PlayerState();
        p.deltaMovement = new Vec3d(0.3, -0.1, 0.2);
        p.setSprinting(true);
        return p;
    }

    private static ClientPlayerData data(int ticks) {
        ClientPlayerData data = new ClientPlayerData();
        data.localMode = GameType.SURVIVAL;
        for (int i = 0; i < ticks; i++) ClientAttack.tick(data);
        return data;
    }

    private static Set<Unmodeled> attack(PlayerState p, ClientPlayerData data, String type) {
        return new ClientPlayerActions().apply(p, data, Wire.of(new Attack(TARGET)), target(type)).gaps();
    }

    @Test
    void aSprintingFullStrengthAttackOnAClientHurtTargetSlowsTheAttacker() {
        for (String type : new String[]{"minecraft:player", "minecraft:oak_boat", "minecraft:bamboo_raft", "minecraft:minecart",
                "minecraft:tnt_minecart", "minecraft:shulker_bullet", "minecraft:end_crystal"}) {
            PlayerState p = sprinting();
            ClientPlayerData data = data(5);
            Set<Unmodeled> gaps = attack(p, data, type);
            assertEquals(Set.of(), gaps, type);
            assertEquals(new Vec3d(0.3 * 0.6, -0.1 * 1.0, 0.2 * 0.6), p.deltaMovement, type);
            assertFalse(p.sprinting, type);
            assertEquals((double) 0.1F, p.attributes.value(Attribute.MOVEMENT_SPEED), "the sprint modifier is gone");
            // the attack resets the ticker
            assertEquals(0, data.attackTicksMin);
            assertEquals(0, data.attackTicksMax);
        }
    }

    @Test
    void nothingElseSlowsTheAttacker() {
        for (String type : new String[]{"minecraft:zombie", "minecraft:item_frame", "minecraft:painting", "minecraft:armor_stand",
                "minecraft:interaction", "minecraft:item", "minecraft:arrow", "minecraft:fireball"}) {
            PlayerState p = sprinting();
            assertEquals(Set.of(), attack(p, data(20), type), type);
            assertEquals(new Vec3d(0.3, -0.1, 0.2), p.deltaMovement, type);
            assertTrue(p.sprinting, type);
        }
        // not sprinting
        PlayerState walking = new PlayerState();
        walking.deltaMovement = new Vec3d(0.3, -0.1, 0.2);
        assertEquals(Set.of(), attack(walking, data(20), "minecraft:player"));
        assertEquals(new Vec3d(0.3, -0.1, 0.2), walking.deltaMovement);
        // a target the model does not know
        assertEquals(Set.of(Unmodeled.ATTACK), attack(sprinting(), data(20), null));
    }

    @Test
    void theStrengthMustBeAboveNinetyPercent() {
        // attack speed 4: a delay of 5 ticks, (4 + 0.5) / 5 = 0.9 is not above 0.9F
        PlayerState weak = sprinting();
        attack(weak, data(4), "minecraft:player");
        assertTrue(weak.sprinting);
        // a sword (attack speed 1.6): 12.5 ticks, (11 + 0.5) / 12.5 = 0.92, (10 + 0.5) / 12.5 = 0.84
        for (int ticks : new int[]{10, 11}) {
            PlayerState p = sprinting();
            p.attributes.get(Attribute.ATTACK_SPEED).setBaseValue(1.6);
            attack(p, data(ticks), "minecraft:player");
            assertEquals(ticks == 10, p.sprinting, "ticks " + ticks);
        }
    }

    @Test
    void aSwingOrAnAbortMayHaveResetTheTickerAStabAndANewItemDid() {
        ClientPlayerData data = data(10);
        new ClientPlayerActions().apply(new PlayerState(), data, Wire.of(new Swing(Swing.Hand.MAIN_HAND)));
        assertEquals(0, data.attackTicksMin);
        assertEquals(10, data.attackTicksMax);
        PlayerState p = sprinting();
        assertEquals(Set.of(Unmodeled.ATTACK), attack(p, data, "minecraft:player"));
        assertTrue(p.sprinting, "not decided: unchanged");

        ClientPlayerData aborted = data(10);
        new ClientPlayerActions().apply(new PlayerState(), aborted,
                Wire.of(PlayerAction.of(PlayerAction.Action.ABORT_DESTROY_BLOCK)));
        assertEquals(0, aborted.attackTicksMin);
        assertEquals(10, aborted.attackTicksMax);

        ClientPlayerData stabbed = data(10);
        new ClientPlayerActions().apply(new PlayerState(), stabbed,
                Wire.of(PlayerAction.of(PlayerAction.Action.STAB)));
        assertEquals(0, stabbed.attackTicksMax);

        // Player.tick: another item in the main hand resets, the same item does not
        ItemBehavior items = ItemBehavior.get();
        ClientPlayerData swapped = data(10);
        swapped.inventory.setItem(1, FixtureItems.stack(WireItem.of("minecraft:diamond_sword", 1)));
        swapped.inventory.setItem(2, FixtureItems.stack(WireItem.of("minecraft:diamond_sword", 1)));
        swapped.inventory.setSelected(1);
        ClientAttack.tick(swapped);
        assertEquals(0, swapped.attackTicksMax, "empty hand to a sword");
        for (int i = 0; i < 9; i++) ClientAttack.tick(swapped);
        swapped.inventory.setSelected(2);
        ClientAttack.tick(swapped);
        assertEquals(10, swapped.attackTicksMin, "another sword: the same item");
        swapped.inventory.setUnknown(2, true);
        ClientAttack.tick(swapped);
        assertEquals(0, swapped.attackTicksMin);
        assertEquals(11, swapped.attackTicksMax);
    }

    @Test
    void aSpectatorAttacksFrom26_1() {
        PlayerState p = sprinting();
        ClientPlayerData data = data(10);
        data.localMode = GameType.SPECTATOR;
        attack(p, data, "minecraft:player");
        assertFalse(p.sprinting);
        assertEquals(0, data.attackTicksMax);
    }

    @Test
    void aSpinAttackUsesTheClientsSpinDamage() {
        for (float damage : new float[]{0.0F, 8.0F}) {
            PlayerState p = sprinting();
            p.autoSpinAttack = true;
            p.autoSpinAttackDamage = damage;
            attack(p, data(10), "minecraft:player");
            assertEquals(damage == 0.0F, p.sprinting, "damage " + damage);
        }
    }
}
