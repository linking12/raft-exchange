package exchange.core2.core.common;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class CommonByShard {
    public final IntLongHashMap amounts = new IntLongHashMap();

    public static CommonByShard[] createArray(int size) {
        CommonByShard[] arr = new CommonByShard[size];
        for (int i = 0; i < size; i++)
            arr[i] = new CommonByShard();
        return arr;
    }

    public static void reset(CommonByShard[] commonByShards) {
        for (CommonByShard commonByShard : commonByShards) {
            commonByShard.amounts.clear();
        }
    }

}
