package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

public interface PrintTask {
    BlockPos pos();

    /** Unknown workflows retain ownership conservatively until they explicitly report otherwise. */
    default boolean hasStarted() {
        return true;
    }

    /** History alone is not ownership, but unknown workflows keep the conservative policy. */
    default boolean hasInFlightOwnership() {
        return this.hasStarted();
    }

    default boolean owns(@Nullable BlockPos pos) {
        return pos != null && pos.equals(this.pos());
    }

    boolean shouldKeep(ClientLevel level, WorldSchematic schematic);

    default boolean isWaitingForWorldUpdate(ClientLevel level, WorldSchematic schematic) {
        return false;
    }

    /** Earliest fallback poll when no matching server block update is received. */
    default long nextCheckTick() {
        return Long.MIN_VALUE;
    }

    PrintTaskBuildResult buildAction(SchematicBlockContext context);

    @Nullable
    PrintTaskAction createActionHandle(SchematicBlockContext context, Action action);
}
