package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

/** Consumables for the next preparation step, excluding materials already in the machine. */
final class BedrockSupplies {
    private BedrockSupplies() { }

    static Requirement missing(ClientLevel level, BedrockTargetRegistry targets) {
        boolean preparing = false;
        var iterator = targets.iterator();
        while (iterator.hasNext()) {
            BedrockTarget target = iterator.next();
            if (target == null || target.hasTried()
                    || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(target.getBedrockPos()))) continue;
            preparing = true;
            var piston = level.getBlockState(target.getPistonPos());
            boolean installed = piston.is(Blocks.PISTON)
                    && (piston.getValue(PistonBaseBlock.FACING) == target.layout().getPrimingFacing()
                        || piston.getValue(PistonBaseBlock.FACING) == target.layout().getExecuteFacing());
            Requirement missing = missing(installed ? 1 : 2,
                    target.hasOwnedTorchPowerSource() ? 0 : 1,
                    target.getSlimePos() == null || level.getBlockState(target.getSlimePos()).is(Blocks.SLIME_BLOCK) ? 0 : 1);
            if (missing != null) return missing;
        }
        // No unfinished preparation: keep the original admission requirements for a new machine.
        return preparing ? null : missing(2, 1, 1);
    }

    private static Requirement missing(int pistons, int torches, int slime) {
        if (!BedrockInventory.hasAtLeast(Items.PISTON, pistons)) return new Requirement(Items.PISTON, pistons);
        if (!BedrockInventory.hasAtLeast(Items.REDSTONE_TORCH, torches)) return new Requirement(Items.REDSTONE_TORCH, torches);
        if (!BedrockInventory.hasAtLeast(Items.SLIME_BLOCK, slime)) return new Requirement(Items.SLIME_BLOCK, slime);
        return null;
    }

    static boolean hasDispatchedAttempt(BedrockTargetRegistry targets) {
        var iterator = targets.iterator();
        while (iterator.hasNext()) {
            BedrockTarget target = iterator.next();
            if (target != null && target.hasTried()) return true;
        }
        return false;
    }

    record Requirement(Item item, int minimumCount) { }
}
