package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.VoidWarehouseTriggerItem;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public final class BedrockInventory {
    private static final Minecraft CLIENT = Minecraft.getInstance();

    private BedrockInventory() {
    }

    public static String warningMessage() {
        LocalPlayer player = CLIENT.player;
        if (player == null || CLIENT.gameMode == null) {
            return "bedrockminer.fail.missing.survival";
        }
        if (CLIENT.gameMode.getPlayerMode().isCreative()) {
            return "bedrockminer.fail.missing.survival";
        }
        if (count(Items.PISTON) < 2) {
            return "bedrockminer.fail.missing.piston";
        }
        if (count(Items.REDSTONE_TORCH) < 1) {
            return "bedrockminer.fail.missing.redstonetorch";
        }
        if (count(Items.SLIME_BLOCK) < 1) {
            return "bedrockminer.fail.missing.slime";
        }
        if (!canInstantMinePiston(player)) {
            return "bedrockminer.fail.missing.instantmine";
        }
        return null;
    }

    public static boolean switchToItem(Item item) {
        LocalPlayer player = CLIENT.player;
        return player != null && InventoryUtils.switchToItems(player, new Item[]{item}, MaterialRequest.Source.BEDROCK);
    }

    public static boolean switchToOffhand(Item item) {
        return InventoryUtils.setItemToOffhand(new ItemStack(item), CLIENT);
    }

    static boolean materialRequiresInventorySwap(Item item) {
        LocalPlayer player = CLIENT.player;
        if (player == null || item == null) return false;
        if (player.getMainHandItem().is(item)
                && VoidWarehouseTriggerItem.isUsableAsMaterial(player.getMainHandItem())) return false;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item) && VoidWarehouseTriggerItem.isUsableAsMaterial(stack)) {
                return !Inventory.isHotbarSlot(slot);
            }
        }
        return false;
    }

    static boolean toolRequiresInventorySwap(BlockState state, boolean cleanup) {
        LocalPlayer player = CLIENT.player;
        if (player == null || state == null || state.isAir()) return false;
        ItemStack best = cleanup ? findBestCleanupBreakingStack(player, state) : findBestBreakingStack(player, state);
        return !best.isEmpty() && !ItemStack.isSameItemSameComponents(best, player.getMainHandItem())
                && !Inventory.isHotbarSlot(player.getInventory().findSlotMatchingItem(best));
    }

    public static boolean switchToBestTool(BlockState blockState) {
        LocalPlayer player = CLIENT.player;
        if (player == null || blockState == null || blockState.isAir()) {
            return false;
        }

        ItemStack bestStack = findBestBreakingStack(player, blockState);
        return switchToResolvedTool(player, bestStack);
    }

    public static boolean switchToCleanupTool(BlockState blockState) {
        LocalPlayer player = CLIENT.player;
        if (player == null || blockState == null || blockState.isAir()) {
            return false;
        }

        ItemStack bestStack = findBestCleanupBreakingStack(player, blockState);
        return switchToResolvedTool(player, bestStack);
    }

    public static boolean hasAtLeast(Item item, int count) {
        return count(item) >= count;
    }

    private static int count(Item item) {
        LocalPlayer player = CLIENT.player;
        return player == null ? 0 : player.getInventory().countItem(item);
    }

    private static ItemStack findBestBreakingStack(LocalPlayer player, BlockState blockState) {
        return findBestTool(player, blockState, false);
    }

    private static ItemStack findBestCleanupBreakingStack(LocalPlayer player, BlockState blockState) {
        return findBestTool(player, getCleanupProbeState(blockState), true);
    }

    private static ItemStack findBestTool(LocalPlayer player, BlockState state, boolean cleanup) {
        var runtime = RuntimeAccess.currentOrNull();
        var service = runtime == null ? null : runtime.toolSwitchService();
        boolean guarded = service != null && service.isDurabilityGuardActive();
        ItemStack best = ItemStack.EMPTY;
        ItemStack unsafe = ItemStack.EMPTY;
        float bestScore = 0F, unsafeScore = 0F;
        boolean bestPreferred = false, unsafePreferred = false;
        for (ItemStack stack : InventoryUtils.getMainStacks(player.getInventory())) {
            if (!isAllowedBedrockTool(stack, state)) continue;
            float score = cleanup ? getCleanupToolScore(player, state, stack)
                    : PlayerUtils.getDestroyProgress(player, state, stack);
            if (!(score > 0F)) continue;
            boolean preferred = cleanup && isPreferredCleanupTool(stack, state);
            if (guarded && !service.isToolUsable(stack)) {
                if (unsafe.isEmpty() || preferred && !unsafePreferred || preferred == unsafePreferred && score > unsafeScore) {
                    unsafe = stack;
                    unsafeScore = score;
                    unsafePreferred = preferred;
                }
            } else if (best.isEmpty() || preferred && !bestPreferred || preferred == bestPreferred && score > bestScore) {
                best = stack;
                bestScore = score;
                bestPreferred = preferred;
            }
        }
        if (!best.isEmpty()) return best;
        // With no usable candidate, let the existing guard attempt its own replacement.
        if (!unsafe.isEmpty()) return unsafe;
        ItemStack current = player.getMainHandItem();
        return isAllowedBedrockTool(current, state) ? current : ItemStack.EMPTY;
    }

    private static boolean switchToResolvedTool(LocalPlayer player, ItemStack bestStack) {
        if (bestStack.isEmpty()) {
            return false;
        }
        if (ItemStack.isSameItemSameComponents(bestStack, player.getMainHandItem())) {
            return true;
        }
        boolean inventorySwap = !Inventory.isHotbarSlot(player.getInventory().findSlotMatchingItem(bestStack));
        boolean selected = InventoryUtils.setPickedItemToHand(bestStack, CLIENT);
        if (selected && inventorySwap) {
            // ToolSwitchService is entered after this selection. Register a real
            // inventory SWAP here so it cannot mistake the predicted hand for a
            // previously settled tool. A hotbar selection needs only packet order.
            RuntimeAccess.get().inventorySwitchGuard().markSwitchIfNeeded(player.getMainHandItem());
        }
        return selected;
    }

    private static boolean isPreferredCleanupTool(ItemStack stack, BlockState blockState) {
        return stack.isCorrectToolForDrops(blockState) || stack.getDestroySpeed(blockState) > 1.0F;
    }

    private static boolean isAllowedBedrockTool(ItemStack stack, BlockState blockState) {
        if (stack.isEmpty() || blockState == null || blockState.isAir()) {
            return false;
        }

        if (stack.isCorrectToolForDrops(blockState)) {
            return true;
        }

        String itemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return itemPath.equals("shears")
                || itemPath.endsWith("_pickaxe")
                || itemPath.endsWith("_axe")
                || itemPath.endsWith("_shovel")
                || itemPath.endsWith("_hoe");
    }

    private static BlockState getCleanupProbeState(BlockState cleanupState) {
        if (cleanupState.is(Blocks.MOVING_PISTON)
                || cleanupState.is(Blocks.PISTON)
                || cleanupState.is(Blocks.PISTON_HEAD)) {
            return Blocks.PISTON.defaultBlockState();
        }
        if (cleanupState.is(Blocks.SLIME_BLOCK)) {
            return Blocks.SLIME_BLOCK.defaultBlockState();
        }
        if (cleanupState.is(Blocks.REDSTONE_TORCH)
                || cleanupState.is(Blocks.REDSTONE_WALL_TORCH)) {
            return Blocks.REDSTONE_TORCH.defaultBlockState();
        }
        return cleanupState;
    }

    private static float getCleanupToolScore(LocalPlayer player, BlockState probeState, ItemStack stack) {
        float speed = getBlockBreakingSpeed(player, probeState, stack);
        if (!Float.isFinite(speed) || speed < 0.0F) {
            return 0.0F;
        }
        return speed;
    }

    private static boolean canInstantMinePiston(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (canBreakInCriticalBundle(player, Blocks.PISTON.defaultBlockState(), inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    static boolean canBreakInCriticalBundle(LocalPlayer player, BlockState state, ItemStack stack) {
        // START followed by STOP in the same server tick must reach the server's 0.7 STOP threshold.
        // Use actual progress so water, being airborne, effects and block hardness all count.
        return isAllowedBedrockTool(stack, state)
                && PlayerUtils.getDestroyProgress(player, state, stack) >= 0.7F;
    }

    private static float getBlockBreakingSpeed(LocalPlayer player, BlockState blockState, ItemStack itemStack) {
        float speed = itemStack.getDestroySpeed(blockState);
        //#if MC > 12006
        if (speed > 1.0F) {
            for (var entry : itemStack.getEnchantments().entrySet()) {
                Optional<net.minecraft.resources.ResourceKey<Enchantment>> key = entry.getKey().unwrapKey();
                if (key.isPresent() && key.get() == Enchantments.EFFICIENCY) {
                    int level = EnchantmentHelper.getItemEnchantmentLevel(entry.getKey(), itemStack);
                    if (level > 0 && !itemStack.isEmpty()) {
                        speed += (float) (level * level + 1);
                    }
                }
            }
        }
        //#else
        //$$ if (speed > 1.0F) {
        //$$     int level = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.EFFICIENCY, itemStack);
        //$$     if (level > 0 && !itemStack.isEmpty()) {
        //$$         speed += (float) (level * level + 1);
        //$$     }
        //$$ }
        //#endif
        if (MobEffectUtil.hasDigSpeed(player)) {
            speed *= 1.0F + (float) (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2F;
        }
        if (player.hasEffect(MobEffects.MINING_FATIGUE)) {
            int amplifier = player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier();
            speed *= switch (amplifier) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        //#if MC > 12006
        AttributeInstance breakSpeed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (breakSpeed != null) {
            speed *= (float) breakSpeed.getValue();
        }
        //#endif
        if (!player.onGround()) {
            speed /= 5.0F;
        }
        return speed;
    }
}
