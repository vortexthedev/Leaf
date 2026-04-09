package org.dreeam.leaf.config.modules.opt;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;
import org.dreeam.leaf.config.LeafConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class DynamicActivationofBrain extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".dab";
    }

    public static boolean enabled = false;
    public static int startDistance = 12;
    public static int startDistanceSquared;
    public static int maximumActivationPrio = 20;
    public static int activationDistanceMod = 8;
    public static boolean dontEnableIfInWater = false;
    public static List<String> blackedEntities = new ArrayList<>(Arrays.asList(
        "villager",
        "axolotl",
        "hoglin",
        "zombified_piglin",
        "goat"
    ));

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(), """
                Optimizes entity brains when
                they're far away from the player""");

        enabled = config.getBoolean(getBasePath() + ".enabled", enabled);
        dontEnableIfInWater = config.getBoolean(getBasePath() + ".dont-enable-if-in-water", dontEnableIfInWater, """
                After enabling this, non-aquatic entities in the water will not be affected by DAB.
                This could fix entities suffocate in the water.""");
        startDistance = config.getInt(getBasePath() + ".start-distance", startDistance, """
                This value determines how far away an entity has to be
                from the player to start being effected by DEAR.""");
        maximumActivationPrio = config.getInt(getBasePath() + ".max-tick-freq", maximumActivationPrio, """
                This value defines how often in ticks, the furthest entity
                will get their pathfinders and behaviors ticked. 20 = 1s""");
        activationDistanceMod = config.getInt(getBasePath() + ".activation-dist-mod", activationDistanceMod, """
            This value defines how much distance modifies an entity's
            tick frequency. freq = (distanceToPlayer^2) / (2^value)",
            If you want further away entities to tick less often, use 7.
            If you want further away entities to tick more often, try 9.""");
        blackedEntities = config.getList(getBasePath() + ".blacklisted-entities", blackedEntities,"A list of entities to ignore for activation");

        startDistanceSquared = startDistance * startDistance;
    }

    @Override
    public void onPostLoaded() {
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            entityType.dabEnabled = true; // reset all, before setting the ones to true
        }

        final String DEFAULT_PREFIX = Identifier.DEFAULT_NAMESPACE + Identifier.NAMESPACE_SEPARATOR;

        for (String name : blackedEntities) {
            // Be compatible with both `minecraft:example` and `example` syntax
            // If unknown, show user config value in the logger instead of parsed result
            String lowerName = name.toLowerCase(Locale.ROOT);
            String typeId = lowerName.startsWith(DEFAULT_PREFIX) ? lowerName : DEFAULT_PREFIX + lowerName;

            EntityType.byString(typeId).ifPresentOrElse(entityType ->
                    entityType.dabEnabled = false,
                () -> LeafConfig.LOGGER.warn("Skip unknown entity {}, in {}", name, getBasePath() + ".blacklisted-entities")
            );
        }
    }
}
