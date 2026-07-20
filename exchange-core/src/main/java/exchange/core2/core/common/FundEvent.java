package exchange.core2.core.common;

import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * 用户资金 / 仓位变动事件，下发给下游做对账与余额展示。具体语义由 {@link FundEventType} 区分，
 * 覆盖三个域：<b>现货</b>、<b>期货</b>、<b>现货借贷 loan</b>；字段按域分区，跨域字段互不复用（loan 借贷侧除外，见下）。
 *
 * <p>所有事件都是<b>全量快照</b>——字段一律是"本次操作完成后"的状态，不下发任何增量。需要"本次变动"时，
 * 由下游对相邻两条事件相减得出；累计类指标（{@code loanInterestPaidTotal}）
 * 同样是单调递增的快照，相减即得本次量。
 *
 * <h3>消费端如何计算"该 currency 真实持有的资产"</h3>
 *
 * <b>现货：</b>
 * <pre>
 *   真实持有 = free + locked
 * </pre>
 *   - 现货下单<b>不</b>从 accounts 扣减，而是把冻结额累计到 {@code UserProfile.exchangeLocked}。
 *   - {@code locked} 已包含现货挂单冻结（{@code calculateLocked} 内部含 {@code exchangeLocked}），
 *     消费端<b>不需要</b>再从订单簿聚合未成交挂单的 hold 量。
 *   - {@code free = accounts − locked}；纯现货用户若有未成交挂单，{@code locked} 即等于 spot 挂单冻结额。
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
 *   - {@code free} / {@code locked} 已按 currency 跨所有 position 聚合（currencyScale 单位）。
 *   - {@code extraMargin} / {@code profit} 仅是本事件所属<b>单个 position</b> 的值（sizePriceScale 单位
 *     = baseScaleK × quoteScaleK）。消费端需按 position 维护状态、跨所有 position 累加才得到 currency 维度总值。
 *   - {@code profit} 是"已实现但未 sweep 到 accounts"的 PnL（funding fee 累积 + 部分平仓 PnL），
 *     在 {@code RiskEngine.removePositionRecord} 时才落地到 accounts。
 *
 * <h3>借贷 loan 事件的两侧布局</h3>
 * loan 天然涉及两个币种，字段按<b>借贷侧</b>（借的是什么、欠多少）与<b>抵押侧</b>（押的是什么、押多少）分组，
 * 两侧同构：币种 + 精度 + 账户可用 + 账户冻结 + 该侧 loan 金额。区别仅在借贷侧前四项复用通用槽位
 * （{@code currency} = loanCurrency、{@code currencyScaleK} / {@code free} / {@code locked}），抵押侧另有一组专用字段。
 *
 * <p>三个易错点：
 *   - {@code loanDebtPrincipal} 是<b>负债</b>不是余额；放款时本金已计入借贷侧 {@code free}，二者<b>勿相加</b>。
 *   - {@code loanCollateralPledged} 已包含在 {@code loanCollateralLocked} 内（抵押是虚拟锁定，不从 accounts 扣），<b>勿重复计</b>。
 *   - 抵押币 == 借款币时，抵押侧与通用 free/locked 指向同一账户、值相同，<b>勿重复计</b>。
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FundEvent {
    public boolean processed; // 下游是否已处理（对象池复用标记）

    // ==================== 通用：所有事件类型都有效 ====================
    public FundEventType eventType;
    public long orderId;        // 事件归属 id：现货/期货 = 订单 id；loan = loanId；系统触发 = -1
    public long uid;            // 用户 id（loan 事件 = 借款人）
    public int currency;        // 变动币种（loan 事件 = 借款币）
    public long currencyScaleK; // currency 缩放系数（还原金额小数位用）
    public long free;           // 该 currency 可用余额（= accounts − locked，currencyScale 单位）
    public long locked;         // 该 currency 总冻结额（currencyScale 单位）
                                //   = Σ(期货 position 的 openInitMarginSum + pendingMargin + 潜在 fee)
                                //   + exchangeLocked（现货挂单冻结）+ loan 抵押虚拟锁定
                                // 注意：不含 extraMargin、不含 position.profit。

    // ==================== 期货：仓位与保证金 ====================
    public int symbol;                  // 交易对 id
    public long baseScaleK;             // 基础币缩放系数（还原 size 用）
    public long quoteScaleK;            // 计价币缩放系数（还原 price 用）
    public PositionDirection direction; // 仓位方向
    public long openVolume;             // 持仓数量
    public long openInitMarginSum;      // 初始保证金总额
    public long openPriceSum;           // 持仓总成本；openPriceSum / openVolume = 平均持仓成本
    public long profit;                 // 该 position 已实现但尚未 sweep 到 accounts 的 PnL（funding fee 累积 + 部分平仓 PnL）。
                                        // sizePriceScale 单位（= baseScaleK × quoteScaleK），removePositionRecord 时才落地 accounts。
    public long pendingSellSize;        // 未成交卖单数量
    public long pendingBuySize;         // 未成交买单数量
    public long pendingSellAvgPrice;    // 未成交卖单均价
    public long pendingBuyAvgPrice;     // 未成交买单均价
    public int leverage;                // 杠杆倍数
    public MarginMode marginMode;       // 逐仓 / 全仓
    public long extraMargin;            // 逐仓追加保证金。sizePriceScale 单位，与 currencyScale 不同，
                                        // 参与"真实持有"累加前需换算（详见类注释）。
    // 以下为引擎算好下发的估算指标，下游不必自行计算
    public long unrealizedProfit;       // 未实现盈亏
    public long liquidationPrice;       // 强平价格
    public long marginRatioScaleK;      // 保证金率 = 维持保证金 / 资金占用 × 缩放系数。
                                        // 全仓资金占用 = 当前币种余额 + 该币种总未实现盈亏；逐仓 = 开仓保证金 + extraMargin
    public long markPrice;              // 标记价格

    // ==================== 现货借贷 loan（orderId = loanId、uid = 借款人）====================
    public byte loanMode;                 // 0 = Isolated，1 = Cross（决定下列字段语义）

    // ── 借贷侧：币种/精度/可用余额/冻结额复用上面通用的 currency（= loanCurrency）/ currencyScaleK / free / locked，
    //    金额按 currencyScaleK 还原
    public long loanDebtPrincipal;        // 操作后未偿本金（负债）；放款时本金已计入借贷侧 free，二者勿相加
    public long loanDebtInterest;         // 操作后未付利息（负债）
    public long loanInterestPaidTotal;    // 本笔贷款累计已付利息（单调递增，LOAN_BORROW 时为 0 起算）
    public long loanLtvBps;               // 操作后 LTV =（未偿本金 + 应计利息）/ 抵押物市值，bps（10000 = 100%）；未计算时 0
    public long loanThresholdBps;         // 仅 MARGIN_CALL：触发本次预警的 LTV 阈值（bps）

    // ── 抵押侧：抵押币与借款币 scale 不同，须整组单独下发，金额按 loanCollateralCurrencyScaleK 还原
    public int loanCollateralCurrency;        // Isolated = 该 loan 的抵押币；Cross = 本次操作涉及的抵押币（BORROW / REPAY 不涉及，为 0）
    public long loanCollateralCurrencyScaleK; // 抵押币缩放系数（还原 pledged 小数位用）
    public long loanCollateralPledged;        // 操作后已质押抵押物（Isolated = 本笔；Cross = 该币在账户抵押池的余额）；已含在 loanCollateralLocked 内
    public long loanCollateralFree;           // 抵押币账户可用余额
    public long loanCollateralLocked;         // 抵押币账户冻结额（含本 loan 抵押虚拟锁定）

    public FundEvent nextEvent; // 同一 cmd 的事件单链表；由 taker / maker 桶串起，下游按链遍历

    /**
     * 事件类型。code 按域分段：1–5 现货、6–18 期货、20–21 通知、30 运营、40–44 借贷、50 内部转账。
     * code 与 proto {@code FundEventType} 一一对应，新增类型两边必须同步。
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
        MARGIN_ADJUST(14),      // 逐仓追加补充保证金
        MARGIN_REFUND(15),      // 逐仓平仓返还补充保证金
        IF_POSITION_CLOSE(16),    // IF 接管仓位平仓
        ADL_ORIGIN_CLOSE(17),    // ADL 中破产仓位平仓
        ADL_POSITION_CLOSE(18),  // ADL 中盈利仓位被减仓

        // 通知类事件
        MARGIN_ALERT(20),       // 通知追加保证金
        LIQUIDATION_ALERT(21),  // 通知强平单创建

        // 其他事件
        RESET_FEE(30),          // 重置手续费

        // 现货借贷 loan 用户维度事件：借贷侧 / 抵押侧全量快照。
        // Cross 的 BORROW / REPAY 不涉及具体抵押币，抵押侧整组（含币种）为 0。
        LOAN_MARGIN_CALL(40),        // LTV 触及预警线（节流 ≥ 5min）：仅 ltvBps + thresholdBps 有效，其余为 0
        LOAN_BORROW(41),             // 放款：本金进账户，抵押转为虚拟锁定（LOAN_CREATE / LOAN_CROSS_BORROW）
        LOAN_REPAY(42),              // 还款，利息优先于本金；interestPaid = 本次实付利息（LOAN_REPAY / LOAN_CROSS_REPAY）
        LOAN_COLLATERAL_CHANGE(43),  // 加 / 减抵押：仅抵押侧与 LTV 变动，本金不变（ADD / RELEASE / CROSS_ADD / CROSS_WITHDRAW）
        LOAN_LIQUIDATED(44),         // 强平核销：卖抵押抵债；市场按破产价接不住则由 LIF 承接

        // 用户间内部转账（付款方 / 收款方共用；方向由 event.uid 是 from 还是 to 区分）
        INTERNAL_TRANSFER(50);

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
        loanMode = 0;
        loanCollateralCurrency = 0;
        loanDebtPrincipal = 0;
        loanDebtInterest = 0;
        loanCollateralPledged = 0;
        loanLtvBps = 0;
        loanInterestPaidTotal = 0;
        loanThresholdBps = 0;
        loanCollateralCurrencyScaleK = 0;
        loanCollateralFree = 0;
        loanCollateralLocked = 0;
        nextEvent = null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(processed, eventType, orderId, uid, currency, currencyScaleK, free, locked, symbol, baseScaleK,
                quoteScaleK, direction, openVolume, openInitMarginSum, openPriceSum, profit, pendingSellSize, pendingBuySize,
                pendingSellAvgPrice, pendingBuyAvgPrice, leverage, marginMode, extraMargin, unrealizedProfit, liquidationPrice,
                marginRatioScaleK, markPrice, loanMode, loanCollateralCurrency, loanDebtPrincipal,
                loanDebtInterest, loanCollateralPledged, loanLtvBps, loanInterestPaidTotal, loanThresholdBps, loanCollateralCurrencyScaleK,
                loanCollateralFree, loanCollateralLocked, nextEvent);
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
            && loanMode == other.loanMode && loanCollateralCurrency == other.loanCollateralCurrency
            && loanDebtPrincipal == other.loanDebtPrincipal && loanDebtInterest == other.loanDebtInterest
            && loanCollateralPledged == other.loanCollateralPledged && loanLtvBps == other.loanLtvBps
            && loanInterestPaidTotal == other.loanInterestPaidTotal && loanThresholdBps == other.loanThresholdBps
            && loanCollateralCurrencyScaleK == other.loanCollateralCurrencyScaleK
            && loanCollateralFree == other.loanCollateralFree && loanCollateralLocked == other.loanCollateralLocked
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
            + ", loanMode=" + loanMode + ", loanCollateralCurrency=" + loanCollateralCurrency
            + ", loanDebtPrincipal=" + loanDebtPrincipal + ", loanDebtInterest=" + loanDebtInterest
            + ", loanCollateralPledged=" + loanCollateralPledged + ", loanLtvBps=" + loanLtvBps
            + ", loanInterestPaidTotal=" + loanInterestPaidTotal + ", loanThresholdBps=" + loanThresholdBps
            + ", loanCollateralCurrencyScaleK=" + loanCollateralCurrencyScaleK + ", loanCollateralFree=" + loanCollateralFree
            + ", loanCollateralLocked=" + loanCollateralLocked
            + ", nextEvent=" + (nextEvent != null) + "]";
    }

}