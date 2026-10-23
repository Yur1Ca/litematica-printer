package me.aleksilassila.litematica.printer.integration.inventory;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncTransfer;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerClicks;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

//#if MC >= 12104
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBank;
import red.jackf.chesttracker.api.memory.MemoryKey;
import red.jackf.chesttracker.api.memory.MemoryBankAccess;
import red.jackf.chesttracker.api.providers.ProviderUtils;
//#endif

/** Optional Chest Tracker world-container material provider. */
public final class ChestTrackerAdapter implements InventoryProvider, RuntimeComponent {
    private static final String LEASE_OWNER = "chest_tracker";
    private static final int MAX_SCAN_CANDIDATES = 64;
    private static final int OPEN_TIMEOUT_BASE_TICKS = 8;
    private static final int OPEN_TIMEOUT_SAFETY_TICKS = 2;
    private static final int OPEN_TIMEOUT_MAX_TICKS = 60;
    private static final long REQUEST_TIMEOUT_TICKS = 200L;
    private static final long NOT_FOUND_COOLDOWN_TICKS = 400L;

    private final Minecraft client;
    private final ActionPort actionBroker;
    private final SelectedContainerCache selectedContainers = new SelectedContainerCache();
    private final Map<Item, List<Candidate>> index = new HashMap<>();
    private final Set<BlockPos> invalidCandidates = new HashSet<>();
    private MaterialRequest activeRequest;
    private List<Candidate> candidates = List.of();
    private int candidateIndex;
    private BlockPos targetPos;
    private Item requestedItem;
    private List<Item> requestedItems = List.of();
    private ItemStack requestedStack = ItemStack.EMPTY;
    private boolean exactMatch;
    private Phase phase = Phase.IDLE;
    private long startedTick;
    private long openDeadline;
    private long requestDeadline;
    private long lastFailedTick = Long.MIN_VALUE;
    private Item lastFailedItem;
    private final NestedShulkerReturn nestedReturn = new NestedShulkerReturn();
    private boolean suppressContainerScreen;
    private int expectedContainerId = -1;
    private AbstractContainerMenu ownedMenu;
    private Object ownedConnection;
    private Object ownedLevel;
    private ChestTrackerTransfer transfer;
    private AbstractContainerMenu openingMenu;
    private int openingRevision;
    private boolean cancelling, closing;
    private ItemStack pendingShulker = ItemStack.EMPTY;
    private List<ItemStack> inventoryBeforeTake = List.of();
    private int pendingSourceSlot;
    private MaterialReservation completedReservation;
    private AbstractContainerScreen<?> suppressedScreen;
    private AbstractContainerMenu recoveryShownMenu;

    public ChestTrackerAdapter(ActionPort actionBroker) {
        this.client = Minecraft.getInstance();
        this.actionBroker = actionBroker;
    }

    @Override
    public String id() {
        return "chest_tracker";
    }

    @Override
    public MaterialReservation request(MaterialRequest request) {
        if (!enabled()) return unavailable(request);
        if (this.activeRequest != null) {
            return this.activeRequest.token() == request.token() ? this.status(request) : pending(request);
        }
        LocalPlayer player = this.client.player;
        if (player == null || this.client.level == null) return unavailable(request);
        for (Item item : request.acceptedItems()) {
            if (InventoryUtils.playerHasItemInInventory(player, item, request::acceptsStack)) {
                return MaterialReservation.available(request, item);
            }
        }
        Item item = request.preferredItem();
        if (failedRecently(item)) return unavailable(request);
        // Candidate failures are scoped to one material request. Keeping them
        // across requests would permanently hide a box after the first miss.
        this.invalidCandidates.clear();
        this.activeRequest = request;
        this.requestedItem = item;
        this.requestedItems = request.acceptedItems();
        this.requestedStack = request.preferredItem() == null ? ItemStack.EMPTY : new ItemStack(item);
        this.exactMatch = false;
        rebuildIndex();
        this.candidates = orderedCandidates(this.requestedItems);
        this.candidateIndex = 0;
        this.startedTick = gameTick();
        this.requestDeadline = this.startedTick + REQUEST_TIMEOUT_TICKS;
        this.phase = Phase.SCANNING;
        if (!openNextCandidate()) {
            finishUnavailable(item);
            return unavailable(request);
        }
        MessageUtils.setOverlayMessage("Chest Tracker: 取物中 " + new ItemStack(item).getHoverName().getString());
        return pending(request);
    }

