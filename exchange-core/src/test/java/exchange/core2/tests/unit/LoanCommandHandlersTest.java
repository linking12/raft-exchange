package exchange.core2.tests.unit;

import exchange.core2.collections.objpool.ObjectsPool;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.loan.LoanCommandHandlers;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * LoanCommandHandlers 单元测试。
 *
 * <p>覆盖：10 个真实 handler 的 happy path + 关键 reject 分支 + 2 个 force-sell stub。
 * 用 Mockito 打桩 RiskEngine 的 getter 与 calculateLocked / uidForThisHandler，其他 state（LoanService / UserProfile / spec / price cache）
 * 用真实实例，以便 assert 状态转移。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanCommandHandlersTest {

    private static final long UID = 42L;
    private static final int SYMBOL = 100;
    private static final int BTC = 1;
    private static final int USDT = 2;
    private static final int ETH = 3;

    @Mock private RiskEngine engine;
    @Mock private UserProfileService userProfileService;

    private LoanService loanService;
    private UserProfile up;
    private SymbolSpecificationProvider specProvider;
    private CurrencySpecificationProvider currencyProvider;
    private IntObjectHashMap<LastPriceCacheRecord> priceCache;
    private IntLongHashMap fees;
    private IntLongHashMap adjustments;
    private ObjectsPool objectsPool;
    private LoanCommandHandlers handlers;

    @BeforeEach
    void setUp() {
        loanService = new LoanService();
        loanService.setNumeraireCurrency(USDT); // 走 UPDATE_LOAN_NUMERAIRE_CONFIG 通道的等价初始化
        up = new UserProfile(UID, UserStatus.ACTIVE);
        specProvider = new SymbolSpecificationProvider();
        currencyProvider = new CurrencySpecificationProvider();
        priceCache = new IntObjectHashMap<>();
        fees = new IntLongHashMap();
        adjustments = new IntLongHashMap();
        // 真实 ObjectsPool 让 get/put 走完整 pool 语义（不 mock 掉，测 pooling 副作用）
        java.util.HashMap<Integer, Integer> poolCfg = new java.util.HashMap<>();
        poolCfg.put(ObjectsPool.ISOLATED_LOAN_RECORD, 64);
        poolCfg.put(ObjectsPool.CROSS_LOAN_RECORD, 64);
        objectsPool = new ObjectsPool(poolCfg);

        // digit=0 → currencyScaleK=1，跟 spec baseScaleK/quoteScaleK=1 一致 → 尺度换算 identity，测试数字不用调
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build());
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build());

        // BTC/USDT spec：60% initial LTV / 80% liquidation / 70% marginCall / 5% APR / 90d 期限 / 90% collateral weight
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT)
                .baseScaleK(1).quoteScaleK(1)
                .loanInitialLtvBps(6000)
                .loanLiquidationLtvBps(8000)
                .loanMarginCallLtvBps(7000)
                .loanRateBps(500)
                .loanMaxAmount(0)
                .loanMaxTermDays(90)
                .collateralWeightBps(9000)
                .build();
        specProvider.registerSymbol(SYMBOL, spec);

        // markPrice = 50000 USDT/BTC
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50000L;
        priceCache.put(SYMBOL, price);

        // 池子有 100k USDT 可借
        loanService.getLoanPoolAvailable().put(USDT, 100_000L);

        // 用户账户：10 BTC，0 USDT
        up.accounts.put(BTC, 10L);
        // Cross 抵押池预设 10 BTC，让 Cross BORROW / WITHDRAW happy path 的 LTV 计算能过
        // (10 BTC × 50000 mark × 90% weight = 450000 USDT weighted，借 20k 时 LTV ≈ 444 bps << initial 6000)
        up.crossLoanCollateral.put(BTC, 10L);

        when(engine.getUserProfileService()).thenReturn(userProfileService);
        when(userProfileService.getUserProfile(UID)).thenReturn(up);
        when(engine.getSymbolSpecificationProvider()).thenReturn(specProvider);
        when(engine.getCurrencySpecificationProvider()).thenReturn(currencyProvider);
        when(engine.getLastPriceCache()).thenReturn(priceCache);
        when(engine.getLoanService()).thenReturn(loanService);
        when(engine.getFees()).thenReturn(fees);
        when(engine.getAdjustments()).thenReturn(adjustments);
        when(engine.getObjectsPool()).thenReturn(objectsPool);
        when(engine.getShardId()).thenReturn(0);
        when(engine.calculateLocked(any(), anyInt())).thenReturn(0L);
        when(engine.uidForThisHandler(anyLong())).thenReturn(true);

        handlers = new LoanCommandHandlers(engine);
    }

    private OrderCommand build(OrderCommandType type, long externalId, long uid, long loanId, int symbol,
        long size, long price) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = type;
        cmd.orderId = externalId;
        cmd.uid = uid;
        cmd.reserveBidPrice = loanId;
        cmd.symbol = symbol;
        cmd.size = size;
        cmd.price = price;
        cmd.timestamp = 1000L;
        return cmd;
    }

    // ================================================================
    // LOAN_CREATE
    // ================================================================

    @Test
    void loanCreate_happy() {
        // 1 BTC 抵押借 30000 USDT，LTV = 30000/(1*50000) = 60% 刚到线
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L);
        CommandResultCode rc = handlers.handleLoanCreate(cmd);
        assertEquals(CommandResultCode.SUCCESS, rc);
        assertEquals(1, up.isolatedLoans.size());
        assertEquals(30_000L, up.accounts.get(USDT), "principal 打进 accounts");
        assertEquals(70_000L, loanService.getLoanPoolAvailable().get(USDT), "pool 扣掉 30k");
        assertEquals(30_000L, loanService.getLoanPoolBorrowed().get(USDT));
    }

    @Test
    void loanCreate_userSuspended() {
        up.userStatus = UserStatus.SUSPENDED;
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L);
        assertEquals(CommandResultCode.LOAN_USER_SUSPENDED, handlers.handleLoanCreate(cmd));
    }

    @Test
    void loanCreate_loanAlreadyExists() {
        up.isolatedLoans.put(999L, new IsolatedLoanRecord(UID, 999L, BTC, USDT, 500, 0L));
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L);
        assertEquals(CommandResultCode.LOAN_ALREADY_EXISTS, handlers.handleLoanCreate(cmd));
    }

    @Test
    void loanCreate_ltvTooHigh() {
        // 借 40000 → LTV = 80% > 60% initial
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 40_000L);
        assertEquals(CommandResultCode.LOAN_LTV_TOO_HIGH, handlers.handleLoanCreate(cmd));
    }

    @Test
    void loanCreate_collateralInsufficient() {
        // 想抵押 100 BTC 但账户只有 10
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 100L, 30_000L);
        assertEquals(CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT, handlers.handleLoanCreate(cmd));
    }

    @Test
    void loanCreate_poolInsufficient() {
        // 池子只放 5k，借 30k
        loanService.getLoanPoolAvailable().put(USDT, 5_000L);
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L);
        assertEquals(CommandResultCode.LOAN_POOL_INSUFFICIENT, handlers.handleLoanCreate(cmd));
    }

    @Test
    void loanCreate_dedupSameExternalId() {
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L);
        assertEquals(CommandResultCode.SUCCESS, handlers.handleLoanCreate(cmd));
        // 同 externalId 重试
        OrderCommand cmd2 = build(OrderCommandType.LOAN_CREATE, 1L, UID, 1000L, SYMBOL, 1L, 30_000L);
        assertEquals(CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME,
            handlers.handleLoanCreate(cmd2));
    }

    // ================================================================
    // LOAN_REPAY
    // ================================================================

    @Test
    void loanRepay_happy_fullPayoff() {
        // 先 CREATE
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        // 全还本息
        OrderCommand cmd = build(OrderCommandType.LOAN_REPAY, 2L, UID, 999L, 0, 0, 0);  // price=0 = payoff
        CommandResultCode rc = handlers.handleLoanRepay(cmd);
        assertEquals(CommandResultCode.SUCCESS, rc);
        // 部分还款不释放抵押，全还清 principal & interest 但 collateral 仍在 → loan 记录保留（loan.md §5.3）
        assertEquals(1, up.isolatedLoans.size(), "collateral 未清，loan 保留");
        IsolatedLoanRecord loan = up.isolatedLoans.get(999L);
        assertEquals(0L, loan.outstandingPrincipal, "principal 清零");
        assertEquals(0L, loan.accumulatedInterest, "interest 清零");
        assertEquals(1L, loan.collateralAmount, "collateral 保留");
        assertEquals(0L, up.accounts.get(USDT));
        assertEquals(100_000L, loanService.getLoanPoolAvailable().get(USDT), "池子回到初始");
    }

    @Test
    void loanRepay_loanNotFound() {
        OrderCommand cmd = build(OrderCommandType.LOAN_REPAY, 1L, UID, 999L, 0, 0, 0);
        assertEquals(CommandResultCode.LOAN_NOT_FOUND, handlers.handleLoanRepay(cmd));
    }

    @Test
    void loanRepay_accountInsufficient() {
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        // 用户把钱花了
        up.accounts.put(USDT, 0L);
        OrderCommand cmd = build(OrderCommandType.LOAN_REPAY, 2L, UID, 999L, 0, 0, 0);
        assertEquals(CommandResultCode.LOAN_ACCOUNT_INSUFFICIENT, handlers.handleLoanRepay(cmd));
    }

    @Test
    void loanRepay_partial_interestOnly_paysInterestFirstPoolUnchanged() {
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        // 强注入 1000 USDT 利息（模拟已 accrue；lastAccrueTs 同 cmd.ts 让新 accrue delta=0 便于断言）
        IsolatedLoanRecord loan = up.isolatedLoans.get(999L);
        loan.accumulatedInterest = 1000L;
        loan.lastAccrueTs = 1000L;

        long accountsBefore = up.accounts.get(USDT);
        long poolBefore = loanService.getLoanPoolAvailable().get(USDT);
        long interestRevBefore = loanService.getInterestRevenue().get(USDT);
        long borrowedBefore = loanService.getLoanPoolBorrowed().get(USDT);

        // 部分还 500 —— 全走利息优先
        handlers.handleLoanRepay(build(OrderCommandType.LOAN_REPAY, 2L, UID, 999L, 0, 0, 500L));

        assertEquals(500L, loan.accumulatedInterest, "利息减 500 剩 500");
        assertEquals(30_000L, loan.outstandingPrincipal, "principal 不动");
        assertEquals(accountsBefore - 500L, up.accounts.get(USDT), "账户扣 500");
        assertEquals(poolBefore, loanService.getLoanPoolAvailable().get(USDT), "池子未增（没还 principal）");
        assertEquals(borrowedBefore, loanService.getLoanPoolBorrowed().get(USDT), "borrowed 未减");
        assertEquals(interestRevBefore + 500L, loanService.getInterestRevenue().get(USDT), "利息进 interestRevenue");
    }

    @Test
    void loanRepay_partial_interestPlusSomePrincipal_conservationHolds() {
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        IsolatedLoanRecord loan = up.isolatedLoans.get(999L);
        loan.accumulatedInterest = 1000L;
        loan.lastAccrueTs = 1000L;

        long accountsBefore = up.accounts.get(USDT);
        long poolBefore = loanService.getLoanPoolAvailable().get(USDT);
        long interestRevBefore = loanService.getInterestRevenue().get(USDT);
        long borrowedBefore = loanService.getLoanPoolBorrowed().get(USDT);

        // 还 3000 = 全利息 1000 + 2000 principal
        handlers.handleLoanRepay(build(OrderCommandType.LOAN_REPAY, 2L, UID, 999L, 0, 0, 3_000L));

        assertEquals(0L, loan.accumulatedInterest);
        assertEquals(28_000L, loan.outstandingPrincipal);
        assertEquals(accountsBefore - 3_000L, up.accounts.get(USDT));
        assertEquals(poolBefore + 2_000L, loanService.getLoanPoolAvailable().get(USDT), "池子回收 principal 部分");
        assertEquals(interestRevBefore + 1_000L, loanService.getInterestRevenue().get(USDT));
        assertEquals(borrowedBefore - 2_000L, loanService.getLoanPoolBorrowed().get(USDT));

        // 守恒方程：Σ(accounts + pool_avail + interestRevenue) delta = 0
        long deltaAccounts = up.accounts.get(USDT) - accountsBefore;
        long deltaPool = loanService.getLoanPoolAvailable().get(USDT) - poolBefore;
        long deltaInterestRev = loanService.getInterestRevenue().get(USDT) - interestRevBefore;
        assertEquals(0L, deltaAccounts + deltaPool + deltaInterestRev, "资金守恒");
    }

    @Test
    void loanCreate_conservationHolds() {
        long accountsBefore = up.accounts.get(USDT);
        long poolBefore = loanService.getLoanPoolAvailable().get(USDT);
        long feesBefore = fees.get(USDT);

        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));

        long deltaAccounts = up.accounts.get(USDT) - accountsBefore;
        long deltaPool = loanService.getLoanPoolAvailable().get(USDT) - poolBefore;
        long deltaFees = fees.get(USDT) - feesBefore;
        assertEquals(30_000L, deltaAccounts, "principal 打入账户");
        assertEquals(-30_000L, deltaPool, "池子扣 principal");
        assertEquals(0L, deltaFees);
        assertEquals(0L, deltaAccounts + deltaPool + deltaFees, "资金守恒");
    }

    @Test
    void poolDeposit_conservationHolds() {
        long poolBefore = loanService.getLoanPoolAvailable().get(USDT);
        long adjustmentsBefore = adjustments.get(USDT);

        handlers.handlePoolDeposit(build(OrderCommandType.POOL_DEPOSIT, 1L, 0L, 0, USDT, 50_000L, 0));

        long deltaPool = loanService.getLoanPoolAvailable().get(USDT) - poolBefore;
        long deltaAdj = adjustments.get(USDT) - adjustmentsBefore;
        assertEquals(50_000L, deltaPool);
        assertEquals(-50_000L, deltaAdj);
        assertEquals(0L, deltaPool + deltaAdj, "pool ↑ / adjustments ↓ 对冲");
    }

    // ================================================================
    // LOAN_ADD_COLLATERAL
    // ================================================================

    @Test
    void loanAddCollateral_happy() {
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        OrderCommand cmd = build(OrderCommandType.LOAN_ADD_COLLATERAL, 2L, UID, 999L, 0, 2L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handleLoanAddCollateral(cmd));
        assertEquals(3L, up.isolatedLoans.get(999L).collateralAmount);
    }

    @Test
    void loanAddCollateral_collateralInsufficient() {
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        // 想再加 100 BTC 但账户只剩 9（10 - 1 已作抵押；calculateLocked mock 返 0 所以 free=9）
        OrderCommand cmd = build(OrderCommandType.LOAN_ADD_COLLATERAL, 2L, UID, 999L, 0, 100L, 0);
        assertEquals(CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT, handlers.handleLoanAddCollateral(cmd));
    }

    // ================================================================
    // LOAN_RELEASE_COLLATERAL
    // ================================================================

    @Test
    void loanReleaseCollateral_happy_smallReduce() {
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 2L, 30_000L));
        // 2 BTC 抵押 30000 USDT，减 0.5 BTC 后剩 1.5 BTC，LTV = 30000/(1.5*50000) = 40% < 80% liquidation
        // 但 amount 是 long，1.5 表示不了，改成整数：抵押 2，撤 1，剩 1 → LTV = 60% < 80% ✓
        OrderCommand cmd = build(OrderCommandType.LOAN_RELEASE_COLLATERAL, 2L, UID, 999L, 0, 1L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handleLoanReleaseCollateral(cmd));
        assertEquals(1L, up.isolatedLoans.get(999L).collateralAmount);
    }

    @Test
    void loanReleaseCollateral_ltvTooHighAfterRelease() {
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        // 只有 1 BTC 抵押 30000，撤走全部就 underwater
        OrderCommand cmd = build(OrderCommandType.LOAN_RELEASE_COLLATERAL, 2L, UID, 999L, 0, 1L, 0);
        assertEquals(CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_RELEASE,
            handlers.handleLoanReleaseCollateral(cmd));
    }

    @Test
    void loanReleaseCollateral_exceedsLoan() {
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        OrderCommand cmd = build(OrderCommandType.LOAN_RELEASE_COLLATERAL, 2L, UID, 999L, 0, 5L, 0);
        assertEquals(CommandResultCode.LOAN_COLLATERAL_EXCEEDS_LOAN,
            handlers.handleLoanReleaseCollateral(cmd));
    }

    @Test
    void loanCreate_afterRepayPayoffAndRelease_reusesPooledRecord() {
        // 1. CREATE 分配实例 A（对象池空 → new）
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L));
        IsolatedLoanRecord recordA = up.isolatedLoans.get(999L);
        // 2. REPAY 全额付清后 collateralAmount 还在 → record 还在 map，还没归还池
        handlers.handleLoanRepay(build(OrderCommandType.LOAN_REPAY, 2L, UID, 999L, 0, 0, 0));
        assertEquals(recordA, up.isolatedLoans.get(999L));
        // 3. RELEASE collateral 到 0 → isEmpty → record 归还池
        handlers.handleLoanReleaseCollateral(
            build(OrderCommandType.LOAN_RELEASE_COLLATERAL, 3L, UID, 999L, 0, 1L, 0));
        assertTrue(up.isolatedLoans.isEmpty());
        // 4. 新 CREATE 应从池里拿出同一个实例（recordA），initialize 覆盖 identity
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 4L, UID, 888L, SYMBOL, 1L, 20_000L));
        IsolatedLoanRecord recordB = up.isolatedLoans.get(888L);
        assertSame(recordA, recordB, "对象池复用同一实例");
        assertEquals(888L, recordB.loanId, "identity 被 initialize 覆盖");
        assertEquals(20_000L, recordB.outstandingPrincipal);
        assertEquals(0L, recordB.accumulatedInterest, "累计利息重置为 0");
    }

    @Test
    void loanCrossBorrow_repayPayoff_returnsRecordToPool() {
        // 1. BORROW 分配 recordA
        handlers.handleLoanCrossBorrow(build(OrderCommandType.LOAN_CROSS_BORROW, 1L, UID, 555L, USDT, 0, 20_000L));
        exchange.core2.core.common.CrossLoanRecord recordA = up.crossLoans.get(555L);
        // 2. REPAY payoff → 归还池
        handlers.handleLoanCrossRepay(build(OrderCommandType.LOAN_CROSS_REPAY, 2L, UID, 555L, 0, 0, 0));
        assertTrue(up.crossLoans.isEmpty());
        // 3. 新 BORROW 拿到同一实例
        handlers.handleLoanCrossBorrow(build(OrderCommandType.LOAN_CROSS_BORROW, 3L, UID, 777L, USDT, 0, 10_000L));
        exchange.core2.core.common.CrossLoanRecord recordB = up.crossLoans.get(777L);
        assertSame(recordA, recordB, "Cross record 池复用");
        assertEquals(777L, recordB.loanId);
        assertEquals(10_000L, recordB.outstandingPrincipal);
    }

    @Test
    void loanReleaseCollateral_removesLoanAfterFullPayoffAndZeroCollateral() {
        // 1. CREATE loan (2 BTC 抵押 30k USDT)
        handlers.handleLoanCreate(build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 2L, 30_000L));
        assertEquals(1, up.isolatedLoans.size());
        // 2. REPAY 全清 principal + interest；collateral 未动，loan 记录还在（design §5.3）
        handlers.handleLoanRepay(build(OrderCommandType.LOAN_REPAY, 2L, UID, 999L, 0, 0, 0));
        assertEquals(1, up.isolatedLoans.size(), "REPAY payoff 后 loan 仍在（有 collateral）");
        assertEquals(2L, up.isolatedLoans.get(999L).collateralAmount);
        // 3. RELEASE 全部抵押 —— principal=0 所以 (newCollateral==0 && princ>0) 不拦截
        //    应触发死壳清理：loan.isEmpty() → remove
        CommandResultCode rc = handlers.handleLoanReleaseCollateral(
            build(OrderCommandType.LOAN_RELEASE_COLLATERAL, 3L, UID, 999L, 0, 2L, 0));
        assertEquals(CommandResultCode.SUCCESS, rc);
        assertTrue(up.isolatedLoans.isEmpty(), "loan record 应被死壳清理移除");
        // 4. 同 loanId 可被 CREATE 复用（不再 LOAN_ALREADY_EXISTS）
        CommandResultCode rc2 = handlers.handleLoanCreate(
            build(OrderCommandType.LOAN_CREATE, 4L, UID, 999L, SYMBOL, 1L, 30_000L));
        assertEquals(CommandResultCode.SUCCESS, rc2, "同 loanId 可复用");
    }

    // ================================================================
    // LOAN_CROSS_ADD_COLLATERAL
    // ================================================================

    @Test
    void loanCrossAddCollateral_happy() {
        // setUp 已预置 10 BTC crossLoanCollateral，本 test 再加 3 → 13
        long before = up.crossLoanCollateral.get(BTC);
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_ADD_COLLATERAL, 1L, UID, 0, BTC, 3L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handleLoanCrossAddCollateral(cmd));
        assertEquals(before + 3L, up.crossLoanCollateral.get(BTC));
    }

    @Test
    void loanCrossAddCollateral_notAllowed() {
        // 用一个 collateralWeightBps == 0 的 currency（USDT symbol 里 base=BTC 有 weight，USDT 自身作 base 找不到 spec）
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_ADD_COLLATERAL, 1L, UID, 0, USDT, 1000L, 0);
        assertEquals(CommandResultCode.LOAN_COLLATERAL_NOT_ALLOWED, handlers.handleLoanCrossAddCollateral(cmd));
    }

    // ================================================================
    // LOAN_CROSS_WITHDRAW_COLLATERAL
    // ================================================================

    @Test
    void loanCrossWithdrawCollateral_happy_noDebt_ltvZero() {
        up.crossLoanCollateral.put(BTC, 5L);
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_WITHDRAW_COLLATERAL, 1L, UID, 0, BTC, 2L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handleLoanCrossWithdrawCollateral(cmd));
        assertEquals(3L, up.crossLoanCollateral.get(BTC));
    }

    @Test
    void loanCrossWithdrawCollateral_ltvExceedsLiquidation_revertsCollateral() {
        // 绕开 NUMERAIRE_UNSET=0 sentinel：spy LoanService，让 calculateCrossAccountLtvBps 强制返高 LTV
        LoanService spied = spy(loanService);
        doReturn(10_000L).when(spied).calculateCrossAccountLtvBps(any(), anyLong(), any(), any(), any(), anyInt());
        when(engine.getLoanService()).thenReturn(spied);

        up.crossLoanCollateral.put(BTC, 5L);
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_WITHDRAW_COLLATERAL, 1L, UID, 0, BTC, 2L, 0);
        CommandResultCode rc = handlers.handleLoanCrossWithdrawCollateral(cmd);

        assertEquals(CommandResultCode.LOAN_CROSS_LTV_TOO_HIGH_AFTER_WITHDRAW, rc);
        assertEquals(5L, up.crossLoanCollateral.get(BTC), "拒绝后 collateral revert 到原值");
    }

    // ================================================================
    // LOAN_CROSS_BORROW
    // ================================================================

    @Test
    void loanCrossBorrow_happy() {
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_BORROW, 1L, UID, 555L, USDT, 0, 20_000L);
        assertEquals(CommandResultCode.SUCCESS, handlers.handleLoanCrossBorrow(cmd));
        assertEquals(1, up.crossLoans.size());
        assertEquals(20_000L, up.accounts.get(USDT));
    }

    @Test
    void loanCrossBorrow_ltvExceedsInitial_revertsLoanEntryAndKeepsAccountsPoolIntact() {
        // spy LoanService，让 LTV 校验必然失败（触发 revert 分支）
        LoanService spied = spy(loanService);
        doReturn(10_000L).when(spied).calculateCrossAccountLtvBps(any(), anyLong(), any(), any(), any(), anyInt());
        when(engine.getLoanService()).thenReturn(spied);

        long accountsBefore = up.accounts.get(USDT);
        long poolAvailBefore = loanService.getLoanPoolAvailable().get(USDT);
        long poolBorrowBefore = loanService.getLoanPoolBorrowed().get(USDT);

        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_BORROW, 1L, UID, 555L, USDT, 0, 20_000L);
        CommandResultCode rc = handlers.handleLoanCrossBorrow(cmd);

        assertEquals(CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_BORROW, rc);
        assertTrue(up.crossLoans.isEmpty(), "loan record 应被 revert 移除");
        assertEquals(accountsBefore, up.accounts.get(USDT), "principal 未打进账户");
        assertEquals(poolAvailBefore, loanService.getLoanPoolAvailable().get(USDT), "池子未扣");
        assertEquals(poolBorrowBefore, loanService.getLoanPoolBorrowed().get(USDT), "borrowed 未增");
    }

    // ================================================================
    // LOAN_CROSS_REPAY
    // ================================================================

    @Test
    void loanCrossRepay_happy() {
        handlers.handleLoanCrossBorrow(build(OrderCommandType.LOAN_CROSS_BORROW, 1L, UID, 555L, USDT, 0, 20_000L));
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_REPAY, 2L, UID, 555L, 0, 0, 0);  // payoff
        assertEquals(CommandResultCode.SUCCESS, handlers.handleLoanCrossRepay(cmd));
        assertEquals(0, up.crossLoans.size());
    }

    // ================================================================
    // POOL_DEPOSIT / POOL_WITHDRAW
    // ================================================================

    @Test
    void poolDeposit_happy() {
        OrderCommand cmd = build(OrderCommandType.POOL_DEPOSIT, 1L, 0L /* shardId */, 0, USDT, 50_000L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handlePoolDeposit(cmd));
        assertEquals(150_000L, loanService.getLoanPoolAvailable().get(USDT));
        assertEquals(-50_000L, adjustments.get(USDT), "adjustments 反向对冲");
    }

    @Test
    void poolDeposit_wrongShardNoop() {
        OrderCommand cmd = build(OrderCommandType.POOL_DEPOSIT, 1L, 99L /* wrong shardId */, 0, USDT, 50_000L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handlePoolDeposit(cmd), "静默 SUCCESS");
        assertEquals(100_000L, loanService.getLoanPoolAvailable().get(USDT), "池子未变");
    }

    @Test
    void poolWithdraw_happy() {
        OrderCommand cmd = build(OrderCommandType.POOL_WITHDRAW, 1L, 0L, 0, USDT, 30_000L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handlePoolWithdraw(cmd));
        assertEquals(70_000L, loanService.getLoanPoolAvailable().get(USDT));
        assertEquals(30_000L, adjustments.get(USDT));
    }

    @Test
    void poolWithdraw_poolInsufficient() {
        OrderCommand cmd = build(OrderCommandType.POOL_WITHDRAW, 1L, 0L, 0, USDT, 500_000L, 0);
        assertEquals(CommandResultCode.LOAN_POOL_INSUFFICIENT, handlers.handlePoolWithdraw(cmd));
    }

    // ================================================================
    // force-sell stubs（LOAN_FORCE_LIQUIDATE / LOAN_CROSS_FORCE_LIQUIDATE）
    // ================================================================

    @Test
    void loanForceLiquidate_loanNotFound_returnsLoanNotFound() {
        // loanId 走 cmd.reserveBidPrice；isolatedLoans 里没这笔 → LOAN_NOT_FOUND
        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE, 1L, UID, 999L, SYMBOL, 1L, 50000L);
        assertEquals(CommandResultCode.LOAN_NOT_FOUND, handlers.handleLoanForceLiquidate(cmd));
    }

    @Test
    void loanForceLiquidate_happyPath_preMovesCollateral() {
        // 手动挂一笔 Isolated loan：collateral=5 BTC, principal=100k USDT
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 111L, BTC, USDT, 500, 0L);
        loan.collateralAmount = 5L;
        loan.outstandingPrincipal = 100_000L;
        up.isolatedLoans.put(111L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);

        long orderId = LoanService.generateIsolatedForceSellOrderId(loan);
        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE, orderId, UID, 111L, SYMBOL, 3L, 49500L);

        CommandResultCode rc = handlers.handleLoanForceLiquidate(cmd);

        assertEquals(CommandResultCode.VALID_FOR_MATCHING_ENGINE, rc);
        assertEquals(2L, loan.collateralAmount, "collateral pre-move -3");
        assertEquals(3L, up.exchangeLocked.get(BTC), "exchangeLocked +3");
    }

    @Test
    void loanForceLiquidate_suspendedUser_stillLiquidates() {
        // suspend 不该挡系统强平：否则 suspended 用户的 underwater loan 永远清不掉 → 坏账
        up.userStatus = UserStatus.SUSPENDED;
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 111L, BTC, USDT, 500, 0L);
        loan.collateralAmount = 5L;
        loan.outstandingPrincipal = 100_000L;
        up.isolatedLoans.put(111L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);

        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE,
            LoanService.generateIsolatedForceSellOrderId(loan), UID, 111L, SYMBOL, 3L, 49_500L);
        assertEquals(CommandResultCode.VALID_FOR_MATCHING_ENGINE, handlers.handleLoanForceLiquidate(cmd),
            "suspended 用户的 loan 仍须能被系统强平");
        assertEquals(2L, loan.collateralAmount, "pre-move 照常执行");
    }

    @Test
    void loanCrossForceLiquidate_suspendedUser_stillLiquidates() {
        up.userStatus = UserStatus.SUSPENDED;
        CrossLoanRecord targetLoan = new CrossLoanRecord(UID, 777L, USDT, 0, 0L);
        targetLoan.outstandingPrincipal = 30_000L;
        up.crossLoans.put(777L, targetLoan);
        up.crossLoanCollateral.put(BTC, 5L);

        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE, 1L, UID, 777L, SYMBOL, 3L, 49_500L);
        assertEquals(CommandResultCode.VALID_FOR_MATCHING_ENGINE, handlers.handleLoanCrossForceLiquidate(cmd),
            "suspended 用户的 cross loan 仍须能被系统强平");
        assertEquals(2L, up.crossLoanCollateral.get(BTC), "pre-move 挪 3 张(=3) → 剩 2");
    }

    @Test
    void loanForceLiquidate_duplicateApply_secondRejectedByCollateralGuard() {
        // failover 幂等命门：即使新 leader 因空 in-flight 重复发一条强平，apply 路径的抵押边界会挡下第二条。
        // pre-move 是"先校验 sellAmount ≤ collateralAmount，再扣减"的原子 compare-and-consume，
        // raft 日志顺序执行 → 第一条消费掉抵押，第二条扑空 LOAN_INVALID_AMOUNT，不会双重卖出。
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 111L, BTC, USDT, 500, 0L);
        loan.collateralAmount = 3L;
        loan.outstandingPrincipal = 100_000L;
        up.isolatedLoans.put(111L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);

        // 第一条（X）：卖光 3 张 → pre-move 成功，抵押 3→0 全挪进 exchangeLocked
        OrderCommand x = build(OrderCommandType.LOAN_FORCE_LIQUIDATE,
            LoanService.generateIsolatedForceSellOrderId(loan), UID, 111L, SYMBOL, 3L, 49500L);
        assertEquals(CommandResultCode.VALID_FOR_MATCHING_ENGINE, handlers.handleLoanForceLiquidate(x));
        assertEquals(0L, loan.collateralAmount);
        assertEquals(3L, up.exchangeLocked.get(BTC));

        // 第二条（Y，新 orderId，模拟 failover 后新 leader 按旧状态又发的 3 张）：抵押已被 X 消费 → 拒
        OrderCommand y = build(OrderCommandType.LOAN_FORCE_LIQUIDATE, 777_777L, UID, 111L, SYMBOL, 3L, 49500L);
        assertEquals(CommandResultCode.LOAN_INVALID_AMOUNT, handlers.handleLoanForceLiquidate(y),
            "第二条重复强平必须被抵押边界拒（无双重卖出）");
        // 状态未被第二条改动
        assertEquals(0L, loan.collateralAmount, "第二条不得再扣抵押");
        assertEquals(3L, up.exchangeLocked.get(BTC), "第二条不得再挪 exchangeLocked");
    }

    @Test
    void loanCrossForceLiquidate_targetLoanNotFound_returnsLoanNotFound() {
        // targetLoanId 走 cmd.reserveBidPrice；crossLoans 里没这笔 → LOAN_NOT_FOUND
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE, 1L, UID, 999L, SYMBOL, 1L, 50000L);
        assertEquals(CommandResultCode.LOAN_NOT_FOUND, handlers.handleLoanCrossForceLiquidate(cmd));
    }

    // ================================================================
    // dispatch() 入口
    // ================================================================

    @Test
    void dispatch_routesLoanCreateToHandler() {
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L);
        handlers.dispatch(cmd);
        assertEquals(CommandResultCode.SUCCESS, cmd.resultCode);
        assertEquals(1, up.isolatedLoans.size());
    }

    @Test
    void dispatch_routesPoolOpsWithShardFilter() {
        // target shard 写 resultCode
        OrderCommand cmd = build(OrderCommandType.POOL_DEPOSIT, 1L, 0L, 0, USDT, 50_000L, 0);
        handlers.dispatch(cmd);
        assertEquals(CommandResultCode.SUCCESS, cmd.resultCode);
    }

    @Test
    void dispatch_wrongShardPoolNoopButNoResultCode() {
        // 其他 shard 不写 resultCode（只 target shard 才写）
        OrderCommand cmd = build(OrderCommandType.POOL_DEPOSIT, 1L, 99L, 0, USDT, 50_000L, 0);
        handlers.dispatch(cmd);
        assertNull(cmd.resultCode, "非 target shard 不写 resultCode");
    }

    @Test
    void dispatch_wrongCommandType_throws() {
        // Feed a non-loan cmd → default branch throws
        OrderCommand cmd = build(OrderCommandType.PLACE_ORDER, 1L, UID, 0, 0, 0, 0);
        assertThrows(IllegalStateException.class, () -> handlers.dispatch(cmd));
    }

    @Test
    void dispatch_nonMatchingShardUidUserCommand_noResultCode() {
        when(engine.uidForThisHandler(anyLong())).thenReturn(false);
        OrderCommand cmd = build(OrderCommandType.LOAN_CREATE, 1L, UID, 999L, SYMBOL, 1L, 30_000L);
        handlers.dispatch(cmd);
        assertNull(cmd.resultCode, "非本 shard 用户命令：不写 resultCode（跟 IF_TAKEOVER 等一致）");
        assertTrue(up.isolatedLoans.isEmpty(), "handler 未被调用");
    }

    // ================================================================
    // POOL_ABSORB_BAD_DEBT
    // ================================================================

    @Test
    void poolAbsorbBadDebt_happyPath_reducesTrackerButNotPool() {
        // 预置：badDebt[USDT] = 500，poolAvailable[USDT] 已在 setUp 里预置 100k
        loanService.getBadDebt().put(USDT, 500L);
        long poolBefore = loanService.getLoanPoolAvailable().get(USDT);

        OrderCommand cmd = build(OrderCommandType.POOL_ABSORB_BAD_DEBT, 1L, 0L, 0, USDT, 500L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handlePoolAbsorbBadDebt(cmd));

        assertEquals(0L, loanService.getBadDebt().get(USDT), "badDebt 清零");
        assertEquals(poolBefore, loanService.getLoanPoolAvailable().get(USDT), "poolAvailable 不动（Bug 1 修法）");
    }

    @Test
    void poolAbsorbBadDebt_partial_capsAtBadDebtAmount() {
        // badDebt=300 但请求 absorb 1000 → 只 absorb 300（min）
        loanService.getBadDebt().put(USDT, 300L);
        OrderCommand cmd = build(OrderCommandType.POOL_ABSORB_BAD_DEBT, 1L, 0L, 0, USDT, 1000L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handlePoolAbsorbBadDebt(cmd));
        assertEquals(0L, loanService.getBadDebt().get(USDT), "封顶到 badDebt 值");
    }

    @Test
    void poolAbsorbBadDebt_noBadDebt_returnsInvalidAmount() {
        // badDebt = 0 时无法 absorb
        OrderCommand cmd = build(OrderCommandType.POOL_ABSORB_BAD_DEBT, 1L, 0L, 0, USDT, 500L, 0);
        assertEquals(CommandResultCode.LOAN_INVALID_AMOUNT, handlers.handlePoolAbsorbBadDebt(cmd));
    }

    @Test
    void poolAbsorbBadDebt_wrongShard_silentSuccess() {
        // shard 不匹配（uid=99 ≠ shardId=0）→ 静默 SUCCESS，什么都不做
        loanService.getBadDebt().put(USDT, 500L);
        OrderCommand cmd = build(OrderCommandType.POOL_ABSORB_BAD_DEBT, 1L, 99L, 0, USDT, 500L, 0);
        assertEquals(CommandResultCode.SUCCESS, handlers.handlePoolAbsorbBadDebt(cmd));
        assertEquals(500L, loanService.getBadDebt().get(USDT), "非 target shard 不改 state");
    }

    // ================================================================
    // Numeraire 未配置 fail-close（Cross BORROW / WITHDRAW）
    // ================================================================

    @Test
    void loanCrossBorrow_numeraireUnconfigured_failsClose() {
        loanService.setNumeraireCurrency(LoanService.NUMERAIRE_UNSET); // 清 setUp 里配的 USDT
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_BORROW, 1L, UID, 555L, USDT, 0, 20_000L);
        assertEquals(CommandResultCode.LOAN_NUMERAIRE_NOT_CONFIGURED, handlers.handleLoanCrossBorrow(cmd));
        assertTrue(up.crossLoans.isEmpty(), "拒绝后不落 loan");
    }

    @Test
    void loanCrossWithdrawCollateral_numeraireUnconfigured_failsClose() {
        loanService.setNumeraireCurrency(LoanService.NUMERAIRE_UNSET);
        long collateralBefore = up.crossLoanCollateral.get(BTC);
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_WITHDRAW_COLLATERAL, 1L, UID, 0, BTC, 2L, 0);
        assertEquals(CommandResultCode.LOAN_NUMERAIRE_NOT_CONFIGURED, handlers.handleLoanCrossWithdrawCollateral(cmd));
        assertEquals(collateralBefore, up.crossLoanCollateral.get(BTC), "拒绝后 collateral 不动");
    }

    // ================================================================
    // Cross force-sell underwater multi-collateral（Bug 2 修法）
    //
    // 直接构造 postProcess 需要 MatcherTradeEvent chain，改用行为一致的等价检查：
    // 手动调用 postProcess 时，crossLoanCollateral 里只有一个币归零 vs 全部归零，判定结果不同。
    // ================================================================

    @Test
    void crossForceLiquidatePostProcess_oneCurrencyZeroButOthersRemain_keepsLoan() {
        // 场景：BTC=0（这轮卖光了）但 ETH=10 还在 → target loan 不该被写成 badDebt
        // ETH 必须有现货对（cross 抵押本就要求有对），10 才算"有可卖整张"
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(ETH).name("ETH").digit(0).build());
        specProvider.registerSymbol(301, CoreSymbolSpecification.builder()
            .symbolId(301).type(SymbolType.CURRENCY_EXCHANGE_PAIR).baseCurrency(ETH).quoteCurrency(USDT)
            .baseScaleK(1).quoteScaleK(1).collateralWeightBps(9000).build());
        CrossLoanRecord targetLoan = new CrossLoanRecord(UID, 777L, USDT, 500, 0L);
        targetLoan.outstandingPrincipal = 30_000L;
        up.crossLoans.put(777L, targetLoan);
        up.crossLoanCollateral.put(BTC, 0L);
        up.crossLoanCollateral.put(ETH, 10L);
        loanService.getLoanPoolBorrowed().put(USDT, 30_000L);
        long badDebtBefore = loanService.getBadDebt().get(USDT);

        // 空 matcher event chain 模拟"卖出但没成交" —— 只走 underwater 判定路径
        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE, 1L, UID, 777L, SYMBOL, 5L, 50000L);
        cmd.matcherEvent = null;
        CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(SYMBOL);
        handlers.postProcessLoanCrossForceLiquidate(cmd, spec, up);

        assertEquals(1, up.crossLoans.size(), "还有 ETH 抵押可卖，target loan 不该关");
        assertEquals(badDebtBefore, loanService.getBadDebt().get(USDT), "badDebt 不该增加");
    }

    // ================================================================
    // Scale 混用复现：baseScaleK != currencyScaleK(base) 时强平资金不守恒
    //
    // loan.collateralAmount / exchangeLocked 全程 currency scale；撮合订单 size 是 symbol/lot scale。
    // pre-move（handleLoanForceLiquidate）把 cmd.size 原样搬进 exchangeLocked / 扣 collateralAmount，
    // 但 reject 回填（postProcess）用 symbolToCurrencyScale(rejectedSize)。两侧 scale 不一致 →
    // 一笔"全部被拒（0 成交）"的强平——本应是 no-op——会破坏 loan.collateralAmount。
    // setUp 里 BTC digit=0 + baseScaleK=1 → identity，掩盖此 bug；这里显式用 non-identity scale 暴露。
    // ================================================================

    private static final int WBTC = 10;      // base 币：digit=2 → currencyScaleK=100
    private static final int SYMBOL_NI = 200; // baseScaleK=1 → 与 WBTC currencyScaleK=100 不一致

    /** 注册一个 baseScaleK(1) 但 base 币 currencyScaleK=100 的现货 pair，返回其 spec。 */
    private CoreSymbolSpecification registerNonIdentityScaleSymbol() {
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(WBTC).name("WBTC").digit(2).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
            .symbolId(SYMBOL_NI).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(WBTC).quoteCurrency(USDT)
            .baseScaleK(1).quoteScaleK(1)
            .loanInitialLtvBps(6000).loanLiquidationLtvBps(8000).loanMarginCallLtvBps(7000)
            .loanRateBps(0).loanMaxAmount(0).loanMaxTermDays(0)
            .collateralWeightBps(9000)
            .build();
        specProvider.registerSymbol(SYMBOL_NI, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50000L;
        priceCache.put(SYMBOL_NI, price);
        return spec;
    }

    /** REJECT-only matcher event chain（0 成交，全拒），size = 未成交 lot 数。 */
    private exchange.core2.core.common.MatcherTradeEvent rejectEvent(long unmatchedSize) {
        exchange.core2.core.common.MatcherTradeEvent ev = new exchange.core2.core.common.MatcherTradeEvent();
        ev.eventType = exchange.core2.core.common.MatcherEventType.REJECT;
        ev.size = unmatchedSize;
        ev.price = 0L;
        ev.nextEvent = null;
        return ev;
    }

    /** 单条 TRADE 事件（有成交）。 */
    private exchange.core2.core.common.MatcherTradeEvent tradeEvent(long size, long price) {
        exchange.core2.core.common.MatcherTradeEvent ev = new exchange.core2.core.common.MatcherTradeEvent();
        ev.eventType = exchange.core2.core.common.MatcherEventType.TRADE;
        ev.size = size;
        ev.price = price;
        ev.nextEvent = null;
        return ev;
    }

    // ================================================================
    // P0-3 卡单计数：postProcess 里零成交 stuckLiqAttempts++、有成交清 0
    // ================================================================

    @Test
    void forceLiquidate_postProcess_zeroTrade_incrementsStuckAttempts() {
        CoreSymbolSpecification spec = specProvider.getSymbolSpecification(SYMBOL);
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 111L, BTC, USDT, 0, 0L);
        loan.collateralAmount = 5L;
        loan.outstandingPrincipal = 100_000L;
        loan.stuckLiqAttempts = 0;
        up.isolatedLoans.put(111L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);

        // 全拒（0 成交）→ loan 保留、attempts +1
        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE, 1L, UID, 111L, SYMBOL, 3L, 49_500L);
        cmd.matcherEvent = rejectEvent(3L);
        handlers.postProcessLoanForceLiquidate(cmd, spec, up);
        assertEquals(1, up.isolatedLoans.get(111L).stuckLiqAttempts, "全拒 → attempts=1");

        // 再全拒 → 累加到 2
        cmd.matcherEvent = rejectEvent(3L);
        handlers.postProcessLoanForceLiquidate(cmd, spec, up);
        assertEquals(2, up.isolatedLoans.get(111L).stuckLiqAttempts, "再全拒 → attempts=2");
    }

    @Test
    void forceLiquidate_postProcess_hasTrade_resetsStuckAttempts() {
        CoreSymbolSpecification spec = specProvider.getSymbolSpecification(SYMBOL);
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 111L, BTC, USDT, 0, 0L);
        loan.collateralAmount = 5L;
        loan.outstandingPrincipal = 100_000L;
        loan.stuckLiqAttempts = 5; // 之前卡了 5 次
        up.isolatedLoans.put(111L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);

        // 有成交（2 张 @50000）→ loan 保留（还有债务）、attempts 清 0
        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE, 1L, UID, 111L, SYMBOL, 2L, 49_500L);
        cmd.matcherEvent = tradeEvent(2L, 50_000L);
        handlers.postProcessLoanForceLiquidate(cmd, spec, up);
        assertEquals(0, up.isolatedLoans.get(111L).stuckLiqAttempts, "有成交 → attempts 清 0");
    }

    @Test
    void forceLiquidate_fullReject_nonIdentityScale_mustBeNoOp() {
        CoreSymbolSpecification spec = registerNonIdentityScaleSymbol();
        CoreCurrencySpecification baseSpec = currencyProvider.getCurrencySpecification(WBTC);

        // 抵押 300（WBTC currency scale = 3.00 WBTC）；负债 100k USDT
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 111L, WBTC, USDT, 0, 0L);
        loan.collateralAmount = 300L;
        loan.outstandingPrincipal = 100_000L;
        up.isolatedLoans.put(111L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);

        final long collateralBefore = loan.collateralAmount;

        // scanner 修复后下单 size = currencyToSymbolScale(collateralAmount) = 300/100 = 3 lot
        long sellSizeLots = CoreArithmeticUtils.currencyToSymbolScale(loan.collateralAmount, spec, baseSpec);
        assertEquals(3L, sellSizeLots, "300 currency → 3 lot");
        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE,
            LoanService.generateIsolatedForceSellOrderId(loan), UID, 111L, SYMBOL_NI, sellSizeLots, 49_500L);

        // R1 pre-move：3 lot → symbolToCurrencyScale(3)=300 currency 挪进 exchangeLocked
        assertEquals(CommandResultCode.VALID_FOR_MATCHING_ENGINE, handlers.handleLoanForceLiquidate(cmd));
        assertEquals(0L, loan.collateralAmount, "pre-move 扣 300 currency");
        assertEquals(300L, up.exchangeLocked.get(WBTC),
            "pre-move 搬进 exchangeLocked 的是 currency scale(300)，与结算/reject 释放同 scale");

        // 撮合全拒（0 成交）：REJECT 事件 size = 下单 lot 数 = 3
        cmd.matcherEvent = rejectEvent(sellSizeLots);
        handlers.postProcessLoanForceLiquidate(cmd, spec, up);

        // 全拒 = 什么都没卖 → collateralAmount 恢复原值（reject 回填 symbolToCurrencyScale(3)=300）
        assertEquals(collateralBefore, loan.collateralAmount,
            "全拒强平是 no-op，collateralAmount 恢复原值（scale 边界修复后）");
    }

    @Test
    void forceLiquidate_fullReject_identityScale_isNoOp_control() {
        // 对照组：setUp 的 BTC（digit=0, baseScaleK=1 → identity），同样全拒 → 正确 no-op
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 222L, BTC, USDT, 0, 0L);
        loan.collateralAmount = 3L;
        loan.outstandingPrincipal = 100_000L;
        up.isolatedLoans.put(222L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);

        CoreSymbolSpecification spec = specProvider.getSymbolSpecification(SYMBOL);
        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE,
            LoanService.generateIsolatedForceSellOrderId(loan), UID, 222L, SYMBOL, 3L, 49_500L);
        assertEquals(CommandResultCode.VALID_FOR_MATCHING_ENGINE, handlers.handleLoanForceLiquidate(cmd));
        assertEquals(3L, up.exchangeLocked.get(BTC), "identity: pre-move 挪 3");
        cmd.matcherEvent = rejectEvent(3L);
        handlers.postProcessLoanForceLiquidate(cmd, spec, up);

        assertEquals(3L, loan.collateralAmount, "identity scale 下全拒是正确 no-op");
    }

    // ================================================================
    // sub-lot 尘埃 underwater 核销（非 identity scale 才会出现尘埃）
    // ================================================================

    @Test
    void forceLiquidate_underwaterWithSubLotDust_writesBadDebtAndCloses() {
        CoreSymbolSpecification spec = registerNonIdentityScaleSymbol();
        // 抵押 50（0.5 张，collateralAmountToLots(50)=0）+ 债务未清 → 尘埃卖不掉、underwater
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 111L, WBTC, USDT, 0, 0L);
        loan.collateralAmount = 50L;
        loan.outstandingPrincipal = 100_000L;
        up.isolatedLoans.put(111L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);
        long badDebtBefore = loanService.getBadDebt().get(USDT);

        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE, 1L, UID, 111L, SYMBOL_NI, 0L, 49_500L);
        cmd.matcherEvent = null; // 整张已卖光，本轮无成交，只剩尘埃
        handlers.postProcessLoanForceLiquidate(cmd, spec, up);

        assertTrue(up.isolatedLoans.isEmpty(), "尘埃 underwater → loan 核销关闭");
        assertEquals(badDebtBefore + 100_000L, loanService.getBadDebt().get(USDT), "剩余债务写 badDebt");
        assertEquals(0L, loanService.getLoanPoolBorrowed().get(USDT), "principal 从 borrowed 移除");
    }

    @Test
    void forceLiquidate_underwaterButWholeLotRemains_keepsLoan() {
        CoreSymbolSpecification spec = registerNonIdentityScaleSymbol();
        // 抵押 100（正好 1 张，可卖）+ 债务未清 → 不该核销，等下轮继续卖
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 112L, WBTC, USDT, 0, 0L);
        loan.collateralAmount = 100L;
        loan.outstandingPrincipal = 100_000L;
        up.isolatedLoans.put(112L, loan);
        loanService.getLoanPoolBorrowed().put(USDT, 100_000L);
        long badDebtBefore = loanService.getBadDebt().get(USDT);

        OrderCommand cmd = build(OrderCommandType.LOAN_FORCE_LIQUIDATE, 1L, UID, 112L, SYMBOL_NI, 0L, 49_500L);
        cmd.matcherEvent = null;
        handlers.postProcessLoanForceLiquidate(cmd, spec, up);

        assertEquals(1, up.isolatedLoans.size(), "还有整张可卖，不核销");
        assertEquals(badDebtBefore, loanService.getBadDebt().get(USDT), "badDebt 不增");
    }

    @Test
    void crossForceLiquidate_allCollateralSubLotDust_writesBadDebt() {
        CoreSymbolSpecification spec = registerNonIdentityScaleSymbol(); // base=WBTC，currencyScaleK=100
        CrossLoanRecord targetLoan = new CrossLoanRecord(UID, 889L, USDT, 500, 0L);
        targetLoan.outstandingPrincipal = 30_000L;
        up.crossLoans.put(889L, targetLoan);
        up.crossLoanCollateral.clear(); // 清掉 setUp 的 BTC=10
        up.crossLoanCollateral.put(WBTC, 50L); // 0.5 张尘埃，无可卖整张
        loanService.getLoanPoolBorrowed().put(USDT, 30_000L);
        long badDebtBefore = loanService.getBadDebt().get(USDT);

        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE, 1L, UID, 889L, SYMBOL_NI, 0L, 49_500L);
        cmd.matcherEvent = null;
        handlers.postProcessLoanCrossForceLiquidate(cmd, spec, up);

        assertTrue(up.crossLoans.isEmpty(), "全是尘埃 → target loan 核销");
        assertEquals(badDebtBefore + 30_000L, loanService.getBadDebt().get(USDT), "剩余债务写 badDebt");
    }

    @Test
    void crossForceLiquidatePostProcess_allCollateralExhausted_writesBadDebt() {
        // 场景：所有 collateral 都归零 → 真正 underwater，target loan 写 badDebt
        CrossLoanRecord targetLoan = new CrossLoanRecord(UID, 888L, USDT, 500, 0L);
        targetLoan.outstandingPrincipal = 30_000L;
        up.crossLoans.put(888L, targetLoan);
        up.crossLoanCollateral.put(BTC, 0L);
        up.crossLoanCollateral.put(ETH, 0L);
        loanService.getLoanPoolBorrowed().put(USDT, 30_000L);

        OrderCommand cmd = build(OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE, 1L, UID, 888L, SYMBOL, 5L, 50000L);
        cmd.matcherEvent = null;
        CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(SYMBOL);
        handlers.postProcessLoanCrossForceLiquidate(cmd, spec, up);

        assertTrue(up.crossLoans.isEmpty(), "target loan 写 badDebt 关闭");
        assertEquals(30_000L, loanService.getBadDebt().get(USDT), "badDebt 记录 30k 损失");
    }
}
