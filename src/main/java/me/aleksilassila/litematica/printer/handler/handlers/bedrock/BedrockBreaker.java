package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
<<<<<<< HEAD
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
=======
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

public final class BedrockBreaker {
    private static final Minecraft CLIENT = Minecraft.getInstance();

    private BedrockBreaker() {
    }

    public static boolean breakBlock(BlockPos pos) {
        return breakBlock(pos, Direction.DOWN, true);
    }

    public static boolean breakBlock(BlockPos pos, boolean predictRemoval) {
        return breakBlock(pos, Direction.DOWN, predictRemoval);
    }

    public static boolean breakBlock(BlockPos pos, Direction direction, boolean predictRemoval) {
        if (CLIENT.level == null || CLIENT.player == null) {
            return false;
        }
        var state = CLIENT.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        boolean cleanupResidue = BedrockTargetBlocks.isCleanupResidue(state);
        boolean switched = cleanupResidue
                ? BedrockInventory.switchToCleanupTool(state)
                : BedrockInventory.switchToBestTool(state);
        if (!switched) {
            return false;
        }
        if (!InteractionUtils.protectCurrentToolBeforeBreak(state)) {
            return false;
        }

<<<<<<< HEAD
        if (CLIENT.gameMode instanceof MultiPlayerGameModeExtension gameModeExtension) {
            BlockBreakResult result = gameModeExtension.litematica_printer$continueDestroyBlock(
                    false,
                    pos,
                    direction,
                    false,
                    false
            );
            return result != BlockBreakResult.FAILED && result != BlockBreakResult.ABORTED;
        }
=======
        if (CLIENT.gameMode instanceof MultiPlayerGameModeExtension gameModeExtension && !shouldPredictRemoval()) {
            gameModeExtension.litematica_printer$continueDestroyBlock(false, pos, direction);
        }

        //#if MC >= 11900
        NetworkUtils.sendPacket(sequence -> new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                pos,
                direction,
                sequence
        ));
        NetworkUtils.sendPacket(sequence -> new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                pos,
                direction,
                sequence
        ));
        //#else
        //$$ NetworkUtils.sendPacket(new ServerboundPlayerActionPacket(
        //$$         ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
        //$$         pos,
        //$$         Direction.DOWN
        //$$ ));
        //$$ NetworkUtils.sendPacket(new ServerboundPlayerActionPacket(
        //$$         ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
        //$$         pos,
        //$$         Direction.DOWN
        //$$ ));
        //#endif

        boolean allowPrediction = predictRemoval && shouldPredictRemoval();
        if (allowPrediction) {
            CLIENT.level.removeBlock(pos, false);
        }

        return true;
    }

    private static boolean shouldPredictRemoval() {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        return false;
    }
}
