package ru.minecomplex.mac.world;

import java.util.ArrayList;
import java.util.List;

/**
 * The sections of one chunk column, as the client received them, indexed from the world's lowest section.
 *
 * <p>Columns are shared between a world and its forks. Only the world whose token owns a column mutates it in
 * place; any other world copies the column (and each section it changes) first.
 */
public final class ChunkColumn {
    /** Diagnostic input history; truncation remains explicit uncertainty until verified replay snapshots exist. */
    private static final int MAX_DYNAMIC_INPUTS = 256;
    private final int x;
    private final int z;
    private final PalettedSection[] sections;
    private final boolean[] ownedSections;
    private Object owner;
    private Object chunkGeneration = new Object();
    private List<BlockInput> dynamicInputs = List.of();
    private boolean dynamicInputsTruncated;

    public ChunkColumn(int x, int z, PalettedSection[] sections) {
        this(x, z, sections, null);
    }

    private ChunkColumn(int x, int z, PalettedSection[] sections, Object owner) {
        this.x = x;
        this.z = z;
        this.sections = sections;
        this.ownedSections = new boolean[sections.length];
        this.owner = owner;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public int sectionCount() {
        return sections.length;
    }

    /** The section at index {@code i} counted from the bottom of the world, or {@code null} outside. */
    public PalettedSection section(int i) {
        return i >= 0 && i < sections.length ? sections[i] : null;
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public boolean ownedBy(Object token) {
        return owner == token;
    }

    /** Takes exclusive ownership of a column nobody else can reference any more (fresh or promoted). */
    public void claim(Object token) {
        owner = token;
        java.util.Arrays.fill(ownedSections, true);
    }

    /** A column for {@code token} sharing every section with this one. */
    public ChunkColumn forkFor(Object token) {
        ChunkColumn fork = new ChunkColumn(x, z, sections.clone(), token);
        fork.dynamicInputs = dynamicInputs;
        fork.dynamicInputsTruncated = dynamicInputsTruncated;
        fork.chunkGeneration = chunkGeneration;
        return fork;
    }

    /** Immutable handler inputs for this loaded column, in packet order; not an animated block-entity snapshot. */
    public List<BlockInput> dynamicInputs() { return dynamicInputs; }

    public boolean dynamicInputsTruncated() { return dynamicInputsTruncated; }

    public Object chunkGeneration() { return chunkGeneration; }

    public ChunkColumn detached() {
        PalettedSection[] copy = new PalettedSection[sections.length];
        for (int i = 0; i < copy.length; i++) copy[i] = sections[i].copy();
        ChunkColumn result = new ChunkColumn(x, z, copy);
        result.chunkGeneration = chunkGeneration; result.dynamicInputs = dynamicInputs;
        result.dynamicInputsTruncated = dynamicInputsTruncated;
        return result;
    }

    public void dynamicInput(BlockInput input) {
        var copy = new ArrayList<>(dynamicInputs);
        if (copy.size() == MAX_DYNAMIC_INPUTS) {
            copy.removeFirst();
            dynamicInputsTruncated = true;
        }
        copy.add(input);
        dynamicInputs = List.copyOf(copy);
    }

    /** Sets a block, copying the section first if it is shared. Only for columns owned by the caller. */
    public void setBlock(int sectionIndex, int localX, int localY, int localZ, int state) {
        PalettedSection section = sections[sectionIndex];
        if (!ownedSections[sectionIndex]) {
            section = section.copy();
            sections[sectionIndex] = section;
            ownedSections[sectionIndex] = true;
        }
        section.set(localX, localY, localZ, state);
    }
}
