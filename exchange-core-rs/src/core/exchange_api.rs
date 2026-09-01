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
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
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
    // 只读内省（供测试/上层校验守恒态用，非 Java ExchangeApi 原有方法）。
    // ------------------------------------------------------------------

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
}
