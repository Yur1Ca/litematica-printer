package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.interaction.StrippableBlockLookup;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Map;

/**
 * 去皮原木
 */
public class StripLogGuide extends Guide {

    @SuppressWarnings("all")
    private static final Map<Block, Block> STRIPPED_LOGS = StrippableBlockLookup.strippables();

    public StripLogGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction.Axis axis = getProperty(requiredState, BlockStateProperties.AXIS).orElse(null);
        if (axis == null) return Result.PASS;

        Action action = new Action().setSides(axis);

        // 配置启用去皮时，可接受原版或去皮版本
        if (Configs.Print.STRIP_LOGS.getBooleanValue()) {
            for (Map.Entry<Block, Block> entry : STRIPPED_LOGS.entrySet()) {
                if (requiredBlock == entry.getValue()) {
                    action.setItems(entry.getValue().asItem(), entry.getKey().asItem());
                    return Result.success(action);
                }
            }
        }

        return Result.success(action);
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        // Stateful source-log stripping is exclusively owned by PrintWorkflowScheduler.
        return Result.PASS;
    }
}
