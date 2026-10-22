package ru.minecomplex.mac.entity.attribute;

/**
 * {@code net.minecraft.resources.Identifier} ({@code Identifier} before 1.21.11) as a hash key. The hash code is
 * vanilla's, because it decides the iteration order of attribute modifiers ({@link ModifierTable}).
 */
public record Identifier(String namespace, String path) {
    public static Identifier parse(String text) {
        int colon = text.indexOf(':');
        return colon < 0 ? new Identifier("minecraft", text) : new Identifier(text.substring(0, colon), text.substring(colon + 1));
    }

    public static Identifier minecraft(String path) {
        return new Identifier("minecraft", path);
    }

    @Override
    public int hashCode() {
        return 31 * namespace.hashCode() + path.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Identifier that && namespace.equals(that.namespace) && path.equals(that.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
