use crate::core::common::margin_mode::MarginMode;
use crate::core::common::position_direction::PositionDirection;

/// 对应 Java `FundEvent.orderId` 为系统触发（非用户下单）事件时的取值。
pub const SYSTEM_TRIGGERED_ORDER_ID: i64 = -1;

/// 对应 Java `FundEvent.FundEventType`。code 按域分段:1–5 现货、6–18 期货、20–21 通知、30 运营、40–44 借贷、50 内部转账;
/// code 与 proto `FundEventType` 一一对应,新增类型两边必须同步。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum FundEventType {
    // ── 现货事件
    #[default]
    Deposit, // 现货充值(free 增加)
    Locked, // 现货下单成功冻结(free -> locked)
    Transfer, // 现货撮合成交后资产互换
    Unlocked, // 订单取消或未成交释放(locked -> free)
    Withdraw, // 现货提现(free 减少)
    // ── 期货事件
    LockPending, // 提交期货订单冻结初始保证金
    UnlockPending, // 未成交释放初始保证金
    OpenPosition, // 新增持仓记录
    ClosePosition, // 平仓:释放保证金 + 盈亏落地 + 手续费
    LiquidationClose, // 强平关仓
    LiquidationFee, // 强平费
    FundingfeeSettlement, // 资金费率结算
    PnlSettlement, // 交割合约结算
    MarginAdjust, // 逐仓追加补充保证金
    MarginRefund, // 逐仓平仓返还补充保证金
    IfPositionClose, // IF 接管仓位平仓
    AdlOriginClose, // ADL 中破产仓位平仓
    AdlPositionClose, // ADL 中盈利仓位被减仓
    // ── 通知类事件
    MarginAlert, // 通知追加保证金
    LiquidationAlert, // 通知强平单创建
    // ── 其他
    ResetFee, // 重置手续费
    // ── 现货借贷 loan 事件
    LoanMarginCall, // LTV 触及预警线;仅 ltv_bps + threshold_bps 有效
    LoanBorrow, // 放款
    LoanRepay, // 还款,利息优先于本金
    LoanCollateralChange, // 加/减抵押:仅抵押侧与 LTV 变动,本金不变
    LoanLiquidated, // 强平核销:卖抵押抵债
    // ── 用户间内部转账,方向由 event.uid 是 from 还是 to 区分
    InternalTransfer,
}

impl FundEventType {
    /// 对应 Java `FundEventType.getCode()`;取值须与 proto `FundEventType` 保持一致。
    pub fn code(self) -> i32 {
        match self {
            FundEventType::Deposit => 1,
            FundEventType::Locked => 2,
            FundEventType::Transfer => 3,
            FundEventType::Unlocked => 4,
            FundEventType::Withdraw => 5,
            FundEventType::LockPending => 6,
            FundEventType::UnlockPending => 7,
            FundEventType::OpenPosition => 8,
            FundEventType::ClosePosition => 9,
            FundEventType::LiquidationClose => 10,
            FundEventType::LiquidationFee => 11,
            FundEventType::FundingfeeSettlement => 12,
            FundEventType::PnlSettlement => 13,
            FundEventType::MarginAdjust => 14,
            FundEventType::MarginRefund => 15,
            FundEventType::IfPositionClose => 16,
            FundEventType::AdlOriginClose => 17,
            FundEventType::AdlPositionClose => 18,
            FundEventType::MarginAlert => 20,
            FundEventType::LiquidationAlert => 21,
            FundEventType::ResetFee => 30,
            FundEventType::LoanMarginCall => 40,
            FundEventType::LoanBorrow => 41,
            FundEventType::LoanRepay => 42,
            FundEventType::LoanCollateralChange => 43,
            FundEventType::LoanLiquidated => 44,
            FundEventType::InternalTransfer => 50,
        }
    }
}

