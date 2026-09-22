package me.aleksilassila.litematica.printer.guide.guides;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterGuideTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void liquidTargetGuideIncludesLavaForWrongBlockCleanup() {
        assertTrue(WaterGuide.isFluidTarget(Blocks.LAVA.defaultBlockState()));
    }
}
