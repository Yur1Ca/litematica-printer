package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 箱子
 */
public class ChestGuide extends Guide {

    public ChestGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, ChestBlock.FACING).orElseThrow();

        Direction facingOpposite = facing.getOpposite();
        ChestType chestType = getProperty(requiredState, BlockStateProperties.CHEST_TYPE).orElse(ChestType.SINGLE);

        // 收集所有不与其他箱子相邻的面
        Map<Direction, Vec3> noChestSides = new HashMap<>();
        for (Direction side : Direction.values()) {
            if (level.getBlockState(blockPos.relative(side)).getBlock() instanceof ChestBlock) {
                continue;
            }
            noChestSides.put(side, Vec3.ZERO);
        }

        if (chestType == ChestType.SINGLE) {
            // 有水平方向的箱子邻居 → 潜行放置（防止自动合并）
            boolean hasChestNeighbor = Direction.Plane.HORIZONTAL.stream()
                    .anyMatch(s -> !noChestSides.containsKey(s));
            if (hasChestNeighbor) {
                return Result.success(new Action().setSides(noChestSides).setLookDirection(facingOpposite).setShift());
            }
            return Result.success(new Action().setSides(noChestSides).setLookDirection(facingOpposite));
        }

        Direction partnerDir = chestType == ChestType.LEFT
                ? facing.getClockWise()
                : facing.getCounterClockWise();
        BlockPos partnerPos = blockPos.relative(partnerDir);
        BlockState expectedPartner = schematic.getBlockState(partnerPos);
        BlockState actualPartner = level.getBlockState(partnerPos);
        DoubleChestStep step = chooseDoubleChestStep(requiredState, expectedPartner, actualPartner,
                blockPos, partnerPos);
        if (step == DoubleChestStep.WAIT) return Result.SKIP;
        if (step == DoubleChestStep.JOIN_PARTNER) {
            // Sneak-click the matching single chest: vanilla then selects that exact partner.
            return Result.success(new Action().setSides(partnerDir).setRequiresSupport()
                    .setLookDirection(facingOpposite).setShift());
        }
        // A missing partner must not make this half merge with an unrelated adjacent chest.
        return Result.success(new Action().setSides(noChestSides)
                .setLookDirection(facingOpposite).setShift());
    }

    static DoubleChestStep chooseDoubleChestStep(BlockState required, BlockState expectedPartner,
                                                 BlockState actualPartner,
                                                 BlockPos targetPos,
                                                 BlockPos partnerPos) {
        if (!(expectedPartner.getBlock() instanceof ChestBlock)
                || expectedPartner.getBlock() != required.getBlock()
                || expectedPartner.getValue(ChestBlock.FACING) != required.getValue(ChestBlock.FACING)
                || expectedPartner.getValue(BlockStateProperties.CHEST_TYPE)
                != (required.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.LEFT
                        ? ChestType.RIGHT : ChestType.LEFT)) {
            return DoubleChestStep.WAIT;
        }
        if (actualPartner.getBlock() != required.getBlock()) {
            return targetPos.getX() < partnerPos.getX() || targetPos.getZ() < partnerPos.getZ()
                    ? DoubleChestStep.FIRST_HALF : DoubleChestStep.WAIT;
        }
        return actualPartner.getValue(ChestBlock.FACING) == required.getValue(ChestBlock.FACING)
                && actualPartner.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE
                ? DoubleChestStep.JOIN_PARTNER : DoubleChestStep.WAIT;
    }

    enum DoubleChestStep { FIRST_HALF, JOIN_PARTNER, WAIT }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.SKIP;
    }
}
