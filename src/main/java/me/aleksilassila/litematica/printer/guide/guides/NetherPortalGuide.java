package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.item.Items;
<<<<<<< HEAD
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.NetherPortalBlock;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

/**
 * 下界传送门
 */
public class NetherPortalGuide extends Guide {

    public NetherPortalGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
<<<<<<< HEAD
        Direction.Axis requiredAxis = getProperty(requiredState, NetherPortalBlock.AXIS)
                .orElse(Direction.Axis.X);
        boolean canCreatePortal = PortalShape.findEmptyPortalShape(level, blockPos, requiredAxis).isPresent();
=======
        boolean canCreatePortal = PortalShape.findEmptyPortalShape(level, blockPos, net.minecraft.core.Direction.Axis.X).isPresent();
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        if (canCreatePortal) {
            return Result.success(new Action()
                    .setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE)
                    .setRequiresSupport());
        }
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.SKIP;
    }
}
