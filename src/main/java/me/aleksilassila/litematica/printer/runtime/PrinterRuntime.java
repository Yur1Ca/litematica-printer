package me.aleksilassila.litematica.printer.runtime;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Stage;
import me.aleksilassila.litematica.printer.feature.FeatureActionPolicies;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEventBus;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeScope;
import me.aleksilassila.litematica.printer.handler.FeatureModuleSet;
import me.aleksilassila.litematica.printer.handler.InventoryAvailabilityTracker;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.RttReplayController;
import me.aleksilassila.litematica.printer.printer.PlacementRateController;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryMessageCooldown;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.PlayerInventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.ChestTrackerAdapter;
import me.aleksilassila.litematica.printer.integration.inventory.TakeItOutAdapter;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerAdapter;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEngine;
import me.aleksilassila.litematica.printer.interaction.ToolSwitchService;
import me.aleksilassila.litematica.printer.interaction.StrippableBlockPort;
import me.aleksilassila.litematica.printer.integration.inventory.StrippableBlockAdapter;
//#if MC >= 12111
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockAdapter;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseReturnController;
//#endif
import net.minecraft.client.Minecraft;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.guide.guides.RailGuide;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the active client runtime and is the only platform tick entry point.
 * Platform callbacks and feature modules share this runtime's lifecycle.
 */
public final class PrinterRuntime {
    private final RuntimeTiming timing = new RuntimeTiming(Boolean.getBoolean("litematica.printer.profile"), System::nanoTime);
    private final RuntimeDiagnostics diagnostics = new RuntimeDiagnostics(this.timing,
            message -> Reference.LOGGER.info(message), 100);
    private final RuntimeEventBus events = new RuntimeEventBus();
    private final RuntimeScope scope = new RuntimeScope();
    private RuntimeEpoch epoch = RuntimeEpoch.INITIAL;
    private Object levelIdentity;
    private Object connectionIdentity;
    private Object disconnectedConnection;
    private long currentTick = Long.MIN_VALUE;
    private boolean resetting;
    private MaterialRequestCoordinator materialRequests;
    private final BedrockEngine bedrockEngine;
    private final FeatureModuleSet modules;
    private final ActionBroker actionBroker;
    private final ScanEngine scanEngine;
    private final InventoryAvailabilityTracker inventoryAvailability;
    private final CooldownUtils cooldownUtils;
    private final InteractionUtils interactionUtils;
    private final RttReplayController rttReplayController;
    private final PlacementRateController placementRateController;
    private final MissingMaterialTracker missingMaterials;
    private final HudStatsManager hudStats;
    private final QuickShulkerAdapter quickShulkerAdapter;
    private final ChestTrackerAdapter chestTrackerAdapter;
    private final InventorySwitchGuard inventorySwitchGuard;
    private final InventoryMessageCooldown inventoryMessageCooldown;
    private final LitematicaAdapter litematica;
    private final TweakerooAdapter tweakeroo;
    private final ToolSwitchService toolSwitchService;
    private final StrippableBlockPort strippableBlocks;
    private final me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncController containerSync;
    //#if MC >= 12111
    /** 虚空仓库返还：补货多给的整组材料用不完时退回去，避免材料种类多时背包被占满。 */
    private final VoidWarehouseReturnController warehouseReturn;
    //#endif

