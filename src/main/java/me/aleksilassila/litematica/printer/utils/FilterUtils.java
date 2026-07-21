package me.aleksilassila.litematica.printer.utils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Minecraft 方块/物品名称匹配工具类
 * 支持：标签匹配、中文/拼音模糊匹配、包含/精确相等判断
 */
public class FilterUtils {
    // 常量定义：提升可读性，避免魔法值
    private static final char TAG_PREFIX = '#';
    private static final String SPLIT_SEPARATOR = ",";
    private static final String CONTAINS_FLAG = "c";
    private static final char STATE_START = '[';
    private static final char STATE_END = ']';
    private static final char PROPERTY_EQUALS = '=';

    /**
     * 字符串匹配规则：支持"包含"（c参数）或"精确相等"
     * @param targetStr 目标字符串（待匹配的字符串）
     * @param matchStr  匹配字符串（要检查的内容）
     * @param matchRules 匹配规则参数（包含"c"则启用包含匹配）
     * @return true=匹配成功，false=匹配失败
     */
    public static boolean matchString(String targetStr, String matchStr, String[] matchRules) {
        // 空值防护：任意字符串为null则直接不匹配
        if (targetStr == null || matchStr == null) {
            return false;
        }
        // 规则1：是否启用"包含"匹配（只要有一个"c"参数就启用）
        boolean enableContainsMatch = Arrays.asList(matchRules).contains(CONTAINS_FLAG);
        boolean containsMatchResult = enableContainsMatch && targetStr.contains(matchStr);
        // 规则2：精确相等匹配
        boolean exactMatchResult = targetStr.equals(matchStr);
        // 满足任一规则即匹配成功
        return containsMatchResult || exactMatchResult;
    }

    /**
     * 匹配方块名称（封装matchName，提升语义）
     */
    public static boolean matchBlockName(String expectedName, BlockState blockState) {
        return matchName(expectedName, blockState);
    }

    /**
     * 匹配物品名称（封装matchName，提升语义）
     */
    public static boolean matchItemName(String expectedName, ItemStack itemStack) {
        return matchName(expectedName, itemStack);
    }