    @Override
    public MaterialReservation status(MaterialRequest request) {
        if (this.completedReservation != null && this.completedReservation.token() == request.token()) {
            return this.completedReservation;
        }
        if (this.activeRequest == null || this.activeRequest.token() != request.token()) {
            return unavailable(request);
        }
        if (this.transfer != null || this.openingMenu != null || this.phase == Phase.WAITING_CONTENT
                || waitForRestoreContent(this.phase) || this.cancelling) return pending(request);
        for (Item item : request.acceptedItems()) {
            if (InventoryUtils.playerHasItemInInventory(this.client.player, item, request::acceptsStack)) {
                if (this.nestedReturn.hasSource() && !this.nestedReturn.isRestoring()) {
                    beginNestedRestore();
                    if (this.activeRequest != null) return pending(request);
                }
                if (this.activeRequest == null) return MaterialReservation.available(request, item);
                finishAvailable();
                return MaterialReservation.available(request, item);
            }
        }
        if (gameTick() >= this.requestDeadline) {
            failAndContinue();
            return this.activeRequest == null ? unavailable(request) : pending(request);
        }
        return pending(request);
    }

    @Override
    public void tick() {
        if (this.activeRequest == null) return;
        if ((this.transfer != null || this.openingMenu != null || this.ownedMenu != null) && !sameWorld()) {
            discard();
            return;
        }
        if (this.transfer != null) {
            if (!acquire()) return;
            this.transfer.tick();
            finishTransfer();
            showRecoveryScreen();
            return;
        }
        if (this.ownedMenu != null && this.client.player.containerMenu != this.ownedMenu) {
            this.cancelling = true;
            this.transfer = new ChestTrackerTransfer(this.client, this.ownedMenu);
            this.transfer.cancel();
            this.transfer.synchronize();
            acquire();
            return;
        }
        long now = gameTick();
        if (this.openingMenu != null || this.phase == Phase.WAITING_CONTENT || this.phase == Phase.RESTORE_WAIT_CONTENT) {
            acquire();
            // Elapsed time cancels intent, but cannot prove that a delayed open has settled.
            if (now >= this.openDeadline) this.cancelling = true;
            return;
        }
        if (this.phase == Phase.WAITING_INVENTORY && hasRequestedItem()) {
            if (this.nestedReturn.hasSource() && !this.nestedReturn.isRestoring()) {
                beginNestedRestore();
            } else {
                finishAvailable();
            }
            return;
        }
        if (now >= this.requestDeadline) {
            finishUnavailable(this.requestedItem);
        }
    }

    public void onContainerOpen(int containerId) {
        LocalPlayer player = this.client.player;
        if (this.activeRequest != null
                && (this.phase == Phase.WAITING_CONTENT || this.phase == Phase.RESTORE_WAIT_CONTENT)
                && this.suppressContainerScreen
                && (this.openingMenu == null || sameWorld())
                && player != null
                && player.containerMenu != player.inventoryMenu
                && player.containerMenu.containerId == containerId) {
            // The packet callback runs after vanilla assigns the new menu, even when its screen is hidden.
            this.expectedContainerId = containerId;
            this.ownedMenu = player.containerMenu;
            this.ownedConnection = this.client.getConnection();
            this.ownedLevel = this.client.level;
            this.openingMenu = null;
        }
    }

