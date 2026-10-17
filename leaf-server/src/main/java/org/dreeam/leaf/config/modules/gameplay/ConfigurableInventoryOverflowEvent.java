package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class ConfigurableInventoryOverflowEvent extends ConfigModule {

    public String basePath() {
        return ConfigCategory.GAMEPLAY.basePath() + ".inventory-overflow-event";
    }

    public static boolean enabled = false;
    public static String listenerClass = "com.example.package.PlayerInventoryOverflowEvent";

    @Override
    public void onLoaded() {
        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled, """
                The event called when used plugin to Inventory#addItem
                into player's inventory, and the inventory is full.
                This is not recommended to use, please re-design to use the
                returned map of Inventory#addItem method as soon as possible!""");
        listenerClass = globalConfig.getString(basePath() + ".listener-class", listenerClass, """
                The full class name of the listener which listens to this inventory overflow event.""");
    }
}
