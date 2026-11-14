package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** The lower half supplies the upper half and requires the upstream placement direction. */
public final class SmallDripleafGuide extends Guide {
    public SmallDripleafGuide(SchematicBlockContext context) { super(context); }
    @Override protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        return Result.success(new Action().setSides(Direction.DOWN).setRequiresSupport()
                .setLookDirection(requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite())
                .setNeedWaitModifyLook());
    }
}
