package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Tracks material gains once per client tick without treating normal consumption as a rescan event. */
public final class InventoryAvailabilityTracker implements RuntimeComponent {
    private final Map<Item, Integer> previousCounts = new IdentityHashMap<>();
    private final Map<Item, Integer> currentCounts = new IdentityHashMap<>();
    private final Map<Item, Long> lastGainByItem = new IdentityHashMap<>();
    private final Set<Item> previousAvailableItems = new HashSet<>();
    private final Set<Item> availableItems = new HashSet<>();
    private volatile Set<Item> availableItemsSnapshot = Collections.emptySet();
    private boolean initialized;
    private boolean availabilityTracking;
    private long gainRevision;
    private volatile long availabilityRevision;

    public InventoryAvailabilityTracker() {
    }

    public void tick(LocalPlayer player) {
        if (player == null) {
            this.reset();
            return;
        }
        this.currentCounts.clear();
        boolean trackAvailability = Configs.Core.RENDER_ONLY_HOLDING_ITEMS.getBooleanValue();
        if (trackAvailability) {
            this.previousAvailableItems.clear();
            this.previousAvailableItems.addAll(this.availableItems);
            this.availableItems.clear();
        }
        int size = player.getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                this.currentCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                if (trackAvailability) {
                    this.availableItems.add(stack.getItem());
                    if (isShulkerBox(stack.getItem())) {
                        collectShulkerContents(stack, this.availableItems::add);
                    }
                }
            }
        }
        if (trackAvailability) {
            // Take It Out throttles this request internally (500 ms and one in-flight payload),
            // so polling here keeps the render-only view fresh without sending a packet every tick.
            TakeItOutUtils.requestAvailableItemsRefresh();
            // Quick Shulker and Take It Out may have accepted the material request while the
            // resulting stack is still travelling through their external inventory flow.
            this.availableItems.addAll(RuntimeAccess.get().materialRequests().activeItems());
            // Take It Out keeps linked-container contents outside the player inventory. Include
            // its positive-count cache entries so Render Only Holding Items reflects material that
            // can actually be fetched through the enabled integration.
            this.availableItems.addAll(TakeItOutUtils.getAvailableItems());
        }
        if (this.initialized) {
            this.recordGains(player);
        } else {
            this.initialized = true;
        }
        this.previousCounts.clear();
        this.previousCounts.putAll(this.currentCounts);

        if (trackAvailability) {
            boolean changed = !this.availabilityTracking
                    || !this.availableItems.equals(this.previousAvailableItems);
            if (changed) {
                this.availabilityRevision++;
                this.availableItemsSnapshot = Set.copyOf(this.availableItems);
                this.scheduleSchematicRenderRefresh();
            }
            this.availabilityTracking = true;
        } else {
            if (this.availabilityTracking || !this.availableItems.isEmpty()) {
                this.availabilityRevision++;
            }
            this.availabilityTracking = false;
            this.availableItems.clear();
            this.previousAvailableItems.clear();
            this.availableItemsSnapshot = Collections.emptySet();
        }
    }

    private void recordGains(LocalPlayer player) {
        long nextRevision = this.gainRevision + 1;
        Set<Item> gainedBoxes = null;
        for (var entry : this.currentCounts.entrySet()) {
            Item item = entry.getKey();
            if (entry.getValue() <= this.previousCounts.getOrDefault(item, 0)) continue;
            this.gainRevision = nextRevision;
            this.lastGainByItem.put(item, nextRevision);
            if (Configs.Placement.QUICK_SHULKER.getBooleanValue() && isShulkerBox(item)) {
                if (gainedBoxes == null) gainedBoxes = new HashSet<>();
                gainedBoxes.add(item);
            }
        }
        if (gainedBoxes == null) return;
        // Quick Shulker can supply the contents of a newly acquired singleton box immediately.
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack box = inventory.getItem(slot);
            if (box.getCount() == 1 && gainedBoxes.contains(box.getItem())) {
                collectShulkerContents(box, stored -> this.lastGainByItem.put(stored, nextRevision));
            }
        }
    }

    private static boolean isShulkerBox(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString().contains("shulker_box");
    }

    private static void collectShulkerContents(ItemStack stack, Consumer<Item> accept) {
        try {
            for (ItemStack stored : InventoryUtils.getStoredItems(stack, -1)) {
                if (!stored.isEmpty()) {
                    accept.accept(stored.getItem());
                }
            }
        } catch (Exception ignored) {
            // Contents unreadable for this stack - keep the outer shulker item available.
        }
    }

    private void scheduleSchematicRenderRefresh() {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            return;
        }
        ChunkManagerSchematic chunkManager = (ChunkManagerSchematic) schematic.getChunkSource();
        for (ChunkSchematic chunk : chunkManager.getLoadedValueSet()) {
            if (chunk == null) {
                continue;
            }
            //#if MC >= 260100
            schematic.scheduleChunkRenders(chunk.getPos().x(), chunk.getPos().z());
            //#else
            //$$ schematic.scheduleChunkRenders(chunk.getPos().x, chunk.getPos().z);
            //#endif
        }
    }

    public long gainRevision() {
        return this.gainRevision;
    }

    /** Include every gain since this module last ran, even across skipped ticks. */
    public boolean gainedSince(Item[] alternatives, long revision) {
        if (alternatives == null || alternatives.length == 0) return this.gainRevision > revision;
        for (Item item : alternatives) {
            if (this.lastGainByItem.getOrDefault(item, Long.MIN_VALUE) > revision) return true;
        }
        return false;
    }

    public long availabilityRevision() {
        return this.availabilityRevision;
    }

    public boolean isAvailable(Item item) {
        return item != null && this.availableItemsSnapshot.contains(item);
    }

    public void reset() {
        this.lastGainByItem.clear();
        this.previousCounts.clear();
        this.currentCounts.clear();
        this.previousAvailableItems.clear();
        this.availableItems.clear();
        this.availableItemsSnapshot = Collections.emptySet();
        this.initialized = false;
        this.availabilityTracking = false;
        this.gainRevision++;
        this.availabilityRevision++;
    }

    @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) { this.reset(); }
}
