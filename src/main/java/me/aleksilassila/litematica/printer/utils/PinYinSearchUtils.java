package me.aleksilassila.litematica.printer.utils;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 拼音搜索工具类：将中文字符串转换为全拼/简拼组合，支持拼音包含性判断
 * 无状态设计，线程安全
 */
public class PinYinSearchUtils {
    // 复用拼音格式配置（常量），避免重复创建
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT;
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final int MAX_COMBINATIONS = 256;
    private static final Map<String, List<String>> CACHE = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return this.size() > MAX_CACHE_ENTRIES;
        }
    };

    static {
        // 静态初始化：配置拼音输出格式（小写、无声调、v代替ü）
        PINYIN_FORMAT = new HanyuPinyinOutputFormat();
        PINYIN_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PINYIN_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        PINYIN_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    /**
     * 将字符串转换为所有可能的拼音组合（全拼 + 简拼）
     * @param str 输入字符串（支持中文、英文、数字等混合）
     * @return 拼音组合列表（非null）
     */
    public static synchronized ArrayList<String> getPinYin(@Nullable String str) {
        // 空值处理：输入null/空字符串返回空列表
        if (str == null || str.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> cached = CACHE.get(str);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        char[] chars = str.toCharArray();
        // 改用方法内局部变量存储每个字符的拼音数组，移除静态变量
        List<String[]> charPinyinList = new ArrayList<>();

        try {
            for (char c : chars) {
                if (c < 128) {
                    // 非中文字符：直接保留原字符
                    charPinyinList.add(new String[]{String.valueOf(c)});
                } else {
                    // 中文字符：获取所有读音，处理null情况
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FORMAT);
                    if (pinyinArray == null || pinyinArray.length == 0) {
                        // 生僻字/无法识别的汉字：保留原字符
                        charPinyinList.add(new String[]{String.valueOf(c)});
                    } else {
                        charPinyinList.add(new LinkedHashSet<>(List.of(pinyinArray)).toArray(new String[0]));
                    }
                }
            }
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            // 友好的异常提示，携带上下文信息
            throw new RuntimeException("拼音格式配置错误，无法转换字符串：" + str, e);
        }

        // 生成全拼+简拼组合
        ArrayList<String> result = generatePinyinCombinations(charPinyinList);
        CACHE.put(str, List.copyOf(result));
        return result;
    }

    /**
     * 生成拼音组合（全拼 + 简拼）
     * @param charPinyinList 每个字符的拼音数组列表
     * @return 全拼+简拼组合列表
     */
    @NotNull
    private static ArrayList<String> generatePinyinCombinations(List<String[]> charPinyinList) {
        List<String> fullPinyinList = List.of("");
        List<String> shortPinyinList = List.of("");
        for (String[] currentPinyinArray : charPinyinList) {
            ArrayList<String> nextFull = new ArrayList<>();
            ArrayList<String> nextShort = new ArrayList<>();
            for (String existing : fullPinyinList) {
                for (String pinyin : currentPinyinArray) {
                    if (nextFull.size() >= MAX_COMBINATIONS) break;
                    nextFull.add(existing + pinyin);
                }
                if (nextFull.size() >= MAX_COMBINATIONS) break;
            }
            for (String existing : shortPinyinList) {
                for (String pinyin : currentPinyinArray) {
                    if (nextShort.size() >= MAX_COMBINATIONS) break;
                    nextShort.add(existing + pinyin.charAt(0));
                }
                if (nextShort.size() >= MAX_COMBINATIONS) break;
            }
            fullPinyinList = nextFull;
            shortPinyinList = nextShort;
        }
        LinkedHashSet<String> combined = new LinkedHashSet<>(fullPinyinList);
        combined.addAll(shortPinyinList);
        return new ArrayList<>(combined);
    }
}
