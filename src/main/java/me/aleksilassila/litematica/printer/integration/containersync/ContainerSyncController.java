package me.aleksilassila.litematica.printer.integration.containersync;

import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerClicks;
import me.aleksilassila.litematica.printer.integration.quickshulker.HighlightBlockRenderer;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Selection/template orchestration. Inventory transport remains alive while ordinary modules pause. */
public final class ContainerSyncController implements RuntimeComponent {
    private static final String OWNER = "container_sync";
    private static final EnumSet<ResourceLease> RESOURCES = EnumSet.of(ResourceLease.INVENTORY,
            ResourceLease.CONTAINER, ResourceLease.MAIN_HAND, ResourceLease.INTERACTION);
    private enum Phase { IDLE, SCANNING, READY, OPENING, CONTENT, TRANSFER, RESYNC, PAUSED }
    private final PrinterRuntime runtime;
    private final Minecraft client;
    private Phase phase = Phase.IDLE;
    private Object level, connection;
    private Block block;
    private BlockPos reference, target;
    private int size, candidate = -1;
    private int sentRevision;
    private int openingInventoryRevision;
    private boolean cancelling, closing;
    private long requestedAt;
    private AbstractContainerMenu menu;
    private AbstractContainerScreen<?> hiddenScreen;
    private AbstractContainerMenu resyncMenu;
    private int resyncRevision;
    private InventorySyncPackets.SyncFence resyncFence;
    private ItemStack resyncCursor;
    private boolean resyncNeedsAttention;
    private ContainerSyncTransfer transfer;
    private ContainerSyncLayout layout;
    private int slotStatesSeen, openingContentRevision;
    private List<ItemStack> openingContents;
    private ItemStack openingCursor;
    private List<ItemStack> template;
    private final LinkedHashSet<BlockPos> remaining = new LinkedHashSet<>();
    private Set<BlockPos> highlights;
    private Iterator<PrinterBox> boxes;
    private List<PrinterBox> selection;
    private boolean scanPaused;
    private Iterator<BlockPos> positions = Collections.emptyIterator();

    public ContainerSyncController(PrinterRuntime runtime) { this.runtime = runtime; this.client = runtime.client(); }

    public boolean isActive() { return phase != Phase.IDLE && phase != Phase.PAUSED; }

    public void toggle() {
        if (handoffStalledMenu()) return;
        if (phase == Phase.PAUSED) {
            cancelling = false;
            if (scanPaused) {
                remaining.clear(); boxes = selection.iterator(); positions = Collections.emptyIterator();
                scanPaused = false; phase = Phase.SCANNING;
            } else phase = Phase.READY;
            message("resuming"); return;
        }
        if (phase != Phase.IDLE) { cancel(); return; }
        if (client.player == null || client.level == null || client.gameMode == null
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || !(client.hitResult instanceof BlockHitResult hit)) { message("select"); return; }
        var state = client.level.getBlockState(hit.getBlockPos());
        block = state.getBlock();
        layout = ContainerSyncLayout.of(state);
        if (layout == null) {
            message("unsupported"); return;
        }
        reference = canonical(hit.getBlockPos());
        size = layout.size;
        selection = runtime.litematica().createSelectionBoxes();
        if (selection.isEmpty()) { message("selection"); return; }
        if (!acquire()) { message("busy"); return; }
        level = client.level; connection = client.getConnection();
        template = null; remaining.clear(); cancelling = false;
        boxes = selection.iterator(); positions = Collections.emptyIterator();
        HighlightBlockRenderer.createHighlightBlockList(OWNER, new fi.dy.masa.malilib.config.options.ConfigColor(OWNER, "#4CFF4CE6", ""));
        highlights = HighlightBlockRenderer.getHighlightBlockPosList(OWNER);
        highlights.clear();
        phase = Phase.SCANNING;
        message("scanning");
    }

