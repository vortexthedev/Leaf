package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.LeafConfig;
import org.dreeam.leaf.config.annotations.Experimental;

import java.util.regex.Pattern;

public class VanillaUsernameCheck extends ConfigModule {

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".vanilla-username-check";
    }

    @Deprecated
    public static boolean removeAllCheck = false;
    public static boolean enforceSkullValidation = true;
    @Experimental
    public static boolean allowOldPlayersJoin = false;
    public static boolean useUsernameRegex = false;
    private static final String defaultRegexString = "^[a-zA-Z0-9_.]*$";
    public static Pattern usernameRegex = Pattern.compile(defaultRegexString);
    public static boolean shouldSkipNonPlayerNameCheck() { // helper
        return removeAllCheck || useUsernameRegex;
    }

    @Override
    public void onLoaded() {
        removeAllCheck = globalConfig.getBoolean(basePath() + ".remove-all-check", removeAllCheck, """
                Remove Vanilla username check,
                allowing all characters as username.
                WARNING: UNSAFE, USE AT YOUR OWN RISK!""");
        enforceSkullValidation = globalConfig.getBoolean(basePath() + ".enforce-skull-validation", enforceSkullValidation, """
                Enforce skull validation,
                preventing skulls with invalid names from disconnecting the client.""");
        allowOldPlayersJoin = globalConfig.getBoolean(basePath() + ".allow-old-players-join", allowOldPlayersJoin, """
                Allow old players to join the server after the username regex is changed,
                even if their names don't meet the new requirements.""");
        useUsernameRegex = globalConfig.getBoolean(basePath() + ".use-username-regex", useUsernameRegex, """
                Use username regex to validate usernames,
                allowing only characters specified in the regex.""");
        String regexString = globalConfig.getString(basePath() + ".username-regex", defaultRegexString, """
            Username regex,
            specifying the characters allowed in usernames.
            Default: %s""".formatted(defaultRegexString));
        if (!regexString.isBlank()) {
            try {
                usernameRegex = Pattern.compile(regexString);
            } catch (Exception e) {
                LeafConfig.LOGGER.error("Invalid username regex {} found, falling back to default.", regexString, e);
            }
        }
        if (useUsernameRegex && removeAllCheck) {
            LeafConfig.LOGGER.warn("Found conflicting configuration, remove-all-check and use-username-regex cannot be enabled at same time, ignoring remove-all-check...");
            removeAllCheck = false;
        }
    }
}
