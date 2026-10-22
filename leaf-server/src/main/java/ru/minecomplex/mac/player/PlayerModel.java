package ru.minecomplex.mac.player;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import net.minecraft.world.level.GameType;

import java.util.UUID;
import ru.minecomplex.mac.entity.EntityDataItems;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.packet.Packets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ru.minecomplex.mac.player.state.LocalPlayerState;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.player.transaction.ChangeLog;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.listener.ClientPlayerActions;
import ru.minecomplex.mac.listener.ClientPlayerHandlers;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * Clientbound changes of the local player in wire order, and the client-player hypotheses they are applied to.
 *
 * <p>Unlike the world there is no acknowledged player: the player also changes by its own client ticks, so every
 * player state belongs to a hypothesis that interleaves ticks with packet prefixes. A {@link Hypothesis} owns the
 * forkable {@link PlayerState}, {@link LocalPlayerState} and {@link ClientPlayerData}; {@link Hypothesis#apply}
 * runs the vanilla handlers ({@link ClientPlayerHandlers}) for the pending changes up to a prefix end, with the same
 * contract as {@code WorldModel.Hypothesis}.
 */
public final class PlayerModel {
    private static final int MAX_HYPOTHESES = 64;

    private final ClientPlayerHandlers handlers;
    private final ClientPlayerActions actions;
    private final ChangeLog<Object> pending = new ChangeLog<>();
    private final List<Hypothesis> hypotheses = new ArrayList<>();
    private long settledThrough = -1;

    public PlayerModel() {
        this(new PlayerConnectionContext(null, null));
    }

    public PlayerModel(PlayerConnectionContext facts) {
        this.handlers = new ClientPlayerHandlers(facts);
        this.actions = new ClientPlayerActions();
    }

    /**
     * Whether the client's handler for {@code event} changes its own player.
     *
     * @param playerEntityId the client's entity id at this wire position ({@code login}; {@code respawn} keeps it)
     */
    public static boolean isPlayerChange(Object packet, int playerEntityId, UUID self) {
        return switch (packet) {
            case ClientboundPlayerPositionPacket ignored -> true;
            case ClientboundPlayerRotationPacket ignored -> true;
            // its own id, or the id of the vehicle removed while carrying it (which the world hypothesis knows)
            case ClientboundTeleportEntityPacket ignored -> true;
            case ClientboundExplodePacket explosion -> explosion.playerKnockback().isPresent();
            case ClientboundPlayerAbilitiesPacket ignored -> true;
            case ClientboundGameEventPacket game -> game.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE;
            case ClientboundSetHealthPacket ignored -> true;
            case ClientboundPlayerInfoUpdatePacket info -> Packets.concernsOwnEntry(info, self);
            case ClientboundPlayerInfoRemovePacket remove -> remove.profileIds().contains(self);
            case ClientboundLoginPacket ignored -> true;
            case ClientboundRespawnPacket ignored -> true;
            case ClientboundSetEntityMotionPacket velocity -> velocity.id() == playerEntityId;
            case ClientboundUpdateMobEffectPacket update -> update.getEntityId() == playerEntityId;
            case ClientboundRemoveMobEffectPacket remove -> remove.entityId() == playerEntityId;
            case ClientboundUpdateAttributesPacket attributes -> attributes.getEntityId() == playerEntityId;
            case ClientboundSetEntityDataPacket data -> data.id() == playerEntityId && !EntityDataItems.of(data).isEmpty();
            case ClientboundAnimatePacket animation -> animation.getId() == playerEntityId;
            case ClientboundContainerSetContentPacket ignored -> true;
            case ClientboundContainerSetSlotPacket ignored -> true;
            case ClientboundSetPlayerInventoryPacket ignored -> true;
            case ClientboundSetHeldSlotPacket ignored -> true;
            case ClientboundSetCursorItemPacket ignored -> true;
            case ClientboundOpenScreenPacket ignored -> true;
            case ClientboundMountScreenOpenPacket ignored -> true;
            case ClientboundEntityEventPacket entityEvent -> entityEvent.entityId() == playerEntityId
                    && entityEvent.getEventId() == Packets.FINISHED_USING_ITEM;
            case ClientboundCooldownPacket ignored -> true;
            case ClientboundContainerClosePacket ignored -> true;
            default -> false;
        };
    }

    /** A local player change left at wire position {@code seq}. */
    public void sent(long seq, Object change) {
        pending.append(seq, change);
        for (Hypothesis hypothesis : hypotheses) {
            hypothesis.changes.append(seq, change);
        }
    }

    /**
     * Forgets changes every client certainly processed. Live hypotheses keep their own copies; a new hypothesis can
     * only start at or after the settled position.
     */
    public void settle(SyncWindow window) {
        if (window.certainThrough() < settledThrough) {
            throw new IllegalStateException("Window " + window + " is older than the settled state (" + settledThrough + ")");
        }
        settledThrough = window.certainThrough();
        pending.truncateThrough(settledThrough);
    }

    /**
     * Starts a hypothesis from a resolved client player state that has processed every change up to and including
     * {@code appliedThrough}. The states are owned by the hypothesis from now on.
     */
    public Hypothesis start(PlayerState player, LocalPlayerState local, ClientPlayerData data, long appliedThrough) {
        if (appliedThrough < settledThrough) {
            throw new IllegalArgumentException("Changes up to " + settledThrough + " are already forgotten; cannot start at " + appliedThrough);
        }
        if (hypotheses.size() >= MAX_HYPOTHESES) {
            throw new IllegalStateException("Too many live player hypotheses; retire unused forks");
        }
        Hypothesis hypothesis = new Hypothesis(player, local, data, appliedThrough, EnumSet.noneOf(Unmodeled.class));
        for (ChangeLog.Entry<Object> entry : pending.between(appliedThrough, Long.MAX_VALUE)) {
            hypothesis.changes.append(entry.seq(), entry.change());
        }
        hypotheses.add(hypothesis);
        return hypothesis;
    }

    /** Pending changes not yet settled (diagnostics). */
    public int pendingCount() {
        return pending.size();
    }

    /** A packet a handler sent while processing the change at wire position {@code cause}. */
    public record Reply(long cause, Object packet) {
    }

    /** One possible client player: its state after its own ticks and an admissible prefix of the changes. */
    public final class Hypothesis implements AutoCloseable {
        private PlayerState player;
        private LocalPlayerState local;
        private final ClientPlayerData data;
        private final ChangeLog<Object> changes = new ChangeLog<>();
        private final EnumSet<Unmodeled> gaps;
        private long appliedThrough;
        private boolean closed;

        private Hypothesis(PlayerState player, LocalPlayerState local, ClientPlayerData data, long appliedThrough,
                           EnumSet<Unmodeled> gaps) {
            this.player = player;
            this.local = local;
            this.data = data;
            this.appliedThrough = appliedThrough;
            this.gaps = gaps;
        }

        /**
         * Applies the pending changes with {@code appliedThrough < seq <= through}, in wire order, to this hypothesis.
         *
         * @return the handlers' replies, in the order the client sends them
         */
        public List<Reply> apply(SyncWindow window, long through) {
            return apply(window, through, ClientPlayerActions.Target.UNKNOWN);
        }

        /** {@link #apply(SyncWindow, long)} with the client world's entities for the changes that name one. */
        public List<Reply> apply(SyncWindow window, long through, ClientPlayerActions.Target target) {
            checkOpen();
            if (through < appliedThrough || through < window.certainThrough() || through >= window.possibleBefore()) {
                throw new IllegalArgumentException("Invalid player prefix " + through + " in " + window + " (applied " + appliedThrough + ")");
            }
            return applyStep(through, target);
        }

        /**
         * One step of an application interleaved with the world's changes (the passenger state of the player changes
         * between them): the pending changes through {@code through}. The interleaving caller ends with {@link #apply},
         * which checks the window.
         */
        public List<Reply> applyStep(long through, ClientPlayerActions.Target target) {
            checkOpen();
            if (through < appliedThrough) throw new IllegalArgumentException("Invalid player step " + through + " (applied " + appliedThrough + ")");
            List<Reply> replies = new ArrayList<>();
            for (ChangeLog.Entry<Object> entry : changes.between(appliedThrough, through + 1)) {
                ClientPlayerHandlers.Result result = handlers.apply(player, local, data, entry.change(), target);
                if (result.player() != null) {
                    // login/respawn: a new LocalPlayer; callers must re-read player() and local()
                    player = result.player();
                    local = result.local();
                }
                for (Object reply : result.replies()) {
                    replies.add(new Reply(entry.seq(), reply));
                }
                gaps.removeAll(result.resolves());
                gaps.addAll(result.gaps());
            }
            changes.truncateThrough(through);
            appliedThrough = through;
            return replies;
        }

        /**
         * A serverbound packet of this hypothesis' client, in wire order, after the changes it had applied (not a reply
         * {@link #apply} predicted): its client-local inventory effect ({@link ClientPlayerActions}).
         *
         * @return what a vanilla client would not have sent
         */
        public Set<ClientPlayerActions.Violation> act(Object event) {
            return act(event, ClientPlayerActions.Target.UNKNOWN);
        }

        /** {@link #act(PacketWrapper)} with the client world's blocks and entities for its interactions. */
        public Set<ClientPlayerActions.Violation> act(Object event, ClientPlayerActions.Target target) {
            checkOpen();
            return merge(actions.apply(player, data, event, target));
        }

        /** The changes sent before wire position {@code before} that this hypothesis has not applied, in wire order. */
        public List<Object> pendingBefore(long before) {
            checkOpen();
            List<Object> pending = new ArrayList<>();
            for (ChangeLog.Entry<Object> entry : changes.between(appliedThrough, before)) pending.add(entry.change());
            return pending;
        }

        /** The wire positions of {@link #pendingBefore}. */
        public long[] pendingSeqs(long before) {
            checkOpen();
            return changes.between(appliedThrough, before).stream().mapToLong(ChangeLog.Entry::seq).toArray();
        }

        /**
         * The start of the LocalPlayer's {@code LivingEntity.tick} ({@code updatingUsingItem}); the prediction layer
         * calls it before the movement of every tick the player ticks.
         */
        public void livingTickStart() {
            checkOpen();
            player.spectator = data.playerInfoMode() == GameType.SPECTATOR;
            ClientItemUse.livingTick(data, gaps);
        }

        /** Local action history that may differ between hypotheses at the same wire prefix. */
        public Object actionHistory() {
            return new ActionHistory(data.usingItem, data.usingOffHand, data.useRemaining, data.usedStack(),
                    data.useItemSlot, data.useItemVersion, data.inventory.selected(), data.carriedIndex, data.lastMainHandItem);
        }

        private record ActionHistory(boolean using, boolean offHand, int remaining,
                                     net.minecraft.world.item.ItemStack stack,
                                     int slot, int version, int selected, int carriedIndex, String lastMainHand) { }

        /** The end of the LocalPlayer's {@code Player.tick} ({@code ItemCooldowns.tick}). */
        public void playerTickEnd() {
            checkOpen();
            // Player.tick: attackStrengthTicker and the main-hand item check, then ItemCooldowns.tick
            ClientAttack.tick(data);
            ClientItemUse.tickCooldowns(data);
            if (!data.useOutcomeOpen()) gaps.remove(Unmodeled.ITEM_USE_OUTCOME);
            gaps.remove(Unmodeled.RIPTIDE);
            gaps.remove(Unmodeled.ATTACK);
            gaps.remove(Unmodeled.ENTITY_MOTION);
        }

        /** The client's {@code client_tick_end}. */
        public Set<ClientPlayerActions.Violation> tickEnded() {
            checkOpen();
            return merge(actions.tickEnded(player, data));
        }

        private Set<ClientPlayerActions.Violation> merge(ClientPlayerActions.Result result) {
            gaps.removeAll(result.resolves());
            gaps.addAll(result.gaps());
            return result.violations();
        }

        /** An independent copy that continues from the same prefix with the same pending changes. */
        public Hypothesis fork() {
            checkOpen();
            if (hypotheses.size() >= MAX_HYPOTHESES) {
                throw new IllegalStateException("Too many live player hypotheses");
            }
            Hypothesis fork = new Hypothesis(player.copy(), local.copy(), data.copy(), appliedThrough, EnumSet.copyOf(gaps));
            for (ChangeLog.Entry<Object> entry : changes.between(appliedThrough, Long.MAX_VALUE)) {
                fork.changes.append(entry.seq(), entry.change());
            }
            hypotheses.add(fork);
            return fork;
        }

        /** The current player; {@code login}/{@code respawn} replace it, so re-read it after {@link #apply}. */
        public PlayerState player() {
            checkOpen();
            return player;
        }

        public LocalPlayerState local() {
            checkOpen();
            return local;
        }

        public ClientPlayerData data() {
            checkOpen();
            return data;
        }

        public long appliedThrough() {
            return appliedThrough;
        }

        /** Facts the applied changes left unresolved (sticky until the owner resolves and clears them). */
        public Set<Unmodeled> gaps() {
            return gaps.isEmpty() ? Set.of() : EnumSet.copyOf(gaps);
        }

        /** The owner resolved {@code gap} (e.g. reinitialised the player after a respawn). */
        public void resolve(Unmodeled gap) {
            gaps.remove(gap);
        }

        @Override
        public void close() {
            hypotheses.remove(this);
            closed = true;
        }

        private void checkOpen() {
            if (closed) {
                throw new IllegalStateException("Closed player hypothesis");
            }
        }
    }
}
