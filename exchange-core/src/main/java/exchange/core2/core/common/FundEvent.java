package exchange.core2.core.common;

import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * 资金事件类，用于记录现货和期货的资金及仓位变动。通过 FundEventType 区分。
 *
 * <h3>消费端如何计算"该 currency 真实持有的资产"</h3>
 *
 * <b>期货（同一 currency，跨所有 position 聚合）：</b>
 * <pre>
 *   真实持有 = free + locked
 *            + Σᵢ toCurrencyScale(positionᵢ.extraMargin)
 *            + Σᵢ toCurrencyScale(positionᵢ.profit)
 *
 *   其中 toCurrencyScale(x) = x × currencyScaleK / (baseScaleK × quoteScaleK)
 *   （等价于 {@code CoreArithmeticUtils.sizePriceToCurrencyScale}）
 * </pre>
 * 注意：
 *   - {@code free} / {@code locked} 已按 currency 跨所有 position 聚合（currencyScale 单位）。
 *   - {@code extraMargin} / {@code profit} 仅是本事件所属<b>单个 position</b> 的值（sizePriceScale 单位，
 *     sizePriceScale = baseScaleK × quoteScaleK）。消费端需按 position 维护状态、
 *     跨所有 position 累加后才能得到 currency 维度的总值。
 *   - {@code profit} 是"已实现但未 sweep 到 accounts" 的 PnL（funding fee 累积 + 部分平仓 PnL），
 *     在 {@code RiskEngine.removePositionRecord} 时才会落地到 accounts。
 *
 * <b>现货：</b>
 * <pre>
 *   真实持有 = free + locked
 * </pre>
 *   - 现货下单<b>不</b>从 accounts 扣减，而是把冻结额累计到 {@code UserProfile.exchangeLocked}。
 *   - {@code locked} 字段已包含现货挂单冻结（{@code calculateLocked} 内部含 {@code exchangeLocked}），
 *     消费端<b>不需要</b>再从订单簿聚合未成交挂单的 hold 量。
 *   - {@code free = accounts - locked}，{@code locked} = Σ(期货 position margin) + exchangeLocked（spot 冻结）。
 *   - 纯现货用户若有未成交挂单，{@code locked} 即等于 spot 挂单冻结额。
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FundEvent {
    public boolean processed; // 是否已处理

    // 基础字段
    public FundEventType eventType; // 事件类型
    public long orderId; // 订单 ID
    public long uid; // 用户 ID
    public int currency; // 变动货币
    public long currencyScaleK; // currency缩放系数（用于还原金额）
    public long free; // 该 currency 可用余额（= accounts - locked，currencyScale 单位）
    public long locked; // 该 currency 总冻结额（currencyScale 单位）=
                        //   Σ(期货 position 的 openInitMarginSum + pendingMargin + 潜在 fee)  +  exchangeLocked（现货挂单冻结）
                        // 注意：不含 extraMargin、不含 position.profit。

    // 期货使用字段（仓位字段同）
    public int symbol; // 交易对 ID
    public long baseScaleK; // 基础货币的缩放系数（用于还原size）
    public long quoteScaleK; // 计价货币的缩放系数（用于还原price）
    public PositionDirection direction; // 仓位方向
    public long openVolume; // 持仓数量
    public long openInitMarginSum; // 初始保证金总额
    public long openPriceSum; // 持仓总成本，openPriceSum/openVolume=平均持仓成本
    public long profit; // 该 position 已实现但<b>尚未 sweep 到 accounts</b> 的 PnL（funding fee 累积 + 部分平仓 PnL）。
                        // sizePriceScale 单位（=baseScaleK×quoteScaleK）。仅在 removePositionRecord 时才结算回 accounts。
    public long pendingSellSize; // 挂单卖出数量（未成交的卖单）
    public long pendingBuySize; // 挂单买入数量（未成交的买单）
    public long pendingSellAvgPrice; // 挂单卖出平均价格（未成交的卖单）
    public long pendingBuyAvgPrice; // 挂单买入平均价格（未成交的买单）
    public int leverage; // 杠杆倍数
    public MarginMode marginMode; // 模式：逐仓或全仓
    public long extraMargin; // 额外保证金（逐仓追加保证金）。sizePriceScale 单位（=baseScaleK×quoteScaleK），
                             // 与 currencyScale 不同，参与"真实持有"累加前需做单位换算（详见类注释）。
    // 仓位计算字段
    public long unrealizedProfit; // 未实现盈亏
    public long liquidationPrice; // 强平价格
    public long marginRatioScaleK; // 保证金率，维持保证金/资金占用*缩放系数。全仓下资金占用=当前币种余额+该币种总体未实现盈亏；逐仓下资金占用=开仓保证金+extraMargin
    // 额外字段
    public long markPrice; // 标记价格

    // ===== 现货借贷 loan 事件专用（详见 loan.md §9.5；仅 LOAN_MARGIN_CALL / LOAN_INTEREST_SETTLE / LOAN_BAD_DEBT_INCURRED 使用）=====
    // loanAmount 语义随 eventType 变化：
    //   LOAN_MARGIN_CALL         → 当前 LTV（bps）
    //   LOAN_INTEREST_SETTLE     → 本次结算的利息量（currencyScale）
    //   LOAN_BAD_DEBT_INCURRED   → 本次坏账的 principal + interest 合计（currencyScale，正值）
    public long loanAmount;
    // loanExtra 仅 LOAN_MARGIN_CALL 使用：预警阈值 bps；其他 loan 事件保 0。
    public long loanExtra;
    // 0 = Isolated，1 = Cross；Cross MARGIN_CALL 场景 orderId = 0（无具体 loanId），其他所有 loan 事件 orderId = loanId。
    public byte loanMode;

    public FundEvent nextEvent;

    /**
     * 事件类型枚举。 前五种为现货事件，后面为期货事件。
     */
    public enum FundEventType {
        // 现货事件
        DEPOSIT(1),         // 现货充值（free 增加）
        LOCKED(2),          // 现货下单成功冻结（accounts 不动；exchangeLocked +=，locked 字段相应增加，free 相应减少）
        TRANSFER(3),        // 现货撮合成交后资产互换（买方减少quote，加base）
        UNLOCKED(4),        // 订单取消或未成交释放（locked -> free）
        WITHDRAW(5),        // 现货提现（free 减少）
        // 期货事件
        LOCK_PENDING(6),     // 提交期货订单冻结初始保证金（pendingHold）（free -> locked）
        UNLOCK_PENDING(7),   // 未成交释放初始保证金（pendingHold 释放，locked -> free）
        OPEN_POSITION(8),    // 新增持仓记录（仅标记持仓信息）
        CLOSE_POSITION(9),   // 平仓：释放保证金 + 盈亏落地 + 手续费
        LIQUIDATION_CLOSE(10),     // 强平关仓
        LIQUIDATION_FEE(11),       // 强平费
        FUNDINGFEE_SETTLEMENT(12),// 资金费率结算
        PNL_SETTLEMENT(13),       // 交割合约结算
        // 补充保证金事件
        MARGIN_ADJUST(14),      // 逐仓追加补充保证金
        MARGIN_REFUND(15),      // 逐仓平仓返还补充保证金
        // IF
        IF_POSITION_CLOSE(16),    // IF 接管仓位平仓
        // ADL
        ADL_ORIGIN_CLOSE(17),    // ADL 中破产仓位平仓
        ADL_POSITION_CLOSE(18),  // ADL 中盈利仓位被减仓

        // 通知类事件
        MARGIN_ALERT(20),       // 通知追加保证金
        LIQUIDATION_ALERT(21),  // 通知强平单创建

        // 其他事件
        RESET_FEE(30),          // 重置手续费

        // 现货借贷 loan 事件（详见 loan.md §9.5）
        // 通道跟期货 MARGIN_ALERT / LIQUIDATION_ALERT 一样 leader-local ring-buffer bypass raft，best-effort。
        LOAN_MARGIN_CALL(40),        // Cross 账户级或 Isolated 单笔预警（LTV 达到 marginCall 阈值），节流 per-loanId/uid ≥ 5 min
        LOAN_INTEREST_SETTLE(41),    // 还款 / 强平时同步 emit，让下游把利息从 fees 桶里拆出作利息收入统计
        LOAN_BAD_DEBT_INCURRED(42);  // Underwater force-sell 时坏账入账；badDebt bucket 是权威真值，本事件用于审计回溯

        private final int code;

        FundEventType(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static FundEventType of(int code) {
            for (FundEventType type : values()) {
                if (type.code == code)
                    return type;
            }
            throw new IllegalArgumentException("Unknown FundEventType code: " + code);
        }
    }

    public static FundEvent createEventChain(int chainLength) {
        final FundEvent head = new FundEvent();
        FundEvent current = head;
        for (int i = 1; i < chainLength; i++) {
            FundEvent next = new FundEvent();
            current.nextEvent = next;
            current = next;
        }
        return head;
    }

    public void reset() {
        processed = false;
        eventType = null;
        orderId = 0;
        uid = 0;
        currency = 0;
        currencyScaleK = 0;
        free = 0;
        locked = 0;
        symbol = 0;
        baseScaleK = 0;
        quoteScaleK = 0;
        direction = PositionDirection.EMPTY;
        openVolume = 0;
        openInitMarginSum = 0;
        openPriceSum = 0;
        profit = 0;
        pendingSellSize = 0;
        pendingBuySize = 0;
        pendingSellAvgPrice = 0;
        pendingBuyAvgPrice = 0;
        leverage = 0;
        marginMode = MarginMode.ISOLATED;
        extraMargin = 0;
        unrealizedProfit = 0;
        liquidationPrice = 0;
        marginRatioScaleK = 0;
        markPrice = 0;
        loanAmount = 0;
        loanExtra = 0;
        loanMode = 0;
        nextEvent = null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(processed, eventType, orderId, uid, currency, currencyScaleK, free, locked, symbol, baseScaleK,
                quoteScaleK, direction, openVolume, openInitMarginSum, openPriceSum, profit, pendingSellSize, pendingBuySize,
                pendingSellAvgPrice, pendingBuyAvgPrice, leverage, marginMode, extraMargin, unrealizedProfit, liquidationPrice,
                marginRatioScaleK, markPrice, loanAmount, loanExtra, loanMode, nextEvent);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        FundEvent other = (FundEvent)obj;
        return processed == other.processed && eventType == other.eventType && orderId == other.orderId
            && uid == other.uid && currency == other.currency && currencyScaleK == other.currencyScaleK && free == other.free
            && locked == other.locked && symbol == other.symbol && baseScaleK == other.baseScaleK && quoteScaleK == other.quoteScaleK
            && direction == other.direction && openVolume == other.openVolume && openInitMarginSum == other.openInitMarginSum
            && openPriceSum == other.openPriceSum && profit == other.profit && pendingSellSize == other.pendingSellSize
            && pendingBuySize == other.pendingBuySize && pendingSellAvgPrice == other.pendingSellAvgPrice && pendingBuyAvgPrice == other.pendingBuyAvgPrice
            && leverage == other.leverage && marginMode == other.marginMode && extraMargin == other.extraMargin && unrealizedProfit == other.unrealizedProfit
            && liquidationPrice == other.liquidationPrice && marginRatioScaleK == other.marginRatioScaleK && markPrice == other.markPrice
            && loanAmount == other.loanAmount && loanExtra == other.loanExtra && loanMode == other.loanMode
            && ((nextEvent == null && other.nextEvent == null) || (nextEvent != null && nextEvent.equals(other.nextEvent)));
    }

    @Override
    public String toString() {
        return "FundEvent [processed=" + processed + ", eventType=" + eventType + ", orderId=" + orderId + ", uid=" + uid
            + ", currency=" + currency + ", currencyScaleK=" + currencyScaleK + ", free=" + free + ", locked=" + locked
            + ", symbol=" + symbol + ", baseScaleK=" + baseScaleK + ", quoteScaleK=" + quoteScaleK
            + ", direction=" + direction + ", openVolume=" + openVolume + ", openInitMarginSum=" + openInitMarginSum
            + ", openPriceSum=" + openPriceSum + ", profit=" + profit + ", pendingSellSize=" + pendingSellSize
            + ", pendingBuySize=" + pendingBuySize + ", pendingSellAvgPrice=" + pendingSellAvgPrice + ", pendingBuyAvgPrice=" + pendingBuyAvgPrice
            + ", leverage=" + leverage + ", marginMode=" + marginMode + ", extraMargin=" + extraMargin + ", unrealizedProfit=" + unrealizedProfit
            + ", liquidationPrice=" + liquidationPrice + ", marginRatioScaleK=" + marginRatioScaleK + ", markPrice=" + markPrice
            + ", loanAmount=" + loanAmount + ", loanExtra=" + loanExtra + ", loanMode=" + loanMode
            + ", nextEvent=" + (nextEvent != null) + "]";
    }

}