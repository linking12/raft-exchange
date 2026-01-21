package exchange.core2.tests.unit;

import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.processors.liquidation.LiquidationService;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LiquidationService 单元测试
 * 测试 IF 资金池管理和 ADL 盈利仓位缓存
 */
class LiquidationServiceTest {

    private LiquidationService liquidationService;

    @BeforeEach
    void setUp() {
        liquidationService = new LiquidationService();
    }

    // ========== IF 资金池测试 ==========

    @Test
    void testCreditLiquidationFee() {
        // 测试强平手续费注入 IF
        int symbol = 10001;
        long fee = 1000L;

        liquidationService.creditLiquidationFee(symbol, fee);
        liquidationService.creditLiquidationFee(symbol, 500L);

        // 验证累加
        long reserved = liquidationService.reserveIFNotional(symbol, 100, 15);
        assertEquals(1500L, reserved); // 可以预留全部
    }

    @Test
    void testReserveIFNotional_SufficientFunds() {
        // 测试预留 IF 资金 - 充足场景
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 10000L);

        long reserved = liquidationService.reserveIFNotional(symbol, 5, 100);

        assertEquals(500L, reserved); // 5 * 100 = 500
    }

    @Test
    void testReserveIFNotional_InsufficientFunds() {
        // 测试预留 IF 资金 - 不足场景
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 300L);

        long reserved = liquidationService.reserveIFNotional(symbol, 5, 100);

        assertEquals(300L, reserved); // 只能预留 300
    }

    @Test
    void testReserveIFNotional_MultipleReservations() {
        // 测试多次预留
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 1000L);

        long reserved1 = liquidationService.reserveIFNotional(symbol, 3, 100);
        long reserved2 = liquidationService.reserveIFNotional(symbol, 5, 100);

        assertEquals(300L, reserved1);
        assertEquals(500L, reserved2);
        // 总预留 = 800，还剩 200 可用
        long reserved3 = liquidationService.reserveIFNotional(symbol, 10, 100);
        assertEquals(200L, reserved3);
    }

    @Test
    void testReserveIFNotional_EmptyPool() {
        // 测试空资金池
        int symbol = 10001;
        long reserved = liquidationService.reserveIFNotional(symbol, 5, 100);
        assertEquals(0L, reserved);
    }

    @Test
    void testReleaseReservedIFNotional() {
        // 测试释放预留资金
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 1000L);

        long reserved1 = liquidationService.reserveIFNotional(symbol, 5, 100);
        assertEquals(500L, reserved1);

        // 释放
        liquidationService.releaseReservedIFNotional(symbol, 500L);

        // 再次预留应该成功
        long reserved2 = liquidationService.reserveIFNotional(symbol, 5, 100);
        assertEquals(500L, reserved2);
    }

    @Test
    void testAcceptIFPosition_LongPosition() {
        // 测试 IF 接管多头仓位
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 10000L);
        liquidationService.reserveIFNotional(symbol, 10, 100);

        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, 10, 100);

        // 验证资金消耗（需要反射访问私有字段，或通过后续预留验证）
        long reserved = liquidationService.reserveIFNotional(symbol, 9000, 1);
        assertEquals(9000L, reserved); // 10000 - 1000 = 9000
    }

    @Test
    void testAcceptIFPosition_ShortPosition() {
        // 测试 IF 接管空头仓位
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 10000L);

        liquidationService.acceptIFPosition(symbol, PositionDirection.SHORT, 5, 200);

        long reserved = liquidationService.reserveIFNotional(symbol, 9000, 1);
        assertEquals(9000L, reserved); // 10000 - 1000 = 9000
    }

    @Test
    void testAcceptIFPosition_MultipleSymbols() {
        // 测试多个 symbol 的 IF 管理
        int symbol1 = 10001;
        int symbol2 = 10002;

        liquidationService.creditLiquidationFee(symbol1, 5000L);
        liquidationService.creditLiquidationFee(symbol2, 3000L);

        liquidationService.acceptIFPosition(symbol1, PositionDirection.LONG, 10, 100);
        liquidationService.acceptIFPosition(symbol2, PositionDirection.SHORT, 5, 200);

        // 验证各 symbol 独立管理
        long reserved1 = liquidationService.reserveIFNotional(symbol1, 4000, 1);
        long reserved2 = liquidationService.reserveIFNotional(symbol2, 2000, 1);

        assertEquals(4000L, reserved1); // 5000 - 1000
        assertEquals(2000L, reserved2); // 3000 - 1000
    }

    // ========== ADL 盈利仓位缓存测试 ==========

    @Test
    void testSetProfitablePositionsBySymbol() {
        // 测试设置盈利仓位快照
        int symbol = 10001;
        IntObjectHashMap<MutableList<SymbolPositionRecord>> snapshot = new IntObjectHashMap<>();
        MutableList<SymbolPositionRecord> positions = FastList.newList();

        SymbolPositionRecord pos1 = new SymbolPositionRecord();
        pos1.uid = 100;
        pos1.symbol = symbol;
        pos1.openVolume = 10;
        positions.add(pos1);

        snapshot.put(symbol, positions);
        liquidationService.setProfitablePositionsBySymbol(snapshot);

        MutableList<SymbolPositionRecord> retrieved = liquidationService.getProfitablePositionsBySymbol(symbol);
        assertEquals(1, retrieved.size());
        assertEquals(100, retrieved.get(0).uid);
    }

    @Test
    void testGetProfitablePositionsBySymbol_EmptySymbol() {
        // 测试获取空 symbol 的盈利仓位
        int symbol = 10001;
        MutableList<SymbolPositionRecord> positions = liquidationService.getProfitablePositionsBySymbol(symbol);
        assertTrue(positions.isEmpty());
    }

    @Test
    void testGetProfitablePositionsBySymbol_AtomicUpdate() {
        // 测试原子更新
        int symbol = 10001;

        // 初始快照
        IntObjectHashMap<MutableList<SymbolPositionRecord>> snapshot1 = new IntObjectHashMap<>();
        snapshot1.put(symbol, FastList.newListWith(createPosition(100, symbol, 10)));
        liquidationService.setProfitablePositionsBySymbol(snapshot1);

        // 验证
        assertEquals(1, liquidationService.getProfitablePositionsBySymbol(symbol).size());

        // 更新快照
        IntObjectHashMap<MutableList<SymbolPositionRecord>> snapshot2 = new IntObjectHashMap<>();
        snapshot2.put(symbol, FastList.newListWith(
            createPosition(200, symbol, 20),
            createPosition(300, symbol, 30)
        ));
        liquidationService.setProfitablePositionsBySymbol(snapshot2);

        // 验证原子替换
        MutableList<SymbolPositionRecord> positions = liquidationService.getProfitablePositionsBySymbol(symbol);
        assertEquals(2, positions.size());
        assertEquals(200, positions.get(0).uid);
        assertEquals(300, positions.get(1).uid);
    }

    @Test
    void testReset() {
        // 测试重置
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 10000L);
        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, 10, 100);

        IntObjectHashMap<MutableList<SymbolPositionRecord>> snapshot = new IntObjectHashMap<>();
        snapshot.put(symbol, FastList.newListWith(createPosition(100, symbol, 10)));
        liquidationService.setProfitablePositionsBySymbol(snapshot);

        liquidationService.reset();

        // 验证清空
        long reserved = liquidationService.reserveIFNotional(symbol, 100, 1);
        assertEquals(0L, reserved);

        MutableList<SymbolPositionRecord> positions = liquidationService.getProfitablePositionsBySymbol(symbol);
        assertTrue(positions.isEmpty());
    }

    // ========== 并发安全测试 ==========

    @Test
    void testConcurrentReserveIFNotional() throws InterruptedException {
        // 测试并发预留
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 100000L);

        int threadCount = 10;
        int reservePerThread = 50; // 每个线程预留 50 * 100 = 5000
        long[] results = new long[threadCount];

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = liquidationService.reserveIFNotional(symbol, reservePerThread, 100);
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // 验证总预留不超过可用资金
        long totalReserved = 0;
        for (long result : results) {
            totalReserved += result;
        }
        assertTrue(totalReserved <= 100000L);
    }

    // ========== 辅助方法 ==========

    private SymbolPositionRecord createPosition(long uid, int symbol, long volume) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = uid;
        pos.symbol = symbol;
        pos.openVolume = volume;
        return pos;
    }
}
