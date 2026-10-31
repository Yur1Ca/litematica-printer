package me.aleksilassila.litematica.printer.integration.voidwarehouse;

//#if MC >= 12111
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import neko.voidwarehouse.restock.PrinterTakePayload;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把服务端 carpet-Neko-Addition 的虚空仓库接进打印机的补货责任链。
 *
 * <h2>它取代的是什么</h2>
 * <p>原版打印机缺料时会按顺序拜托 Take It Out（世界容器）、QuickShulker（背包潜影盒）、
 * Chest Tracker（记忆里的箱子）去要货。这些来源的共同点是：<b>发的都是玩家自己攒下的物资</b>。
 * 虚空仓库则是服务端的一笔独立库存。把它挂在 Take It Out 之后、快捷潜影盒之前，
 * 语义就是「确实有地方拿就先拿真的，都拿不到才动用仓库」。</p>
 *
 * <h2>一次请求的时间线</h2>
 * <ol>
 *   <li>{@code request}：把候选物品的注册表 id 打包发过去，标记这套 token 在等回执；</li>
 *   <li>{@code status}：每 tick 轮询 —— 背包里真的出现了就算成功，
 *      收到否定回执就立刻判定缺料，不用干等到超时；</li>
 *   <li>真超时由 {@code MaterialRequestCoordinator} 兜底，它会调用 {@code reset}。</li>
 * </ol>
 *
 * <h2>为什么成功与否以「背包里有没有」为准</h2>
 * <p>服务端回 {@code OK} 只说明它发货了，还要经过一次背包同步才会到客户端手上。
 * 以回执为准会出现「判定成功但手上还是空的」，下一步放置必然失败。</p>
 *
 * <p>从发出补货请求到收到背包更新、完成选取期间持有库存租约，
 * 避免与容器搬运或其他补料组件交叉操作。等待别的组件释放租约不消耗响应超时。</p>
 */
public final class VoidWarehouseRestockAdapter implements InventoryProvider {

    /**
     * 同一次缺料失败后，隔多久才肯再问一次服务端。
     *
     * <p>coordinator 判定缺料后就把 request 清掉了，而打印机每 tick 都会为同一个缺料重发请求。
     * 没有这个冷却的话，缺一种方块就会每秒二十次地问服务端要同一种东西 ——
     * 服务端虽然有 50 毫秒限流拦着，但白白的坐标系写盘开销一点也没省下来。
     * 5 秒足够让「真的去补货」这件事完成。</p>
     */
    private static final long FAILED_COOLDOWN_TICKS = 100L;
    private static final String LEASE_OWNER = "void_warehouse";

    /** 主背包 + 快捷栏 = 36 格，与服务端发货／返还的口径一致。 */
    private static final int MAIN_INVENTORY_SLOTS = 36;

    private final Minecraft client = Minecraft.getInstance();
    private final ActionPort actionBroker;
    private final VoidWarehouseRestockPort network;

    private MaterialRequest activeRequest;
    private int activeNonce;

    private MaterialRequest lastFailedRequest;
    private long lastFailedTick = Long.MIN_VALUE;

    /**
     * 返还控制器：到货时往它账上记一笔，等背包吃紧或收工时退回去。
     *
     * <p>允许为 {@code null}（单测、自检这类没有运行时的场合）：那时补货照旧，
     * 只是不记返还账。</p>
     */
    private VoidWarehouseReturnController returnController;

    /**
     * 发请求那一刻，候选物品在背包里的件数；到货后拿它算「仓库到底给了多少」。
     *
     * <p>为什么不记「哪一格」：服务端塞货时会先合并到已有的同类堆、再找空格，
     * 落在哪一格由它自己决定，客户端事后无从推断；件数是唯一稳定的口径。</p>
     */
    private final Map<Item, Integer> heldBefore = new HashMap<>();

    public VoidWarehouseRestockAdapter(ActionPort actionBroker) {
        this(actionBroker, new VoidWarehouseRestockPort() {
            @Override public boolean isSupported() { return VoidWarehouseRestockNetwork.isSupported(); }
            @Override public int requestTake(List<String> candidates) { return VoidWarehouseRestockNetwork.requestTake(candidates); }
            @Override public int statusOf(int nonce) { return VoidWarehouseRestockNetwork.statusOf(nonce); }
            @Override public void forget() { VoidWarehouseRestockNetwork.forget(); }
        });
    }