    public PrinterRuntime() {
        Minecraft client = Minecraft.getInstance();
        this.scope.register(this.diagnostics);
        this.cooldownUtils = new CooldownUtils();
        this.scope.register(this.cooldownUtils);
        this.litematica = new LitematicaAdapter();
        this.bedrockEngine = new BedrockEngine(client, this.cooldownUtils, this::currentTick, this.litematica, this.timing);
        this.events.subscribe(this.bedrockEngine::onRuntimeEvent);
        this.scope.register(new MinecraftInteractionRuntime(client));
        this.scope.register(this.bedrockEngine);
        this.actionBroker = new ActionBroker(this, new ActionManager(FeatureActionPolicies::forSource));
        this.scope.register(this.actionBroker);
        this.inventorySwitchGuard = new InventorySwitchGuard(client, this::currentTick);
        this.scope.register(new InventorySwitchRuntime(this.inventorySwitchGuard));
        this.tweakeroo = new TweakerooAdapter();
        this.toolSwitchService = new ToolSwitchService(client, this.tweakeroo, this.inventorySwitchGuard);
        this.strippableBlocks = new StrippableBlockAdapter();
        this.inventoryMessageCooldown = new InventoryMessageCooldown();
        this.scope.register(this.inventoryMessageCooldown);
        this.scanEngine = new ScanEngine(this);
        this.scope.register(this.scanEngine);
        this.inventoryAvailability = new InventoryAvailabilityTracker();
        this.scope.register(this.inventoryAvailability);
        this.interactionUtils = new InteractionUtils();
        this.scope.register(this.interactionUtils);
        this.rttReplayController = new RttReplayController();
        this.scope.register(this.rttReplayController);
        this.placementRateController = new PlacementRateController(this.rttReplayController);
        this.scope.register(this.placementRateController);
        this.missingMaterials = new MissingMaterialTracker();
        this.scope.register(this.missingMaterials);
        this.hudStats = new HudStatsManager(client, this::currentTick, this.rttReplayController);
        this.scope.register(this.hudStats);
        this.quickShulkerAdapter = new QuickShulkerAdapter(this.actionBroker);
        this.scope.register(this.quickShulkerAdapter);
        this.chestTrackerAdapter = new ChestTrackerAdapter(this.actionBroker);
        this.scope.register(this.chestTrackerAdapter);
        this.containerSync = new me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncController(this);
        this.scope.register(this.containerSync);
        //#if MC >= 12111
        // 必须晚于 inventorySwitchGuard（两个 supplier 都会用到它）。
        // 独立组件而不是塞进 containerSync：那条路要开潜影盒、占容器与主手租约，
        // 这条只发一个包、什么都不占，时序上不相容，混在一起只会互相踩状态。
        this.warehouseReturn = new VoidWarehouseReturnController(
                client,
                this::isPrinterWorkingForWarehouse,
                this.inventorySwitchGuard::isWaiting,
                this::warehouseRequestedItems);
        this.scope.register(this.warehouseReturn);
        //#endif
        this.modules = new FeatureModuleSet(this);
    }

    public RuntimeEpoch epoch() {
        return this.epoch;
    }

    public RuntimeTiming timing() { return this.timing; }

    public me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncController containerSync() {
        return this.containerSync;
    }

    public long currentTick() {
        return this.currentTick;
    }

    public RuntimeEventBus events() {
        return this.events;
    }

    public BedrockEngine bedrockEngine() {
        return this.bedrockEngine;
    }

    public FeatureModuleSet modules() {
        return this.modules;
    }

    public ActionBroker actionBroker() {
        return this.actionBroker;
    }

    public ScanEngine scanEngine() {
        return this.scanEngine;
    }

    public InventoryAvailabilityTracker inventoryAvailability() {
        return this.inventoryAvailability;
    }

    public CooldownUtils cooldownUtils() {
        return this.cooldownUtils;
    }

    public InteractionUtils interactionUtils() {
        return this.interactionUtils;
    }

    public RttReplayController rttReplayController() {
        return this.rttReplayController;
    }

    public PlacementRateController placementRateController() {
        return this.placementRateController;
    }

    public MissingMaterialTracker missingMaterials() {
        return this.missingMaterials;
    }

    public HudStatsManager hudStats() {
        return this.hudStats;
    }

    public QuickShulkerAdapter quickShulkerAdapter() {
        return this.quickShulkerAdapter;
    }

    public ChestTrackerAdapter chestTrackerAdapter() {
        return this.chestTrackerAdapter;
    }

    public InventorySwitchGuard inventorySwitchGuard() {
        return this.inventorySwitchGuard;
    }

    public InventoryMessageCooldown inventoryMessageCooldown() {
        return this.inventoryMessageCooldown;
    }

    public LitematicaAdapter litematica() {
        return this.litematica;
    }

    public TweakerooAdapter tweakeroo() {
        return this.tweakeroo;
    }

    public ToolSwitchService toolSwitchService() {
        return this.toolSwitchService;
    }

    public StrippableBlockPort strippableBlocks() {
        return this.strippableBlocks;
    }

    public Minecraft client() {
        return Minecraft.getInstance();
    }

    public AutoCloseable register(RuntimeComponent component) {
        return this.scope.register(component);
    }

