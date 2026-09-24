package me.aleksilassila.litematica.printer.guide.guides;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChestGuideTest {
    private final BlockState left = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);
    private final BlockState right = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.RIGHT);
    private final BlockState single = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void secondHalfWaitsForFirstHalfWorldUpdate() {
        assertEquals(ChestGuide.DoubleChestStep.WAIT,
                ChestGuide.chooseDoubleChestStep(right, left, Blocks.AIR.defaultBlockState(),
                        new BlockPos(1, 0, 0), new BlockPos(0, 0, 0)));
    }

    @Test
    void secondHalfTargetsConfirmedMatchingSingleChest() {
        assertEquals(ChestGuide.DoubleChestStep.JOIN_PARTNER,
                ChestGuide.chooseDoubleChestStep(right, left, single,
                        new BlockPos(1, 0, 0), new BlockPos(0, 0, 0)));
    }

    @Test
    void firstHalfCanBePlacedWithoutPartner() {
        assertEquals(ChestGuide.DoubleChestStep.FIRST_HALF,
                ChestGuide.chooseDoubleChestStep(left, right, Blocks.AIR.defaultBlockState(),
                        new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)));
    }

    @Test
    void zAxisPairUsesTheSameFirstHalfRule() {
        BlockState eastLeft = left.setValue(ChestBlock.FACING, Direction.EAST);
        BlockState eastRight = right.setValue(ChestBlock.FACING, Direction.EAST);
        assertEquals(ChestGuide.DoubleChestStep.FIRST_HALF,
                ChestGuide.chooseDoubleChestStep(eastLeft, eastRight, Blocks.AIR.defaultBlockState(),
                        new BlockPos(0, 0, 0), new BlockPos(0, 0, 1)));
        assertEquals(ChestGuide.DoubleChestStep.WAIT,
                ChestGuide.chooseDoubleChestStep(eastRight, eastLeft, Blocks.AIR.defaultBlockState(),
                        new BlockPos(0, 0, 1), new BlockPos(0, 0, 0)));
    }

    @Test
    void refusesToJoinWrongFacingOrWrongSchematicPartner() {
        assertEquals(ChestGuide.DoubleChestStep.WAIT,
                ChestGuide.chooseDoubleChestStep(right, left,
                        single.setValue(ChestBlock.FACING, Direction.SOUTH),
                        new BlockPos(1, 0, 0), new BlockPos(0, 0, 0)));
        assertEquals(ChestGuide.DoubleChestStep.WAIT,
                ChestGuide.chooseDoubleChestStep(right, right, single,
                        new BlockPos(1, 0, 0), new BlockPos(0, 0, 0)));
    }
}