    public VoidWarehouseRestockAdapter(ActionPort actionBroker, VoidWarehouseRestockPort network) {
        this.actionBroker = actionBroker;
        this.network = network;
    }

    /**
     * 挂上返还控制器（{@code PrinterRuntime} 装配时调一次）。
     *
     * <p>用 setter 而不是加构造参数，是为了不破坏上游已有的两参构造与
     * {@link VoidWarehouseRestockPort} 这层间接 —— 那个构造是给单测用的。</p>
     */
    public void attachReturnController(VoidWarehouseReturnController controller) {
        this.returnController = controller;
    }

    @Override
    public String id() {
        return "void_warehouse";
    }

    @Override
    public MaterialReservation request(MaterialRequest request) {
        if (!enabled(request)) {
            finish();
            return unavailable(request);
        }
        this.activeRequest = request;
        if (!acquireResources()) {
            return pending(request);
        }
        Item availableItem = findAvailableItem(request);
        if (availableItem != null) {
            selectCompletedPickBlockItem(request, availableItem);
            finish();
            return MaterialReservation.available(request, availableItem);
        }
        List<String> candidates = candidatesOf(request);
        if (candidates.isEmpty()) {
            finish();
            return unavailable(request);
        }
        // 发货前的件数快照：到货后靠它算净增量（服务端塞不下的部分会回滚）
        rememberHeldBefore(request);
        int nonce = this.network.requestTake(candidates);
        if (nonce == 0) {
            finish();
            return unavailable(request);
        }
        this.activeRequest = request;
        this.activeNonce = nonce;
        return pending(request);
    }

    @Override
    public MaterialReservation status(MaterialRequest request) {
        if (this.activeRequest == null || this.activeRequest.token() != request.token()) {
            return unavailable(request);
        }
        if (!Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue()
                || this.client.player == null || this.client.level == null) {
            finish();
            return unavailable(request);
        }
        if (this.activeNonce == 0) {
            return request(request);
        }
        if (!acquireResources()) {
            return pending(request);
        }
        Item availableItem = findAvailableItem(request);
        if (availableItem != null) {
            recordDelivery(availableItem);
            selectCompletedPickBlockItem(request, availableItem);
            finish();
            return MaterialReservation.available(request, availableItem);
        }
        int status = this.network.statusOf(this.activeNonce);
        if (status == PrinterTakeResultPayload.NONE) {
            // 还没等到回执，继续等；真超时由 coordinator 负责
            return pending(request);
        }
        if (status == PrinterTakeResultPayload.OK) {
            // 服务端说给了，但背包同步还没到。继续轮询背包，直到超时为止。
            return pending(request);
        }
        rememberFailure(request);
        finish();
        return unavailable(request);
    }

    @Override
    public long pendingTimeoutTicks() {
        return 100L;
    }

    @Override
    public boolean blocksPrinterWhilePending() {
        return true;
    }

    @Override
    public boolean isWaitingToStart() {
        return this.activeRequest != null && this.activeNonce == 0;
    }

    @Override
    public void reset() {
        finish();
        this.lastFailedRequest = null;
        this.lastFailedTick = Long.MIN_VALUE;
    }

    /**
     * 双开关：服务端那边要开 Carpet 规则（不知道开没开也无所谓，服务端会回 RULE_OFF），
     * 本地这边要有配置项。
     */
    private boolean enabled(MaterialRequest request) {
        if (!Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue()) {
            return false;
        }
        if (this.client.player == null || this.client.level == null) {
            return false;
        }
        if (failedRecently(request)) {
            return false;
        }
        return this.network.isSupported();
    }

    private boolean failedRecently(MaterialRequest request) {
        // Tokens and callers may change while the material requirement stays the same.
        // A different candidate list or stack constraint must be allowed to query the warehouse.
        if (this.lastFailedRequest == null
                || request.preferredItem() != this.lastFailedRequest.preferredItem()
                || !request.acceptedItems().equals(this.lastFailedRequest.acceptedItems())
                || request.emptyShulkerOnly() != this.lastFailedRequest.emptyShulkerOnly()) {
            return false;
        }
        return gameTick() - this.lastFailedTick < FAILED_COOLDOWN_TICKS;
    }

    private void rememberFailure(MaterialRequest request) {
        this.lastFailedRequest = request;
        this.lastFailedTick = gameTick();
    }

