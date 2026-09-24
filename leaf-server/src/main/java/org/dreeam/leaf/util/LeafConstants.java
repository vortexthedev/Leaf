package org.dreeam.leaf.util;

public final class LeafConstants {

    private LeafConstants() {
    }

    public static final boolean ENABLE_FMA = Boolean.getBoolean("Leaf.enableFMA");
    public static final boolean ENABLE_IO_URING = Boolean.getBoolean("Leaf.enable-io-uring");
    public static final boolean ENABLE_BASE64CODER_WARNING = Boolean.getBoolean("Leaf.enable-base64coder-warning");
    public static final boolean DISABLE_VANILLA_DEBUG_FEATURE = Boolean.getBoolean("Leaf.disable-vanilla-debug-feature");
    public static final String LINEAR_V2_READ_ONLY_FLAG = "Leaf.linear-v2-read-only";
    public static final boolean LINEAR_V2_READ_ONLY = Boolean.getBoolean(LINEAR_V2_READ_ONLY_FLAG);

}
