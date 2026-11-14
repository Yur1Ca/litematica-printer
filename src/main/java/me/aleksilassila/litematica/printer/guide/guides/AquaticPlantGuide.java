package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;

/** Aquatic placement rules adapted from BiliXWhite/litematica-printer@6cef6d31. */
public final class AquaticPlantGuide extends Guide {
    public AquaticPlantGuide(SchematicBlockContext context) { super(context); }

    @Override protected Result onBuildAction(BlockMatchResult state) {
        if (state == BlockMatchResult.WRONG_STATE && requiredBlock instanceof KelpBlock
                && requiredState.getValue(KelpBlock.AGE) == 25 && currentState.getValue(KelpBlock.AGE) < 25) {
            return Result.success(new ClickAction().setItem(Items.SHEARS));
        }
        if ((requiredBlock instanceof KelpBlock || requiredBlock instanceof KelpPlantBlock)
                && (currentBlock instanceof KelpBlock || currentBlock instanceof KelpPlantBlock)) {
            // Adding the upper neighbor turns a kelp tip into its stem automatically.
            return Result.SKIP;
        }
        if (requiredBlock instanceof TallSeagrassBlock && currentBlock == Blocks.SEAGRASS) {
            return Result.success(new ClickAction().setItem(Items.BONE_MEAL));
        }
        return Result.PASS;
    }

    @Override protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        return Result.success(new Action()
                .setItem(requiredBlock instanceof KelpBlock || requiredBlock instanceof KelpPlantBlock
                        ? Items.KELP : Items.SEAGRASS)
                .setSides(Direction.DOWN).setRequiresSupport());
    }
}
