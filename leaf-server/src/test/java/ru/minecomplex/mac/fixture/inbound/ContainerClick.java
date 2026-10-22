package ru.minecomplex.mac.fixture.inbound;

import ru.minecomplex.mac.fixture.ClaimedStack;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import ru.minecomplex.mac.fixture.ItemSampleCodec;
import ru.minecomplex.mac.fixture.ItemComponentSample;
import ru.minecomplex.mac.fixture.WireItem;

/**
 * {@code container_click}: the client already applied the click to its open menu ({@code AbstractContainerMenu.clicked}
 * in {@code MultiPlayerGameMode.handleInventoryMouseClick}/{@code handleContainerInput}) and reports the result: every
 * menu slot whose stack changed ({@code ItemStack.matches}) and the carried stack. The stacks are flat payloads
 * ({@link #changed()}, {@link #carried()}).
 *
 * @param clickType {@code ClickType}/{@code ContainerInput} ordinal (pickup, quick move, swap, clone, throw, quick craft,
 *                  pickup all)
 */
public record ContainerClick(int containerId, int stateId, int slot, int button, int clickType, byte[] changedSlots,
                             byte[] carriedStack) implements InboundEvent {

    public ContainerClick {
        changedSlots = changedSlots.clone();
        carriedStack = carriedStack.clone();
    }

    /** @param changed menu slot → its stack after the click */
    public static ContainerClick of(int containerId, int stateId, int slot, int button, int clickType,
                                    Map<Integer, ClaimedStack> changed, ClaimedStack carried) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            Map<Integer, ClaimedStack> sorted = new TreeMap<>(changed);
            out.writeInt(sorted.size());
            for (var entry : sorted.entrySet()) {
                out.writeInt(entry.getKey());
                write(out, entry.getValue());
            }
            var carriedBytes = new ByteArrayOutputStream();
            write(new DataOutputStream(carriedBytes), carried);
            return new ContainerClick(containerId, stateId, slot, button, clickType, bytes.toByteArray(), carriedBytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** Menu slot → its stack after the click, in slot order. */
    public Map<Integer, ClaimedStack> changed() {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(changedSlots));
            int count = in.readInt();
            Map<Integer, ClaimedStack> changed = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                int slot = in.readInt();
                changed.put(slot, read(in));
            }
            return changed;
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    /** The carried stack after the click. */
    public ClaimedStack carried() {
        try {
            return read(new DataInputStream(new ByteArrayInputStream(carriedStack)));
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    @Override
    public byte[] changedSlots() {
        return changedSlots.clone();
    }

    @Override
    public byte[] carriedStack() {
        return carriedStack.clone();
    }

    private static void write(DataOutputStream out, ClaimedStack claim) throws IOException {
        WireItem stack = claim.stack();
        out.writeBoolean(claim.hashed());
        out.writeUTF(stack.item());
        out.writeInt(stack.count());
        out.writeInt(stack.set());
        out.writeInt(stack.removed());
        // a hashed stack carries no values
        if (!claim.hashed()) ItemSampleCodec.write(out, stack.values(), stack.set());
    }

    private static ClaimedStack read(DataInputStream in) throws IOException {
        boolean hashed = in.readBoolean();
        String item = in.readUTF();
        int count = in.readInt(), set = in.readInt(), removed = in.readInt();
        if (hashed) return ClaimedStack.hashed(item, count, set, removed);
        ItemComponentSample values = ItemSampleCodec.read(in, set);
        return ClaimedStack.exact(new WireItem(item, count, set, removed, values));
    }

    /** A container screen click, from input handling. */
    @Override
    public boolean betweenTicks() {
        return true;
    }
}
