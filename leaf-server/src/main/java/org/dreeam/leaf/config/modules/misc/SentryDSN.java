package org.dreeam.leaf.config.modules.misc;

import org.apache.logging.log4j.Level;
import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class SentryDSN extends ConfigModule {

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".sentry";
    }

    public static String sentryDsnConfigPath;
    public static String sentryDsn = "";
    public static String logLevel = "WARN";
    public static boolean onlyLogThrown = true;

    @Override
    public void onLoaded() {
        String sentryEnvironment = System.getenv("SENTRY_DSN");
        String sentryConfig = globalConfig.getString(sentryDsnConfigPath = basePath() + ".dsn", sentryDsn, """
                Sentry DSN for improved error logging, leave blank to disable,
                Obtain from https://sentry.io/""");

        sentryDsn = sentryEnvironment == null
            ? sentryConfig
            : sentryEnvironment;
        logLevel = globalConfig.getString(basePath() + ".log-level", logLevel, """
                Logs with a level higher than or equal to this level will be recorded.""");
        onlyLogThrown = globalConfig.getBoolean(basePath() + ".only-log-thrown", onlyLogThrown, """
                Only log with a Throwable will be recorded after enabling this.""");

        if (sentryDsn != null && !sentryDsn.isBlank()) {
            gg.pufferfish.pufferfish.sentry.SentryManager.init(Level.getLevel(logLevel));
        }
    }
}
