//! 对应 Java: `exchange.core2.core.ExchangeApi`（现货子集门面）。设计文档 §4。
//!
//! `ExchangeApi` 包一层 [`ExchangeCore`]：symbol/currency 注册走**直接 API**（不构造
//! `OrderCommand`，本移植未含 `ADD_SYMBOL`/`ADD_CURRENCY` 这类命令变体——Java 里 symbol/currency
//! 注册本就是 `ExchangeApi` 直调 `SymbolSpecificationProvider`/`MatchingEngineRouter`，不经
//! Disruptor 环）；下单/撤单/查询等交易操作则构造 `OrderCommand` 并走
//! [`ExchangeCore::process_command`] 确定性管线。
//!
//! # 排序要求：currency 必须先于引用它的 symbol 注册
//! [`ExchangeApi::add_symbol`] 会校验 `spec.base_currency`/`spec.quote_currency` 是否已经
//! 通过 [`ExchangeApi::add_currency`] 注册；未注册则拒绝（`InvalidSymbol`），不会把该 symbol
//! 写入注册表，也不会在 matching router 里建 order book——避免出现"symbol 存在但引用的
//! currency spec 缺失"这种下单时才会 panic 的悬空状态（R1 `place_order_risk_check` 用
//! `ssp.get_currency(..).unwrap_or_else(|| panic!(..))` 假定 currency 必存在）。
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::common::cmd::order_command::{OrderCommand, FLAG_REDUCE_ONLY};
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::processors::risk_engine::RiskEngine;

use super::exchange_core::ExchangeCore;

/// [`ExchangeApi::place_order`] 的入参（对应 Java `ExchangeApi.submitCommandAsync` 里手工拼
/// `PlaceOrder` 建造器的那组字段）。
#[derive(Debug, Clone)]
pub struct PlaceOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub price: i64,
    pub size: i64,
    pub reserve_bid_price: i64,
    pub action: OrderAction,
    pub order_type: OrderType,
}

/// [`ExchangeApi::cancel_order`] 的入参。
#[derive(Debug, Clone, Copy)]
pub struct CancelOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
}

/// [`ExchangeApi::move_order`] 的入参。
#[derive(Debug, Clone, Copy)]
pub struct MoveOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub new_price: i64,
}

/// [`ExchangeApi::reduce_order`] 的入参。
#[derive(Debug, Clone, Copy)]
pub struct ReduceOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub reduce_size: i64,
}

/// [`ExchangeApi::place_futures_order`] 的入参（对应 Java `ExchangeApi` 手工拼 `PLACE_ORDER`
/// 建造器 + P4 期货扩展字段 `leverage`/`marginMode`/`FLAG_REDUCE_ONLY`）。无 `reserve_bid_price`
/// 字段——期货 `place_order` 分支（`RiskEngine::place_order`）不读该字段（现货专属：BID 保守价
/// 校验），传 `PlaceOrderRequest` 的等价字段会被期货风控静默忽略，故这里干脆不建模，避免误导
/// 调用方以为它对期货下单有意义。
#[derive(Debug, Clone, Copy)]
pub struct PlaceFuturesOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub price: i64,
    pub size: i64,
    pub action: OrderAction,
    pub order_type: OrderType,
    pub leverage: i32,
    pub margin_mode: MarginMode,
    pub reduce_only: bool,
}

/// [`ExchangeApi::close_position`] 的入参（对应 Java `CLOSE_POSITION` 命令字段）。`action` 是
/// **平仓方向**（与被平仓位方向相反：平多传 `Ask`，平空传 `Bid`）——[`RiskEngine::close_position_risk_check`]
/// 用它定位 `positions` map 里对应的仓位记录、并校验反向。`size`/`price` 是平仓单的委托量/价，
/// 实际成交量会被风控收敛到 `min(size, position.open_volume)`；`leverage`/`margin_mode` 会被风控
/// 强制覆盖为仓位自身的值（调用方传什么都不影响结果，见该方法文档），此处仍要求传入只是为了
/// 复用同一个 `OrderCommand` 构造路径，字段留空也不影响正确性。
#[derive(Debug, Clone, Copy)]
pub struct ClosePositionRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub action: OrderAction,
    pub price: i64,
    pub size: i64,
    pub order_type: OrderType,
}

