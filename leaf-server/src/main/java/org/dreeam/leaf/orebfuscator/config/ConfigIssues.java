package org.dreeam.leaf.orebfuscator.config;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects config warnings and errors per config path. Errors of an isolated section only disable
 * that section, errors of other sections also count for their parent.
 */
@NullMarked
public final class ConfigIssues {

    static final String MISSING_OR_EMPTY = "is missing or empty";
    static final String MISSING_USING_DEFAULTS = "is missing, using defaults";
    static final String DISABLED_ERRORS = "got disabled due to errors";

    private final Map<String, ConfigIssues> sections = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean isolateErrors;

    public ConfigIssues section(final String name) {
        return this.section(name, false);
    }

    public ConfigIssues section(final String name, final boolean isolateErrors) {
        final ConfigIssues section = this.sections.computeIfAbsent(name, key -> new ConfigIssues());
        section.isolateErrors = isolateErrors;
        return section;
    }

    public void warn(final String message, final Object... arguments) {
        this.warnings.add(arguments.length == 0 ? message : message.formatted(arguments));
    }

    public void error(final String message, final Object... arguments) {
        this.errors.add(arguments.length == 0 ? message : message.formatted(arguments));
    }

    void errorMinValue(final String path, final long min, final long value) {
        if (value < min) {
            this.section(path).error("value too low {value(%d) < min(%d)}", value, min);
        }
    }

    void errorMinMaxValue(final String path, final long min, final long max, final long value) {
        if (value < min || value > max) {
            this.section(path).error("value out of range {value(%d) not in range[%d, %d]}", value, min, max);
        }
    }

    public boolean hasErrors() {
        if (!this.errors.isEmpty()) {
            return true;
        }
        for (final ConfigIssues section : this.sections.values()) {
            if (!section.isolateErrors && section.hasErrors()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the enabled state after errors are taken into account
     */
    boolean disableIfError(final boolean enabled) {
        if (enabled && this.hasErrors()) {
            this.warn(DISABLED_ERRORS);
            return false;
        }
        return enabled;
    }

    private int count() {
        int count = this.errors.size() + this.warnings.size();
        for (final ConfigIssues section : this.sections.values()) {
            count += section.count();
        }
        return count;
    }

    private void append(final StringBuilder builder, final String indent) {
        for (final String error : this.errors) {
            builder.append(indent).append("- [error] ").append(error).append('\n');
        }
        for (final String warning : this.warnings) {
            builder.append(indent).append("- ").append(warning).append('\n');
        }
        for (final Map.Entry<String, ConfigIssues> entry : this.sections.entrySet()) {
            if (entry.getValue().count() > 0) {
                builder.append(indent).append(entry.getKey()).append(":\n");
                entry.getValue().append(builder, indent + "  ");
            }
        }
    }

    /**
     * @return a readable report of all issues or null if there are none
     */
    public @Nullable String report() {
        final int count = this.count();
        if (count == 0) {
            return null;
        }

        final StringBuilder builder = new StringBuilder()
            .append("Encountered ").append(count).append(" issue(s) while parsing the Orebfuscator config:\n");
        this.append(builder, "");
        return builder.toString();
    }
}
