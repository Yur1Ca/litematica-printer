package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.printer.action.PlacementPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

final class QueuedClick {
    final BlockPos target;
    final Direction side;
    Vec3 hitModifier;
    final boolean useShift;
    boolean useProtocol;
    final int repeatCount;
    final PlacementPolicy policy;
    @Nullable
    final Vec3 queuedPlayerPosition;
    final long queuedTick;
    @Nullable
    Item[] expectedItems;
    @Nullable
    Consumer<ActionManager.SendResult> completionListener;
    @Nullable
    Predicate<ItemStack> expectedStackPredicate;

    QueuedClick(
            @NotNull BlockPos target,
            @NotNull Direction side,
            @NotNull Vec3 hitModifier,
            boolean useShift,
            int repeatCount,
            @NotNull PlacementPolicy policy
    ) {
        this.target = target;
        this.side = side;
        this.hitModifier = hitModifier;
        this.useShift = useShift;
        this.repeatCount = Math.max(1, repeatCount);
        this.policy = policy;
        Minecraft client = Minecraft.getInstance();
        this.queuedPlayerPosition = client.player == null ? null : client.player.position();
        this.queuedTick = client.level == null ? Long.MIN_VALUE : client.level.getGameTime();
    }

    void useProtocolHit(Vec3 hitModifier) {
        this.hitModifier = hitModifier;
        this.useProtocol = true;
    }

    void expectItems(@Nullable Item[] expectedItems) {
        this.expectedItems = expectedItems == null ? null : expectedItems.clone();
    }

    void onCompletion(@Nullable Consumer<ActionManager.SendResult> completionListener) {
        this.completionListener = completionListener;
    }

    void expectStack(@Nullable Predicate<ItemStack> expectedStackPredicate) {
        this.expectedStackPredicate = expectedStackPredicate;
    }
}
