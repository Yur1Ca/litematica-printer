package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Keeps inventory-dependent item interactions outside the warehouse placement protocol. */
public final class VoidWarehousePlacementPolicy {
    private VoidWarehousePlacementPolicy() { }

    public static boolean supports(Action action, @Nullable Item[] items, @Nullable Vec3 precisionHit) {
        return !(action instanceof ClickAction)
                && action.getClickRepeatCount() == 1
                && action.getRequiredStackPredicate() == null
                && (precisionHit == null || isFinite(precisionHit))
                && supportsItems(items);
    }

    private static boolean isFinite(Vec3 hit) {
        return Double.isFinite(hit.x) && Double.isFinite(hit.y) && Double.isFinite(hit.z);
    }

    public static boolean supportsItems(@Nullable Item[] items) {
        if (items == null || items.length == 0 || items.length > 8) return false;
        for (Item item : items) {
            // Subclasses can return buckets, open screens or perform their own useOn operation.
            // Match the server's initial scope instead of silently changing those semantics.
            if (item == null || item.getClass() != BlockItem.class
                    || ((BlockItem) item).getBlock() instanceof ShulkerBoxBlock
                    || ((BlockItem) item).getBlock() instanceof BedBlock
                    || ((BlockItem) item).getBlock() instanceof DoorBlock
                    || ((BlockItem) item).getBlock() instanceof DoublePlantBlock) return false;
        }
        return true;
    }
}
