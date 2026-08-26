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

/** Tracks material gains once per client tick without treating normal consumption as a rescan event. */
public final class InventoryAvailabilityTracker implements RuntimeComponent {
    private final Map<Item, Integer> previousCounts = new IdentityHashMap<>();
    private final Map<Item, Integer> currentCounts = new IdentityHashMap<>();
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
                    if (isShulkerBox(stack)) {
                        this.collectShulkerContents(stack);
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
            for (Map.Entry<Item, Integer> entry : this.currentCounts.entrySet()) {
                if (entry.getValue() > this.previousCounts.getOrDefault(entry.getKey(), 0)) {
                    this.gainRevision++;
                    break;
                }
            }
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

    private static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains("shulker_box");
    }

    private void collectShulkerContents(ItemStack stack) {
        try {
            for (ItemStack stored : InventoryUtils.getStoredItems(stack, -1)) {
                if (!stored.isEmpty()) {
                    this.availableItems.add(stored.getItem());
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
        //#if MC >= 12111
        for (ChunkSchematic chunk : chunkManager.getLoadedValueSet()) {
        //#else
        //$$ for (ChunkSchematic chunk : chunkManager.getLoadedChunks().values()) {
        //#endif
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

    public long availabilityRevision() {
        return this.availabilityRevision;
    }

    public boolean isAvailable(Item item) {
        return item != null && this.availableItemsSnapshot.contains(item);
    }

    public Set<Item> availableItemsView() {
        return this.availableItemsSnapshot;
    }

    public void reset() {
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