/// [`ExchangeApi::margin_adjustment`] 的入参（对应 Java `MARGIN_ADJUSTMENT` 命令字段复用）。
/// `symbol` 语义按 `margin_mode` 二义：`Isolated` 时是真实 symbol id（定位某仓追加保证金）；
/// `Cross` 时是 currency id（等价于对该币种做一笔 `BALANCE_ADJUSTMENT`，见
/// [`RiskEngine::margin_adjustment`] 文档）。`amount` 走 `cmd.price`，恒为正（追加）。
#[derive(Debug, Clone, Copy)]
pub struct MarginAdjustmentRequest {
    pub uid: i64,
    pub symbol: i32,
    pub action: OrderAction,
    pub amount: i64,
    pub margin_mode: MarginMode,
    pub order_id: i64,
}

/// 对应 Java `ExchangeApi`（现货子集门面）：构造命令、提交 [`ExchangeCore::process_command`]、
/// 取回结果，供测试/未来消费方使用。本期单线程直调（Java 版本经 Disruptor `RingBuffer` 异步
/// 提交 + `Future`/`CompletableFuture` 取结果——单线程确定性管线下二者退化为同步直调）。
#[derive(Default)]
pub struct ExchangeApi {
    core: ExchangeCore,
}

impl ExchangeApi {
    pub fn new() -> Self {
        ExchangeApi { core: ExchangeCore::new() }
    }

    /// 直接注册 currency spec（非命令，对应 Java `ExchangeApi` 里 currency 是启动期静态配置）。
    /// **必须先于引用它的 symbol 调用**（见模块级文档）。
    pub fn add_currency(&mut self, currency: i32, scale_k: i64) {
        self.core.ssp.add_currency(CoreCurrencySpecification { currency, currency_scale_k: scale_k });
    }

    /// 直接注册 symbol spec：写 [`SymbolSpecificationProvider`] 并在
    /// [`MatchingEngineRouter`] 里建对应 order book。要求 `spec` 引用的 base/quote currency
    /// 已经 [`Self::add_currency`] 过，否则返回 `InvalidSymbol` 且两处都不写入。
    pub fn add_symbol(&mut self, spec: CoreSymbolSpecification) -> CommandResultCode {
        if self.core.ssp.get_currency(spec.base_currency).is_none()
            || self.core.ssp.get_currency(spec.quote_currency).is_none()
        {
            return CommandResultCode::InvalidSymbol;
        }
        let rc = self.core.ssp.add_symbol(spec.clone());
        if rc == CommandResultCode::Success {
            self.core.matching.add_symbol(&spec);
        }
        rc
    }

