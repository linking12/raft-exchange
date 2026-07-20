package exchange.core2.tests.unit;

import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.processors.liquidation.LiquidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void testDepositToInsuranceFund_NewSymbol() {
        // 测试 admin 充值在空池上的首次入账
        int symbol = 10001;
        liquidationService.depositToInsuranceFund(symbol, 2000L);

        // 入账后该 symbol 的可用 notional 应为 2000
        long reserved = liquidationService.reserveIFNotional(symbol, 200, 10);
        assertEquals(2000L, reserved);
    }

    @Test
    void testDepositToInsuranceFund_Cumulative() {
        // 测试多次充值的累加（与 creditLiquidationFee 共享同一个 IFNotional 槽）
        int symbol = 10001;
        liquidationService.depositToInsuranceFund(symbol, 1000L);
        liquidationService.creditLiquidationFee(symbol, 500L);      // 走另一条入账路径
        liquidationService.depositToInsuranceFund(symbol, 200L);

        // 累计 1700
        long reserved = liquidationService.reserveIFNotional(symbol, 200, 10);
        assertEquals(1700L, reserved);
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
    void testWithdrawFromInsuranceFund_NotionalNotExist() {
        // 不存在的 symbol：withdraw 直接失败
        int symbol = 10001;
        assertFalse(liquidationService.withdrawFromInsuranceFund(symbol, 100L));
    }

    @Test
    void testWithdrawFromInsuranceFund_InsufficientAvailable() {
        // available 不足以覆盖：失败且不改动状态
        int symbol = 10001;
        liquidationService.depositToInsuranceFund(symbol, 500L);

        // 抽 501（超 available）→ false
        assertFalse(liquidationService.withdrawFromInsuranceFund(symbol, 501L));

        // available 未变，仍可全额预留
        long reserved = liquidationService.reserveIFNotional(symbol, 1, 500);
        assertEquals(500L, reserved);
    }

    @Test
    void testWithdrawFromInsuranceFund_Success() {
        // 正常抽资：available -= amount
        int symbol = 10001;
        liquidationService.depositToInsuranceFund(symbol, 1000L);

        assertTrue(liquidationService.withdrawFromInsuranceFund(symbol, 300L));

        // 抽走后剩 700，reserve 应能拿满 700
        long reserved = liquidationService.reserveIFNotional(symbol, 1, 700);
        assertEquals(700L, reserved);
    }

    @Test
    void testWithdrawFromInsuranceFund_DoesNotTouchReserved() {
        // reserved > 0 时不能被 withdraw 拿走：withdraw 只扣 available，reserved 是"正在保护某笔强平的预冻结部分"
        int symbol = 10001;
        liquidationService.depositToInsuranceFund(symbol, 1000L);
        // 预留 400 → available=1000, reserved=400
        long reserved = liquidationService.reserveIFNotional(symbol, 1, 400);
        assertEquals(400L, reserved);

        // 现在 IFNotional.available=1000, IFNotional.reserved=400
        // withdrawFromInsuranceFund 只看 available（不考虑 reserved），可以抽满 1000
        assertTrue(liquidationService.withdrawFromInsuranceFund(symbol, 1000L));

        // reserved 未动，reserve 后续释放仍生效
        liquidationService.releaseReservedIFNotional(symbol, 400L);
        // available=0, reserved=0 → 无法再 reserve
        long secondReserve = liquidationService.reserveIFNotional(symbol, 1, 100);
        assertEquals(0L, secondReserve);
    }

    @Test
    void testWithdrawFromInsuranceFund_ExactAvailable() {
        // 抽正好等于 available：成功，池子归零
        int symbol = 10001;
        liquidationService.depositToInsuranceFund(symbol, 500L);
        assertTrue(liquidationService.withdrawFromInsuranceFund(symbol, 500L));
        // 再抽任何金额都失败
        assertFalse(liquidationService.withdrawFromInsuranceFund(symbol, 1L));
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
    void testReserveReleaseReserveCycle() {
        // 完整 R1 → R2 → R1 流程：reserve 占用、release 归还、reserve 再次成功
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 1000L);

        // 第一次 reserve 占满
        long reserved1 = liquidationService.reserveIFNotional(symbol, 10, 100);
        assertEquals(1000L, reserved1);

        // 此时再 reserve 应为 0
        long reservedMid = liquidationService.reserveIFNotional(symbol, 1, 100);
        assertEquals(0L, reservedMid);

        // 部分 release
        liquidationService.releaseReservedIFNotional(symbol, 400L);

        // 再 reserve 应只能拿到 400
        long reserved2 = liquidationService.reserveIFNotional(symbol, 10, 100);
        assertEquals(400L, reserved2);
    }

    // ========== generateLiquidationOrderId（FORCE）测试 ==========

    @Test
    void testGenerateLiquidationOrderId_encodesSymbolUidSide() {
        SymbolPositionRecord pos = newPos(0xABCDEF, 12345L, PositionDirection.LONG);
        long id = LiquidationService.generateLiquidationOrderId(pos);

        assertEquals(0xABCDEFL, id >>> 32, "symbol in bits 32-63");
        long expectedUidHash = (12345L * 31 + 17) & 0xFFFFF;
        assertEquals(expectedUidHash, (id >>> 12) & 0xFFFFF, "uidHash in bits 12-31");
        assertEquals(0L, (id >>> 11) & 1L, "LONG → side bit = 0");
    }

    @Test
    void testGenerateLiquidationOrderId_shortSetsSideBit() {
        SymbolPositionRecord pos = newPos(1001, 100L, PositionDirection.SHORT);
        long id = LiquidationService.generateLiquidationOrderId(pos);
        assertEquals(1L, (id >>> 11) & 1L, "SHORT → side bit = 1");
    }

    @Test
    void testGenerateLiquidationOrderId_longVsShortDifferent() {
        // HEDGE 模式同 symbol 同 uid 双向破产，orderId 必须不同
        SymbolPositionRecord longPos = newPos(1001, 100L, PositionDirection.LONG);
        SymbolPositionRecord shortPos = newPos(1001, 100L, PositionDirection.SHORT);
        long idLong = LiquidationService.generateLiquidationOrderId(longPos);
        long idShort = LiquidationService.generateLiquidationOrderId(shortPos);
        assertNotEquals(idLong, idShort, "LONG/SHORT 必须有不同 orderId（差 side bit）");
    }

    @Test
    void testIsLiquidationOrderId_recognizesForceOrderId() {
        SymbolPositionRecord pos = newPos(1001, 200L, PositionDirection.LONG);
        long id = LiquidationService.generateLiquidationOrderId(pos);
        assertTrue(LiquidationService.isLiquidationOrderId(id, 1001, 200L));
        // 不同 uid 不应识别为对应 user 的强平单
        assertFalse(LiquidationService.isLiquidationOrderId(id, 1001, 999L));
        // 不同 symbol 不应识别
        assertFalse(LiquidationService.isLiquidationOrderId(id, 2002, 200L));
    }

    @Test
    void testIsLiquidationOrderId_rejectsDerivedIFAndADL() {
        // IF/ADL orderId 不是 FORCE orderId，反查必须返 false（高字节是 tag 不是 symbol）
        SymbolPositionRecord pos = newPos(1001, 200L, PositionDirection.LONG);
        long forceId = LiquidationService.generateLiquidationOrderId(pos);
        long ifId = LiquidationService.generateIFOrderId(forceId);
        long adlId = LiquidationService.generateADLOrderId(forceId);
        assertFalse(LiquidationService.isLiquidationOrderId(ifId, 1001, 200L), "IF orderId 不应被识别为 FORCE");
        assertFalse(LiquidationService.isLiquidationOrderId(adlId, 1001, 200L), "ADL orderId 不应被识别为 FORCE");
    }

    // ========== generateIFOrderId 测试 ==========

    @Test
    void testGenerateIFOrderId_TagBits() {
        // 验证高字节为 'I'(0x49)，低 56 位为 liquidationOrderId
        long liquidationOrderId = 0x0123456789ABCDL;
        long ifOrderId = LiquidationService.generateIFOrderId(liquidationOrderId);

        assertEquals(0x49L, (ifOrderId >>> 56) & 0xFFL);
        assertEquals(liquidationOrderId, ifOrderId & 0x00FFFFFFFFFFFFFFL);
    }

    @Test
    void testGenerateIFOrderId_TruncatesHighBits() {
        // 高于 56 位的部分会被截断（只保留低 56 位）
        long liquidationOrderId = 0xFFFFFFFFFFFFFFFFL;
        long ifOrderId = LiquidationService.generateIFOrderId(liquidationOrderId);

        assertEquals(0x49L, (ifOrderId >>> 56) & 0xFFL);
        assertEquals(0x00FFFFFFFFFFFFFFL, ifOrderId & 0x00FFFFFFFFFFFFFFL);
    }

    // ========== generateADLOrderId（从 FORCE 派生）测试 ==========

    @Test
    void testGenerateADLOrderId_tagAndLowBits() {
        // 验证高字节为 'A'(0x41)，低 56 位为 liquidationOrderId
        long liquidationOrderId = 0x0123456789ABCDL;
        long adlOrderId = LiquidationService.generateADLOrderId(liquidationOrderId);

        assertEquals(0x41L, (adlOrderId >>> 56) & 0xFFL, "tag = 'A' 0x41");
        assertEquals(liquidationOrderId, adlOrderId & 0x00FFFFFFFFFFFFFFL, "低 56 位 == FORCE 低 56 位");
    }

    @Test
    void testGenerateADLOrderId_symmetricWithIF() {
        // ADL 和 IF 仅 tag 不同（'A' vs 'I'），低 56 位完全相等
        long liquidationOrderId = 0x12345678L;
        long ifId = LiquidationService.generateIFOrderId(liquidationOrderId);
        long adlId = LiquidationService.generateADLOrderId(liquidationOrderId);
        assertEquals(ifId & 0x00FFFFFFFFFFFFFFL, adlId & 0x00FFFFFFFFFFFFFFL,
                "IF/ADL 派生自同一 FORCE 时低 56 位应相等");
        assertNotEquals(ifId, adlId, "tag 不同 → 整体不等");
    }

    private static SymbolPositionRecord newPos(int symbol, long uid, PositionDirection direction) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.symbol = symbol;
        pos.uid = uid;
        pos.direction = direction;
        return pos;
    }

    @Test
    void testAcceptIFPosition_ShortPosition() {
        // 测试 IF 接管空头仓位
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 10000L);

        liquidationService.acceptIFPosition(symbol, PositionDirection.SHORT, 5, 200);

        long reserved = liquidationService.reserveIFNotional(symbol, 9000, 1);
        assertEquals(9000L, reserved); // 10000 - 1000 = 9000

        // 验证 SHORT 持仓存储在 -symbol 槽
        LiquidationService.IFPositionRecord pos = liquidationService.getPositions().get(-symbol);
        assertEquals(symbol, pos.symbol);
        assertEquals(PositionDirection.SHORT, pos.direction);
        assertEquals(5L, pos.openVolume);
        assertEquals(1000L, pos.openPriceSum);
    }

    @Test
    void testAcceptIFPosition_LongPosition() {
        // 测试 IF 接管多头仓位，并验证存储 key 为 +symbol
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 10000L);

        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, 4, 250);

        LiquidationService.IFPositionRecord pos = liquidationService.getPositions().get(symbol);
        assertEquals(symbol, pos.symbol);
        assertEquals(PositionDirection.LONG, pos.direction);
        assertEquals(4L, pos.openVolume);
        assertEquals(1000L, pos.openPriceSum);

        // available 应被扣减 4*250 = 1000
        long reserved = liquidationService.reserveIFNotional(symbol, 9000, 1);
        assertEquals(9000L, reserved);
    }

    @Test
    void testAcceptIFPosition_AccumulateSameDirection() {
        // 同一 direction 多次接管：openVolume / openPriceSum 累加
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 100000L);

        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, 3, 100);  // spend 300
        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, 2, 150);  // spend 300

        LiquidationService.IFPositionRecord pos = liquidationService.getPositions().get(symbol);
        assertEquals(5L, pos.openVolume);
        assertEquals(600L, pos.openPriceSum);

        // 可用资金应剩 100000 - 600 = 99400
        long reserved = liquidationService.reserveIFNotional(symbol, 99400, 1);
        assertEquals(99400L, reserved);
    }

    @Test
    void testAcceptIFPosition_LongAndShortSeparated() {
        // 同 symbol 的 LONG 和 SHORT 分别存储在 +symbol / -symbol 槽
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 100000L);

        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, 5, 100);   // +symbol
        liquidationService.acceptIFPosition(symbol, PositionDirection.SHORT, 3, 200);  // -symbol

        LiquidationService.IFPositionRecord longPos = liquidationService.getPositions().get(symbol);
        LiquidationService.IFPositionRecord shortPos = liquidationService.getPositions().get(-symbol);

        assertEquals(PositionDirection.LONG, longPos.direction);
        assertEquals(5L, longPos.openVolume);
        assertEquals(500L, longPos.openPriceSum);

        assertEquals(PositionDirection.SHORT, shortPos.direction);
        assertEquals(3L, shortPos.openVolume);
        assertEquals(600L, shortPos.openPriceSum);
    }

    @Test
    void testAcceptIFPosition_NotionalZeroSum() {
        // 守恒：accept 时 available 减少的 spend 必须等于 openPriceSum 增加的 spend。
        // 这是 TotalCurrencyBalanceReportQuery 算 ifBalances = available + positionValue
        // 在 mark == openPrice 时仍守恒的根本前提（钱在 IF 内部从"现金口袋"挪到"持仓口袋"）。
        int symbol = 10001;
        long initialCredit = 10000L;
        liquidationService.creditLiquidationFee(symbol, initialCredit);

        LiquidationService.IFNotional before = liquidationService.getNotionals().get(symbol);
        long availBefore = before.available;

        long size = 7;
        long price = 250;
        long spend = size * price;
        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, size, price);

        LiquidationService.IFNotional after = liquidationService.getNotionals().get(symbol);
        LiquidationService.IFPositionRecord pos = liquidationService.getPositions().get(symbol);

        long availDelta = availBefore - after.available;
        assertEquals(spend, availDelta);
        assertEquals(spend, pos.openPriceSum);
        // 0-sum：available 掉了多少，持仓成本就涨了多少
        assertEquals(availDelta, pos.openPriceSum);
        // 总额守恒：available + openPriceSum == 接管前 available
        assertEquals(initialCredit, after.available + pos.openPriceSum);
    }

    @Test
    void testReserveSeesAcceptedAvailable() {
        // reserve 必须看"当前 available - reserved"，而非历史 credit 总额。
        // 否则 IF 接管仓位后还会按旧的 available 放行 reserve，导致 ifBalances 超卖。
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 10000L);

        // 先 reserve 2000（reserved=2000）
        long r1 = liquidationService.reserveIFNotional(symbol, 20, 100);
        assertEquals(2000L, r1);

        // accept 花掉 3000（available 10000 -> 7000）
        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, 30, 100);

        // 此时真实可用 = available(7000) - reserved(2000) = 5000
        long r2 = liquidationService.reserveIFNotional(symbol, 99999, 1);
        assertEquals(5000L, r2);

        // 全部 release 后，可用应回到 available 全额（accept 已经从 available 永久扣掉，回不来）
        liquidationService.releaseReservedIFNotional(symbol, 2000L + 5000L);
        long r3 = liquidationService.reserveIFNotional(symbol, 99999, 1);
        assertEquals(7000L, r3);
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

    @Test
    void testReset() {
        // 测试重置——只测保留的 IF 余额 / IF 仓位字段
        int symbol = 10001;
        liquidationService.creditLiquidationFee(symbol, 10000L);
        liquidationService.acceptIFPosition(symbol, PositionDirection.LONG, 10, 100);

        liquidationService.reset();

        // 验证 IF 余额清空
        long reserved = liquidationService.reserveIFNotional(symbol, 100, 1);
        assertEquals(0L, reserved);
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

}
