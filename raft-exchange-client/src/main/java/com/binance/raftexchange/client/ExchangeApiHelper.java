package com.binance.raftexchange.client;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ExchangeApiHelper {
    private ExchangeApiHelper() {
    }

    /**
     * 将 double 类型的数值转换为 long 类型，使用指定的 valueScaleK 放大。
     * 注意：如果整数部分过大，可能会导致溢出。
     *
     * @param value       要转换的 double 值
     * @param valueScaleK 放大倍数
     * @return 转换后的 long 值
     * @throws ArithmeticException 如果整数部分过大，可能会导致溢出
     */
    public static long doubleToLong(double value, long valueScaleK) {
        long integer = (long) value;
        long fraction = (long) ((value - integer) * valueScaleK);
        if (integer > Long.MAX_VALUE / valueScaleK) {
            throw new ArithmeticException("Integer part too large, overflow risk");
        }
        return integer * valueScaleK + fraction;
    }

    /**
     * 将 long 类型的数值转换为 double 类型，使用指定的 valueScaleK 缩小。
     *
     * @param value       要转换的 long 值
     * @param valueScaleK 缩小倍数
     * @return 转换后的 double 值
     */
    public static double longToDouble(long value, long valueScaleK) {
        return value / (double) valueScaleK;
    }

    /**
     * 从给定的 map 中获取小于或等于指定 key 的最大值。
     * 如果没有找到小于或等于 key 的键，则返回 map 中的第一个键对应的值。
     *
     * @param map 要查询的 map，必须是非空的
     * @param key 要查找的键
     * @return 小于或等于 key 的最大值对应的值
     * @throws IllegalArgumentException 如果 map 为空
     */
    public static <K extends Comparable<? super K>, V> V getFloorValue(Map<K, V> map, K key) {
        if (map.isEmpty()) {
            throw new IllegalArgumentException("map must not be empty");
        }
        NavigableMap<K, V> navigableMap = (map instanceof NavigableMap) ? (NavigableMap<K, V>) map : new TreeMap<>(map);
        K floorKey = navigableMap.floorKey(key);
        if (floorKey == null) {
            floorKey = navigableMap.firstKey();
        }
        return navigableMap.get(floorKey);
    }

    /**
     * 快速构造slot -> value配置表。
     *
     * @param entries 格式为 slot1, value1, slot2, value2, ……
     * @return 构建好的 NavigableMap
     * @throws IllegalArgumentException 如果 entries 长度不是偶数或 slot 不严格递增
     */
    public static NavigableMap<Long, Long> buildSlotValueMap(long... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("entries length must be even");
        }
        NavigableMap<Long, Long> map = new TreeMap<>();
        long lastSlot = Long.MIN_VALUE;
        for (int i = 0; i < entries.length; i += 2) {
            long slot = entries[i];
            long value = entries[i + 1];
            if (slot <= lastSlot) {
                throw new IllegalArgumentException("slot must be strictly increasing: " + slot + " <= " + lastSlot);
            }
            map.put(slot, value);
            lastSlot = slot;
        }
        return map;
    }

    public static void main(String[] args) {
        double value = 123.4567;
        long scaleK = 1000L;
        long scaledValue = doubleToLong(value, scaleK);
        System.out.println("Scaled value: " + scaledValue); // Should print 123456

        NavigableMap<Long, Long> slotValueMap = buildSlotValueMap(100L, 10L, 200L, 20L, 300L, 30L);
        System.out.println("Slot-Value Map: " + getFloorValue(slotValueMap, 250L)); // Should print 20L
    }
}