    pub fn add_user(&mut self, uid: i64) -> CommandResultCode {
        let mut cmd = OrderCommand { command: OrderCommandType::AddUser, uid, ..Default::default() };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    /// `currency` 走 `cmd.symbol`、`amount` 走 `cmd.price`、`txid` 走 `cmd.order_id`
    /// （对应 Java `BALANCE_ADJUSTMENT` 命令字段复用，见 `RiskEngine::balance_adjustment` 文档）。
    pub fn balance_adjustment(
        &mut self,
        uid: i64,
        currency: i32,
        amount: i64,
        txid: i64,
    ) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid,
            symbol: currency,
            price: amount,
            order_id: txid,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    pub fn place_order(&mut self, req: PlaceOrderRequest) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            price: req.price,
            size: req.size,
            reserve_bid_price: req.reserve_bid_price,
            action: Some(req.action),
            order_type: Some(req.order_type),
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    pub fn cancel_order(&mut self, req: CancelOrderRequest) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::CancelOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    pub fn move_order(&mut self, req: MoveOrderRequest) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::MoveOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            price: req.new_price,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    pub fn reduce_order(&mut self, req: ReduceOrderRequest) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::ReduceOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            size: req.reduce_size,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    pub fn request_l2(&mut self, symbol: i32, depth: i32) -> L2MarketData {
        let mut cmd = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol,
            size: depth as i64,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.market_data.take().unwrap_or_default()
    }

    // ------------------------------------------------------------------
    // 期货门面（P4 Task 7）：symbol 注册走与 [`Self::add_symbol`] 相同的直接 API（非命令）；
    // mark price / 下单 / 平仓 / 保证金 / 杠杆调整走各自的 `OrderCommand` 变体 +
    // [`ExchangeCore::process_command`]。
    // ------------------------------------------------------------------

    /// 直接注册期货 symbol spec（同 [`Self::add_symbol`] 的排序要求：currency 必须先注册）。
    /// 唯一区别：显式校验 `spec.symbol_type.is_futures_contract()`，非期货类型直接拒绝——
    /// 防止调用方拿现货 spec 误调这个方法（该校验是本方法独有的输入契约，[`Self::add_symbol`]
    /// 本身对 symbol_type 不作任何限制，两个方法背后是同一条注册路径）。
    pub fn add_futures_symbol(&mut self, spec: CoreSymbolSpecification) -> CommandResultCode {
        if !spec.symbol_type.is_futures_contract() {
            return CommandResultCode::UnsupportedSymbolType;
        }
        self.add_symbol(spec)
    }

    /// `MARKPRICE_ADJUSTMENT`：更新 `RiskEngine::last_price_cache[symbol]`（对应 Java
    /// `adjustMarkPrice`，见 [`RiskEngine::markprice_adjustment`] 文档）。
    pub fn set_mark_price(&mut self, symbol: i32, price: i64) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::MarkpriceAdjustment,
            symbol,
            price,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    /// 期货下单：`PLACE_ORDER` + `leverage`/`margin_mode`/reduce-only 三个期货专属字段
    /// （见 [`PlaceFuturesOrderRequest`] 文档）。
    pub fn place_futures_order(&mut self, req: PlaceFuturesOrderRequest) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            price: req.price,
            size: req.size,
            action: Some(req.action),
            order_type: Some(req.order_type),
            leverage: req.leverage,
            margin_mode: req.margin_mode,
            order_flags: if req.reduce_only { FLAG_REDUCE_ONLY } else { 0 },
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    /// `CLOSE_POSITION`：纯减仓期货命令（见 [`ClosePositionRequest`] 文档）。
    pub fn close_position(&mut self, req: ClosePositionRequest) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            action: Some(req.action),
            price: req.price,
            size: req.size,
            order_type: Some(req.order_type),
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    /// `MARGIN_ADJUSTMENT`：追加保证金（`Isolated`）/ 等价充值（`Cross`），见
    /// [`MarginAdjustmentRequest`] 文档。
    pub fn margin_adjustment(&mut self, req: MarginAdjustmentRequest) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::MarginAdjustment,
            uid: req.uid,
            symbol: req.symbol,
            action: Some(req.action),
            price: req.amount,
            margin_mode: req.margin_mode,
            order_id: req.order_id,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    /// `LEVERAGE_ADJUSTMENT`：调整某 symbol 下用户全部仓位的杠杆（见
    /// [`RiskEngine::leverage_adjustment`] 文档）。
    pub fn leverage_adjustment(&mut self, uid: i64, symbol: i32, leverage: i32) -> CommandResultCode {
        let mut cmd = OrderCommand {
            command: OrderCommandType::LeverageAdjustment,
            uid,
            symbol,
            leverage,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.result_code.expect("process_command always sets result_code")
    }

    // ------------------------------------------------------------------
    // 只读内省（供测试/上层校验守恒态用，非 Java ExchangeApi 原有方法）。
    // ------------------------------------------------------------------

    /// 某用户在某 symbol 上的仓位记录（`ONEWAY` 下 key 恒为 `symbol`；本移植未提供切换
    /// `position_mode` 的门面方法，故不处理 `HEDGE` 双腿键的场景）。
    pub fn user_position(&self, uid: i64, symbol: i32) -> Option<&SymbolPositionRecord> {
        self.core.ups.get(uid).and_then(|p| p.positions.get(&symbol))
    }

    pub fn user_account(&self, uid: i64, currency: i32) -> i64 {
        self.core.ups.get(uid).map(|p| p.account(currency)).unwrap_or(0)
    }

    pub fn user_locked(&self, uid: i64, currency: i32) -> i64 {
        self.core.ups.get(uid).map(|p| p.locked(currency)).unwrap_or(0)
    }

    pub fn fees(&self, currency: i32) -> i64 {
        *self.core.risk.fees.get(&currency).unwrap_or(&0)
    }

    pub fn adjustments(&self, currency: i32) -> i64 {
        *self.core.risk.adjustments.get(&currency).unwrap_or(&0)
    }

    pub fn ups(&self) -> &UserProfileService {
        &self.core.ups
    }

    pub fn ssp(&self) -> &SymbolSpecificationProvider {
        &self.core.ssp
    }

    pub fn risk(&self) -> &RiskEngine {
        &self.core.risk
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::common::position_direction::PositionDirection;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const SELLER: i64 = 1;
    const BUYER: i64 = 2;

    fn spot_spec_fixed_fee(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee,
            maker_fee,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    #[test]
    fn add_symbol_before_currency_is_rejected_and_does_not_register() {
        let mut api = ExchangeApi::new();
        // 未 add_currency：symbol 引用的两种 currency 都不存在。
        let rc = api.add_symbol(spot_spec_fixed_fee(0, 0));
        assert_eq!(rc, CommandResultCode::InvalidSymbol);
        assert!(api.ssp().get_symbol(SYMBOL).is_none());

        // 之后即便补上 currency，也不会因为之前那次失败调用而已经存在——可以正常重试成功。
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(0, 0)), CommandResultCode::Success);
    }

    /// Step 1（RED→GREEN）：一笔现货 taker/maker 成交端到端——经 ExchangeApi 播种
    /// currency/symbol/user/balance，卖方挂 ASK，买方吃单 BID 完全成交，断言：
    /// - 双方最终 base/quote 余额（卖方失 base 得 quote−maker 费；买方失 quote(含 taker 费)
    ///   得 base）；
    /// - fees 桶收到 taker+maker 手续费；
    /// - 全局守恒：`Σ users.accounts[cur] + adjustments[cur] + fees[cur] == 0`
    ///   （两种货币分别验证）；
    /// - `request_l2` 反映撮合后盘口已清空（完全成交，无残量）。
    #[test]
    fn spot_ask_bid_full_match_settles_balances_and_conserves_globally() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        // 固定费：taker_fee=10（size*10）、maker_fee=5（size*5），fee_scale_k=0。
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(10, 5)), CommandResultCode::Success);

