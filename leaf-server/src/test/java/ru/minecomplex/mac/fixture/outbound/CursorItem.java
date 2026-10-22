package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;

import ru.minecomplex.mac.fixture.WireItem;

/** {@code set_cursor_item}: the open menu's carried stack, unless the creative inventory screen is open. */
public record CursorItem(byte[] item) implements OutboundEvent {

    public CursorItem {
        item = item.clone();
    }

    public static CursorItem of(WireItem item) {
        return new CursorItem(WireItem.encode(item));
    }

    public WireItem stack() {
        return WireItem.decodeOne(item);
    }

    @Override
    public byte[] item() {
        return item.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CursorItem that && Arrays.equals(item, that.item);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(item);
    }

    @Override
    public String toString() {
        return "CursorItem[item=" + stack() + "]";
    }
}
