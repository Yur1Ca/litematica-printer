package me.aleksilassila.litematica.printer.guide;

import me.aleksilassila.litematica.printer.printer.action.Action;

import java.util.Optional;

/**
 * Guide 动作执行结果。
 *
 * <pre>
 *     // 成功返回
 *     return Result.success(new Action().setSides(facing));
 *
 *     // 交给下一位
 *     return Result.PASS;
 *
 *     // 便捷方法：仅当条件成立时返回动作
 *     return resultIf(condition, action);
 * </pre>
 */
public record Result(Action action, boolean passToNext, boolean skipOtherGuide) {

    /** 不处理，让下一个 Guide 继续 */
    public static final Result PASS = new Result(null, true, false);

    /** 无结果（兼容旧代码） */
    public static final Result EMPTY = PASS;

    /** 跳过其他指南，不处理此方块 */
    public static final Result SKIP = new Result(null, false, true);

    public static Result success(Action action) {
        return new Result(action, false, false);
    }

    public static Result success() {
        return new Result(null, false, false);
    }

    /**
     * 条件成立时返回成功结果，否则交给下一个 Guide。
     *
     * @param condition 条件
     * @param action    要返回的动作
     * @return 条件成立则返回 {@link #success(Action)}，否则返回 {@link #PASS}
     */
    public static Result resultIf(boolean condition, Action action) {
        return condition ? success(action) : PASS;
    }

    /**
     * 条件成立时返回成功结果，否则交给下一个 Guide。
     *
     * @param condition 条件
     * @param supplier  动作供应者（延迟执行）
     * @return 条件成立则返回成功结果，否则返回 {@link #PASS}
     */
    public static Result resultIf(boolean condition, java.util.function.Supplier<Action> supplier) {
        return condition ? success(supplier.get()) : PASS;
    }

    /**
     * 将 Result 转换为 Optional。
     */
    public Optional<Action> toOptional() {
        return Optional.ofNullable(action);
    }

    /**
     * 检查是否有动作。
     */
    public boolean hasAction() {
        return action != null;
    }

    // ==================== 便捷的流式方法 ====================

    /**
     * 如果当前是 PASS，则返回 other。
     */
    public Result or(Result other) {
        return passToNext ? other : this;
    }

    /**
     * 如果当前是 PASS，则使用 supplier 生成结果。
     */
    public Result or(java.util.function.Supplier<Result> supplier) {
        return passToNext ? supplier.get() : this;
    }
}