        assert_eq!(api.add_user(SELLER), CommandResultCode::Success);
        assert_eq!(api.add_user(BUYER), CommandResultCode::Success);

        // 播种：卖方持有 1000 base；买方持有 100_000 quote（预算充足覆盖 60_000 冻结）。
        assert_eq!(
            api.balance_adjustment(SELLER, BASE, 1_000, 1),
            CommandResultCode::Success
        );
        assert_eq!(
            api.balance_adjustment(BUYER, QUOTE, 100_000, 2),
            CommandResultCode::Success
        );

        // 卖方先挂 ASK @ 50，size 1000（挂单，等待对手盘）。
        let ask_rc = api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: SELLER,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        });
        assert_eq!(ask_rc, CommandResultCode::Success);

        // 买方吃单：BID @ 50，reserve_bid_price=50（非 budget，reserve==price），size 1000，
        // 与卖方 ASK 完全撮合。
        let bid_rc = api.place_order(PlaceOrderRequest {
            order_id: 2,
            uid: BUYER,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
        });
        assert_eq!(bid_rc, CommandResultCode::Success);

        // ---- 逐用户余额断言（手算见 task-10 报告，taker_fee=10/maker_fee=5 固定费）----
        // 卖方（maker/ASK）：base 全部卖出（1000→0，locked 同步清零）；
        // quote 收到 size*price − maker_fee = 50_000 − 5_000 = 45_000。
        assert_eq!(api.user_account(SELLER, BASE), 0);
        assert_eq!(api.user_locked(SELLER, BASE), 0);
        assert_eq!(api.user_account(SELLER, QUOTE), 45_000);

        // 买方（taker/BID）：base 收到成交量 1000；quote 花费 size*price + taker_fee
        // = 50_000 + 10_000 = 60_000（100_000 − 60_000 = 40_000），locked 清零。
        assert_eq!(api.user_account(BUYER, BASE), 1_000);
        assert_eq!(api.user_account(BUYER, QUOTE), 40_000);
        assert_eq!(api.user_locked(BUYER, QUOTE), 0);

        // fees 池：taker_fee(10_000) + maker_fee(5_000) = 15_000，quote 计价。
        assert_eq!(api.fees(QUOTE), 15_000);
        assert_eq!(api.fees(BASE), 0);

        // adjustments 桶：充值方向的对冲（充值为正金额 → adjustments 记负）。
        assert_eq!(api.adjustments(BASE), -1_000);
        assert_eq!(api.adjustments(QUOTE), -100_000);

        // ---- 全局守恒：Σ accounts[cur] + adjustments[cur] + fees[cur] == 0 ----
        let base_sum = api.user_account(SELLER, BASE) + api.user_account(BUYER, BASE)
            + api.adjustments(BASE)
            + api.fees(BASE);
        assert_eq!(base_sum, 0, "base 守恒");

        let quote_sum = api.user_account(SELLER, QUOTE) + api.user_account(BUYER, QUOTE)
            + api.adjustments(QUOTE)
            + api.fees(QUOTE);
        assert_eq!(quote_sum, 0, "quote 守恒");

        // ---- request_l2：完全成交后盘口清空 ----
        let l2 = api.request_l2(SYMBOL, 10);
        assert!(l2.bid_prices.is_empty(), "买方完全成交，无残量挂单");
        assert!(l2.ask_prices.is_empty(), "卖方 ASK 已被完全吃掉");
    }

    #[test]
    fn request_l2_reflects_resting_order_before_match() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(0, 0)), CommandResultCode::Success);
        api.add_user(SELLER);
        api.balance_adjustment(SELLER, BASE, 1_000, 1);

        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: SELLER,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        });

        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.ask_prices, vec![50]);
        assert_eq!(l2.ask_volumes, vec![1000]);
        assert!(l2.bid_prices.is_empty());
    }

    #[test]
    fn cancel_order_via_api_releases_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(0, 0)), CommandResultCode::Success);
        api.add_user(SELLER);
        api.balance_adjustment(SELLER, BASE, 1_000, 1);

        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: SELLER,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        });
        assert_eq!(api.user_locked(SELLER, BASE), 1_000);

        let rc = api.cancel_order(CancelOrderRequest { order_id: 1, uid: SELLER, symbol: SYMBOL });
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(api.user_locked(SELLER, BASE), 0);

        let l2 = api.request_l2(SYMBOL, 10);
        assert!(l2.ask_prices.is_empty());
    }

    // ================================================================================
    // P4 Task 7：期货端到端——经 ExchangeApi 走完整一笔期货开仓成交 + 平仓结算。
    // 参考文档 §3/§4；手算见下方各断言旁注（`base_scale_k=quote_scale_k=currency_scale_k=1`
    // 恒等缩放，`fee_scale_k=0` 固定费，`init_margin`/`max_leverage` 均未配置 →
    // `calculateInitMargin = notional/leverage`，本例 `leverage=1` 恒等于 notional）。
    // ================================================================================

    const FUT_SYMBOL: i32 = 300;
    const LONG_USER: i64 = 10;
    const SHORT_USER: i64 = 20;

    fn futures_spec_fixed_fee(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: FUT_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee,
            maker_fee,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    #[test]
    fn add_futures_symbol_rejects_non_futures_symbol_type() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        let rc = api.add_futures_symbol(spot_spec_fixed_fee(0, 0));
        assert_eq!(rc, CommandResultCode::UnsupportedSymbolType);
        assert!(api.ssp().get_symbol(SYMBOL).is_none(), "拒绝的 symbol 不得注册");
    }

    /// Step 1（RED→GREEN）：一笔期货 taker/maker 成交端到端——建期货 symbol/建用户/充值/设
    /// mark 价 → 空方（SHORT_USER）先挂 ASK（maker，开空）、多方（LONG_USER）吃单 BID（taker，
    /// 开多）完全成交 → 断言双方头寸（direction/open_volume/open_init_margin_sum）+ accounts
    /// （仅 fees 流出）+ 全局守恒；再把 mark 价推高后双方互相平仓，断言已实现 PnL 结算进
    /// accounts 且守恒依旧成立、position 记录被拆除。
    #[test]
    fn futures_long_short_full_match_then_close_settles_pnl_and_conserves_globally() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        // taker_fee=10、maker_fee=5（固定费，fee_scale_k=0，同现货 e2e 测试的费率）。
        assert_eq!(
            api.add_futures_symbol(futures_spec_fixed_fee(10, 5)),
            CommandResultCode::Success
        );

        assert_eq!(api.add_user(LONG_USER), CommandResultCode::Success);
        assert_eq!(api.add_user(SHORT_USER), CommandResultCode::Success);

        // 双方各充值 10_000 quote（覆盖 leverage=1 时 required=1_000(positionMargin)+100(taker
        // fee 估算) 远有余）。
        assert_eq!(api.balance_adjustment(LONG_USER, QUOTE, 10_000, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(SHORT_USER, QUOTE, 10_000, 2), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);

        // SHORT_USER 先挂 ASK @100 size 10（maker，开空，等待对手盘）。
        let ask_rc = api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1,
            uid: SHORT_USER,
            symbol: FUT_SYMBOL,
            price: 100,
            size: 10,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        });
        assert_eq!(ask_rc, CommandResultCode::Success);

        // LONG_USER 吃单 BID @100 size 10（taker，开多），与 SHORT_USER 完全撮合。
        let bid_rc = api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2,
            uid: LONG_USER,
            symbol: FUT_SYMBOL,
            price: 100,
            size: 10,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        });
        assert_eq!(bid_rc, CommandResultCode::Success);

        // ---- 开仓后逐用户头寸断言（手算：mark==trade_price==100，无 openLoss）----
        let long_pos = api.user_position(LONG_USER, FUT_SYMBOL).expect("多头开仓后必有仓位记录");
        assert_eq!(long_pos.direction, PositionDirection::Long);
        assert_eq!(long_pos.open_volume, 10);
        assert_eq!(long_pos.open_init_margin_sum, 1_000); // notional(1000)/leverage(1)
        assert_eq!(long_pos.open_price_sum, 1_000); // 成交价 100 × 10
        assert_eq!(long_pos.profit, 0);

        let short_pos = api.user_position(SHORT_USER, FUT_SYMBOL).expect("空头开仓后必有仓位记录");
        assert_eq!(short_pos.direction, PositionDirection::Short);
        assert_eq!(short_pos.open_volume, 10);
        assert_eq!(short_pos.open_init_margin_sum, 1_000);
        assert_eq!(short_pos.open_price_sum, 1_000);
        assert_eq!(short_pos.profit, 0);

        // ---- accounts：仅 taker/maker 手续费流出，margin 是仓位内部虚拟字段，不动 accounts/locked ----
        assert_eq!(api.user_account(LONG_USER, QUOTE), 10_000 - 100, "taker fee = size(10)*taker_fee(10)");
        assert_eq!(api.user_account(SHORT_USER, QUOTE), 10_000 - 50, "maker fee = size(10)*maker_fee(5)");
        assert_eq!(api.user_locked(LONG_USER, QUOTE), 0, "期货保证金不占用 locked（纯虚拟仓位字段）");
        assert_eq!(api.user_locked(SHORT_USER, QUOTE), 0);
        assert_eq!(api.fees(QUOTE), 150); // 100(taker) + 50(maker)

        // ---- 全局守恒（开仓后）：Σ accounts + adjustments + fees == 0 ----
        let conserved = |api: &ExchangeApi| {
            api.user_account(LONG_USER, QUOTE) + api.user_account(SHORT_USER, QUOTE)
                + api.adjustments(QUOTE)
                + api.fees(QUOTE)
        };
        assert_eq!(conserved(&api), 0, "开仓后 quote 守恒");

        // ---- 平仓：mark 价推高到 150，双方互相平仓（多头 ASK 平多、空头 BID 平空）----
        assert_eq!(api.set_mark_price(FUT_SYMBOL, 150), CommandResultCode::Success);

        // SHORT_USER 先挂平仓 BID @150（maker，反向平空）。
        let close_short_rc = api.close_position(ClosePositionRequest {
            order_id: 3,
            uid: SHORT_USER,
            symbol: FUT_SYMBOL,
            action: OrderAction::Bid,
            price: 150,
            size: 10,
            order_type: OrderType::Gtc,
        });
        assert_eq!(close_short_rc, CommandResultCode::Success);

        // LONG_USER 平仓 ASK @150（taker，反向平多），与 SHORT_USER 的平仓单完全撮合。
        let close_long_rc = api.close_position(ClosePositionRequest {
            order_id: 4,
            uid: LONG_USER,
            symbol: FUT_SYMBOL,
            action: OrderAction::Ask,
            price: 150,
            size: 10,
            order_type: OrderType::Gtc,
        });
        assert_eq!(close_long_rc, CommandResultCode::Success);

        // ---- 平仓后头寸记录应被拆除（open_volume/pending 均清零 -> is_empty -> remove）----
        assert!(api.user_position(LONG_USER, FUT_SYMBOL).is_none(), "多头完全平仓后 position 记录应被拆除");
        assert!(api.user_position(SHORT_USER, FUT_SYMBOL).is_none(), "空头完全平仓后 position 记录应被拆除");

        // ---- accounts：已实现 PnL 结算进账户（手算：close_notional=150*10=1500，
        // open_price_sum=1000，pnl_raw=500；LONG 方向乘数+1 → +500，SHORT 方向乘数-1 → -500）+
        // 平仓手续费（LONG 是 taker 付 size(10)*taker_fee(10)=100；SHORT 是 maker 付
        // size(10)*maker_fee(5)=50）----
        assert_eq!(
            api.user_account(LONG_USER, QUOTE),
            10_000 - 100 /* 开仓 taker fee */ - 100 /* 平仓 taker fee */ + 500, /* 已实现盈利 */
        );
        assert_eq!(
            api.user_account(SHORT_USER, QUOTE),
            10_000 - 50 /* 开仓 maker fee */ - 50 /* 平仓 maker fee */ - 500, /* 已实现亏损 */
        );
        assert_eq!(api.fees(QUOTE), 150 + 100 + 50, "累计四笔手续费：150(开仓)+100+50(平仓)");

        // ---- 全局守恒（平仓后，PnL 零和 + 手续费流出）----
        assert_eq!(conserved(&api), 0, "平仓结算 PnL 后 quote 依旧守恒");

        // ---- request_l2：双方平仓单完全对锁，盘口清空 ----
        let l2 = api.request_l2(FUT_SYMBOL, 10);
        assert!(l2.bid_prices.is_empty());
        assert!(l2.ask_prices.is_empty());
    }
}
