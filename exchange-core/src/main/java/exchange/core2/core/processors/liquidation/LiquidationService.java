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
 * 强平流程的核心 service，每个分片一个实例。承担三类职责：
 * <ul>
 * <li><b>IF 保险基金池状态</b>：notionals（available / reserved）+ IF 接管的 positions。
 * 经 {@link WriteBytesMarshallable} 序列化进 raft snapshot，跨节点强一致。</li>
 * <li><b>强平命令 orderId 编码</b>：FORCE 是根，IF / ADL 从它派生；位布局见下方常量区注释。</li>
 * <li><b>ADL 候选构造</b>：{@link #computeProfitablePositionsBySymbol} 在 apply 时按需从复制态
 * （{@link #userProfileService} / {@link #lastPriceCache}）计算，跨节点确定；评分 {@link #riskScore} /
 * {@link #unrealizedPnl} 供排序用。</li>
 * </ul>
 * <p>
 * 字段分两类：<b>序列化状态</b>（{@code final}、进 snapshot）{@code notionals} / {@code positions}；
 * <b>注入依赖</b>（{@link #updateProvider} 注入，不进 snapshot）providers + {@code userProfileService} +
 * {@code lastPriceCache}。
 */
public class LiquidationService implements WriteBytesMarshallable, StateHash {

    // ===== 序列化状态（进 raft snapshot） =====

    // symbol -> IF 名义资金（available / reserved）
    @Getter
    private final IntObjectHashMap<IFNotional> notionals;

    // key = direction.multiplier * symbol：+symbol 为多头仓，-symbol 为空头仓
    @Getter
    private final IntObjectHashMap<IFPositionRecord> positions;

    // ===== 注入依赖（不进 snapshot，由 updateProvider 注入） =====

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
     * IF_WITHDRAW 支持：从 available 扣，含非负校验。只扣 available，不动 reserved （reserved 是正在保护某笔强平的预冻结部分，运营不能拿走）。 返回 true =
     * 扣账成功，false = notional 不存在或 available 不足以覆盖。
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
    // 强平命令 orderId 编码。FORCE 是根，IF / ADL 从它派生（保留低 56 位以便审计回溯）。
    //
    // FORCE_LIQUIDATION (generateLiquidationOrderId)，64 位布局：
    //   bit 63..32 : symbol       (高 32)
    //   bit 31..12 : uid hash     (20 bit)
    //   bit 11     : side bit     (LONG=0 / SHORT=1；HEDGE 下同 symbol 同 uid 同秒两侧防撞)
    //   bit 10..0  : 秒级 ts      (11 bit ≈ 2048s ≈ 34min 内 (symbol,uid,side) 唯一)
    //
    // IF_TAKEOVER  (generateIFOrderId)  : 'I' 0x49 << 56 | FORCE 低 56 位
    // AUTO_DELEVERAGING (generateADLOrderId) : 'A' 0x41 << 56 | FORCE 低 56 位
    //   两者完全对称：高 8 位是标签，低 56 位 ≡ 对应 FORCE 的低 56 位。
    //
    // 注意：派生丢弃 FORCE 高 8 位的 symbol。symbol < 2^24 (≈16M) 时无损，实际场景成立。
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
    // ADL 评分（静态纯函数，供候选排序用）
    // ============================================================================

    /** 按破产价估算浮动盈亏。饱和乘法防止溢出翻转符号（见 {@link #saturatingMultiply}）。 */
    public static long unrealizedPnl(SymbolPositionRecord pos, long bankruptcyPrice) {
        int sign = pos.direction.getMultiplier();
        long notional = saturatingMultiply(bankruptcyPrice, pos.openVolume);
        return saturatingMultiply((long)sign, notional - pos.openPriceSum);
    }

    /** ADL 排序键：浮盈 × 实际杠杆 × 资格因子，越大越优先被摊派。全程饱和乘法。 */
    public static long riskScore(SymbolPositionRecord pos, long bankruptcyPrice) {
        int sign = pos.direction.getMultiplier();
        long notional = saturatingMultiply(bankruptcyPrice, pos.openVolume);
        long unrealizedPnl = saturatingMultiply((long)sign, notional - pos.openPriceSum);
        long actualLeverage = pos.openPriceSum / pos.openInitMarginSum;
        return saturatingMultiply(saturatingMultiply(actualLeverage, unrealizedPnl), pos.adlEligibility);
    }

    /**
     * 饱和乘法：溢出时钳到 {@link Long#MAX_VALUE} / {@link Long#MIN_VALUE}（按符号）。
     * WHY：ADL 排序键若用普通乘法，溢出截断会翻转符号导致排序反转，饱和后仍保持单调。
     */
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
     * ADL 候选构造：apply 时按需算出本 shard 全部可被 ADL 摊派的仓位（symbol → list）。
     * <p>
     * WHY 按需算而非用缓存：ADL apply 必须在所有节点算出相同候选，否则结果发散。若用 leader-only
     * 维护的缓存，follower 侧为空，同一条 ADL 命令在 leader / follower 上会得到不同候选。改为每次从
     * raft 复制态（{@code userProfileService} / {@code lastPriceCache}）现算，保证跨节点确定。
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
