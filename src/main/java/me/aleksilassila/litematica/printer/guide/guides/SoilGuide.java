package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.Reference;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;

/**
 * 耕地/土径
 */
public class SoilGuide extends Guide {

    public SoilGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        if (requiredBlock instanceof net.minecraft.world.level.block.FarmlandBlock) {
            return Result.success(new Action().setItems(
                    Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT));
        }
        if (isDirtPath()) {
            return Result.success(new Action().setItems(
                    Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT,
                    Items.ROOTED_DIRT, Items.MYCELIUM, Items.PODZOL));
        }
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        if (requiredBlock instanceof net.minecraft.world.level.block.FarmlandBlock) {
            Block[] soilBlocks = {Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT_PATH, Blocks.COARSE_DIRT};
            for (Block soilBlock : soilBlocks) {
                if (currentBlock.equals(soilBlock)) {
                    return Result.success(new ClickAction().setItems(Reference.HOE_ITEMS));
                }
            }
        }
        if (isDirtPath()) {
            Block[] soilBlocks = {Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MYCELIUM, Blocks.PODZOL};
            for (Block soilBlock : soilBlocks) {
                if (currentBlock.equals(soilBlock)) {
                    return Result.success(new ClickAction().setItems(Reference.SHOVEL_ITEMS));
                }
            }
        }
        return Result.SKIP;
    }

    private boolean isDirtPath() {
        //#if MC >= 260300
        //$$ return requiredBlock instanceof net.minecraft.world.level.block.PathBlock;
        //#else
        return requiredBlock instanceof net.minecraft.world.level.block.DirtPathBlock;
        //#endif
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.SKIP;
    }
}
