package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LanternBlock;

/**
 * 灯笼
 */
public class LanternGuide extends Guide {

    public LanternGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        if (getProperty(requiredState, LanternBlock.HANGING).orElse(false)) {
<<<<<<< HEAD
            return Result.success(new Action()
                    .setSides(Direction.UP)
                    .setLookDirection(Direction.UP)
                    .setRequiresSupport());
        }
        return Result.success(new Action()
                .setSides(Direction.DOWN)
                .setLookDirection(Direction.DOWN)
                .setRequiresSupport());
=======
            return Result.success(new Action().setLookDirection(Direction.UP));
        }
        return Result.success(new Action().setLookDirection(Direction.DOWN));
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }
}
