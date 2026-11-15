package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.Reference;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.item.Items;

/**
 * 篝火
 */
public class CampfireGuide extends Guide {

    public CampfireGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        boolean requiredLit = getProperty(requiredState, CampfireBlock.LIT).orElseThrow();
        boolean currentLit = getProperty(currentState, CampfireBlock.LIT).orElseThrow();

        if (!requiredLit && currentLit) {
            return Result.success(new ClickAction().setItems(Reference.SHOVEL_ITEMS).setSides(Direction.UP));
        }
        if (requiredLit && !currentLit) {
            return Result.success(new ClickAction().setItems(Items.FIRE_CHARGE, Items.FLINT_AND_STEEL));
        }

        return Result.SKIP;
    }
}
