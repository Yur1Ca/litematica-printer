package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.GuiHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineDebugLog;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.hasPendingSwitchRequest;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.switchItem;

final class TickScheduler {
    private static final Minecraft MC = Minecraft.getInstance();

    private final ImmutableList<Module> modules;
    private int packetTick;
    private int packetEpoch;
    private String lastPauseReason;
<<<<<<< HEAD
    private boolean runtimeActive;
    private int executionScopeHash = Integer.MIN_VALUE;
    private int roundRobinOffset;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

    TickScheduler(ImmutableList<Module> modules) {
        this.modules = modules;
    }

    void tick() {
        HudStatsManager.INSTANCE.tick();
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()) {
<<<<<<< HEAD
            if (this.runtimeActive) {
                ClientPlayerTickManager.resetRuntime("work_switch_disabled");
            }
            this.runtimeActive = false;
            HudStatsManager.INSTANCE.resetAll();
            this.lastPauseReason = null;
            return;
        }
        int currentScopeHash = this.currentExecutionScopeHash();
        if (!this.runtimeActive) {
            this.runtimeActive = true;
            this.executionScopeHash = currentScopeHash;
        } else if (this.executionScopeHash != currentScopeHash) {
            ClientPlayerTickManager.resetRuntime("execution_scope_changed");
            this.runtimeActive = true;
            this.executionScopeHash = currentScopeHash;
            return;
=======
            HudStatsManager.INSTANCE.resetAll();
            this.lastPauseReason = null;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        }
        if (this.pauseForInventoryState("shared_precheck")) {
            return;
        }
        if (this.pauseForPendingLookQueue()) {
            ActionManager.INSTANCE.sendQueue(MC.player);
            return;
        }
        if (this.pauseForLagCheck()) {
            return;
        }
        TickContext context = TickContext.capture();
        this.resume();
        for (Module handler : this.modules) {
<<<<<<< HEAD
            if (handler instanceof GuiHandler) {
                handler.tick(context);
            }
        }
        int actionableCount = Math.max(0, this.modules.size() - 1);
        if (actionableCount == 0) {
            return;
        }
        int startIndex = this.roundRobinOffset % actionableCount;
        this.roundRobinOffset = (this.roundRobinOffset + 1) % actionableCount;
        for (int offset = 0; offset < actionableCount; offset++) {
            Module handler = this.modules.get(1 + (startIndex + offset) % actionableCount);
            if (this.pauseForHandlerPrecheck(handler)) {
                return;
=======
            if (!(handler instanceof GuiHandler)) {
                if (this.pauseForHandlerPrecheck(handler)) {
                    return;
                }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            }
            handler.tick(context);
        }
    }

    int getPacketTick() {
        return this.packetTick;
    }

    void setPacketTick(int packetTick) {
        this.packetTick = packetTick;
    }

    int getPacketEpoch() {
        return this.packetEpoch;
    }

    void recordInboundPacket() {
        this.packetTick = 0;
        this.packetEpoch++;
    }

    void resetRuntime() {
        this.packetTick = 0;
        this.packetEpoch++;
        this.lastPauseReason = null;
<<<<<<< HEAD
        this.runtimeActive = false;
        this.executionScopeHash = Integer.MIN_VALUE;
        this.roundRobinOffset = 0;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    String getLastPauseReason() {
        return this.lastPauseReason;
    }

    private void pause(String reason) {
        if (!reason.equals(this.lastPauseReason)) {
            MineDebugLog.write("scheduler pause reason=" + reason + " packetTick=" + this.packetTick);
            this.lastPauseReason = reason;
        }
    }

    private void resume() {
        if (this.lastPauseReason != null) {
            MineDebugLog.write("scheduler resume after=" + this.lastPauseReason + " packetTick=" + this.packetTick);
            this.lastPauseReason = null;
        }
    }

    private boolean pauseForInventoryState(String reasonPrefix) {
        boolean switchingItem = switchItem();
        boolean pendingSwitch = hasPendingSwitchRequest();
        boolean takeItOutPending = TakeItOutUtils.isAwaitingStack();
        boolean inventorySwitchPending = InventorySwitchGuard.isWaiting();
        boolean openHandler = isOpenHandler;
        if (pendingSwitch || switchingItem || takeItOutPending || inventorySwitchPending) {
            ActionManager.INSTANCE.clearQueue();
            this.pause(reasonPrefix + " openHandler=" + openHandler + " pendingSwitch=" + pendingSwitch + " switchingItem=" + switchingItem + " takeItOutPending=" + takeItOutPending + " inventorySwitchPending=" + inventorySwitchPending);
            return true;
        }
        return false;
    }

    private boolean pauseForPendingLookQueue() {
        if (!ActionManager.INSTANCE.needWaitModifyLook) {
            return false;
        }
        this.pause("send_queue_wait_modify_look");
        return true;
    }

    private boolean pauseForLagCheck() {
        if (!Configs.Core.LAG_CHECK.getBooleanValue()) {
            return false;
        }
        if (this.packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            this.pause("lag_check packetTick=" + this.packetTick + " max=" + Configs.Core.LAG_CHECK_MAX.getIntegerValue());
            return true;
        }
        this.packetTick++;
        return false;
    }

    private boolean pauseForHandlerPrecheck(Module handler) {
        if (this.pauseForInventoryState("handler_precheck handler=" + handler.getId())) {
            return true;
        }
        if (ActionManager.INSTANCE.needWaitModifyLook) {
            this.pause("action_wait_modify_look handler=" + handler.getId());
            return true;
        }
        return false;
    }
<<<<<<< HEAD

    private int currentExecutionScopeHash() {
        int result = Configs.Core.WORK_MODE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Core.WORK_MODE_TYPE.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Core.PRINT.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.MINE.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.FILL.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.FLUID.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Hotkeys.BEDROCK.getBooleanValue());
        return result;
    }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
}
