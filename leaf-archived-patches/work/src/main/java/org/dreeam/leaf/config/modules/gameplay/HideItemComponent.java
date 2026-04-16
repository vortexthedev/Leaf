package org.dreeam.leaf.config.modules.gameplay;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;
import org.dreeam.leaf.config.LeafConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * HideItemComponent
 *
 * @author TheFloodDragon
 * @since 2025/2/4 18:30
 */
public class HideItemComponent extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.GAMEPLAY.getBaseKeyName() + ".hide-item-component";
    }

    public static boolean enabled = false;
    public static List<String> hiddenTypeStrings = new ArrayList<>();
    public static List<DataComponentType<?>> hiddenTypes = List.of();

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(), """
                Controls whether specified component information would be sent to clients.
                It may break resource packs and mods that rely on the information.
                Also, it can avoid some frequent client animations.
                Attention: This is not same as Paper's item-obfuscation, we only hide specified component information from player's inventory.""");
        hiddenTypeStrings = config.getList(getBasePath() + ".hidden-types", new ArrayList<>(), """
                Which type of components will be hidden from clients.
                It needs a component type list, incorrect things will not work.""");
        enabled = config.getBoolean(getBasePath() + ".enabled", enabled,
            "If enabled, specified item component information from player's inventory will be hided.");
    }

    @Override
    public void onPostLoaded() {
        final List<DataComponentType<?>> types = new ArrayList<>(hiddenTypeStrings.size());

        for (String componentType : hiddenTypeStrings) {
            BuiltInRegistries.DATA_COMPONENT_TYPE.get(Identifier.parse(componentType)).ifPresentOrElse(
                optional -> types.add(optional.value()),
                () -> LeafConfig.LOGGER.warn("Unknown component type: {}", componentType)
            );
        }

        hiddenTypes = types;
    }
}
