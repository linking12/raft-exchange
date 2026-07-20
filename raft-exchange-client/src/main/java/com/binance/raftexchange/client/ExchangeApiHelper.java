package com.binance.raftexchange.client;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ExchangeApiHelper {
    private ExchangeApiHelper() {}

    public static long doubleToLong(double value, long valueScaleK) {
        if (!Double.isFinite(value)) {
            throw new ArithmeticException("Value must be finite: " + value);
        }
        double scaled = value * valueScaleK;
        // (double) Long.MAX_VALUE 向上 round；用 next-representable double 卡边界，否则 Math.round 会静默 clamp
        if (scaled >= 9.223372036854776E18 || scaled < Long.MIN_VALUE) {
            throw new ArithmeticException("Value too large, overflow risk: " + value);
        }
        return Math.round(scaled);
    }

    public static double longToDouble(long value, long valueScaleK) {
        return value / (double)valueScaleK;
    }

    public static BigDecimal longToBigDecimal(long value, long valueScaleK) {
        if (valueScaleK == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(valueScaleK), MathContext.DECIMAL128);
    }

    public static long multiplyScaleExact(long a, long b) {
        if (a == 0 || b == 0) {
            return 0L;
        }
        return Math.multiplyExact(a, b);
    }

    public static long pow10(int digit) {
        if (digit < 0 || digit > 18) {
            throw new IllegalArgumentException("digit out of range [0, 18]: " + digit);
        }
        long result = 1L;
        for (int i = 0; i < digit; i++)
            result *= 10;
        return result;
    }

    public static <K extends Comparable<? super K>, V> V getFloorValue(Map<K, V> map, K key) {
        if (map.isEmpty()) {
            throw new IllegalArgumentException("map must not be empty");
        }
        NavigableMap<K, V> navigableMap = (map instanceof NavigableMap) ? (NavigableMap<K, V>)map : new TreeMap<>(map);
        K floorKey = navigableMap.floorKey(key);
        if (floorKey == null) {
            floorKey = navigableMap.firstKey();
        }
        return navigableMap.get(floorKey);
    }

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

}
