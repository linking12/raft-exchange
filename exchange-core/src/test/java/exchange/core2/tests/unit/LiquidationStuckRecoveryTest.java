package exchange.core2.tests.unit;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiAutoDeleveraging;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiIFTakeOver;
import exchange.core2.core.common.api.ApiLiquidationOrder;
import exchange.core2.core.common.api.ApiRepriceLoanRates;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationContext;
import exchange.core2.core.processors.liquidation.LiquidationContext.LiquidationState;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.liquidation.LiquidationService;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scanner 驱动的卡住强平流程恢复 + 幂等门测试。
 *
 * <p>覆盖三大场景：
 * 1. 卡住检测：ctx 非 null 且超过阈值未推进 → scanner 重发对应阶段 cmd
 * 2. 正在推进：ctx 非 null 且未超阈值 → 跳过破产检测但不重发
 * 3. 幂等门：state 不匹配 / ctx==null 上 IF/ADL 非法跳跃 → 静默 skip
 */
class LiquidationStuckRecoveryTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build())
            .build();

    private static final int SYMBOL = 100001;
    private static final long UID = 9801L;
    private static final long STUCK_THRESHOLD_MS = 5000L;

    private LiquidationEngine le;
    private List<ApiCommand> published;
    private UserProfileService userProfileService;

    @BeforeEach
    void setup() {
        le = new LiquidationEngine(null, 0, TEST_CFG);
        published = new ArrayList<>();
        // 忽略周期 reprice（shard0 借 scanner tick 发的 REPRICE_LOAN_RATES）——与本 futures stuck 测试无关
        le.setLiquidationCmdPublisher((cmd, onApplied) -> {
            if (!(cmd instanceof ApiRepriceLoanRates)) {
                published.add(cmd);
            }
        });
        userProfileService = new UserProfileService();
        // 这些 provider 在 stuck 检测路径上不会真正访问到，但 checkLiquidations 入口需要 non-null。
        // SYMBOL 不在 spec 表里 → tryRepublishStuckLiquidation 在前面就返 true 提前退出，不会撞 NPE。
        le.updateProvider(
                new SymbolSpecificationProvider(),
                new CurrencySpecificationProvider(),
                userProfileService,
                new IntObjectHashMap<>(),
                new exchange.core2.core.processors.loan.LoanService());
    }

    // ============================ Stuck 重发 ============================

    @Test
    void scanner_stuckInWaitIfExecution_republishesIfTakeover() {
        SymbolPositionRecord pos = setupBankruptPositionWithStuckCtx(LiquidationState.WAIT_IF_EXECUTION,
                System.currentTimeMillis() - 6000L);
        long originalOrderId = pos.liquidationCtx.originalOrderId;

        le.triggerOnce();

        ApiIFTakeOver ifCmd = assertInstanceOf(ApiIFTakeOver.class, published.get(0),
                "应 republish IF_TAKEOVER");
        assertEquals(pos.uid, ifCmd.uid);
        assertEquals(pos.symbol, ifCmd.symbol);
        assertEquals(pos.liquidationCtx.size, ifCmd.size);
        assertEquals(pos.liquidationCtx.price, ifCmd.price);
        // orderId 必须从 ctx.originalOrderId 派生——保审计连贯，跟正常路径 onMarketDone 用 cmd.orderId 一致
        assertEquals(LiquidationService.generateIFOrderId(originalOrderId), ifCmd.orderId,
                "republished IF orderId 应派生自 ctx.originalOrderId");
        // republish 后 inFlight gate 阻止下一 tick 再发；lastTransitionAt 由 apply path 统一推进，scanner 不写
    }

    // LIQUIDATING stuck 自愈：scanner 设了 ctx 但 FORCE_LIQUIDATION cmd 没成功 commit / apply 丢了
    // → ctx 卡在 LIQUIDATING 没人推。stuck 检测 republish FORCE，让全节点的 ctx.state==LIQUIDATING==expected
    // 一起过 gate，onMarketDone 重跑。**不清 ctx**——leader-local 清空会跟 follower 发散。
    @Test
    void scanner_stuckInLiquidating_republishesForceLiquidation() {
        SymbolPositionRecord pos = setupBankruptPositionWithStuckCtx(LiquidationState.LIQUIDATING,
                System.currentTimeMillis() - 6000L);

        le.triggerOnce();

        // 关键不变量：ctx 保留（不能清空，会跟 follower 发散）
        assertNotNull(pos.liquidationCtx, "LIQUIDATING stuck 不能清空 ctx——leader-local 操作破坏 raft 一致性");
        assertEquals(LiquidationState.LIQUIDATING, pos.liquidationCtx.state, "state 保持不变，等 republish FORCE apply 推进");
        // republish FORCE_LIQUIDATION
        ApiLiquidationOrder forceCmd = assertInstanceOf(ApiLiquidationOrder.class, published.get(0),
                "应 republish FORCE_LIQUIDATION");
        assertEquals(pos.liquidationCtx.size, forceCmd.size);
        assertEquals(pos.liquidationCtx.price, forceCmd.price);
        assertEquals(OrderType.IOC, forceCmd.orderType);
        // action 是 LIQ_USER 平仓视角：LONG → ASK 卖掉、SHORT → BID 买回。
        // 跟 IF/ADL republish 用的 counterparty 接管视角相反，不能共用同一 action 变量。
        OrderAction expectedTakerAction = pos.direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID;
        assertEquals(expectedTakerAction, forceCmd.action,
                "FORCE republish 用 taker(LIQ_USER) 视角；不能跟 IF/ADL 的 counterparty 视角混用");
    }

    @Test
    void scanner_stuckInWaitAdlExecution_republishesAutoDeleveraging() {
        SymbolPositionRecord pos = setupBankruptPositionWithStuckCtx(LiquidationState.WAIT_ADL_EXECUTION,
                System.currentTimeMillis() - 6000L);
        long originalOrderId = pos.liquidationCtx.originalOrderId;

        le.triggerOnce();

        ApiAutoDeleveraging adlCmd = assertInstanceOf(ApiAutoDeleveraging.class, published.get(0),
                "应 republish AUTO_DELEVERAGING");
        // ADL orderId 同 IF 一样派生自 ctx.originalOrderId（'A' tag + FORCE 低 56 位）
        assertEquals(LiquidationService.generateADLOrderId(originalOrderId), adlCmd.orderId,
                "republished ADL orderId 应派生自 ctx.originalOrderId");
        assertEquals(pos.liquidationCtx.size, adlCmd.size);
        assertEquals(pos.liquidationCtx.price, adlCmd.price);
    }

    // ============================ 正常进行中不误判 ============================

    @Test
    void scanner_inFlightWithinThreshold_doesNotRepublish() {
        setupBankruptPositionWithStuckCtx(LiquidationState.WAIT_IF_EXECUTION,
                System.currentTimeMillis() - 2000L);  // 仅卡 2s，未超 5s 阈值

        le.triggerOnce();

        assertTrue(published.isEmpty(), "未超阈值不应 republish");
    }

    // 边界（下方 100ms）：阈值内 4900ms 仍是正常推进，不 republish。
    // 精确等于 5000ms 的测试受测试执行时序漂移影响（setup → trigger 有微秒级延迟，实际 stuckMs 会略大），
    // 改成"明显在阈值内"，留 100ms buffer 保稳定。
    @Test
    void scanner_justUnderThreshold_doesNotRepublish() {
        setupBankruptPositionWithStuckCtx(LiquidationState.WAIT_IF_EXECUTION,
                System.currentTimeMillis() - 4900L);

        le.triggerOnce();

        assertTrue(published.isEmpty(), "stuckMs < 阈值（=4900ms）应被视为正常推进中，不 republish");
    }

    // 边界（上方 100ms）：刚超阈值 5100ms 必须 republish——同 6000ms 等价但更紧贴边界。
    @Test
    void scanner_justOverThreshold_republishes() {
        setupBankruptPositionWithStuckCtx(LiquidationState.WAIT_IF_EXECUTION,
                System.currentTimeMillis() - 5100L);

        le.triggerOnce();

        assertTrue(!published.isEmpty(), "stuckMs > 阈值（=5100ms）应触发 republish");
        assertInstanceOf(ApiIFTakeOver.class, published.get(0));
    }

    @Test
    void scanner_ctxNull_doesNotRepublish() {
        // ctx=null 表示从未进入 强平流程；scanner 不应 republish
        UserProfile up = newUser();
        SymbolPositionRecord pos = newPosition();
        pos.liquidationCtx = null;
        up.positions.put(SYMBOL, pos);

        le.triggerOnce();

        assertTrue(published.isEmpty());
    }

    // ============================ 幂等门 ============================

    @Test
    void illegalJump_ifTakeoverOnNullCtx_skipped() {
        // 上一轮 强平流程 已闭环（ctx=null）后 scanner 误重发 IF_TAKEOVER：
        // 没有进行中的 强平流程，IF 是非法跳跃，apply 路径应 skip 且不破坏状态
        SymbolPositionRecord pos = newPosition();
        pos.liquidationCtx = null;

        OrderCommand cmd = newCmd(OrderCommandType.IF_TAKEOVER, MatcherEventType.REJECT, 5L);
        le.nextLiquidationState(cmd, pos);

        assertNull(pos.liquidationCtx, "非法 IF 跳跃挡下后 ctx 仍为 null");
        assertTrue(published.isEmpty(), "非法 IF 跳跃挡下后不应 publish 任何 cmd");
    }

    @Test
    void illegalJump_adlOnNullCtx_skipped() {
        SymbolPositionRecord pos = newPosition();
        pos.liquidationCtx = null;

        OrderCommand cmd = newCmd(OrderCommandType.AUTO_DELEVERAGING, MatcherEventType.TRADE, 5L);
        le.nextLiquidationState(cmd, pos);

        assertNull(pos.liquidationCtx);
        assertTrue(published.isEmpty());
    }

    @Test
    void idempotenceGate_forceLiquidationOnWaitIfCtx_skipped() {
        // 旧 FORCE_LIQUIDATION 在 强平流程 推进到 WAIT_IF_EXECUTION 后才被 apply（错乱场景）
        SymbolPositionRecord pos = newPosition();
        LiquidationContext ctx = new LiquidationContext(100L, 5L, 7777L, System.currentTimeMillis());
        ctx.state = LiquidationState.WAIT_IF_EXECUTION;
        pos.liquidationCtx = ctx;

        OrderCommand cmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, MatcherEventType.REJECT, 5L);
        le.nextLiquidationState(cmd, pos);

        assertSame(LiquidationState.WAIT_IF_EXECUTION, pos.liquidationCtx.state,
                "幂等门应挡下迟到的 FORCE_LIQUIDATION，state 保持不变");
        assertTrue(published.isEmpty());
    }

    // ============================ lastTransitionAt 跟踪 ============================

    @Test
    void onMarketDone_advancesLastTransitionAt() {
        SymbolPositionRecord pos = newPosition();
        LiquidationContext ctx = new LiquidationContext(100L, 5L, 7777L, 1000L);
        ctx.state = LiquidationState.LIQUIDATING;
        pos.liquidationCtx = ctx;

        OrderCommand cmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, MatcherEventType.REJECT, 3L);
        cmd.timestamp = 5000L;
        le.nextLiquidationState(cmd, pos);

        assertEquals(5000L, pos.liquidationCtx.lastTransitionAt,
                "状态推进后 lastTransitionAt 应同步到 cmd.timestamp");
    }

    // FORCE 在 null ctx 上 bootstrap 一轮 强平流程，ctx 字段全部从 cmd 取。
    // 用 REJECT 让 onMarketDone 把 state 推到 WAIT_IF_EXECUTION（ctx 保留），方便断言。
    @Test
    void bootstrap_forceLiquidationOnNullCtx_createsCtxFromCmd() {
        SymbolPositionRecord pos = newPosition();
        pos.liquidationCtx = null;

        OrderCommand cmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, MatcherEventType.REJECT, 4L);
        cmd.orderId = 12345L;
        cmd.timestamp = 8888L;
        cmd.price = 99L;
        cmd.size = 4L;
        le.nextLiquidationState(cmd, pos);

        LiquidationContext ctx = pos.liquidationCtx;
        assertNotNull(ctx, "FORCE 在 null ctx 上应 bootstrap 新 ctx");
        assertSame(LiquidationState.WAIT_IF_EXECUTION, ctx.state, "REJECT → WAIT_IF_EXECUTION");
        assertEquals(99L, ctx.price, "ctx.price 应来自 cmd.price");
        assertEquals(12345L, ctx.originalOrderId, "ctx.originalOrderId 应来自 cmd.orderId");
        assertEquals(8888L, ctx.lastTransitionAt, "lastTransitionAt 来自 cmd.timestamp");
    }

    // ============================ helpers ============================

    private SymbolPositionRecord setupBankruptPositionWithStuckCtx(LiquidationState state, long lastTransitionAt) {
        UserProfile up = newUser();
        SymbolPositionRecord pos = newPosition();
        LiquidationContext ctx = new LiquidationContext(100L, 5L, 7777L, lastTransitionAt);
        ctx.state = state;
        pos.liquidationCtx = ctx;
        up.positions.put(SYMBOL, pos);
        return pos;
    }

    private UserProfile newUser() {
        userProfileService.addEmptyUserProfile(UID);
        return userProfileService.getUserProfile(UID);
    }

    private static SymbolPositionRecord newPosition() {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = UID;
        pos.symbol = SYMBOL;
        pos.direction = PositionDirection.LONG;
        pos.openVolume = 10;
        pos.marginMode = MarginMode.ISOLATED;
        return pos;
    }

    private static OrderCommand newCmd(OrderCommandType type, MatcherEventType firstEventType, long firstEventSize) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = type;
        cmd.price = 100L;
        cmd.size = firstEventSize;
        cmd.uid = UID;
        cmd.symbol = SYMBOL;
        cmd.orderId = 7777L;
        cmd.timestamp = 1000L;
        MatcherTradeEvent ev = new MatcherTradeEvent();
        ev.eventType = firstEventType;
        ev.size = firstEventSize;
        cmd.matcherEvent = ev;
        return cmd;
    }
}
