package exchange.core2.core.common;

import java.util.Arrays;

public class TenPowers {
    private TenPowers() {
    }

    // long ≈ 9.2 * 10^18，预先准备好 0…18 次幂
    private static final long[] POW10 = {
            1L,               // 10^0
            10L,              // 10^1
            100L,             // 10^2
            1_000L,           // 10^3
            10_000L,          // 10^4
            100_000L,         // 10^5
            1_000_000L,       // 10^6
            10_000_000L,      // 10^7
            100_000_000L,     // 10^8
            1_000_000_000L,   // 10^9
            10_000_000_000L,  // 10^10
            100_000_000_000L, // 10^11
            1_000_000_000_000L,      // 10^12
            10_000_000_000_000L,     // 10^13
            100_000_000_000_000L,    // 10^14
            1_000_000_000_000_000L,  // 10^15
            10_000_000_000_000_000L, // 10^16
            100_000_000_000_000_000L,// 10^17
            1_000_000_000_000_000_000L // 10^18
    };

    /**
     * 返回 10^n，0 <= n <= 18
     */
    public static long pow10(int n) {
        if (n < 0 || n >= POW10.length) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        return POW10[n];
    }

    /**
     * 返回 log10(x)，x 必须是 10 的幂
     */
    public static int log10(long x) {
        int idx = Arrays.binarySearch(POW10, x);
        if (idx < 0) {
            throw new IllegalArgumentException("x not power of 10: " + x);
        }
        return idx;
    }
}