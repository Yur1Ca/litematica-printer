package me.aleksilassila.litematica.printer.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;

/** Minimal Minecraft state bridge needed by the mining state machine. */
public interface MiningInteractionPort {
    Minecraft client();

    BlockPos destroyPos();

    void destroyPos(BlockPos pos);

    ItemStack destroyingItem();

    void destroyingItem(ItemStack stack);

    float destroyProgress();

    void destroyProgress(float progress);

    boolean isDestroying();

    void isDestroying(boolean destroying);

    boolean destroyBlock(BlockPos pos);

    boolean matchesDestroyTarget(BlockPos pos);

    /** Whether a client-side inventory/tool switch must settle before mining continues. */
    boolean isInventorySwitchPending();

    void ensureCarriedItemSent();

    /** Return an expired Mine delivery to its source after both confirmation stores release it. */
    default void onPendingDestroyExpired(BlockPos pos) { }

    ServerboundPlayerActionPacket actionPacket(
            ServerboundPlayerActionPacket.Action action,
            BlockPos pos,
            Direction direction,
            int sequence
    );
}