    public MaterialRequestCoordinator materialRequests() {
        if (this.materialRequests == null) {
            // Behavioral contract: one request walks this list once and stops at the first
            // AVAILABLE/PENDING provider. Changing the order changes user-visible pickup priority.
            List<InventoryProvider> providers = new ArrayList<>();
            providers.add(new PlayerInventoryProvider(Minecraft.getInstance()));
            providers.add(new TakeItOutAdapter(this.actionBroker));
            //#if MC >= 12111
            VoidWarehouseRestockAdapter warehouseRestock = new VoidWarehouseRestockAdapter(this.actionBroker);
            warehouseRestock.attachReturnController(this.warehouseReturn);
            providers.add(warehouseRestock);
            //#endif
            providers.add(this.quickShulkerAdapter);
            providers.add(this.chestTrackerAdapter);
            this.materialRequests = new MaterialRequestCoordinator(List.copyOf(providers));
            this.scope.register(this.materialRequests);
        }
        return this.materialRequests;
    }

    //#if MC >= 12111
    /**
     * 打印机现在算不算「在干活」。
     *
     * <p>判据只有工作总开关：打印中途会有一段时间不请求任何材料（一组建材能打几十个方块），
     * 用「最近有没有请求」当判据会把还要用的材料退回去，形成「退 → 要 → 退」的空转。
     * 关掉总开关（收工）才是最可靠的「这些余料不会再用」信号。</p>
     */
    private boolean isPrinterWorkingForWarehouse() {
        return Configs.Core.WORK_SWITCH.getBooleanValue();
    }

    /**
     * 当前材料请求正在要的物品。
     *
     * <p>退还时要避开它们：打印机对同一种材料是「用完 → 再要一组」的循环，
     * 在它正等这一种时把剩下的半组退回去，就是死循环。</p>
     */
    private List<net.minecraft.world.item.Item> warehouseRequestedItems() {
        if (this.materialRequests == null) {
            return List.of();
        }
        return this.materialRequests.activeItems();
    }
    //#endif

    public void tick(Minecraft client) {
        long tickStart = this.diagnostics.tickStarted();
        try {
            Object currentLevel = client.level;
            Object currentConnection = client.getConnection();
            this.currentTick = currentLevel == null ? Long.MIN_VALUE : client.level.getGameTime();
            if (currentLevel != this.levelIdentity || currentConnection != this.connectionIdentity) {
                this.levelIdentity = currentLevel;
                this.connectionIdentity = currentConnection;
                this.reset("client_scope_changed");
            }

            this.modules.synchronizeActivation();
            this.cooldownUtils.tick();
            long materialStart = this.timing.start();
            try {
                this.containerSync.tick();
                QuickShulkerBridge.onTick();
                this.chestTrackerAdapter.tick();
                if (this.materialRequests != null) {
                    this.materialRequests.tick();
                    this.quickShulkerAdapter.clearExternalRequestIfIdle(this.materialRequests.isBusy());
                }
            } finally {
                this.timing.finish("runtime", Stage.MATERIAL_TICK, materialStart);
            }
            long miningStart = this.timing.start();
            try {
                this.interactionUtils.preprocess();
                RailGuide.tickRepairState(client.level, this.litematica.schematicWorld());
                this.interactionUtils.onTick();
            } finally {
                this.timing.finish("runtime", Stage.MINING_TICK, miningStart);
            }
            this.modules.tick();
            //#if MC >= 12111
            // 放在最后：这一 tick 的取料/放置都已结算，看到的是背包的最终状态。
            this.warehouseReturn.tick();
            //#endif
        } finally {
            this.diagnostics.tickCompleted(this.epoch, tickStart);
        }
    }

    /** Connection mixins also run for LAN peers and on Netty threads. Resolve ownership on the client thread. */
    public void onConnectionDisconnected(Object sourceConnection) {
        Minecraft client = this.client();
        client.execute(() -> {
            var listener = client.getConnection();
            if (listener == null || listener.getConnection() != sourceConnection
                    || this.disconnectedConnection == sourceConnection) {
                return;
            }
            this.disconnectedConnection = sourceConnection;
            this.reset("disconnect");
            RailGuide.clearRepairState();
            if (Configs.Core.AUTO_DISABLE_PRINTER.getBooleanValue() && Configs.Core.WORK_SWITCH.getBooleanValue()) {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
            }
        });
    }

    public void cancelMaterialsFor(String owner) {
        if (this.materialRequests != null) this.materialRequests.cancelOwner(owner);
    }

    public void reset(String reason) {
        if (this.resetting) {
            return;
        }
        this.resetting = true;
        try {
            RuntimeEpoch previous = this.epoch;
            this.epoch = previous.next();
            this.currentTick = Long.MIN_VALUE;
            RuntimeEvent.EpochChanged event = new RuntimeEvent.EpochChanged(previous, this.epoch, reason);
            this.scope.changeEpoch(event);
            this.events.publish(event);
        } finally {
            this.resetting = false;
        }
    }
}