    /** Called after a matching container-content packet has populated the active menu. */
    public void onContainerContent(int containerId, int revision, List<ItemStack> items, ItemStack cursor) {
        if (this.transfer != null) {
            this.transfer.observe(containerId, revision, items, cursor);
            finishTransfer();
            showRecoveryScreen();
            return;
        }
        if (this.openingMenu != null && sameWorld() && this.client.player.containerMenu == this.openingMenu
                && containerId == this.openingMenu.containerId && items.size() == this.openingMenu.slots.size()
                && ContainerSyncTransfer.newerRevision(revision, this.openingRevision)) {
            // The no-op after our open was handled on the old menu: no container was opened.
            var previous = this.openingMenu;
            this.openingMenu = null;
            if (!cursor.isEmpty()) {
                this.cancelling = true;
                this.transfer = new ChestTrackerTransfer(this.client, previous);
                this.transfer.cancel();
                this.transfer.synchronize();
            } else if (this.cancelling) discard();
            else failAndContinue();
            return;
        }
        if (this.activeRequest == null
                || (this.phase != Phase.WAITING_CONTENT && this.phase != Phase.RESTORE_WAIT_CONTENT)) return;
        LocalPlayer player = this.client.player;
        if (player == null || this.ownedMenu == null || !sameWorld() || player.containerMenu != this.ownedMenu
                || this.expectedContainerId != containerId || items.size() != this.ownedMenu.slots.size()) return;
        if (!acquire()) return;
        if (!cursor.isEmpty()) {
            this.cancelling = true;
            this.transfer = new ChestTrackerTransfer(this.client, this.ownedMenu);
            this.transfer.cancel();
            this.transfer.synchronize();
            return;
        }
        if (this.cancelling) { closeContainer(); discard(); return; }
        if (this.client.gameMode == null) {
            finishUnavailable(this.requestedItem);
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu || menu.slots.isEmpty()) {
            failAndContinue();
            return;
        }
        int containerSize = Math.min(menu.slots.size(), menu.slots.get(0).container.getContainerSize());
        if (this.nestedReturn.isRestoring()) {
            restoreNestedShulker(menu, containerSize, player);
            return;
        }
        int sourceSlot = -1;
        boolean nested = false;
        for (int slot = 0; slot < containerSize; slot++) {
            if (matches(menu.slots.get(slot).getItem())) {
                sourceSlot = slot;
                break;
            }
        }
        if (sourceSlot < 0) {
            for (int slot = 0; slot < containerSize; slot++) {
                ItemStack stack = menu.slots.get(slot).getItem();
                if (isShulker(stack) && shulkerContains(stack)) {
                    sourceSlot = slot;
                    nested = true;
                    break;
                }
            }
        }
        if (sourceSlot < 0) {
            this.invalidCandidates.add(this.targetPos.immutable());
            closeContainer();
            if (!openNextCandidate()) finishUnavailable(this.requestedItem);
            return;
        }
        this.pendingShulker = nested ? menu.slots.get(sourceSlot).getItem().copy() : ItemStack.EMPTY;
        this.inventoryBeforeTake = nested ? NestedShulkerReturn.inventorySnapshot(player) : List.of();
        this.pendingSourceSlot = sourceSlot;
        this.phase = Phase.WAITING_INVENTORY;
        this.transfer = new ChestTrackerTransfer(this.client, menu);
        this.transfer.take(sourceSlot);
    }

    public boolean requestFromScreen(ItemStack stack) {
        if (!enabled() || stack == null || stack.isEmpty() || this.activeRequest != null) return false;
        MaterialRequest request = new MaterialRequest(
                Long.MAX_VALUE,
                List.of(stack.getItem()),
                stack.getItem(),
                1,
                MaterialRequest.Source.CHEST_TRACKER_SCREEN
        );
        this.activeRequest = request;
        this.requestedItem = stack.getItem();
        this.requestedItems = List.of(stack.getItem());
        this.requestedStack = stack.copy();
        this.exactMatch = true;
        this.invalidCandidates.clear();
        rebuildIndex();
        this.candidates = orderedCandidates(this.requestedItems);
        this.candidateIndex = 0;
        this.startedTick = gameTick();
        this.requestDeadline = this.startedTick + REQUEST_TIMEOUT_TICKS;
        this.phase = Phase.SCANNING;
        if (!openNextCandidate()) {
            finishUnavailable(this.requestedItem);
            return false;
        }
        return true;
    }

