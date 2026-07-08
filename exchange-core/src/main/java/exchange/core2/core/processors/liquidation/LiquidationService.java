package exchange.core2.core.processors.liquidation;

import java.util.List;
import java.util.Objects;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.HashingUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 强平流程的核心 service——所有"被动操作"统一入口（state + computation）。
 *
 * <p>
 * 承担三类职责：
 * <ul>
 * <li><b>IF 资金池状态</b>：notionals (available/reserved) + IF 接管的 positions。 {@link WriteBytesMarshallable} 序列化进 raft
 * snapshot，跨节点强一致。</li>
 * <li><b>强平类 cmd orderId 派生</b>：FORCE / IF / ADL orderId 三个静态生成器，编码方案见类内注释。</li>
 * <li><b>ADL 候选构造</b>：{@link #computeProfitablePositionsBySymbol} R1 入口，按需从 raft-replicated
 * state（{@link #userProfileService} / {@link #lastPriceCache}）算本 shard 候选，跨节点确定。 评分静态方法 {@link #riskScore} /
 * {@link #unrealizedPnl} 给排序用。</li>
 * </ul>
 *
 * <p>
 * 字段分两类——
 * <ul>
 * <li><b>serialized state</b>（{@code final} + 进 snapshot）：{@code notionals} / {@code positions}</li>
 * <li><b>injected dependencies</b>（非 {@code final} + {@link #updateProvider} 注入）： providers +
 * {@code userProfileService} + {@code lastPriceCache}</li>
 * </ul>
 *
 * <p>
 * 每个 shard 一个 LiquidationService 实例。
 */
public class LiquidationService implements WriteBytesMarshallable, StateHash {

    // ===== serialized state（进 raft snapshot） =====

    // symbol -> IFNotional
    @Getter
    private final IntObjectHashMap<IFNotional> notionals;

    // symbol -> IFPosition
    // +symbol -> long; -symbol -> short
    @Getter
    private final IntObjectHashMap<IFPositionRecord> positions;

    // ===== injected dependencies（不进 snapshot，updateProvider 注入） =====

    private UserProfileService userProfileService;
    private SymbolSpecificationProvider symbolSpecificationProvider;
    private CurrencySpecificationProvider currencySpecificationProvider;
    private MutableIntObjectMap<LastPriceCacheRecord> lastPriceCache;

    public LiquidationService() {
        notionals = new IntObjectHashMap<>(1024);
        positions = new IntObjectHashMap<>(1024);
    }

    public LiquidationService(BytesIn bytes) {
        notionals = SerializationUtils.readIntHashMap(bytes, IFNotional::new);
        positions = SerializationUtils.readIntHashMap(bytes, IFPositionRecord::new);
    }

    public void updateProvider(UserProfileService userProfileService,
        SymbolSpecificationProvider symbolSpecificationProvider,
        CurrencySpecificationProvider currencySpecificationProvider,
        MutableIntObjectMap<LastPriceCacheRecord> lastPriceCache) {
        this.userProfileService = userProfileService;
        this.symbolSpecificationProvider = symbolSpecificationProvider;
        this.currencySpecificationProvider = currencySpecificationProvider;
        this.lastPriceCache = lastPriceCache;
    }

    // ============================================================================
    // IF 资金池操作
    // ============================================================================

    /** 将强平手续费计入 IF 可用资金池。 */
    public void creditLiquidationFee(int symbol, long notionalFee) {
        IFNotional notional = notionals.getIfAbsentPut(symbol, () -> new IFNotional(0, 0));
        notional.available += notionalFee;
    }

    /**
     * 外部充值 IF 可用资金池（admin 触发）。 入参已是 notional (size*price) 尺度，scale 换算由 RiskEngine 完成。 对账闭环依赖 RiskEngine 同一条命令内对
     * adjustments 做反向记账。
     */
    public void depositToInsuranceFund(int symbol, long notionalAmount) {
        IFNotional notional = notionals.getIfAbsentPut(symbol, () -> new IFNotional(0, 0));
        notional.available += notionalAmount;
    }

    /**
     * IF_WITHDRAW 支持：从 available 扣，含非负校验。只扣 available，不动 reserved
     * （reserved 是正在保护某笔强平的预冻结部分，运营不能拿走）。
     * 返回 true = 扣账成功，false = notional 不存在或 available 不足以覆盖。
     */
    public boolean withdrawFromInsuranceFund(int symbol, long notionalAmount) {
        IFNotional notional = notionals.get(symbol);
        if (notional == null || notional.available < notionalAmount) {
            return false;
        }
        notional.available -= notionalAmount;
        return true;
    }

    /** R1：预冻结 IF 可用名义金额。 */
    public long reserveIFNotional(int symbol, long requestSize, long price) {
        IFNotional notional = notionals.getIfAbsentPut(symbol, () -> new IFNotional(0, 0));
        long available = notional.available - notional.reserved;
        long needed = Math.multiplyExact(requestSize, price);
        long canCover = Math.min(available, needed);
        notional.reserved += canCover;
        return canCover;
    }

    /** R2：释放 R1 预冻结的名义金额。 */
    public void releaseReservedIFNotional(int symbol, long reservedNotional) {
        IFNotional notional = notionals.get(symbol);
        notional.reserved -= reservedNotional;
    }

    /** R2：IF 接管仓位。 */
    public void acceptIFPosition(int symbol, PositionDirection direction, long size, long price) {
        IFNotional notional = notionals.get(symbol);
        long spend = Math.multiplyExact(size, price);
        notional.available -= spend;
        IFPositionRecord position = positions.getIfAbsentPut(direction.getMultiplier() * symbol,
            () -> new IFPositionRecord(symbol, direction, 0, 0));
        position.openVolume += size;
        position.openPriceSum += spend;
    }

    public void reset() {
        notionals.clear();
        positions.clear();
    }

    @Override
    public int stateHash() {
        return Objects.hash(HashingUtils.stateHash(notionals), HashingUtils.stateHash(positions));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        SerializationUtils.marshallIntHashMap(notionals, bytes);
        SerializationUtils.marshallIntHashMap(positions, bytes);
    }

    // ============================================================================
    // 强平类 cmd orderId 编码（FORCE 是根，IF / ADL 都从它派生）
    //
    // FORCE_LIQUIDATION (generateLiquidationOrderId):
    // | 63........32 | 31..........12 | 11 | 10....0 |
    // | symbol | uid hash 20 |side| ts 11 | side 区分 LONG/SHORT
    // （HEDGE 模式同 symbol 同 uid 同秒两侧并发破产防撞）
    // ts 11 bit ≈ 2048s ≈ 34min 内 (symbol,uid,side) 唯一
    //
    // IF_TAKEOVER (generateIFOrderId, 从 FORCE 派生):
    // | 63......56 | 55.........................0 |
    // | 'I' 0x49 | FORCE orderId 低 56 位 | 审计回溯：IF 低 56 ≡ FORCE 低 56
    //
    // AUTO_DELEVERAGING (generateADLOrderId, 从 FORCE 派生):
    // | 63......56 | 55.........................0 |
    // | 'A' 0x41 | FORCE orderId 低 56 位 | 同 IF 完全对称
    //
    // 注意：派生丢 FORCE 高 8 位 symbol。symbol < 2^24 (≈16M) 时无损，实际场景成立。
    // ============================================================================

    public static boolean isLiquidationOrderId(long orderId, int symbol, long uid) {
        long expectedSymbol = (orderId >>> 32); // 高 32 位
        if (expectedSymbol != symbol)
            return false;
        long expectedUidHash = (uid * 31 + 17) & 0xFFFFF;
        long actualUidHash = (orderId >>> 12) & 0xFFFFF; // bit 12-31
        return expectedUidHash == actualUidHash;
    }

    public static long generateLiquidationOrderId(SymbolPositionRecord pos) {
        long uidHash = (pos.uid * 31 + 17) & 0xFFFFF; // 20 bit
        long sideBit = (pos.direction == PositionDirection.SHORT) ? 1L : 0L;
        long tsPart = (System.currentTimeMillis() / 1000) & 0x7FF; // 11 bit
        return ((long)pos.symbol << 32) | (uidHash << 12) | (sideBit << 11) | tsPart;
    }

    public static long generateIFOrderId(long liquidationOrderId) {
        long ifOrderTag = 0x49L; // 'I'
        return (ifOrderTag << 56) | (liquidationOrderId & 0x00FFFFFFFFFFFFFFL);
    }

    public static long generateADLOrderId(long liquidationOrderId) {
        long adlOrderTag = 0x41L; // 'A'
        return (adlOrderTag << 56) | (liquidationOrderId & 0x00FFFFFFFFFFFFFFL);
    }

    // ============================================================================
    // ADL 评分（静态 pure function，给排序用）
    // ============================================================================

    public static long unrealizedPnl(SymbolPositionRecord pos, long bankruptcyPrice) {
        int sign = pos.direction.getMultiplier();
        long notional = saturatingMultiply(bankruptcyPrice, pos.openVolume);
        return saturatingMultiply((long)sign, notional - pos.openPriceSum);
    }

    public static long riskScore(SymbolPositionRecord pos, long bankruptcyPrice) {
        int sign = pos.direction.getMultiplier();
        // 仅用于 ADL 排序，各步骤均用饱和乘法（溢出截断会翻转符号导致排序反转）
        long notional = saturatingMultiply(bankruptcyPrice, pos.openVolume);
        long unrealizedPnl = saturatingMultiply((long)sign, notional - pos.openPriceSum);
        long actualLeverage = pos.openPriceSum / pos.openInitMarginSum;
        return saturatingMultiply(saturatingMultiply(actualLeverage, unrealizedPnl), pos.adlEligibility);
    }

    private static long saturatingMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return ((a ^ b) < 0) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    // ============================================================================
    // ADL 候选构造（R1 ADL apply 时调用）
    // ============================================================================

    /**
     * R1 调用——按需算本 shard 全部 profitable positions（symbol → list）。
     *
     * <p>
     * 跟 scanner 破产检查同一份 raft state（userProfileService），但只算 ADL 候选， 不做 scanner 的副作用（破产检查、stuck recovery、warning event）。
     *
     * <p>
     * 为什么 R1 不用 scanner 维护的 cache：scanner 只在 leader 端跑，follower 端 cache 永远空 → 同条 ADL cmd 在 leader/follower 上 R1 算出不同
     * candidates → ADL apply 结果发散。 改成 apply 时按需从 raft-replicated state 算，跨节点确定。
     */
    public IntObjectHashMap<MutableList<SymbolPositionRecord>> computeProfitablePositionsBySymbol() {
        IntObjectHashMap<MutableList<SymbolPositionRecord>> result = IntObjectHashMap.newMap();
        userProfileService.getUserProfiles().forEachValue(userProfile -> {
            if (userProfile == null)
                return;
            IntObjectHashMap<List<SymbolPositionRecord>> crossByCurrency = IntObjectHashMap.newMap();
            userProfile.positions.forEachValue(position -> {
                if (position == null || position.openVolume == 0)
                    return;
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                if (spec == null || !SymbolType.isFuturesContract(spec.type))
                    return;
                if (lastPriceCache.get(position.symbol) == null)
                    return;
                if (position.marginMode == MarginMode.ISOLATED) {
                    // ISOLATED：单仓位独立结算，没有用户级 gating——浮盈 > 0 直接入选
                    addProfitablePosition(position, result);
                } else {
                    crossByCurrency.getIfAbsentPut(spec.quoteCurrency, FastList.newList()).add(position);
                }
            });
            // CROSS：每个 currency 走一遍聚合 + 用户级 gating + factor + 入选
            crossByCurrency.forEachKeyValue(
                (currency, records) -> addCrossPositionsIfUserSafe(userProfile, currency, records, result));
        });
        return result;
    }

    /**
     * Cross 用户单 currency 的 ADL 候选构造——聚合 + 用户级 gating + factor + 入选 + 写 adlEligibility 一次性完成。
     *
     * <p>
     * Gating（账户必须足够安全且净盈利才有资格被 ADL 吃）：
     * <ul>
     * <li>{@code totalProfit > 0}（账户在本 currency 净盈利）</li>
     * <li>{@code equity >= 1.2 × totalMaintenance}（离强平线还有 20%+ 余量）</li>
     * </ul>
     *
     * <p>
     * factor 语义：账户离强平线越远 factor 越大，ADL 排序时优先被吃。clamp 到 [0, 100]。
     */
    private void addCrossPositionsIfUserSafe(UserProfile userProfile, int currency, List<SymbolPositionRecord> records,
        IntObjectHashMap<MutableList<SymbolPositionRecord>> out) {
        // ===== 1. 聚合本 currency 下 totalProfit + totalMaintenance（已 currency-scale 化）=====
        CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        long totalProfit = 0;
        long totalMaintenance = 0;
        for (SymbolPositionRecord position : records) {
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
            LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
            long m = position.calculateMaintenanceMargin(spec, priceRecord);
            if (m == 0)
                continue;
            long p = position.estimatePnl(priceRecord);
            totalProfit += CoreArithmeticUtils.sizePriceToCurrencyScale(p, spec, currencySpec);
            totalMaintenance += CoreArithmeticUtils.sizePriceToCurrencyScale(m, spec, currencySpec);
        }
        // ===== 2. 用户级 gating =====
        if (totalMaintenance <= 0 || totalProfit <= 0)
            return;
        long balance = userProfile.accounts.get(currency) - userProfile.exchangeLocked.get(currency);
        long equity = balance + totalProfit;
        long warningThreshold = Math.multiplyExact(totalMaintenance, 6) / 5;
        if (equity < warningThreshold)
            return;
        // ===== 3. factor + 入选 =====
        long factor = Math.max(0, Math.min(Math.multiplyExact(equity - totalMaintenance, 100) / totalMaintenance, 100));
        for (SymbolPositionRecord position : records) {
            if (addProfitablePosition(position, out)) {
                position.adlEligibility = factor;
            }
        }
    }

    /**
     * 单仓位入选原语（ISOLATED 直接调，CROSS 在 user-level gating 之后逐仓调）—— 按 mark price 判一个仓位是否盈利，盈利则加入 {@code out}，并把"是否加入"通过返回值告诉
     * caller。
     */
    private boolean addProfitablePosition(SymbolPositionRecord position,
        IntObjectHashMap<MutableList<SymbolPositionRecord>> out) {
        LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
        if (priceRecord == null)
            return false;
        if (position.estimateUnrealizedProfit(priceRecord) <= 0)
            return false;
        out.getIfAbsentPut(position.symbol, FastList::new).add(position);
        return true;
    }

    // ============================================================================
    // 嵌套数据类型
    // ============================================================================

    @AllArgsConstructor
    public static final class IFNotional implements WriteBytesMarshallable, StateHash {
        public long available;
        public long reserved;

        public IFNotional(BytesIn bytes) {
            available = bytes.readLong();
            reserved = bytes.readLong();
        }

        @Override
        public int stateHash() {
            return Objects.hash(available, reserved);
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeLong(available);
            bytes.writeLong(reserved);
        }
    }

    @AllArgsConstructor
    public static final class IFPositionRecord implements WriteBytesMarshallable, StateHash {
        public int symbol;
        public PositionDirection direction;
        public long openVolume;
        public long openPriceSum;

        public IFPositionRecord(BytesIn bytes) {
            symbol = bytes.readInt();
            direction = PositionDirection.of(bytes.readByte());
            openVolume = bytes.readLong();
            openPriceSum = bytes.readLong();
        }

        @Override
        public int stateHash() {
            // direction 是 enum：用 multiplier (已是 int) 保持跨 JVM 稳定。
            return Objects.hash(symbol, direction.getMultiplier(), openVolume, openPriceSum);
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeInt(symbol);
            bytes.writeByte((byte)direction.getMultiplier());
            bytes.writeLong(openVolume);
            bytes.writeLong(openPriceSum);
        }
    }
}
