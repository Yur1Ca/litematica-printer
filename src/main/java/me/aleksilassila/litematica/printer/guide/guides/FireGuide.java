package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.SoulFireBlock;
import net.minecraft.world.item.Items;

/**
 * 火焰
 */
public class FireGuide extends Guide {

    public FireGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        return Result.success(new Action()
                .setSides(findFireDirection())
                .setItems(Items.FIRE_CHARGE, Items.FLINT_AND_STEEL)
                .setRequiresSupport());
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        // AGE 不同 → 环境决定，无法修正，跳过
        if (!getProperty(requiredState, FireBlock.AGE).equals(getProperty(currentState, FireBlock.AGE))) {
            return Result.SKIP;
        }
        // SoulFire 没有方向属性，AGE 相同即可
        if (requiredBlock instanceof SoulFireBlock) {
            return Result.SKIP;
        }

        // 方向属性不对 → 放置性错误，破坏重放
        return Result.PASS;
    }

    /**
     * 根据 requiredState 的方向属性确定火焰放置面。
     * 火焰有六个方向属性（east/north/south/west/up），表示火焰可以向该方向蔓延。
     * 选择第一个为 true 的水平方向；若无则默认 DOWN。
     */
    private Direction findFireDirection() {
        for (Direction direction : Direction.values()) {
            if (direction == Direction.DOWN) continue;
            Object value = PrinterUtils.getPropertyByName(requiredState, direction.name());
            if (value instanceof Boolean && (Boolean) value) {
                return direction;
            }
        }
        return Direction.DOWN;
    }
}
