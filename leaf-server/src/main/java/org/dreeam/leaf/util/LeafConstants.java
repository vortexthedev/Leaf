package org.dreeam.leaf.util;

public final class LeafConstants {

    private LeafConstants() {
    }

    public static final boolean ENABLE_FMA = Boolean.getBoolean("Leaf.enableFMA");
    public static final boolean ENABLE_IO_URING = Boolean.getBoolean("Leaf.enable-io-uring");

}
