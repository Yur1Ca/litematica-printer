package me.aleksilassila.litematica.printer.runtime;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Routes authoritative client block updates through every interested runtime component. */
public final class ClientBlockUpdateRouter {
    private ClientBlockUpdateRouter() {
    }

    public static void accept(BlockPos pos, BlockState authoritativeState) {
        if (pos == null || authoritativeState == null) {
            return;
        }
        PrinterRuntime runtime = RuntimeAccess.get();
        runtime.scanEngine().invalidate(pos);
        runtime.events().publish(new RuntimeEvent.BlockUpdated(
                pos.getX(), pos.getY(), pos.getZ()));
        runtime.interactionUtils().confirmServerBlockUpdate(pos, authoritativeState.isAir());
        runtime.hudStats().confirmBlockUpdate(pos, authoritativeState);
    }
}
