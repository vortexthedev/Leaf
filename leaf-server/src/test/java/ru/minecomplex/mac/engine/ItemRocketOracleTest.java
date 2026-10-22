package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

/** Separately pinned current-JAR rocket traces include actual useItem in both hands and real attached rocket ticks. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ItemRocketOracleTest {

    @Test
    void actualUseAndBoost()throws Exception{new FireworkProbeOracleTest().replayResource(false,"item-rocket-flights");}
    @Test
    void actualUseAndBoostWithLateMarkers()throws Exception{new FireworkProbeOracleTest().replayResource(true,"item-rocket-flights");}
}
