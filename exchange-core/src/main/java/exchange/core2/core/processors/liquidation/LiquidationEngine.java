package exchange.core2.core.processors.liquidation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.set.MultiReaderSet;
import org.eclipse.collections.api.tuple.primitive.LongObjectPair;
import org.eclipse.collections.impl.factory.Sets;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectLongHashMap;
import org.eclipse.collections.impl.tuple.primitive.PrimitiveTuples;

import exchange.core2.core.common.BoundedLongDedupSet;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.api.ApiAutoDeleveraging;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiIFTakeOver;
import exchange.core2.core.common.api.ApiLiquidationOrder;
import exchange.core2.core.common.api.ApiSystemLiquidationNotify;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.FundEventsHelper;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationContext.LiquidationState;
import exchange.core2.core.processors.loan.LoanGlobalConfig;
import exchange.core2.core.processors.loan.LoanLiquidationEngine;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.processors.loan.rate.FixedRateModel;
import exchange.core2.core.processors.loan.rate.FloatingRateModel;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 强平引擎。两条互相独立的路径：
 *
 * <p>
 * <b>Scanner 路径</b>（off-lane，{@link SimpleScheduledService} 每 2 秒触发）：扫描所有用户的持仓，发现破产则 publish
 * {@code FORCE_LIQUIDATION}；发现强平流程卡在某阶段则 republish 对应阶段 cmd。Scanner 不修改任何 replicated state—— 不写 ctx，不写
 * lastTransitionAt——只读判断 + publish。
 *
 * <p>
 * <b>Apply 路径</b>（on-lane，raft cmd 落地后）：{@link #nextLiquidationState} 由 RiskEngine 在强平类 cmd （{@code FORCE_LIQUIDATION}
 * / {@code IF_TAKEOVER} / {@code AUTO_DELEVERAGING}）apply 完成后调用。 推进强平流程状态机（LIQUIDATING → WAIT_IF_EXECUTION →
 * WAIT_ADL_EXECUTION → 闭环置 null），并 publish 后续阶段 cmd。<b>所有 {@link LiquidationContext} 写入唯一入口都在这里。</b>
 *
 * <p>
 * <b>In-flight 去重</b>：{@link #inFlightLiquidationCmd} 在 leader 端覆盖"已 publish 但未 apply"这段空窗，通过
 * {@link LiquidationCmdPublisher} 的 onApplied 回调由 publisher 自动清理（包括 raft 失败路径，失败也触发 onApplied， 避免死值）。Failover 时 set
 * 跟进程同生死，新 leader 从空集合起步，已 apply 的 ctx 通过 raft 已复制到位。
 */
@Slf4j
@Getter
public final class LiquidationEngine extends SimpleScheduledService {
    private final int shardId;
    private final long stuckThresholdMs;
    private final FundEventsHelper eventsHelper;
    @Setter
    private boolean insuranceFundEnabled = true;
    private SymbolSpecificationProvider symbolSpecificationProvider;
    private CurrencySpecificationProvider currencySpecificationProvider;
    private UserProfileService userProfileService;
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
    private LoanService loanService;
    @Setter
    private LiquidationCmdPublisher liquidationCmdPublisher;
    private LoanLiquidationEngine loanLiquidationEngine;

    private final MultiReaderSet<SymbolPositionRecord> inFlightLiquidationCmd = Sets.multiReader.empty();
    private final LongObjectHashMap<IntObjectHashMap<ObjectLongHashMap<SymbolPositionRecord>>> tickBpMarginBaseCache =
        new LongObjectHashMap<>();

    public LiquidationEngine(Supplier<FundEvent> eventSupplier, int shardId,
        ExchangeConfiguration exchangeConfiguration) {
        super(Long.parseLong(System.getProperty("raftexchange.liquidation.interval", "2")), TimeUnit.SECONDS,
            exchangeConfiguration.getPerformanceCfg().getLiquidationThreadFactory());
        this.shardId = shardId;
        this.stuckThresholdMs = Long.parseLong(System.getProperty("raftexchange.liquidation.stuckThresholdMs", "5000"));
        this.eventsHelper = new FundEventsHelper(eventSupplier, shardId);
    }

    public void updateProvider(SymbolSpecificationProvider symbolSpecProvider,
        CurrencySpecificationProvider currencySpecProvider, UserProfileService userService,
        IntObjectHashMap<LastPriceCacheRecord> lastPriceService, LoanService loanSvc) {
        symbolSpecificationProvider = symbolSpecProvider;
        currencySpecificationProvider = currencySpecProvider;
        userProfileService = userService;
        lastPriceCache = lastPriceService;
        eventsHelper.setSymbolSpecificationProvider(symbolSpecificationProvider);
        eventsHelper.setCurrencySpecificationProvider(currencySpecificationProvider);
        eventsHelper.setUserProfileService(userProfileService);
        eventsHelper.setLastPriceCache(lastPriceCache);
        this.loanService = loanSvc;
        this.loanLiquidationEngine = new LoanLiquidationEngine(this);
    }

    // ============================== 生命周期 ==============================

    @Override
    public synchronized void start() {
        Objects.requireNonNull(liquidationCmdPublisher,
            "liquidationCmdPublisher must be set before LiquidationEngine.start()");
        super.start();
    }

    @Override
    protected void runOneIteration() throws Exception {
        log.debug("Checking liquidation for shard {}", shardId);
        try {
            checkLiquidations();
        } catch (Throwable e) {
            log.error("Error during liquidation check for shard {}", shardId, e);
        }
    }

    public void triggerOnce() {
        try {
            log.info("Manual trigger: Checking liquidation for shard {}", shardId);
            checkLiquidations();
        } catch (Throwable e) {
            log.error("Manual trigger failed for shard {}", shardId, e);
        }
    }

    // ============================== Scanner 路径（off-lane） ==============================

    private void checkLiquidations() {
        long now = System.currentTimeMillis();
        tickBpMarginBaseCache.clear();
        userProfileService.getUserProfiles().forEachValue(userProfile -> {
            if (userProfile == null)
                return;
            MutableIntObjectMap<SymbolPositionRecord> positions = userProfile.positions.asUnmodifiable();
            IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency = IntObjectHashMap.newMap();
            positions.forEachValue(position -> {
                if (position == null || position.openVolume == 0) {
                    return;
                }
                // 卡住的强平流程检测先于破产检测：流程正在进行中的 position 不应被识别为"新破产"重启，
                // 否则会重启整轮强平引入额外 fee
                if (tryRepublishStuckLiquidation(position, now)) {
                    return;
                }
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                if (!SymbolType.isFuturesContract(spec.type)) {
                    return;
                }
                LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
                if (priceRecord == null) {
                    log.debug("No price record for symbol={}", position.symbol);
                    return;
                }
                // 逐仓直接破产检查；全仓按 currency 聚合后统一处理
                if (position.marginMode == MarginMode.ISOLATED) {
                    checkLiquidationIsolated(userProfile, spec, priceRecord, position);
                } else {
                    crossPositionsByCurrency.getIfAbsentPut(spec.quoteCurrency, FastList.newList()).add(position);
                }
            });
            checkLiquidationCross(userProfile, crossPositionsByCurrency);
            // loan 3-lane 二级 scanner：整块委托给 LoanLiquidationEngine（详见 loan.md §7.1）
            loanLiquidationEngine.check(userProfile);
        });
    }

    /**
     * 检测强平流程是否卡住（ctx 非 null 且超阈值未推进）并 republish 对应阶段命令。 返回 true 表示 position 处于强平流程中（无论是否 republish），caller 应跳过常规破产检测。
     */
    private boolean tryRepublishStuckLiquidation(SymbolPositionRecord position, long now) {
        LiquidationContext ctx = position.liquidationCtx;
        if (ctx == null) {
            return false;
        }
        long stuckMs = now - ctx.lastTransitionAt;
        if (stuckMs <= stuckThresholdMs) {
            return true;
        }
        if (inFlightLiquidationCmd.contains(position)) {
            // 已发过 republish，等 callback 清掉再判
            return true;
        }
        ApiCommand cmd = switch (ctx.state) {
            case WAIT_IF_EXECUTION -> buildIFCmd(position, ctx);
            case WAIT_ADL_EXECUTION -> buildADLCmd(position, ctx);
            case LIQUIDATING -> buildForceCmd(position, LiquidationService.generateLiquidationOrderId(position),
                ctx.price, ctx.size);
        };
        log.warn("Republish stuck liquidation cmd={} ctx.state={} uid={} symbol={} stuck_ms={}",
            cmd.getClass().getSimpleName(), ctx.state, position.uid, position.symbol, stuckMs);
        publishTracked(cmd, position);
        return true;
    }

    private void checkLiquidationIsolated(UserProfile userProfile, CoreSymbolSpecification spec,
        LastPriceCacheRecord priceRecord, SymbolPositionRecord position) {
        // equity = openInitMarginSum + 未实现盈亏 + extraMargin
        long profit = position.estimateUnrealizedProfit(priceRecord);
        long equity = position.openInitMarginSum + profit + position.extraMargin;
        long maintenanceMargin = position.calculateMaintenanceMargin(spec, priceRecord);
        // 预警阈值 = 1.2 × 维持保证金，提前提醒用户追加资金
        long warningThreshold = Math.multiplyExact(maintenanceMargin, 6) / 5;
        if (equity < maintenanceMargin) {
            long price = calculateBankruptcyPrice(position);
            long sizeToLiquidate = Math.min(position.openVolume,
                CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord));
            if (sizeToLiquidate > 0) {
                startLiquidationFlow(userProfile, position, price, sizeToLiquidate);
            }
        } else if (equity < warningThreshold) {
            sendWarningEvent(userProfile, position, equity, warningThreshold);
        }
    }

    private void checkLiquidationCross(UserProfile userProfile,
        IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency) {
        crossPositionsByCurrency.forEachKeyValue((currency, records) -> {
            CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
            long totalProfit = 0;
            long totalMaintenanceMargin = 0;
            List<LongObjectPair<SymbolPositionRecord>> riskPairs = FastList.newList(records.size());
            for (SymbolPositionRecord position : records) {
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
                long maintenance = position.calculateMaintenanceMargin(spec, priceRecord);
                if (maintenance == 0) {
                    continue;
                }
                long profit =
                    CoreArithmeticUtils.sizePriceToCurrencyScale(position.estimatePnl(priceRecord), spec, currencySpec);
                maintenance = CoreArithmeticUtils.sizePriceToCurrencyScale(maintenance, spec, currencySpec);
                totalProfit += profit;
                totalMaintenanceMargin += maintenance;
                // 每仓位 risk = (profit - maintenance) / maintenance，值越小风险越大
                long risk = Math.multiplyExact(profit - maintenance, 100) / maintenance;
                riskPairs.add(PrimitiveTuples.pair(risk, position));
            }
            // balance 剥离逐仓虚拟锁定 margin，避免 LP 触发时机偏晚，详见 calculateCrossAvailableCurrency
            long balance = userProfile.calculateCrossAvailable(currency, currencySpec,
                symbolSpecificationProvider::getSymbolSpecification);
            long equity = balance + totalProfit;
            long warningThreshold = Math.multiplyExact(totalMaintenanceMargin, 6) / 5;
            // ADL gating 不在这里维护——R1 在 ADL apply 时按需算 candidates，scanner 不需要缓存盈利仓位
            if (equity >= warningThreshold) {
                return;
            }
            riskPairs.sort(Comparator.comparingLong(LongObjectPair::getOne)); // 升序：risk 小的先平
            if (equity < totalMaintenanceMargin) {
                forceCrossLiquidation(userProfile, riskPairs, totalMaintenanceMargin - equity);
            } else {
                sendWarningEvent(userProfile, riskPairs.get(0).getTwo(), equity, warningThreshold);
            }
        });
    }

    private void forceCrossLiquidation(UserProfile userProfile,
        List<LongObjectPair<SymbolPositionRecord>> positionPairs, long deficit) {
        long marginReleased = 0;
        for (LongObjectPair<SymbolPositionRecord> pair : positionPairs) {
            if (marginReleased >= deficit)
                break;
            SymbolPositionRecord position = pair.getTwo();
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
            LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
            long price = calculateBankruptcyPrice(position);
            long sizeToLiquidate = Math.min(position.openVolume,
                CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord));
            if (sizeToLiquidate > 0) {
                // 假设能成交，先行更新释放量
                marginReleased +=
                    CoreArithmeticUtils.calculateDeficitAfterLiquidate(sizeToLiquidate, position, spec, priceRecord);
                startLiquidationFlow(userProfile, position, price, sizeToLiquidate);
            }
        }
    }

    private void startLiquidationFlow(UserProfile userProfile, SymbolPositionRecord position, long price, long size) {
        // 双 gate：raft 在飞 + 已 apply 进强平流程。卡住恢复由 scanner stuck-check 分支接管
        if (inFlightLiquidationCmd.contains(position) || position.liquidationCtx != null) {
            return;
        }
        // 限价 IOC，price 按市场价算，深度够就能成交；IOC 不进 orderBook
        long orderId = LiquidationService.generateLiquidationOrderId(position);
        publishTracked(buildForceCmd(position, orderId, price, size), position);

        FundEvent event = eventsHelper.sendLiquidationAlertEvent(orderId, position);
        publishUntracked(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
        log.debug("Liquidated: uid={} symbol={} size={} price={}", userProfile.uid, position.symbol, size, price);
    }

    private void sendWarningEvent(UserProfile userProfile, SymbolPositionRecord position, long equity,
        long warningThreshold) {
        FundEvent event = eventsHelper.sendMarginAlertEvent(position);
        publishUntracked(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
        log.debug("Margin call: uid={} symbol={} equity={} threshold={}", userProfile.uid, position.symbol, equity,
            warningThreshold);
    }

    /**
     * 破产价（BP）—— scanner 强平入口，作为 FORCE / IF / ADL 全流程复用的 {@code ctx.price} seed。
     *
     * <p>
     * 路径分派：
     * <ul>
     * <li>{@code ISOLATED} — 单仓 self-contained，marginBase = openInitMarginSum + extraMargin</li>
     * <li>{@code CROSS} — 账户级按 MM 占比分配 marginBase 后交给下游反解</li>
     * <li>{@code spec == null} 兜底 — 退 markPrice，保 FORCE 单能挂出去</li>
     * <li>{@code UP == null} 兜底 — 退 ISOLATED 公式，数量级仍在 EP 附近</li>
     * </ul>
     * 兜底不抛异常——scanner 每 2s 一 tick，异常状态若已修复下轮自然恢复正确算法。
     *
     * <p>
     * 输入全是 replicated state，跨节点 apply 到同 raft offset 算出同值——failover 后新 leader 从 {@code ctx.price} 读到的是同一值。
     */
    private long calculateBankruptcyPrice(SymbolPositionRecord pos) {
        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(pos.symbol);
        if (spec == null) {
            // spec 缺失兜底：退 markPrice 让 FORCE 单能挂出去，下轮 scanner tick 若 spec 恢复再算真 BP；
            // markPrice 也拿不到就退 0（IOC 单被 reject，位置本 tick 跳过，下轮再试）
            final LastPriceCacheRecord priceRecord = lastPriceCache.get(pos.symbol);
            final long mark = priceRecord != null ? priceRecord.markPrice : 0L;
            log.warn("BP fallback (spec missing): uid={} symbol={} → markPrice={}", pos.uid, pos.symbol, mark);
            return mark;
        }
        if (pos.marginMode == MarginMode.ISOLATED) {
            return pos.calculateBankruptcyPrice(spec);
        }
        final UserProfile up = userProfileService.getUserProfile(pos.uid);
        if (up == null) {
            return pos.calculateBankruptcyPrice(spec); // 兜底：UP 缺失退 ISOLATED 公式
        }
        final CoreCurrencySpecification currencySpec =
            currencySpecificationProvider.getCurrencySpecification(pos.currency);
        // tick-scoped 缓存：同 (uid, currency) 只算一次 marginBase 分配
        final long marginBaseCurrency = tickBpMarginBaseCache.getIfAbsentPut(pos.uid, IntObjectHashMap::new)
            .getIfAbsentPut(pos.currency, () -> calculateCrossBpMarginBaseAllocation(up, pos.currency, currencySpec))
            .get(pos);
        final long marginBase = CoreArithmeticUtils.currencyToSizePriceScale(marginBaseCurrency, spec, currencySpec);
        return pos.calculateBankruptcyPrice(spec, marginBase);
    }

    /**
     * 账户级 marginBalance 按 MM 占比分给同 currency 每个 CROSS 仓，返回每仓的 EP-基础 marginBase （currency scale），可直接喂给
     * {@link SymbolPositionRecord#calculateBankruptcyPrice(CoreSymbolSpecification, long)}。
     *
     * <p>
     * 分配方程（MP 基础）：
     * 
     * <pre>
     *   marginBalance = crossAvailable + Σ CROSS UPnL          （账户级）
     *   Σ MM          = Σ CROSS 仓各自的维持保证金                （账户级）
     *   allocated_i   = marginBalance × mm_i / Σ MM             （每仓按 MM 占比公平分账户余额）
     *   BP_i          = MP_i − allocated_i / (Q_i × side_i)
     * </pre>
     * 
     * 而 {@code calcBankruptcyPriceFromMarginBase} 用 EP 基础 {@code BP = EP − sign × marginBase / Q}， 代数换算：
     * 
     * <pre>
     *   marginBase_i = allocated_i − UPnL_i
     * </pre>
     *
     * <p>
     * 闭合不变式：{@code Σ marginBase_i = marginBalance − Σ UPnL_i = crossAvailable}——分配前后 账户可支配余额守恒（整型除法截断可能少几个单位，量纲不影响）。
     *
     * <p>
     * 单仓等价：{@code mm_i/ΣMM = 1} → allocated = marginBalance → marginBase = crossAvailable， 跟旧 4 参重载单仓语义代数一致；多仓时按 MM
     * 占比分。
     *
     * <p>
     * 边界：
     * <ul>
     * <li>{@code marginBalance < 0} — 沿用统一公式（allocated 为负 → marginBase 为负），未实现 "盈利仓/亏损仓分岔"，数学总账仍守恒</li>
     * <li>{@code Σ MM = 0} — 返回空 Map，caller {@code .get(pos)} 拿默认 0</li>
     * <li>{@code spec / price 缺失} — 该仓跳过，其他仓照分</li>
     * </ul>
     */
    private ObjectLongHashMap<SymbolPositionRecord> calculateCrossBpMarginBaseAllocation(UserProfile up, int currency,
        CoreCurrencySpecification currencySpec) {
        final ObjectLongHashMap<SymbolPositionRecord> upnlByPos = new ObjectLongHashMap<>();
        final ObjectLongHashMap<SymbolPositionRecord> mmByPos = new ObjectLongHashMap<>();
        long totalUpnl = 0;
        long totalMm = 0;
        for (SymbolPositionRecord p : up.positions) {
            if (p.marginMode != MarginMode.CROSS || p.currency != currency)
                continue;
            final CoreSymbolSpecification pSpec = symbolSpecificationProvider.getSymbolSpecification(p.symbol);
            final LastPriceCacheRecord pPrice = lastPriceCache.get(p.symbol);
            if (pSpec == null || pPrice == null)
                continue;
            final long pnl = CoreArithmeticUtils.sizePriceToCurrencyScale(p.estimatePnl(pPrice), pSpec, currencySpec);
            final long mm = CoreArithmeticUtils.sizePriceToCurrencyScale(p.calculateMaintenanceMargin(pSpec, pPrice),
                pSpec, currencySpec);
            upnlByPos.put(p, pnl);
            mmByPos.put(p, mm);
            totalUpnl += pnl;
            totalMm += mm;
        }
        final ObjectLongHashMap<SymbolPositionRecord> marginBaseByPos = new ObjectLongHashMap<>(mmByPos.size());
        if (totalMm == 0) {
            return marginBaseByPos;
        }
        final long totalMmFinal = totalMm;
        final long marginBalance = Math.addExact(
            up.calculateCrossAvailable(currency, currencySpec, symbolSpecificationProvider::getSymbolSpecification),
            totalUpnl);
        mmByPos.forEachKeyValue((pos, mm) -> {
            final long allocated = Math.multiplyExact(marginBalance, mm) / totalMmFinal;
            marginBaseByPos.put(pos, Math.subtractExact(allocated, upnlByPos.get(pos)));
        });
        return marginBaseByPos;
    }

    // ============================== Apply 路径（on-lane） ==============================

    /**
     * 由 RiskEngine 在强平类 cmd 的 apply 完成后调用。推进状态机并 publish 后续阶段 cmd。 所有 {@link LiquidationContext} 写入唯一入口都在这里——scanner
     * 路径只读不写。
     */
    public void nextLiquidationState(OrderCommand cmd, SymbolPositionRecord pos) {
        // 任何强平类 cmd 的 apply 都顺手延寿 lastTransitionAt，卡住判定有确定性时钟
        if (pos.liquidationCtx != null) {
            pos.liquidationCtx.lastTransitionAt = cmd.timestamp;
        }
        if (pos.liquidationCtx == null) {
            // 没有进行中的强平流程：仅 FORCE 能开启新一轮；IF/ADL 是非法跳跃
            if (cmd.command != OrderCommandType.FORCE_LIQUIDATION) {
                log.warn("Illegal liquidation cmd={} on null ctx: skip uid={} symbol={}", cmd.command, pos.uid,
                    pos.symbol);
                return;
            }
            pos.liquidationCtx = new LiquidationContext(cmd.price, cmd.size, cmd.orderId, cmd.timestamp);
        } else {
            // 有进行中的强平流程：cmd 必须命中匹配的阶段，否则当重复 cmd 丢
            LiquidationState expected = switch (cmd.command) {
                case FORCE_LIQUIDATION -> LiquidationState.LIQUIDATING;
                case IF_TAKEOVER -> LiquidationState.WAIT_IF_EXECUTION;
                case AUTO_DELEVERAGING -> LiquidationState.WAIT_ADL_EXECUTION;
                default -> null;
            };
            if (pos.liquidationCtx.state != expected) {
                log.warn("Duplicate liquidation cmd={} ctx.state={} expected={} uid={} symbol={}: skip", cmd.command,
                    pos.liquidationCtx.state, expected, pos.uid, pos.symbol);
                return;
            }
        }
        switch (cmd.command) {
            case FORCE_LIQUIDATION -> onMarketDone(cmd, pos);
            case IF_TAKEOVER -> onIFTakeoverDone(cmd, pos);
            case AUTO_DELEVERAGING -> onADLDone(pos);
            default -> {
            }
        }
    }

    /** FORCE 撮合结果：全吃满 → 闭环置 null；有剩余 → IF 接（或绕过 IF 直接走 ADL）。 */
    private void onMarketDone(OrderCommand cmd, SymbolPositionRecord pos) {
        LiquidationContext ctx = pos.liquidationCtx;
        MatcherTradeEvent firstEvent = cmd.matcherEvent;
        if (firstEvent.eventType != MatcherEventType.REJECT) {
            pos.liquidationCtx = null;
            return;
        }
        ctx.size = firstEvent.size;
        if (isInsuranceFundEnabled()) {
            ctx.state = LiquidationState.WAIT_IF_EXECUTION;
            log.warn("Publish IF takeover: uid={} symbol={} size={} price={}", pos.uid, pos.symbol, ctx.size,
                ctx.price);
            publishTracked(buildIFCmd(pos, ctx), pos);
        } else {
            enterAdlPhase(pos, ctx);
        }
    }

    /** IF 撮合结果：接成 → 闭环置 null；REJECT → 走 ADL。 */
    private void onIFTakeoverDone(OrderCommand cmd, SymbolPositionRecord pos) {
        LiquidationContext ctx = pos.liquidationCtx;
        if (cmd.matcherEvent.eventType != MatcherEventType.REJECT) {
            pos.liquidationCtx = null;
            return;
        }
        enterAdlPhase(pos, ctx);
    }

    private void enterAdlPhase(SymbolPositionRecord pos, LiquidationContext ctx) {
        ctx.state = LiquidationState.WAIT_ADL_EXECUTION;
        log.warn("Publish ADL: uid={} symbol={} size={} price={}", pos.uid, pos.symbol, ctx.size, ctx.price);
        publishTracked(buildADLCmd(pos, ctx), pos);
    }

    /** ADL 完成：整轮强平流程闭环，ctx 置 null。 */
    private void onADLDone(SymbolPositionRecord pos) {
        pos.liquidationCtx = null;
    }

    // ============================== Cmd/action helper ==============================

    // FORCE 用 taker（平仓）视角，IF/ADL 用 counterparty（接管）视角——方向相反
    private static OrderAction takerActionFor(PositionDirection direction) {
        return direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID;
    }

    private static OrderAction counterpartyActionFor(PositionDirection direction) {
        return direction == PositionDirection.LONG ? OrderAction.BID : OrderAction.ASK;
    }

    private ApiLiquidationOrder buildForceCmd(SymbolPositionRecord pos, long orderId, long price, long size) {
        return ApiLiquidationOrder.builder().orderType(OrderType.IOC).orderId(orderId).uid(pos.uid).symbol(pos.symbol)
            .price(price).size(size).action(takerActionFor(pos.direction)).build();
    }

    private ApiIFTakeOver buildIFCmd(SymbolPositionRecord pos, LiquidationContext ctx) {
        return ApiIFTakeOver.builder().orderId(LiquidationService.generateIFOrderId(ctx.originalOrderId)).uid(pos.uid)
            .symbol(pos.symbol).action(counterpartyActionFor(pos.direction)).size(ctx.size).price(ctx.price).build();
    }

    private ApiAutoDeleveraging buildADLCmd(SymbolPositionRecord pos, LiquidationContext ctx) {
        return ApiAutoDeleveraging.builder().orderId(LiquidationService.generateADLOrderId(ctx.originalOrderId))
            .uid(pos.uid).symbol(pos.symbol).action(counterpartyActionFor(pos.direction)).size(ctx.size)
            .price(ctx.price).build();
    }

    // ============================== Publisher 辅助 ==============================

    private void publishTracked(ApiCommand cmd, SymbolPositionRecord pos) {
        inFlightLiquidationCmd.add(pos);
        try {
            liquidationCmdPublisher.publish(cmd, () -> inFlightLiquidationCmd.remove(pos));
        } catch (Throwable t) {
            inFlightLiquidationCmd.remove(pos);
            throw t;
        }
    }

    private void publishUntracked(ApiCommand cmd) {
        liquidationCmdPublisher.publish(cmd, null);
    }
}
