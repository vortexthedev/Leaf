package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ConfigurableInventoryOverflowEvent extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.GAMEPLAY.getBaseKeyName() + ".inventory-overflow-event";
    }

    public static boolean enabled = false;
    public static String listenerClass = "com.example.package.PlayerInventoryOverflowEvent";

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath() + ".enabled", enabled, """
                The event called when used plugin to Inventory#addItem
                into player's inventory, and the inventory is full.
                This is not recommended to use, please re-design to use the
                returned map of Inventory#addItem method as soon as possible!""");
        listenerClass = config.getString(getBasePath() + ".listener-class", listenerClass, """
                The full class name of the listener which listens to this inventory overflow event.""");
    }
}