    /**
     * 核心匹配逻辑：支持方块/物品的名称、标签、中文/拼音模糊匹配
     * @param expectedName 期望的名称（支持#开头的标签，逗号分隔规则参数）
     * @param targetObj    目标对象（仅支持BlockState/ItemStack）
     * @return true=匹配成功，false=匹配失败
     */
    public static boolean matchName(String expectedName, Object targetObj) {
        // 空值防护
        if (expectedName == null || targetObj == null) {
            return false;
        }

        // 解析期望名称和匹配规则（逗号分隔，第一个是名称，后续是规则）
        ParsedFilter parsedFilter = parseExpectedName(expectedName);
        String coreName = parsedFilter.coreName();
        String[] matchRules = parsedFilter.matchRules();

        // 获取目标对象的核心标识（注册表名称）
        String targetRegistryName = getTargetRegistryName(targetObj);
        if (targetRegistryName == null) {
            return false;
        }
        if (coreName.isEmpty()) {
            return false;
        }

        if (targetObj instanceof BlockState blockState) {
            ParsedBlockStateFilter stateFilter = parseBlockStateFilter(coreName);
            if (stateFilter != null) {
                return matchBlockStateFilter(blockState, stateFilter, matchRules);
            }
        }

        // 优先匹配标签（如果期望名称以#开头）
        if (coreName.startsWith(String.valueOf(TAG_PREFIX))) {
            String tagName = coreName.substring(1);
            // 拆分处理Block和Item的标签，避免泛型转换错误
            if (targetObj instanceof BlockState blockState) {
                return matchBlockTag(blockState, tagName, matchRules);
            } else if (targetObj instanceof ItemStack itemStack) {
                return matchItemTag(itemStack, tagName, matchRules);
            }
            return false;
        }

        // 匹配中文名称、拼音、注册表名称
        String targetDisplayName = getTargetDisplayName(targetObj);
        if (targetDisplayName == null) {
            return false;
        }

<<<<<<< HEAD
        if (matchString(targetDisplayName, coreName, matchRules)
                || matchString(targetRegistryName, coreName, matchRules)) {
            return true;
        }
        return PinYinSearchUtils.getPinYin(targetDisplayName)
                .stream()
                .anyMatch(pinyin -> matchString(pinyin, coreName, matchRules));
=======
        // 中文名称匹配
        boolean displayNameMatch = matchString(targetDisplayName, coreName, matchRules);
        // 拼音匹配
        boolean pinyinMatch = PinYinSearchUtils.getPinYin(targetDisplayName)
                .stream()
                .anyMatch(pinyin -> matchString(pinyin, coreName, matchRules));
        // 注册表名称匹配
        boolean registryNameMatch = matchString(targetRegistryName, coreName, matchRules);

        // 任一匹配成功即返回 true
        return displayNameMatch || pinyinMatch || registryNameMatch;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    private static ParsedFilter parseExpectedName(String expectedName) {
        List<String> parts = splitTopLevel(expectedName, SPLIT_SEPARATOR.charAt(0));
        String coreName = parts.isEmpty() ? "" : parts.get(0).trim();
        String[] matchRules = parts.size() > 1
                ? parts.subList(1, parts.size()).stream()
                .map(String::trim)
                .toArray(String[]::new)
                : new String[0];
        return new ParsedFilter(coreName, matchRules);
    }

    private static List<String> splitTopLevel(String input, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int stateDepth = 0;

        for (int i = 0; i < input.length(); i++) {
            char currentChar = input.charAt(i);
            if (currentChar == STATE_START) {
                stateDepth++;
            } else if (currentChar == STATE_END && stateDepth > 0) {
                stateDepth--;
            }

            if (currentChar == separator && stateDepth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }

            current.append(currentChar);
        }

        parts.add(current.toString());
        return parts;
    }

    private static ParsedBlockStateFilter parseBlockStateFilter(String coreName) {
        int stateStartIndex = coreName.indexOf(STATE_START);
        int stateEndIndex = coreName.lastIndexOf(STATE_END);

        if (stateStartIndex < 0 && stateEndIndex < 0) {
            return null;
        }
        if (stateStartIndex <= 0 || stateEndIndex != coreName.length() - 1 || stateStartIndex > stateEndIndex) {
            return ParsedBlockStateFilter.invalid();
        }

        String blockName = coreName.substring(0, stateStartIndex).trim();
        String stateExpression = coreName.substring(stateStartIndex + 1, stateEndIndex).trim();
        if (blockName.isEmpty()) {
            return ParsedBlockStateFilter.invalid();
        }
        if (stateExpression.isEmpty()) {
            return new ParsedBlockStateFilter(blockName, Map.of(), true);
        }

        Map<String, String> propertyFilters = new LinkedHashMap<>();
        for (String propertyExpression : splitTopLevel(stateExpression, SPLIT_SEPARATOR.charAt(0))) {
            String trimmedPropertyExpression = propertyExpression.trim();
            int equalsIndex = trimmedPropertyExpression.indexOf(PROPERTY_EQUALS);
            if (equalsIndex <= 0 || equalsIndex == trimmedPropertyExpression.length() - 1) {
                return ParsedBlockStateFilter.invalid();
            }

            String propertyName = trimmedPropertyExpression.substring(0, equalsIndex).trim();
            String propertyValue = trimmedPropertyExpression.substring(equalsIndex + 1).trim();
            if (propertyName.isEmpty() || propertyValue.isEmpty()) {
                return ParsedBlockStateFilter.invalid();
            }

            propertyFilters.put(propertyName, propertyValue);
        }

        return new ParsedBlockStateFilter(blockName, propertyFilters, true);
    }

    private static boolean matchBlockStateFilter(BlockState blockState, ParsedBlockStateFilter stateFilter, String[] matchRules) {
        if (!stateFilter.valid()) {
            return false;
        }
        if (!matchPlainName(stateFilter.blockName(), blockState, matchRules)) {
            return false;
        }

        for (Map.Entry<String, String> entry : stateFilter.propertyFilters().entrySet()) {
            Property<?> property = findPropertyByName(blockState, entry.getKey());
            if (property == null || !matchPropertyValue(blockState, property, entry.getValue())) {
                return false;
            }
        }

        return true;
    }

    private static boolean matchPlainName(String coreName, Object targetObj, String[] matchRules) {
        String targetDisplayName = getTargetDisplayName(targetObj);
        String targetRegistryName = getTargetRegistryName(targetObj);
        if (targetDisplayName == null || targetRegistryName == null) {
            return false;
        }

<<<<<<< HEAD
        if (matchString(targetDisplayName, coreName, matchRules)
                || matchString(targetRegistryName, coreName, matchRules)) {
            return true;
        }
        return PinYinSearchUtils.getPinYin(targetDisplayName)
                .stream()
                .anyMatch(pinyin -> matchString(pinyin, coreName, matchRules));
=======
        boolean displayNameMatch = matchString(targetDisplayName, coreName, matchRules);
        boolean pinyinMatch = PinYinSearchUtils.getPinYin(targetDisplayName)
                .stream()
                .anyMatch(pinyin -> matchString(pinyin, coreName, matchRules));
        boolean registryNameMatch = matchString(targetRegistryName, coreName, matchRules);

        return displayNameMatch || pinyinMatch || registryNameMatch;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    private static Property<?> findPropertyByName(BlockState blockState, String propertyName) {
        for (Property<?> property : blockState.getProperties()) {
            if (property.getName().equalsIgnoreCase(propertyName)) {
                return property;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean matchPropertyValue(BlockState blockState, Property<?> property, String expectedValue) {
        return getPropertyValueName(blockState, (Property) property).equalsIgnoreCase(expectedValue);
    }

    private static <T extends Comparable<T>> String getPropertyValueName(BlockState blockState, Property<T> property) {
        return property.getName(blockState.getValue(property));
    }

    /**
     * 获取目标对象（BlockState/ItemStack）的注册表名称
     */
    private static String getTargetRegistryName(Object targetObj) {
        if (targetObj instanceof BlockState blockState) {
            return BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
        } else if (targetObj instanceof ItemStack itemStack) {
            return BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
        }
        return null;
    }

    /**
     * 获取目标对象（BlockState/ItemStack）的显示名称（中文名称）
     */
    private static String getTargetDisplayName(Object targetObj) {
        if (targetObj instanceof BlockState blockState) {
            return blockState.getBlock().getName().getString();
        } else if (targetObj instanceof ItemStack itemStack) {
            return itemStack.getHoverName().getString();
        }
        return null;
    }

    /**
     * 匹配方块的标签（类型安全，无泛型转换）
     */
    private static boolean matchBlockTag(BlockState blockState, String tagName, String[] matchRules) {
        if (tagName.isEmpty()) {
            return false;
        }

        // 直接处理Block类型的TagKey流，无类型转换
        Stream<TagKey<Block>> blockTagStream = blockState.tags();
        return blockTagStream
                .map(tag -> tag.location().toString())
                .anyMatch(tagFullName -> matchString(tagFullName, tagName, matchRules));
    }

    /**
     * 匹配物品的标签（类型安全，无泛型转换）
     */
    private static boolean matchItemTag(ItemStack itemStack, String tagName, String[] matchRules) {
        if (tagName.isEmpty()) {
            return false;
        }
        // 直接处理Item类型的TagKey流，无类型转换
        Stream<TagKey<Item>> itemTagStream = itemStack.tags();
        return itemTagStream
                .map(tag -> tag.location().toString())
                .anyMatch(tagFullName -> matchString(tagFullName, tagName, matchRules));
    }

    private record ParsedFilter(String coreName, String[] matchRules) {
    }

    private record ParsedBlockStateFilter(String blockName, Map<String, String> propertyFilters, boolean valid) {
        private static ParsedBlockStateFilter invalid() {
            return new ParsedBlockStateFilter("", Map.of(), false);
        }
    }
}