    public boolean shouldSuppressContainerScreen() {
        // Only hide the screen opened by our own remote interaction. Keeping
        // this tied to the phase used to block a player's manually opened
        // chest while an inventory packet was still pending.
        return this.suppressContainerScreen && (this.transfer == null || !this.transfer.needsAttention());
    }

    public void rememberSuppressedScreen(AbstractContainerScreen<?> screen) {
        if (this.activeRequest != null && this.client.player != null
                && screen.getMenu() == this.client.player.containerMenu) this.suppressedScreen = screen;
    }

    private void showRecoveryScreen() {
        if (this.transfer == null || !this.transfer.needsAttention() || !sameWorld()) return;
        var current = this.client.player.containerMenu;
        if (this.recoveryShownMenu == current) return;
        // Reuse the actual suppressed screen: an InventoryScreen while a chest menu is active
        // would send recovery clicks to the wrong container ID.
        AbstractContainerScreen<?> screen = this.suppressedScreen;
        if (current == this.client.player.inventoryMenu) screen = new InventoryScreen(this.client.player);
        if (screen == null || screen.getMenu() != current) return;
        this.recoveryShownMenu = current;
        //#if MC > 260100
        //$$ this.client.gui.setScreen(screen);
        //#else
        this.client.setScreen(screen);
        //#endif
    }

    @Override
    public long pendingTimeoutTicks() {
        return REQUEST_TIMEOUT_TICKS;
    }

    private long openTimeoutTicks() {
        return RuntimeAccess.get().rttReplayController().getWaitTimeoutTicks(
                OPEN_TIMEOUT_BASE_TICKS,
                OPEN_TIMEOUT_SAFETY_TICKS,
                OPEN_TIMEOUT_MAX_TICKS
        );
    }

    @Override
    public boolean blocksPrinterWhilePending() {
        return false;
    }

    /** Adds remembered containers inside the active Litematica AreaSelection to the allow-list. */
    public int addSelectionToCache() {
        if (!ModLoadUtils.isChestTrackerLoaded() || this.client.level == null) return 0;
        List<me.aleksilassila.litematica.printer.printer.PrinterBox> boxes = LitematicaUtils.createSelection1Boxes();
        if (boxes.isEmpty()) return 0;
        int added = 0;
        //#if MC >= 12104
        MemoryBank bank = MemoryBankAccess.INSTANCE.getLoaded().orElse(null);
        var key = ProviderUtils.getPlayersCurrentKey().orElse(null);
        MemoryKey memoryKey = bank == null || key == null ? null : bank.getKey(key).orElse(null);
        if (memoryKey != null) {
            String world = SelectedContainerCache.worldId(this.client);
            String dimension = SelectedContainerCache.dimensionId(this.client);
            for (Map.Entry<BlockPos, Memory> entry : memoryKey.getMemories().entrySet()) {
                Memory memory = entry.getValue();
                BlockPos pos = entry.getKey();
                if (memory != null && memory.container().isPresent() && insideAny(boxes, pos)) {
                    added += this.selectedContainers.add(world, dimension, pos);
                }
            }
        }
        //#endif
        if (added > 0) this.selectedContainers.save();
        return added;
    }

    public int clearSelectionCache() {
        if (this.client.level == null) return 0;
        int removed = this.selectedContainers.clear(
                SelectedContainerCache.worldId(this.client),
                SelectedContainerCache.dimensionId(this.client)
        );
        if (removed > 0) this.selectedContainers.save();
        this.index.clear();
        return removed;
    }

    @Override
    public void reset() {
        this.completedReservation = null;
        if (sameWorld() && (this.transfer != null || this.openingMenu != null || this.ownedMenu != null)) {
            this.cancelling = true;
            if (this.transfer != null) this.transfer.cancel();
            acquire();
            return;
        }
        discard();
    }