    public void tick() {
        if (phase == Phase.IDLE) return;
        if (client.level != level || client.getConnection() != connection || client.player == null) { discard(); return; }
        if (phase == Phase.SCANNING) { scan(); return; }
        if (phase == Phase.PAUSED || phase == Phase.IDLE) return;
        if (phase == Phase.TRANSFER && client.player.containerMenu != menu) {
            // The server sends its close packet before returning the cursor to inventory.
            // The old click may have run or been rejected; never replay it in the new menu.
            transfer.cancel(); transfer = null; menu = null; candidate = -1;
            phase = Phase.RESYNC;
        }
        if (phase == Phase.RESYNC) {
            if (!acquire()) return;
            var current = client.player.containerMenu;
            if (resyncMenu != current || resyncFence == null) {
                var listener = client.getConnection();
                if (listener == null || listener.getConnection() == null) return;
                resyncMenu = current; resyncRevision = current.getStateId();
                resyncCursor = null; resyncNeedsAttention = false;
                requestedAt = now();
                me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics.log(
                        "container-sync closed-menu resync menu=" + current.containerId);
                // A previous click can also request a full snapshot for this menu. The marker
                // orders our read after all old clicks, including manual cursor recovery.
                QuickShulkerClicks.requestFullSync(current);
                resyncFence = InventorySyncPackets.prepareFence(listener.getConnection());
                listener.send(resyncFence.packet());
            } else if (resyncCursor != null && resyncFence.complete()) {
                if (!ItemStack.matches(resyncCursor, current.getCarried())) {
                    // A newer cursor delta superseded the full snapshot. Read this new state once.
                    resyncFence = null; resyncNeedsAttention = false;
                } else if (resyncCursor.isEmpty()) {
                    pause("changed"); return;
                } else {
                    resyncNeedsAttention = true;
                }
            }
            if (resyncNeedsAttention) showRecoveryScreen();
            if (resyncNeedsAttention) message("cursor");
            else message(stalledMenu() ? "take_over" : "waiting");
            return;
        }
        if (phase == Phase.TRANSFER) {
            transfer.tick(now(), !cancelling);
            if (transfer.needsAttention()) { showRecoveryScreen(); message("cursor"); }
            else if (transfer.waitingForServer()) message("take_over");
            if (transfer.finished()) {
                boolean complete = transfer.completed();
                String problem = transfer.problem();
                transfer = null;
                if (problem != null) { pause("changed"); return; }
                closeOwned();
                if (complete) { remaining.remove(target); highlights.remove(target); }
                if (cancelling) { discard(); message("cancelled"); }
                else phase = Phase.READY;
            }
            return;
        }
        if (phase == Phase.OPENING || phase == Phase.CONTENT) {
            // A server-forced close bypasses the manual close guard. No item click has been
            // sent yet, and a late initial snapshot for the lost menu can no longer be used.
            if (phase == Phase.CONTENT && client.player.containerMenu.containerId != candidate) {
                pause("changed"); return;
            }
            if (phase == Phase.CONTENT && cancelling && openingContents != null) {
                content(candidate, openingContentRevision, openingContents, openingCursor);
                return;
            }
            // Time alone cannot prove that a queued open was rejected. Keep ownership until its barrier reply.
            if (now() - requestedAt > 10000) message(stalledMenu() ? "take_over" : "waiting");
            return;
        }
        if (cancelling) { discard(); return; }
        if (template != null && remaining.isEmpty()) { discard(); message("complete"); return; }
        if (!acquire()) return;
        if (currentScreen() != null || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || runtime.inventorySwitchGuard().isWaiting()) return;
        // A blocked chest must not fall through to placing the held block beside it.
        if (!client.player.getMainHandItem().isEmpty()) { message("empty_hand"); return; }
        target = template == null ? reference : remaining.stream()
                .filter(this::reachable).findFirst().orElse(null);
        if (target == null || !reachable(target)) { message("range"); return; }
        if (!client.level.getBlockState(target).is(block) || containerSize(target) != size) { pause("changed"); return; }
        if (!acquire()) return;
        candidate = -1; menu = null; phase = Phase.OPENING; requestedAt = now();
        openingInventoryRevision = client.player.inventoryMenu.getStateId();
        me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics.log("container-sync open target=" + target);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false));
        // Ordered behind the open: rejection leaves menu 0 active and returns a full snapshot;
        // a successful open changes the server menu ID and ignores this menu-0 no-op.
        QuickShulkerClicks.requestFullSync(client.player.inventoryMenu);
    }

    private void scan() {
        for (int budget = 0; budget < 2048; budget++) {
            if (!positions.hasNext()) {
                if (!boxes.hasNext()) {
                    highlights.addAll(remaining);
                    phase = Phase.READY;
                    if (remaining.isEmpty()) { discard(); message("no_targets"); }
                    return;
                }
                positions = boxes.next().iterator();
            }
            BlockPos pos = positions.next();
            if (!client.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                scanPaused = true; pause("unloaded"); return;
            }
            if (client.level.getBlockState(pos).is(block) && containerSize(pos) == size) {
                BlockPos key = canonical(pos);
                if (!key.equals(reference)) remaining.add(key.immutable());
            }
        }
    }

    private BlockPos canonical(BlockPos pos) {
        var state = client.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT)
            return pos.relative(ChestBlock.getConnectedDirection(state));
        return pos.immutable();
    }

    private int containerSize(BlockPos pos) {
        var targetLayout = ContainerSyncLayout.of(client.level.getBlockState(pos));
        return targetLayout == null ? 0 : targetLayout.size;
    }

    private boolean reachable(BlockPos pos) {
        return client.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && PlayerUtils.isWithinBlockInteractionRange(client.player, pos, 0)
                && !client.player.isShiftKeyDown();
    }

    public void opened(int id, MenuType<?> type) {
        if (phase != Phase.OPENING || !sameWorld()) return;
        layout = ContainerSyncLayout.of(block, size);
        if (layout == null || layout.size != size || type != layout.type) { pause("changed"); return; }
        slotStatesSeen = 0; openingContents = null; openingCursor = null;
        candidate = id; phase = Phase.CONTENT;
        me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics.log("container-sync opened menu=" + id);
    }

    /** Vanilla has already installed the menu when it asks to show its screen. */
    public boolean suppressScreen(Screen screen) {
        if (screen == null) {
            // Closing a background menu must not dismiss chat or another foreground screen.
            return closing && hiddenScreen != null && currentScreen() != null;
        }
        if (screen instanceof InventoryScreen && sameWorld() && client.player != null
                && (phase == Phase.OPENING || hiddenScreen != null
                    && client.player.containerMenu == hiddenScreen.getMenu())) {
            // InventoryScreen sends menu-0 slot numbers. Finish the automatic menu first.
            cancel();
            return true;
        }
        if (phase != Phase.CONTENT || hiddenScreen != null || !sameWorld() || client.player == null
                || !(screen instanceof AbstractContainerScreen<?> container)
                || container.getMenu() != client.player.containerMenu || container.getMenu().containerId != candidate
                || layout == null || container.getMenu().getType() != layout.type
                || !layout.acceptsSlots(container.getMenu(), client.player.getInventory())) return false;
        hiddenScreen = container;
        return true;
    }

    private Screen currentScreen() {
        //#if MC > 260100
        //$$ return client.gui.screen();
        //#else
        return client.screen;
        //#endif
    }

    private void showRecoveryScreen() {
        if (hiddenScreen == null || currentScreen() != null) return;
        var current = client.player.containerMenu;
        Screen screen = hiddenScreen;
        if (current != hiddenScreen.getMenu()) {
            if (current != client.player.inventoryMenu) return;
            screen = new InventoryScreen(client.player);
        }
        hiddenScreen = null;
        //#if MC > 260100
        //$$ client.gui.setScreen(screen);
        //#else
        client.setScreen(screen);
        //#endif
    }

    private boolean stalledMenu() {
        if (phase == Phase.TRANSFER) return transfer.waitingForServer();
        return (phase == Phase.CONTENT || phase == Phase.RESYNC) && now() - requestedAt > 10000;
    }

    /** A server that withholds confirmations must still let the player take back its live menu. */
    private boolean handoffStalledMenu() {
        if (!stalledMenu() || !sameWorld() || client.player == null) return false;
        if (hiddenScreen != null && client.player.containerMenu == hiddenScreen.getMenu()
                && currentScreen() != null && currentScreen() != hiddenScreen) return false;
        showRecoveryScreen();
        // Leave the actual menu and cursor with vanilla. Previously sent actions remain ordered
        // before the player's next input; the abandoned plan is never resumed or repeated.
        discard();
        message("handed_over");
        return true;
    }

    public void content(int id, int revision, List<ItemStack> items, ItemStack carried) {
        if (!sameWorld() || client.player == null) return;
        if (phase == Phase.RESYNC) {
            if (resyncFence == null || resyncMenu != client.player.containerMenu || resyncMenu.containerId != id
                    || items.size() != resyncMenu.slots.size()
                    || !ContainerSyncTransfer.newerRevision(revision, resyncRevision)) return;
            resyncRevision = revision;
            resyncCursor = carried.copy();
            resyncNeedsAttention = false;
            return;
        }
        if (phase == Phase.OPENING && id == 0 && ContainerSyncTransfer.newerRevision(revision, openingInventoryRevision)) {
            if (cancelling) { discard(); message("cancelled"); }
            else pause("open_timeout");
            return;
        }
        if (phase == Phase.TRANSFER && client.player.containerMenu == menu && menu.containerId == id) {
            var editable = layout.contents(items);
            if (editable != null && ContainerSyncTransfer.newerRevision(revision, sentRevision)) transfer.observe(menu, editable, carried);
            return;
        }
        if (phase != Phase.CONTENT || candidate != id || client.player.containerMenu.containerId != id) return;
        menu = client.player.containerMenu;
        var editable = layout.contents(items);
        if (editable == null || menu.slots.size() != items.size() || !carried.isEmpty()) {
            pause("changed"); return;
        }
        // Explicit slot ownership prevents modded menus from masquerading as ordinary storage.
        if (!layout.acceptsSlots(menu, client.player.getInventory())) { pause("unsupported"); return; }
        if (cancelling) { closeOwned(); discard(); message("cancelled"); return; }
        // Initial slot data follows the content packet; an all-enabled client default is not confirmation.
        if (layout.crafter && slotStatesSeen != 511) {
            openingContents = ContainerSyncPlan.copy(items); openingCursor = carried.copy();
            openingContentRevision = revision;
            return;
        }
        openingContents = null; openingCursor = null;
        if (template == null) {
            template = ContainerSyncPlan.copy(editable.subList(0, size));
            closeOwned(); phase = Phase.READY; return;
        }
        int disabledSlots = layout.disabledSlots(menu);
        for (int slot = 0; slot < size; slot++) {
            if ((disabledSlots & (1 << slot)) != 0 && !template.get(slot).isEmpty()) { pause("disabled_slot"); return; }
        }
        var rules = new ContainerSyncPlan.Rules() {
            public boolean movable(int slot) {
                if (!menu.getSlot(slot).mayPickup(client.player)) return false;
                if (slot < size) return true;
                int inventorySlot = menu.getSlot(slot).getContainerSlot();
                if (inventorySlot == me.aleksilassila.litematica.printer.utils.InventoryUtils.getSelectedSlot(client.player.getInventory())) return false;
                // Honour Litematica's protected hotbar; main inventory is the material pool.
                return inventorySlot >= 9 && inventorySlot < 36
                        || inventorySlot >= 0 && inventorySlot < 9
                        && InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().contains(inventorySlot)
                        && InventoryUtilsAccessor.canPickToSlot(client.player.getInventory(), inventorySlot);
            }
            public int capacity(int slot, ItemStack stack) {
                var targetSlot = menu.getSlot(slot);
                return targetSlot.mayPlace(stack) ? targetSlot.getMaxStackSize(stack) : 0;
            }
        };
        var plan = ContainerSyncPlan.create(template, editable, rules);
        if (plan.problem() != null) {
            pause(plan.problem() == ContainerSyncPlan.Problem.MISSING_ITEMS ? "missing"
                    : plan.problem() == ContainerSyncPlan.Problem.NO_SPACE ? "space" : "unsupported");
            return;
        }
        final AbstractContainerMenu owned = menu;
        final ContainerSyncLayout ownedLayout = layout;
        var listener = client.getConnection();
        transfer = new ContainerSyncTransfer(new ContainerSyncTransfer.BatchPort() {
            public Object context() {
                return listener != null && listener.getConnection() != null && sameWorld()
                        && client.player != null && client.player.containerMenu == owned ? owned : null;
            }
            public boolean acquire() { return ContainerSyncController.this.acquire(); }
            // The batch owns the lease across menu changes; pause/discard releases it.
            public void release() { }
            public boolean canSendBatch(List<ContainerSyncPlan.Step> steps) {
                var inventory = client.player.getInventory();
                int selected = me.aleksilassila.litematica.printer.utils.InventoryUtils.getSelectedSlot(inventory);
                int heldSlot = owned.findSlot(inventory, selected).orElse(-1);
                for (var step : steps) {
                    if (step.slot() == heldSlot || step.returnSlot() == heldSlot) return false;
                }
                return true;
            }
            public BooleanSupplier sendBatch(List<ContainerSyncPlan.Step> steps) {
                sentRevision = owned.getStateId();
                for (var step : steps) {
                    QuickShulkerClicks.sendPlannedPickup(owned, step.slot(), step.button(),
                            step.items().get(step.slot()), step.cursor());
                }
                QuickShulkerClicks.requestFullSync(owned);
                var fence = InventorySyncPackets.prepareFence(listener.getConnection());
                listener.send(fence.packet());
                return fence::complete;
            }
            public void click(int slot, int button) {
                sentRevision = owned.getStateId();
                QuickShulkerClicks.sendWithFullSync(owned, slot, button, ContainerInput.PICKUP);
            }
            public void refresh() { sentRevision = owned.getStateId(); QuickShulkerClicks.requestFullSync(owned); }
            public boolean unchanged(List<ItemStack> expected, ItemStack cursor) {
                return ownedLayout.disabledSlots(owned) == disabledSlots
                        && ContainerSyncPlan.matches(expected, ownedLayout.contents(owned.slots.stream().map(slot -> slot.getItem()).toList()))
                        && ItemStack.matches(cursor, owned.getCarried());
            }
        }, plan);
        phase = Phase.TRANSFER;
        message("progress", remaining.size());
    }

    /** Called after vanilla applies server container data, including while WORK is disabled. */
    public void data(int id, int slot) {
        if (phase != Phase.CONTENT || !sameWorld() || layout == null || !layout.crafter
                || client.player == null || candidate != id || client.player.containerMenu.containerId != id
                || slot < 0 || slot >= 9) return;
        slotStatesSeen |= 1 << slot;
        if (slotStatesSeen != 511 || openingContents == null) return;
        var items = openingContents;
        var carried = openingCursor;
        if (!ContainerSyncPlan.matches(layout.contents(items), layout.contents(menu.slots.stream().map(s -> s.getItem()).toList()))
                || !ItemStack.matches(carried, menu.getCarried())) { pause("changed"); return; }
        content(id, openingContentRevision, items, carried);
    }

    /** Manual clicks/close request cancellation; an in-flight cursor must be settled first. */
    public boolean guardManualUse() {
        if (!isActive() || !sameWorld()) return false;
        // A second use can open an indistinguishable same-type menu while ours is in flight.
        cancel();
        return phase != Phase.IDLE;
    }

    public boolean guardManualInput() {
        return guardManualInput(true);
    }

    /** A screen may predict state before forwarding the same click to the game-mode guard. */
    public boolean guardManualInputBeforePrediction() {
        return guardManualInput(false);
    }

    private boolean guardManualInput(boolean consumeRecovery) {
        if (closing || !sameWorld() || client.player == null) return false;
        if (phase == Phase.RESYNC) {
            cancel();
            if (!resyncNeedsAttention || resyncMenu != client.player.containerMenu || resyncFence == null) return true;
            // Let one manual recovery click through, then request its full confirmation on the next tick.
            if (consumeRecovery) { resyncFence = null; resyncNeedsAttention = false; }
            return false;
        }
        if (phase == Phase.TRANSFER && client.player.containerMenu == menu && !transfer.finished()) {
            cancel(); return transfer.blockManualClick(consumeRecovery);
        }
        if (phase == Phase.CONTENT) {
            // Keep the opened menu alive until its first snapshot can finish cancellation.
            // Letting a click through here also queues a mutation ahead of our close.
            cancel(); return true;
        }
        if (phase == Phase.OPENING) cancel();
        return false;
    }

    public boolean guardClose() {
        if (!closing && sameWorld() && phase == Phase.RESYNC) { cancel(); return true; }
        if (!closing && sameWorld() && transfer != null && !transfer.finished()
                && client.player != null && client.player.containerMenu == menu) {
            cancel(); return true;
        }
        return guardManualInput();
    }

    private boolean acquire() { return runtime.actionBroker().tryAcquire(OWNER, RESOURCES, 0); }
    private boolean sameWorld() { return client.level == level && client.getConnection() == connection; }
    private static long now() { return System.nanoTime() / 1_000_000L; }

    private void cancel() {
        cancelling = true;
        if (transfer != null) transfer.cancel();
        if (phase == Phase.READY || phase == Phase.SCANNING || phase == Phase.PAUSED) { discard(); message("cancelled"); }
    }

    private void closeOwned() {
        if (sameWorld() && menu != null && client.player != null && client.player.containerMenu == menu && menu.getCarried().isEmpty()) {
            closing = true;
            try { client.player.closeContainer(); } finally { closing = false; }
        }
        menu = null; candidate = -1;
        hiddenScreen = null;
        openingContents = null; openingCursor = null; slotStatesSeen = 0;
    }

    private void pause(String reason) {
        me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics.log(
                "container-sync paused reason=" + reason + " target=" + target);
        if (hiddenScreen != null && menu == hiddenScreen.getMenu() && client.player.containerMenu == menu
                && !menu.getCarried().isEmpty()) {
            // Keep ownership until the server confirms where the unexpected cursor ended up.
            resyncFence = null;
            phase = Phase.RESYNC;
            return;
        }
        closeOwned();
        resyncMenu = null; resyncFence = null; resyncCursor = null; resyncNeedsAttention = false;
        runtime.actionBroker().releaseOwner(OWNER);
        phase = Phase.PAUSED;
        message(reason);
    }

    private void discard() {
        transfer = null; menu = null; hiddenScreen = null; candidate = -1; template = null;
        openingContents = null; openingCursor = null; slotStatesSeen = 0;
        resyncMenu = null; resyncFence = null; resyncCursor = null; resyncNeedsAttention = false;
        remaining.clear(); if (highlights != null) highlights.clear();
        phase = Phase.IDLE; cancelling = false; scanPaused = false;
        runtime.actionBroker().releaseOwner(OWNER);
    }

    @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        // Connection.disconnect may run on Netty. Menu state and highlight sets belong to the client thread.
        if (!client.isSameThread()) {
            Object previousConnection = connection;
            Object previousLevel = level;
            client.execute(() -> {
                if (connection == previousConnection && level == previousLevel) onEpochChanged(event);
            });
            return;
        }
        if (!sameWorld() || event.reason().equals("disconnect") || event.reason().equals("client_scope_changed")
                || event.reason().equals("local_player_init")) discard();
        else if (phase != Phase.IDLE) {
            if (!event.reason().equals("work_switch_off")) cancel();
            // The broker has already cleared leases. Reclaim an active sync or its pending drain.
            if (isActive()) acquire();
        }
    }

    private void message(String key, Object... args) {
        MessageUtils.setOverlayMessage(net.minecraft.client.resources.language.I18n.get("litematica-printer.message.container_sync." + key, args));
    }
}
