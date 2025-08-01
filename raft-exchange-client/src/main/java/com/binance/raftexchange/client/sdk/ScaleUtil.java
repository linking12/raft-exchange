package com.binance.raftexchange.client.sdk;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ScaleUtil {

    public static long doubleToLong(double value, long valueScaleK) {
        long integer = (long) value;
        long fraction = (long) ((value - integer) * valueScaleK);
        if (integer > Long.MAX_VALUE / valueScaleK) {
            throw new ArithmeticException("Integer part too large, overflow risk");
        }
        return integer * valueScaleK + fraction;
    }

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

    public static void main(String[] args) {
        double value = 123.4567;
        long scaleK = 1000L; // Example scale factor
        long scaledValue = doubleToLong(value, scaleK);
        System.out.println("Scaled value: " + scaledValue); // Should print 123456
    }
}