    private void discard() {
        releaseResources();
        this.transfer = null;
        this.openingMenu = null;
        this.cancelling = false;
        this.pendingShulker = ItemStack.EMPTY;
        this.inventoryBeforeTake = List.of();
        this.activeRequest = null;
        this.candidates = List.of();
        this.candidateIndex = 0;
        this.targetPos = null;
        this.requestedItem = null;
        this.requestedItems = List.of();
        this.requestedStack = ItemStack.EMPTY;
        this.nestedReturn.clear();
        this.suppressContainerScreen = false;
        this.expectedContainerId = -1;
        this.exactMatch = false;
        this.phase = Phase.IDLE;
        this.invalidCandidates.clear();
        this.index.clear();
        this.lastFailedItem = null;
        this.lastFailedTick = Long.MIN_VALUE;
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        if (!this.client.isSameThread()) {
            Object connection = this.ownedConnection, level = this.ownedLevel;
            this.client.execute(() -> {
                if (connection == this.ownedConnection && level == this.ownedLevel) onEpochChanged(event);
            });
            return;
        }
        if (event.reason().equals("disconnect") || event.reason().equals("client_scope_changed")
                || event.reason().equals("local_player_init") || !sameWorld()) {
            this.completedReservation = null;
            discard();
        } else reset();
    }

    private boolean enabled() {
        return ModLoadUtils.isChestTrackerLoaded()
                && Configs.Special.REMOTE_TAKE.getBooleanValue();
    }

    private boolean openNextCandidate() {
        while (this.candidateIndex < this.candidates.size()) {
            Candidate candidate = this.candidates.get(this.candidateIndex++);
            if (this.invalidCandidates.contains(candidate.pos()) || !this.client.level.isLoaded(candidate.pos())) continue;
            if (open(candidate.pos())) {
                this.targetPos = candidate.pos();
                this.phase = Phase.WAITING_CONTENT;
                this.openDeadline = gameTick() + openTimeoutTicks();
                return true;
            }
            this.invalidCandidates.add(candidate.pos());
        }
        return false;
    }

    private boolean open(BlockPos pos) {
        if (this.client.player == null || this.client.level == null
                || this.client.player.containerMenu != this.client.player.inventoryMenu
                || !this.client.player.containerMenu.getCarried().isEmpty()) return false;
        this.expectedContainerId = -1;
        if (!acquire()) return false;
        this.ownedConnection = this.client.getConnection();
        this.ownedLevel = this.client.level;
        this.openingMenu = this.client.player.containerMenu;
        this.openingRevision = this.openingMenu.getStateId();
        this.suppressContainerScreen = true;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        InteractionResult result = InteractionUtils.getRuntime().useItemOn(false, InteractionHand.MAIN_HAND, hit);
        if (result == InteractionResult.FAIL) { this.openingMenu = null; releaseResources(); return false; }
        QuickShulkerClicks.requestFullSync(this.openingMenu);
        return true;
    }

    private void failAndContinue() {
        if (this.nestedReturn.isRestoring()) {
            failNestedRestore("归还阶段中止");
            return;
        }
        if (this.targetPos != null) this.invalidCandidates.add(this.targetPos.immutable());
        closeContainer();
        if (!openNextCandidate()) finishUnavailable(this.requestedItem);
    }

    private void finishAvailable() {
        finishAvailable(true);
    }

    private void finishAvailable(boolean closeOwnedMenu) {
        if (this.activeRequest != null) {
            for (Item item : this.activeRequest.acceptedItems()) {
                if (InventoryUtils.playerHasItemInInventory(this.client.player, item, this.activeRequest::acceptsStack)) {
                    this.completedReservation = MaterialReservation.available(this.activeRequest, item);
                    break;
                }
            }
        }
        if (closeOwnedMenu) closeContainer();
        else releaseResources();
        selectCompletedPickBlockItem();
        this.activeRequest = null;
        this.phase = Phase.IDLE;
        this.suppressContainerScreen = false;
        this.expectedContainerId = -1;
        this.nestedReturn.clear();
    }

