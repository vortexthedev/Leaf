package org.dreeam.leaf.orebfuscator.config;

import com.google.common.hash.Hashing;
import net.minecraft.SharedConstants;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Parsed Orebfuscator settings of the Leaf config (misc.orebfuscator.settings), same format as
 * the config.yml of the plugin version 6.
 */
@NullMarked
public final class OrebfuscatorConfig {

    private static final int CONFIG_VERSION = 6;
    // part of the cache hash, bump when the obfuscation output changes
    private static final String IMPLEMENTATION_VERSION = "leaf-1";

    // general
    private final boolean updateOnBlockDamage;
    private final boolean bypassNotification;
    private final boolean ignoreSpectator;
    private final int updateRadius;

    // advanced
    private final boolean verbose;
    private final int threads;
    private long obfuscationTimeout = 10_000L;
    private int proximityDefaultBucketSize = 50;
    private int proximityThreadCheckInterval = 50;
    private int proximityPlayerCheckInterval = 5000;

    // cache
    private final boolean cacheEnabled;
    private int cacheMaximumSize = 32768;
    private long cacheExpireAfterAccess = TimeUnit.SECONDS.toMillis(60);
    private final boolean diskCacheEnabled;
    private Path diskCacheDirectory;
    private long diskCacheDeleteAfterAccess = TimeUnit.DAYS.toMillis(2);

    private final List<ObfuscationConfig> obfuscationConfigs = new ArrayList<>();
    private final List<ProximityConfig> proximityConfigs = new ArrayList<>();
    private final Map<ServerLevel, WorldConfigBundle> worldConfigBundles = new ConcurrentHashMap<>();

    private final byte[] systemHash;
    private final @Nullable String report;

    public OrebfuscatorConfig(final Map<String, Object> settings, final Path worldDirectory) {
        final ConfigurationSection root = new MemoryConfiguration().createSection("settings", settings);
        final ConfigIssues issues = new ConfigIssues();

        // configs of old plugin versions are not migrated
        if (root.getInt("version", CONFIG_VERSION) != CONFIG_VERSION) {
            throw new IllegalArgumentException("Orebfuscator config version " + root.getInt("version") + " is not supported, "
                + "only version " + CONFIG_VERSION + " is, remove misc.orebfuscator.settings from leaf-global.yml to get the default config");
        }

        // general
        final ConfigIssues generalIssues = issues.section("general");
        final ConfigurationSection general = section(root, "general", generalIssues);
        this.updateOnBlockDamage = general.getBoolean("updateOnBlockDamage", true);
        this.bypassNotification = general.getBoolean("bypassNotification", true);
        this.ignoreSpectator = general.getBoolean("ignoreSpectator", false);
        this.updateRadius = general.getInt("updateRadius", 2);
        generalIssues.errorMinValue("updateRadius", 1, this.updateRadius);

        // advanced
        final ConfigIssues advancedIssues = issues.section("advanced");
        final ConfigurationSection advanced = section(root, "advanced", advancedIssues);
        this.verbose = advanced.getBoolean("verbose", false);
        final int threads = advanced.getInt("threads", -1);
        this.threads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();

        final ConfigurationSection advancedObfuscation = advanced.getConfigurationSection("obfuscation");
        if (advancedObfuscation != null) {
            this.obfuscationTimeout = advancedObfuscation.getLong("timeout", 10_000L);
        } else {
            advancedIssues.section("obfuscation").warn(ConfigIssues.MISSING_USING_DEFAULTS);
        }

        final ConfigurationSection advancedProximity = advanced.getConfigurationSection("proximity");
        final ConfigIssues advancedProximityIssues = advancedIssues.section("proximity");
        if (advancedProximity != null) {
            this.proximityDefaultBucketSize = advancedProximity.getInt("defaultBucketSize", 50);
            advancedProximityIssues.errorMinValue("defaultBucketSize", 1, this.proximityDefaultBucketSize);
            this.proximityThreadCheckInterval = advancedProximity.getInt("threadCheckInterval", 50);
            advancedProximityIssues.errorMinValue("threadCheckInterval", 1, this.proximityThreadCheckInterval);
            this.proximityPlayerCheckInterval = advancedProximity.getInt("playerCheckInterval", 5000);
        } else {
            advancedProximityIssues.warn(ConfigIssues.MISSING_USING_DEFAULTS);
        }

        // cache
        final ConfigIssues cacheIssues = issues.section("cache", true);
        final ConfigurationSection cache = section(root, "cache", cacheIssues);
        final boolean cacheEnabledValue = cache.getBoolean("enabled", true);

        final ConfigIssues memoryIssues = cacheIssues.section("memoryCache");
        final ConfigurationSection memoryCache = cache.getConfigurationSection("memoryCache");
        if (memoryCache != null) {
            this.cacheMaximumSize = memoryCache.getInt("maximumSize", 32768);
            memoryIssues.errorMinValue("maximumSize", 1, this.cacheMaximumSize);
            this.cacheExpireAfterAccess = memoryCache.getLong("expireAfterAccess", TimeUnit.SECONDS.toMillis(60));
            memoryIssues.errorMinValue("expireAfterAccess", 1, this.cacheExpireAfterAccess);
        } else {
            memoryIssues.warn(ConfigIssues.MISSING_USING_DEFAULTS);
        }

        // disk cache errors only disable the disk cache
        final ConfigIssues diskIssues = cacheIssues.section("diskCache", true);
        final ConfigurationSection diskCache = cache.getConfigurationSection("diskCache");
        final Path defaultDirectory = worldDirectory.resolve("orebfuscator_cache").normalize();
        this.diskCacheDirectory = defaultDirectory;
        boolean diskCacheEnabledValue = true;
        if (diskCache != null) {
            diskCacheEnabledValue = diskCache.getBoolean("enabled", true);
            final String directory = diskCache.getString("directory", "orebfuscator_cache");
            try {
                this.diskCacheDirectory = worldDirectory.resolve(directory).normalize();
            } catch (final InvalidPathException e) {
                diskIssues.section("directory").warn("contains malformed path '%s', using default path '%s'", directory, defaultDirectory);
            }
            this.diskCacheDeleteAfterAccess = diskCache.getLong("deleteFilesAfterAccess", TimeUnit.DAYS.toMillis(2));
            diskIssues.errorMinValue("deleteFilesAfterAccess", 1, this.diskCacheDeleteAfterAccess);
        } else {
            diskIssues.warn(ConfigIssues.MISSING_USING_DEFAULTS);
        }

        if (cacheEnabledValue && diskCacheEnabledValue) {
            try {
                Files.createDirectories(this.diskCacheDirectory);
            } catch (final IOException e) {
                diskIssues.error("can't create cache directory '%s'", this.diskCacheDirectory);
            }
        }

        this.cacheEnabled = cacheIssues.disableIfError(cacheEnabledValue);
        this.diskCacheEnabled = this.cacheEnabled && diskIssues.disableIfError(diskCacheEnabledValue);

        // obfuscation and proximity sections
        final ConfigIssues obfuscationIssues = issues.section("obfuscation");
        final ConfigurationSection obfuscation = root.getConfigurationSection("obfuscation");
        if (obfuscation != null) {
            for (final String key : obfuscation.getKeys(false)) {
                final ConfigurationSection section = obfuscation.getConfigurationSection(key);
                if (section != null) {
                    this.obfuscationConfigs.add(new ObfuscationConfig(section, obfuscationIssues.section(key, true)));
                }
            }
        }
        if (this.obfuscationConfigs.isEmpty()) {
            obfuscationIssues.warn(ConfigIssues.MISSING_OR_EMPTY);
        }

        final ConfigIssues proximityIssues = issues.section("proximity");
        final ConfigurationSection proximity = root.getConfigurationSection("proximity");
        if (proximity != null) {
            for (final String key : proximity.getKeys(false)) {
                final ConfigurationSection section = proximity.getConfigurationSection(key);
                if (section != null) {
                    this.proximityConfigs.add(new ProximityConfig(section, proximityIssues.section(key, true)));
                }
            }
        }
        if (this.proximityConfigs.isEmpty()) {
            proximityIssues.warn(ConfigIssues.MISSING_OR_EMPTY);
        }

        this.report = issues.report();
        if (issues.hasErrors()) {
            throw new IllegalArgumentException(this.report);
        }

        this.systemHash = Hashing.murmur3_128().newHasher()
            .putString(IMPLEMENTATION_VERSION, StandardCharsets.UTF_8)
            .putString(SharedConstants.getCurrentVersion().name(), StandardCharsets.UTF_8)
            .putString(settings.toString(), StandardCharsets.UTF_8)
            .hash().asBytes();
    }