/// 对应 Java `exchange.core2.core.common.FundEvent`。用户资金/仓位变动事件,下发给下游做对账与余额展示;
/// 覆盖现货、期货、现货借贷 loan 三个域,字段按域分区,跨域字段互不复用。
/// **全量快照**:所有字段都是"本次操作完成后"的状态,不下发增量;需要"本次变动"由下游相邻两条事件相减得出,
/// 累计类指标(`loan_interest_paid_total`)同理是单调递增快照。
///
/// 消费端计算某 currency 真实持有资产:
/// - 现货 = free + locked(现货挂单冻结已计入 locked,不需再从订单簿聚合)。
/// - 期货(同一 currency 跨所有 position 聚合)= free + locked + Σ toCurrencyScale(extra_margin) + Σ toCurrencyScale(profit),
///   其中 `free`/`locked` 已跨 position 聚合(currencyScale),而 `extra_margin`/`profit` 仅是本事件所属单个 position 的值
///   (sizePriceScale = base_scale_k × quote_scale_k),需按 position 累加。
///
/// loan 事件两侧布局:借贷侧(借的是什么/欠多少)复用通用 currency/currency_scale_k/free/locked 槽位,
/// 抵押侧(押的是什么/押多少)另有专用字段;`loan_debt_principal` 是负债不是余额,放款时本金已计入借贷侧 free,勿相加;
/// `loan_collateral_pledged` 已包含在 `loan_collateral_locked` 内,勿重复计。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct FundEvent {
    pub event_type: FundEventType,
    /// 事件归属 id:现货/期货 = 订单 id;loan = loan_id;系统触发 = [`SYSTEM_TRIGGERED_ORDER_ID`]。
    pub order_id: i64,
    /// 用户 id(loan 事件 = 借款人)。
    pub uid: i64,
    /// 变动币种(loan 事件 = 借款币)。
    pub currency: i32,
    /// currency 缩放系数,还原金额小数位用。
    pub currency_scale_k: i64,
    /// 该 currency 可用余额(= accounts − locked,currencyScale 单位)。
    pub free: i64,
    /// 该 currency 总冻结额(currencyScale 单位):期货保证金占用 + 现货挂单冻结 + loan 抵押虚拟锁定;
    /// 不含 extra_margin、不含 position.profit。
    pub locked: i64,

    pub symbol: i32,
    /// 基础币缩放系数,还原 size 用。
    pub base_scale_k: i64,
    /// 计价币缩放系数,还原 price 用。
    pub quote_scale_k: i64,
    pub direction: PositionDirection,
    pub open_volume: i64,
    pub open_init_margin_sum: i64,
    /// 持仓总成本;`open_price_sum / open_volume` = 平均持仓成本。
    pub open_price_sum: i64,
    /// 该 position 已实现但尚未 sweep 到 accounts 的 PnL(funding fee 累积 + 部分平仓 PnL);
    /// sizePriceScale 单位,`removePositionRecord` 时才落地 accounts。
    pub profit: i64,
    pub leverage: i32,
    pub margin_mode: MarginMode,
    /// 逐仓追加保证金,sizePriceScale 单位(与 currencyScale 不同,参与"真实持有"累加前需换算)。
    pub extra_margin: i64,
    pub unrealized_profit: i64,
    pub liquidation_price: i64,
    /// 保证金率 = 维持保证金 / 资金占用 × 缩放系数;全仓资金占用 = 当前币种余额 + 该币种总未实现盈亏,
    /// 逐仓资金占用 = 开仓保证金 + extra_margin。
    pub margin_ratio_scale_k: i64,
    /// `margin_ratio_scale_k` 的还原除数(= spec.maintenanceMarginScaleK)。
    pub maintenance_margin_scale_k: i64,
    pub mark_price: i64,
    pub pending_buy_size: i64,
    pub pending_buy_avg_price: i64,
    pub pending_sell_size: i64,
    pub pending_sell_avg_price: i64,

    /// 0 = Isolated,1 = Cross,决定下方 loan 字段语义。
    pub loan_mode: i8,
    /// 操作后未偿本金(负债);放款时本金已计入借贷侧 free,二者勿相加。
    pub loan_debt_principal: i64,
    /// 操作后未付利息(负债)。
    pub loan_debt_interest: i64,
    /// 本笔贷款累计已付利息(单调递增,LOAN_BORROW 时为 0 起算)。
    pub loan_interest_paid_total: i64,
    /// 操作后 LTV = (未偿本金 + 应计利息) / 抵押物市值,bps(10000 = 100%);未计算时为 0。
    pub loan_ltv_bps: i64,
    /// 仅 MARGIN_CALL:触发本次预警的 LTV 阈值(bps)。
    pub loan_threshold_bps: i64,
    /// Isolated = 该 loan 的抵押币;Cross = 本次操作涉及的抵押币(BORROW/REPAY 不涉及,为 0)。
    pub loan_collateral_currency: i32,
    /// 抵押币缩放系数,还原 pledged 小数位用。
    pub loan_collateral_currency_scale_k: i64,
    /// 操作后已质押抵押物(Isolated = 本笔;Cross = 该币在账户抵押池的余额);已含在 `loan_collateral_locked` 内。
    pub loan_collateral_pledged: i64,
    pub loan_collateral_free: i64,
    /// 抵押币账户冻结额(含本 loan 抵押虚拟锁定)。
    pub loan_collateral_locked: i64,
}

impl FundEvent {
    /// 对应 Java 里现货场景下快速构造 `FundEvent` 的用法:只填通用 + 现货字段,期货/loan 字段留默认零值。
    pub fn spot(event_type: FundEventType, order_id: i64, uid: i64, currency: i32, free: i64, locked: i64) -> Self {
        FundEvent { event_type, order_id, uid, currency, free, locked, ..Default::default() }
    }
}
