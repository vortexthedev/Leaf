package ru.minecomplex.mac.player;

import net.minecraft.world.level.GameType;
import ru.minecomplex.mac.inventory.item.Stacks;
import java.util.EnumSet;
import java.util.Set;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * The client's own attack ({@code MultiPlayerGameMode.attack}: {@code Player.attack(target)}, then
 * {@code resetAttackStrengthTicker}) as far as it moves the attacker, and {@code Player.attackStrengthTicker}.
 *
 * <p>{@code Player.attack} slows the attacker ({@code multiply(0.6, 1.0, 0.6)}, {@code setSprinting(false)}) only when the
 * target's client-side {@code hurtOrSimulate} succeeds and the knockback is positive. On the client the knockback is
 * {@code attack_knockback} (not synced, its base 0; 26.x halves it) plus the sprint bonus of a sprinting full-strength
 * attack (1.0F through 1.21.10, 0.5F after): so the attacker sprints and {@code getAttackStrengthScale(0.5F) > 0.9F}.
 * {@code hurtClient} succeeds for {@code RemotePlayer}, {@code VehicleEntity} (boats, rafts, minecarts),
 * {@code ShulkerBullet} and {@code EndCrystal} (not invulnerable on a client); a {@code BlockAttachedEntity} (frames,
 * paintings, leash knots, 26.3 cushions) is skipped when it can be hurt ({@code skipAttackInteraction}); items,
 * experience orbs, arrows, eyes of ender, falling blocks and rockets are not attackable; every other entity's hurt
 * fails. The attack damage must be positive: {@code attack_damage} is not synced (base 1.0), but while the spin flag
 * is set the client's {@code autoSpinAttackDmg} is used (0 until its own riptide launch).
 *
 * <p>The ticker counts in {@code Player.tick} and resets on an attack, a {@code stab} (1.21.11+: {@code onAttack}),
 * a missed swing ({@code Minecraft.startAttack}), {@code stopDestroyBlock} (an {@code ABORT_DESTROY_BLOCK}) and a new
 * item in the main hand ({@code Player.tick}); swings and aborts the client also sends for other reasons, so they may
 * have reset it.
 */
public final class ClientAttack {

    public ClientAttack() {
    }

    static void newPlayer(ClientPlayerData data) {
        data.attackTicksMin = 0;
        data.attackTicksMax = 0;
        data.lastMainHandItem = "";
    }

    /**
     * {@code MultiPlayerGameMode.attack} on the entity {@code target.entityType} names.
     */
    public Set<Unmodeled> attack(PlayerState p, ClientPlayerData data, String targetType) {
        Set<Unmodeled> gaps = EnumSet.noneOf(Unmodeled.class);
        Tri slows = slows(p, data, targetType);
        if (slows == Tri.TRUE) {
            p.deltaMovement = new Vec3d(p.deltaMovement.x() * 0.6, p.deltaMovement.y() * 1.0, p.deltaMovement.z() * 0.6);
            p.setSprinting(false);
        } else if (slows == Tri.EITHER) {
            gaps.add(Unmodeled.ATTACK);
        }
        reset(data);
        return gaps;
    }

    private static Tri slows(PlayerState p, ClientPlayerData data, String type) {
        if (type == null)
            return p.sprinting ? Tri.EITHER : Tri.FALSE;
        if (!hurtsOnClient(type) || !p.sprinting)
            return Tri.FALSE;
        // baseDamage > 0 (the client's magic boost is 0)
        if (p.autoSpinAttack && !(p.autoSpinAttackDamage > 0.0F))
            return Tri.FALSE;
        float delay = (float) (1.0 / p.attributes.value(Attribute.ATTACK_SPEED) * 20.0);
        boolean fullFrom = fullStrength(data.attackTicksMin, delay), fullTo = fullStrength(data.attackTicksMax, delay);
        return fullFrom == fullTo ? Tri.of(fullFrom) : Tri.EITHER;
    }

    /**
     * {@code getAttackStrengthScale(0.5F) > 0.9F}.
     */
    private static boolean fullStrength(int ticker, float delay) {
        float scale = Math.clamp((ticker + 0.5F) / delay, 0.0F, 1.0F);
        return scale > 0.9F;
    }

    /**
     * The entity types whose client-side {@code hurtOrSimulate} succeeds for a player's attack.
     */
    static boolean hurtsOnClient(String type) {
        return type.equals("minecraft:player") || type.endsWith("_boat") || type.endsWith("_raft") || type.equals("minecraft:minecart") || type.endsWith("_minecart") || type.equals("minecraft:shulker_bullet") || type.equals("minecraft:end_crystal");
    }

    private static void reset(ClientPlayerData data) {
        data.attackTicksMin = 0;
        data.attackTicksMax = 0;
    }

    /**
     * An event that may have reset the ticker (a swing, an abort).
     */
    public static void mayReset(ClientPlayerData data) {
        data.attackTicksMin = 0;
    }

    /**
     * {@code stab} (1.21.11+): {@code Player.onAttack} → {@code resetOnlyAttackStrengthTicker}.
     */
    public static void stab(ClientPlayerData data) {
        reset(data);
    }

    /**
     * {@code Player.tick}: {@code attackStrengthTicker++}, then a main hand of another item resets it.
     */
    static void tick(ClientPlayerData data) {
        data.attackTicksMin = Math.min(data.attackTicksMin + 1, 1_000_000);
        data.attackTicksMax = Math.min(data.attackTicksMax + 1, 1_000_000);
        var inventory = data.inventory;
        int slot = inventory.handSlot(false);
        String now = slot < 0 || inventory.unknown(slot) || inventory.mainHandMayBeEmpty() ? null : inventory.item(slot).isEmpty() ? "" : Stacks.key(inventory.item(slot));
        String last = data.lastMainHandItem;
        if (now == null || last == null) {
            // ItemStack.isSameItem(lastItemInMainHand, mainHand) is not known
            if (!(now != null && now.equals(last)))
                mayReset(data);
        } else if (!now.equals(last)) {
            reset(data);
        }
        data.lastMainHandItem = now;
    }
}