    private void finish() {
        this.actionBroker.releaseOwner(LEASE_OWNER);
        this.activeRequest = null;
        this.activeNonce = 0;
        this.heldBefore.clear();
        this.network.forget();
    }

    private boolean acquireResources() {
        return this.client.player != null
                && this.client.player.containerMenu == this.client.player.inventoryMenu
                && this.actionBroker.tryAcquire(LEASE_OWNER,
                        EnumSet.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0L);
    }

    private Item findAvailableItem(MaterialRequest request) {
        for (Item item : request.acceptedItems()) {
            if (InventoryUtils.playerHasItemInInventory(this.client.player, item, request::acceptsStack)) {
                return item;
            }
        }
        return null;
    }

    private static void selectCompletedPickBlockItem(MaterialRequest request, Item item) {
        Minecraft client = Minecraft.getInstance();
        if (request.source() == MaterialRequest.Source.PICK_BLOCK
                && client.player != null
                && client.player.containerMenu == client.player.inventoryMenu) {
            InventoryUtils.setPickedItemToHand(new ItemStack(item), client);
        }
    }

    /**
     * 把请求里的候选物品翻译成服务端能解析的注册表 id，首选物品排在最前。
     *
     * <p>为什么不是只发首选：打印机常常接受一组「可以互相替代」的方块
     * （比如同一个投影位允许若干种台阶）。让服务端按优先级依次挑一个真有货的，
     * 一次往返就能解决问题。</p>
     */
    private static List<String> candidatesOf(MaterialRequest request) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, request.preferredItem());
        for (Item item : request.acceptedItems()) {
            addCandidate(candidates, item);
        }
        return List.copyOf(candidates);
    }

    private static void addCandidate(List<String> candidates, Item item) {
        if (item == null || candidates.size() >= PrinterTakePayload.MAX_CANDIDATES) {
            return;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        if (key == null) {
            return;
        }
        String id = key.toString();
        if (id.isEmpty() || id.equals("minecraft:air") || candidates.contains(id)) {
            return;
        }
        candidates.add(id);
    }

    /**
     * 发货前把候选物品的件数记下来（见 {@link #heldBefore}）。
     */
    private void rememberHeldBefore(MaterialRequest request) {
        this.heldBefore.clear();
        if (this.returnController == null) {
            return;
        }
        for (Item item : request.acceptedItems()) {
            if (item != null) {
                this.heldBefore.put(item, countHeld(item));
            }
        }
    }

    /**
     * 到货了：把「实际增加的件数」记进返还账，并把这件物品标成「刚到手」。
     *
     * <p>取净增量而不是「请求里写的一组」：背包只塞得下一部分时服务端会回滚剩下的，
     * 那时实际到手的可能只有十几个 —— 按 64 记账会让返还时把背包里原本就有的同种物品
     * 也算成仓库给的，等于把玩家的存货退回仓库。</p>
     *
     * <p>🔴 {@code markDelivered} 不能省：它是整条链上唯一知道「这批材料是刚发给打印机的」
     * 的地方。打印机拿到整组后往往只放掉三五个就换下一种，那段「请求已完成、材料还没用完」
     * 的窗口里 {@code activeItems()} 是空的，控制器自己分不清「余料」与「马上要用的料」，
     * 于是刚发到手就退回、下一 tick 再要，形成「退 → 要 → 退」的空转。</p>
     */
    private void recordDelivery(Item item) {
        if (this.returnController == null) {
            return;
        }
        int before = this.heldBefore.getOrDefault(item, 0);
        int now = countHeld(item);
        int delivered = now - before;
        if (delivered > 0) {
            this.returnController.recordTaken(item, delivered);
            this.returnController.markDelivered(item);
        }
        this.heldBefore.put(item, now);
    }

    /** 主背包 + 快捷栏里某种物品的件数（与服务端的 36 格口径一致）。 */
    private int countHeld(Item item) {
        if (this.client.player == null) {
            return 0;
        }
        net.minecraft.world.entity.player.Inventory inventory = this.client.player.getInventory();
        int size = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int total = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private long gameTick() {
        return this.client.level == null ? 0L : this.client.level.getGameTime();
    }

    private static MaterialReservation pending(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
    }

    private static MaterialReservation unavailable(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
    }
}
//#endif
