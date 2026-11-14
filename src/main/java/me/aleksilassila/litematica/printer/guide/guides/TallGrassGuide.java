package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Survival-obtainable small plants followed by bone meal, as in upstream@6cef6d31. */
public final class TallGrassGuide extends Guide {
    public TallGrassGuide(SchematicBlockContext context) { super(context); }

    @Override protected boolean canExecute() {
        return requiredBlock == Blocks.TALL_GRASS || requiredBlock == Blocks.LARGE_FERN;
    }

    @Override protected Result onBuildAction(BlockMatchResult state) {
        if (currentBlock == (requiredBlock == Blocks.TALL_GRASS ? Blocks.SHORT_GRASS : Blocks.FERN)) {
            return Result.success(new ClickAction().setItem(Items.BONE_MEAL));
        }
        return Result.PASS;
    }

    @Override protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        return Result.success(new Action().setItem(requiredBlock == Blocks.TALL_GRASS ? Items.SHORT_GRASS : Items.FERN)
                .setSides(Direction.DOWN).setRequiresSupport());
    }
}