    private void selectCompletedPickBlockItem() {
        if (this.activeRequest == null
                || this.activeRequest.source() != MaterialRequest.Source.PICK_BLOCK
                || this.client.player == null
                || this.client.player.containerMenu != this.client.player.inventoryMenu) {
            return;
        }
        for (Item item : this.activeRequest.acceptedItems()) {
            if (InventoryUtils.playerHasItemInInventory(this.client.player, item)) {
                InventoryUtils.setPickedItemToHand(new ItemStack(item), this.client);
                return;
            }
        }
    }

    private void finishUnavailable(@Nullable Item item) {
        if (item != null) {
            this.lastFailedItem = item;
            this.lastFailedTick = gameTick();
        }
        closeContainer();
        releaseResources();
        this.activeRequest = null;
        this.phase = Phase.IDLE;
        this.nestedReturn.clear();
    }

    private void closeContainer() {
        if (this.ownedMenu != null
                && this.client.player != null
                && this.client.player.containerMenu == this.ownedMenu
                && this.ownedMenu != this.client.player.inventoryMenu
                && this.client.getConnection() == this.ownedConnection
                && this.client.level == this.ownedLevel) {
            this.closing = true;
            try { this.client.player.closeContainer(); } finally { this.closing = false; }
        }
        clearOwnedMenu();
        this.actionBroker.releaseOwner(LEASE_OWNER);
        this.suppressContainerScreen = false;
        this.expectedContainerId = -1;
    }

    private void releaseResources() {
        clearOwnedMenu();
        this.actionBroker.releaseOwner(LEASE_OWNER);
    }

    private void clearOwnedMenu() {
        this.ownedMenu = null;
        this.ownedConnection = null;
        this.ownedLevel = null;
        this.suppressedScreen = null;
        this.recoveryShownMenu = null;
    }

    private boolean acquire() {
        return this.actionBroker.tryAcquire(LEASE_OWNER, EnumSet.of(ResourceLease.INVENTORY,
                ResourceLease.CONTAINER, ResourceLease.MAIN_HAND, ResourceLease.INTERACTION), 0L);
    }

    private boolean sameWorld() {
        return this.client.player != null && this.client.getConnection() == this.ownedConnection
                && this.client.level == this.ownedLevel;
    }

    private void finishTransfer() {
        if (this.transfer == null || !this.transfer.finished()) return;
        boolean completed = this.transfer.completed();
        this.transfer = null;
        if (this.cancelling || !completed) {
            closeContainer();
            discard();
            return;
        }
        if (this.phase == Phase.WAITING_RESTORE_SYNC) {
            finishAvailable();
            return;
        }
        int shulkerSlot = this.pendingShulker.isEmpty() ? -1
                : NestedShulkerReturn.locateMovedShulker(this.client.player, this.pendingShulker, this.inventoryBeforeTake);
        closeContainer();
        if (!this.pendingShulker.isEmpty() && shulkerSlot >= 0) {
            this.nestedReturn.recordTransfer(this.targetPos, this.pendingSourceSlot, this.pendingShulker, shulkerSlot);
            this.pendingShulker = ItemStack.EMPTY;
            this.inventoryBeforeTake = List.of();
            // The nested provider may acquire inventory only after the outer take is confirmed and closed.
            if (!TakeItOutUtils.tryRequestItem(this.requestedItem)) {
                RuntimeAccess.get().quickShulkerAdapter().requestItemsDirect(List.of(this.requestedItem));
            }
        } else if (hasRequestedItem()) {
            finishAvailable();
        } else {
            failAndContinue();
        }
    }

    public boolean guardManualInput() {
        if (this.closing || !sameWorld()) return false;
        if (this.transfer != null) {
            this.cancelling = true;
            return this.transfer.guardManualInput();
        }
        if (this.openingMenu != null || this.ownedMenu != null) {
            this.cancelling = true;
            return true;
        }
        return false;
    }

    public boolean guardClose() {
        if (this.closing || !sameWorld()) return false;
        if (this.transfer != null) {
            this.cancelling = true;
            this.transfer.cancel();
            return true;
        }
        return guardManualInput();
    }

    private boolean failedRecently(Item item) {
        return item != null && item == this.lastFailedItem && gameTick() - this.lastFailedTick < NOT_FOUND_COOLDOWN_TICKS;
    }

