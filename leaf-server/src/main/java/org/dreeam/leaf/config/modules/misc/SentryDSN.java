package org.dreeam.leaf.config.modules.misc;

import org.apache.logging.log4j.Level;
import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class SentryDSN extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.MISC.getBaseKeyName() + ".sentry";
    }

    public static String sentryDsnConfigPath;
    public static String sentryDsn = "";
    public static String logLevel = "WARN";
    public static boolean onlyLogThrown = true;

    @Override
    public void onLoaded() {
        String sentryEnvironment = System.getenv("SENTRY_DSN");
        String sentryConfig = config.getString(sentryDsnConfigPath = getBasePath() + ".dsn", sentryDsn, """
                Sentry DSN for improved error logging, leave blank to disable,
                Obtain from https://sentry.io/""");

        sentryDsn = sentryEnvironment == null
            ? sentryConfig
            : sentryEnvironment;
        logLevel = config.getString(getBasePath() + ".log-level", logLevel, """
                Logs with a level higher than or equal to this level will be recorded.""");
        onlyLogThrown = config.getBoolean(getBasePath() + ".only-log-thrown", onlyLogThrown, """
                Only log with a Throwable will be recorded after enabling this.""");

        if (sentryDsn != null && !sentryDsn.isBlank()) {
            gg.pufferfish.pufferfish.sentry.SentryManager.init(Level.getLevel(logLevel));
        }
    }
}