    private static ConfigurationSection section(final ConfigurationSection root, final String name, final ConfigIssues issues) {
        final ConfigurationSection section = root.getConfigurationSection(name);
        if (section != null) {
            return section;
        }

        issues.warn(ConfigIssues.MISSING_USING_DEFAULTS);
        return root.createSection(name);
    }

    public WorldConfigBundle world(final ServerLevel level) {
        return this.worldConfigBundles.computeIfAbsent(level, key -> new WorldConfigBundle(key, this.obfuscationConfigs, this.proximityConfigs));
    }

    public void removeWorld(final ServerLevel level) {
        this.worldConfigBundles.remove(level);
    }

    public boolean proximityEnabled() {
        for (final ProximityConfig config : this.proximityConfigs) {
            if (config.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    public byte[] systemHash() {
        return this.systemHash;
    }

    public @Nullable String report() {
        return this.report;
    }

    public boolean updateOnBlockDamage() {
        return this.updateOnBlockDamage;
    }

    public boolean bypassNotification() {
        return this.bypassNotification;
    }

    public boolean ignoreSpectator() {
        return this.ignoreSpectator;
    }

    public int updateRadius() {
        return this.updateRadius;
    }

    public boolean verbose() {
        return this.verbose;
    }

    public int threads() {
        return this.threads;
    }

    public boolean hasObfuscationTimeout() {
        return this.obfuscationTimeout > 0;
    }

    public long obfuscationTimeout() {
        return this.obfuscationTimeout;
    }

    public int proximityDefaultBucketSize() {
        return this.proximityDefaultBucketSize;
    }

    public int proximityThreadCheckInterval() {
        return this.proximityThreadCheckInterval;
    }

    public boolean hasProximityPlayerCheckInterval() {
        return this.proximityPlayerCheckInterval > 0;
    }

    public int proximityPlayerCheckInterval() {
        return this.proximityPlayerCheckInterval;
    }

    public boolean cacheEnabled() {
        return this.cacheEnabled;
    }

    public int cacheMaximumSize() {
        return this.cacheMaximumSize;
    }

    public long cacheExpireAfterAccess() {
        return this.cacheExpireAfterAccess;
    }

    public boolean diskCacheEnabled() {
        return this.diskCacheEnabled;
    }

    public Path diskCacheDirectory() {
        return this.diskCacheDirectory;
    }

    public long diskCacheDeleteAfterAccess() {
        return this.diskCacheDeleteAfterAccess;
    }
}