    private long gameTick() {
        return this.client.level == null ? 0L : this.client.level.getGameTime();
    }

    private boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (this.activeRequest != null && !this.activeRequest.acceptsStack(stack)) return false;
        return this.exactMatch
                ? ItemStack.isSameItemSameComponents(stack, this.requestedStack)
                : this.requestedItems.contains(stack.getItem());
    }

    private boolean hasRequestedItem() {
        if (this.client.player == null || this.requestedItem == null) return false;
        for (int slot = 0; slot < Math.min(36, this.client.player.getInventory().getContainerSize()); slot++) {
            ItemStack stack = this.client.player.getInventory().getItem(slot);
            if (matches(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean shulkerContains(ItemStack stack) {
        try {
            for (ItemStack inner : fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1)) {
                if (matches(inner)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean shulkerContains(ItemStack stack, Item requested) {
        try {
            for (ItemStack inner : fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1)) {
                if (!inner.isEmpty() && inner.is(requested)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean isShulker(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private void beginNestedRestore() {
        if (!this.nestedReturn.beginRestore(
                RuntimeAccess.get().quickShulkerAdapter().hasPendingRequest(),
                this.client.player != null && this.client.player.containerMenu == this.client.player.inventoryMenu)) {
            return;
        }
        if (!open(this.nestedReturn.sourcePos())) {
            MessageUtils.setOverlayMessage("Chest Tracker: 潜影盒未能归还，已保留在背包");
            finishAvailable();
            return;
        }
        this.targetPos = this.nestedReturn.sourcePos();
        this.phase = Phase.RESTORE_WAIT_CONTENT;
        this.openDeadline = gameTick() + openTimeoutTicks();
    }

    private void restoreNestedShulker(AbstractContainerMenu menu, int containerSize, LocalPlayer player) {
        int sourceSlot = this.nestedReturn.sourceSlot();
        if (sourceSlot < 0 || sourceSlot >= containerSize) {
            failNestedRestore("源槽位无效");
            return;
        }
        ItemStack source = menu.slots.get(sourceSlot).getItem();
        if (!this.nestedReturn.acceptsSourceSlot(containerSize, source)) {
            failNestedRestore("源槽位已被占用");
            return;
        }
        int inventorySlot = this.nestedReturn.inventorySlot();
        if (inventorySlot < 0 || inventorySlot >= Math.min(36, player.getInventory().getContainerSize())
                || !this.nestedReturn.acceptsInventorySlot(player.getInventory().getContainerSize(),
                player.getInventory().getItem(inventorySlot))) {
            failNestedRestore("背包中的潜影盒位置已变化");
            return;
        }
        int playerSlot = findPlayerMenuSlot(menu, inventorySlot);
        if (playerSlot < 0) {
            failNestedRestore("背包中找不到原潜影盒");
            return;
        }
        if (!menu.slots.get(playerSlot).mayPickup(player) || !menu.slots.get(sourceSlot).mayPlace(player.getInventory().getItem(inventorySlot))) {
            failNestedRestore("槽位不允许归还");
            return;
        }
        this.phase = Phase.WAITING_RESTORE_SYNC;
        this.transfer = new ChestTrackerTransfer(this.client, menu);
        this.transfer.returnStack(playerSlot, sourceSlot);
    }

    private void failNestedRestore(String reason) {
        failNestedRestore(reason, true);
    }

    private void failNestedRestore(String reason, boolean closeOwnedMenu) {
        MessageUtils.setOverlayMessage("Chest Tracker: 潜影盒未能归还（" + reason + "），已保留在背包");
        this.invalidCandidates.add(this.nestedReturn.hasSource()
                ? this.nestedReturn.sourcePos() : this.targetPos.immutable());
        finishAvailable(closeOwnedMenu);
    }

    private void abortRequest(String reason, boolean closeOwnedMenu) {
        MessageUtils.setOverlayMessage("Chest Tracker: 取物已取消（" + reason + "）");
        if (closeOwnedMenu) closeContainer();
        else releaseResources();
        this.activeRequest = null;
        this.phase = Phase.IDLE;
        this.suppressContainerScreen = false;
        this.expectedContainerId = -1;
        this.nestedReturn.clear();
    }

    private static int findPlayerMenuSlot(AbstractContainerMenu menu, int playerInventorySlot) {
        int ordinal = 0;
        int wanted = playerInventorySlot < 9 ? 27 + playerInventorySlot : playerInventorySlot - 9;
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                if (ordinal == wanted) return menuSlot;
                ordinal++;
            }
        }
        return -1;
    }

    private void rebuildIndex() {
        this.index.clear();
        //#if MC >= 12104
        MemoryBank bank = MemoryBankAccess.INSTANCE.getLoaded().orElse(null);
        var key = ProviderUtils.getPlayersCurrentKey().orElse(null);
        if (bank == null || key == null) return;
        MemoryKey memoryKey = bank.getKey(key).orElse(null);
        if (memoryKey == null) return;
        String world = SelectedContainerCache.worldId(this.client);
        String dimension = SelectedContainerCache.dimensionId(this.client);
        int candidateCount = 0;
        for (Map.Entry<BlockPos, Memory> entry : memoryKey.getMemories().entrySet()) {
            if (candidateCount >= MAX_SCAN_CANDIDATES) break;
            BlockPos pos = entry.getKey();
            Memory memory = entry.getValue();
            if (memory == null || memory.container().isEmpty()
                    || !this.selectedContainers.contains(world, dimension, pos)
                    || this.client.level == null || !this.client.level.isLoaded(pos)) continue;
            double distance = this.client.player == null ? 0.0D : this.client.player.distanceToSqr(Vec3.atCenterOf(pos));
            long timestamp = memory.inGameTimestamp() == null ? Long.MIN_VALUE : memory.inGameTimestamp();
            for (Item requested : this.requestedItems) {
                if (candidateCount >= MAX_SCAN_CANDIDATES) break;
                boolean direct = false;
                boolean nested = false;
                for (ItemStack stack : memory.items()) {
                    if (stack.isEmpty()) continue;
                    if (stack.is(requested)) {
                        direct = true;
                        break;
                    }
                    if (isShulker(stack) && shulkerContains(stack, requested)) {
                        nested = true;
                    }
                }
                if (direct || nested) {
                    this.index.computeIfAbsent(requested, ignored -> new ArrayList<>())
                            .add(new Candidate(pos, !direct, distance, timestamp));
                    candidateCount++;
                }
            }
        }
        //#endif
    }

    private List<Candidate> orderedCandidates(List<Item> items) {
        List<Candidate> values = new ArrayList<>();
        for (Item item : items) values.addAll(this.index.getOrDefault(item, List.of()));
        values.removeIf(candidate -> this.invalidCandidates.contains(candidate.pos()));
        values.sort(Comparator.comparing(Candidate::nested)
                .thenComparingDouble(Candidate::distance)
                .thenComparing(Comparator.comparingLong(Candidate::timestamp).reversed()));
        if (values.size() > MAX_SCAN_CANDIDATES) return List.copyOf(values.subList(0, MAX_SCAN_CANDIDATES));
        return List.copyOf(values);
    }

    private record Candidate(BlockPos pos, boolean nested, double distance, long timestamp) {
    }

    private static boolean insideAny(List<me.aleksilassila.litematica.printer.printer.PrinterBox> boxes, BlockPos pos) {
        for (me.aleksilassila.litematica.printer.printer.PrinterBox box : boxes) {
            if (box.contains(pos)) return true;
        }
        return false;
    }

    static boolean waitForRestoreContent(Phase phase) {
        return phase == Phase.RESTORE_WAIT_CONTENT;
    }

    enum Phase {
        IDLE,
        SCANNING,
        WAITING_CONTENT,
        RESTORE_WAIT_CONTENT,
        WAITING_INVENTORY,
        WAITING_RESTORE_SYNC
    }

    private static MaterialReservation pending(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
    }

    private static MaterialReservation unavailable(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
    }
}
