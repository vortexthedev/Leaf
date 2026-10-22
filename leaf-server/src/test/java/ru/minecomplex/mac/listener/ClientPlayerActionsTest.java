package ru.minecomplex.mac.listener;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import ru.minecomplex.mac.fixture.ClaimedStack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import ru.minecomplex.mac.inventory.item.ItemBehavior;

import ru.minecomplex.mac.inventory.item.Stacks;
import ru.minecomplex.mac.fixture.FixtureItems;
import ru.minecomplex.mac.fixture.WireItem;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.fixture.inbound.ContainerClick;
import ru.minecomplex.mac.fixture.inbound.ContainerClosed;
import ru.minecomplex.mac.fixture.inbound.HeldSlot;
import ru.minecomplex.mac.fixture.inbound.PlayerAction;
import ru.minecomplex.mac.fixture.inbound.PlayerInput;
import ru.minecomplex.mac.fixture.inbound.Swing;
import ru.minecomplex.mac.player.state.ClientPlayerData;

/**
 * Order rules, drop bounds and closing of {@link ClientPlayerActions}; vanilla equivalence is MAPS-tested.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ClientPlayerActionsTest {

    private static final ClientPlayerActions ACTIONS = new ClientPlayerActions();

    private static final ItemBehavior ITEMS = ItemBehavior.get();

    private static final PlayerAction DROP = PlayerAction.of(PlayerAction.Action.DROP_ITEM);

    private static final PlayerAction SWAP = PlayerAction.of(PlayerAction.Action.SWAP_ITEM_WITH_OFFHAND);

    private static final Swing SWING = new Swing(Swing.Hand.MAIN_HAND);

    private static ClientPlayerData stones(int... counts) {
        ClientPlayerData data = new ClientPlayerData();
        for (int slot = 0; slot < counts.length; slot++) {
            data.inventory.setItem(slot, FixtureItems.stack(WireItem.of("minecraft:stone", counts[slot])));
        }
        return data;
    }

    @Test
    void reportsPacketsVanillaDoesNotSend() {
        PlayerState p = new PlayerState();
        ClientPlayerData data = new ClientPlayerData();
        assertEquals(Set.of(ClientPlayerActions.Violation.HELD_SLOT_OUT_OF_RANGE), ACTIONS.apply(p, data, Wire.of(new HeldSlot(9))).violations());
        assertEquals(Set.of(ClientPlayerActions.Violation.HELD_SLOT_UNCHANGED), ACTIONS.apply(p, data, Wire.of(new HeldSlot(0))).violations());
        assertEquals(Set.of(), ACTIONS.apply(p, data, Wire.of(new HeldSlot(4))).violations());
        assertEquals(4, data.carriedIndex);
        assertEquals(Set.of(), ACTIONS.apply(p, data, Wire.of(DROP)).violations());
        assertEquals(Set.of(ClientPlayerActions.Violation.SWAP_AFTER_DROP), ACTIONS.apply(p, data, Wire.of(SWAP)).violations());
        ACTIONS.tickEnded(p, data);
        assertEquals(Set.of(), ACTIONS.apply(p, data, Wire.of(SWAP)).violations(), "a new tick");
        ACTIONS.apply(p, data, Wire.of(new PlayerInput(0)));
        assertEquals(Set.of(ClientPlayerActions.Violation.KEYBIND_AFTER_PLAYER_TICK), ACTIONS.apply(p, data, Wire.of(new HeldSlot(5))).violations());
        ACTIONS.tickEnded(p, data);
        p.spectator = true;
        assertEquals(Set.of(ClientPlayerActions.Violation.ACTION_IN_SPECTATOR), ACTIONS.apply(p, data, Wire.of(DROP)).violations());
        assertEquals(Set.of(ClientPlayerActions.Violation.ACTION_IN_SPECTATOR), ACTIONS.apply(p, data, Wire.of(SWAP)).violations());
    }

    @Test
    void aTickStartsWithTheSlotSentLast() {
        PlayerState p = new PlayerState();
        ClientPlayerData data = new ClientPlayerData();
        // set_held_slot(4) and a scroll back to 0 before the next tick: gameMode.tick() has nothing to send
        data.inventory.setSelected(4);
        ACTIONS.tickEnded(p, data);
        assertEquals(0, data.inventory.selected());
    }

    @Test
    void sinceTheSlotIsSentFirstADropIsExact() {
        PlayerState p = new PlayerState();
        ClientPlayerData data = stones(5, 0, 2);
        ACTIONS.apply(p, data, Wire.of(new HeldSlot(2)));
        ACTIONS.apply(p, data, Wire.of(PlayerAction.of(PlayerAction.Action.DROP_ALL_ITEMS)));
        assertTrue(data.inventory.item(2).isEmpty());
        assertEquals(0, data.inventory.mayLose(0));
    }

    @Test
    void closingAndClientClicks() {
        PlayerState p = new PlayerState();
        ClientPlayerData data = new ClientPlayerData();
        data.inventory.openContainer(3, null);
        assertEquals(Set.of(ClientPlayerActions.Violation.CLOSE_OF_UNKNOWN_CONTAINER), ACTIONS.apply(p, data, Wire.of(new ContainerClosed(4))).violations());
        assertEquals(0, data.inventory.openContainerId());
        assertEquals(Set.of(), ACTIONS.apply(p, data, Wire.of(new ContainerClosed(0))).violations(), "the own inventory screen");
        data.inventory.openContainer(5, null);
        data.inventory.mountScreenOpen(6);
        assertEquals(Set.of(), ACTIONS.apply(p, data, Wire.of(new ContainerClosed(6))).violations());
        assertFalse(data.inventory.mountPending());
        // the client's result of a click on its inventory menu: hotbar slot 0 (menu slot 36) and the carried stack
        var click = ContainerClick.of(0, 1, 36, 0, 0, java.util.Map.of(36, ru.minecomplex.mac.fixture.ClaimedStack.exact(WireItem.of("minecraft:stone", 5))), ru.minecomplex.mac.fixture.ClaimedStack.EMPTY);
        assertEquals(Set.of(), ACTIONS.apply(p, data, Wire.of(click)).gaps());
        assertEquals(5, Stacks.count(data.inventory.item(0)));
        // a click on a menu the model does not hold open
        assertEquals(Set.of(Unmodeled.INVENTORY_CLIENT_ACTION), ACTIONS.apply(p, data, Wire.of(ContainerClick.of(7, 1, 36, 0, 0, java.util.Map.of(), ru.minecomplex.mac.fixture.ClaimedStack.EMPTY))).gaps());
    }
}
