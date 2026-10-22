package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

/** Real charge/release/launch in both hands, and delayed rain roof facts. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ItemRiptideOracleTest {

    @Test
    void knownStrength()throws Exception{new RiptideProbeOracleTest().replay(false,"item-riptides");}
    @Test
    void delayedLight()throws Exception{new RiptideProbeOracleTest().replay(true,"item-riptides");}
}
