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
use crate::core::common::symbol_type::SymbolType;
use crate::core::common::batch_add_loan_command::BatchAddLoanCommand;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::processors::risk_engine::RiskEngine;

use super::exchange_core::ExchangeCore;

// 对应 Java `ExchangeApi`：Java 版是把 ApiCommand 编码后发布到 Disruptor RingBuffer 的异步门面
// （submitCommand/submitCommandAsync/submitCommandAsyncFullResponse 等），本身不持有引擎状态。
// Rust 版塌缩为同步门面：直接持有一个 `ExchangeCore` 并调用 `process_command`，每个 `submit*`
// 方法对应 Java 一种 ApiCommand 的构造 + 提交，返回值也简化为 `CommandResultCode`（无 Future）。

// 以下请求 DTO 是各 submit 方法的强类型入参，字段与目标 `OrderCommand` 字段一一对应；
// 对应 Java 侧各 `ApiPlaceOrder`/`ApiCancelOrder`/... 的字段集合（Java 直接用这些 Api*Command
// 对象经 `submitCommand` 编码进 RingBuffer，这里只是构造 `OrderCommand` 前的中间形态）。
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

#[derive(Debug, Clone, Copy)]
pub struct CancelOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
}

#[derive(Debug, Clone, Copy)]
pub struct MoveOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub new_price: i64,
}

#[derive(Debug, Clone, Copy)]
pub struct ReduceOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub reduce_size: i64,
}

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

#[derive(Debug, Clone, Copy)]
pub struct MarginAdjustmentRequest {
    pub uid: i64,
    pub symbol: i32,
    pub action: OrderAction,
    pub amount: i64,
    pub margin_mode: MarginMode,
    pub order_id: i64,
}

/// 对应 Java `ExchangeApi`：调用方门面。`core` 直接持有引擎（对比 Java 侧通过 RingBuffer 间接驱动
/// 独立线程上的 RiskEngine/MatchingEngineRouter），`last_cmd` 保留最近一次提交后的完整 `OrderCommand`
/// （含 matcher_event/fund_events/market_data），供 `last_*` 系列访问器读取——近似 Java
/// `submitCommandAsyncFullResponse` 拿到的回填结果，但这里是同步、单条、无需 Future。
#[derive(Default)]
pub struct ExchangeApi {
    core: ExchangeCore,
    last_cmd: Option<OrderCommand>,
}

impl ExchangeApi {
    pub fn new() -> Self {
        ExchangeApi { core: ExchangeCore::new(), last_cmd: None }
    }

    // 内部帮助函数：同步跑一条 cmd 并缓存结果，是下面各 submit 类方法的共同尾调用；
    // 对应 Java 每个 ApiXxxCommand 经 `submitCommand`/`submitCommandAsync` 提交后拿 resultCode 的落点。
    fn run(&mut self, mut cmd: OrderCommand) -> CommandResultCode {
        self.core.process_command(&mut cmd);
        let rc = cmd.result_code.expect("process_command always sets result_code");
        self.last_cmd = Some(cmd);
        rc
    }

    /// 对应 Java `BatchAddAccountsCommand`/初始化阶段直接调用 `currencySpecificationProvider.addCurrency`
    /// 的单币种版本；非交易类配置，绕开 `run`/`OrderCommand` 直接改 SSP。
    pub fn add_currency(&mut self, currency: i32, scale_k: i64) {
        self.core.ssp.add_currency(CoreCurrencySpecification { currency, currency_scale_k: scale_k, ..Default::default() });
    }

    /// 对应 Java 添加 symbol 的批处理路径：base/quote 币种必须先注册，否则 `InvalidSymbol`；
    /// 成功后现货/期货 symbol 都要同步进 matching router（对应 Java `MatchingEngineRouter` 建空 orderBook）。
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

    /// `add_symbol` 的期货专用门：非期货类型直接拒绝（`UnsupportedSymbolType`），不进入通用校验。
    pub fn add_futures_symbol(&mut self, spec: CoreSymbolSpecification) -> CommandResultCode {
        if !spec.symbol_type.is_futures_contract() {
            return CommandResultCode::UnsupportedSymbolType;
        }
        self.add_symbol(spec)
    }

    /// 对应 Java `ApiAddUser` / `OrderCommandType.ADD_USER`。
    pub fn add_user(&mut self, uid: i64) -> CommandResultCode {
        let cmd = OrderCommand { command: OrderCommandType::AddUser, uid, ..Default::default() };
        self.run(cmd)
    }

    /// 对应 Java 通过配置项 `MARGIN_TRADING_ENABLED` 挂接 `LiquidationEngine.commandSubmitter` 后
    /// 打开清算调度；这里简化为直接置位 `liquidation_engine.is_running`（无独立扫描线程）。
    pub fn enable_liquidation(&mut self) {
        self.core.risk.liquidation_engine.is_running = true;
    }

    /// 批量版 `add_currency`，对应 Java `BatchAddAccountsCommand` 之类批处理入口的币种部分。
    pub fn add_currencies(&mut self, currencies: impl IntoIterator<Item = CoreCurrencySpecification>) {
        for spec in currencies {
            self.core.ssp.add_currency(spec);
        }
    }

    /// 批量版 `add_symbol`：非现货 symbol 在保证金交易未启用(`cfg_margin_trading_enabled == false`)时
    /// 被静默跳过（对齐 Java 批处理里对 margin symbol 的门控，现货恒放行）。
    pub fn add_symbols(&mut self, symbols: impl IntoIterator<Item = CoreSymbolSpecification>) {
        for spec in symbols {
            if spec.symbol_type != SymbolType::CurrencyExchangePair && !self.core.risk.cfg_margin_trading_enabled {
                log::warn!("Margin symbols are not allowed: symbol={}", spec.symbol_id);
                continue;
            }
            self.add_symbol(spec);
        }
    }

    /// 批量建账户并充值初始余额：已存在的 uid 跳过（不覆盖），充值同时把等额记入
    /// `risk.adjustments` 负值，保持全局守恒（对应 Java 批量种子账户流程 + BALANCE_ADJUSTMENT 的守恒约定）。
    pub fn add_accounts(&mut self, accounts: impl IntoIterator<Item = (i64, Vec<(i32, i64)>)>) {
        for (uid, balances) in accounts {
            if self.core.ups.add_empty_user_profile(uid) != CommandResultCode::Success {
                continue;
            }
            if let Some(up) = self.core.ups.get_mut(uid) {
                for (currency, amount) in balances {
                    up.add_to_account(currency, amount);
                    *self.core.risk.adjustments.entry(currency).or_insert(0) -= amount;
                }
            }
        }
    }

    /// 批量版 `add_loan`（loan 池初始化/预设批处理入口）。
    pub fn add_loans(&mut self, cmds: impl IntoIterator<Item = BatchAddLoanCommand>) {
        for cmd in cmds {
            self.core.risk.apply_add_loan(&cmd, &mut self.core.ssp);
        }
    }

    /// 对应 Java `ADD_LOAN` 批处理指令：绕开 `run`/`OrderCommand`，直接调用 RiskEngine 的 loan 配置
    /// 应用逻辑（非交易类初始化路径，见 loan-config-simplification 分支收敛）。
    pub fn add_loan(&mut self, cmd: BatchAddLoanCommand) {
        self.core.risk.apply_add_loan(&cmd, &mut self.core.ssp);
    }

    /// 对应 Java `ApiAdjustUserBalance` / `OrderCommandType.BALANCE_ADJUSTMENT`：`amount` 正为入金、
    /// 负为出金，`txid` 作为幂等去重键写入 `order_id`。
    pub fn balance_adjustment(
        &mut self,
        uid: i64,
        currency: i32,
        amount: i64,
        txid: i64,
    ) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid,
            symbol: currency,
            price: amount,
            order_id: txid,
            ..Default::default()
        };
        self.run(cmd)
    }

    /// 对应 Java `ApiPlaceOrder`（现货）/ `OrderCommandType.PLACE_ORDER`。
    pub fn place_order(&mut self, req: PlaceOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
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
        self.run(cmd)
    }

    /// 对应 Java `ApiCancelOrder` / `OrderCommandType.CANCEL_ORDER`。
    pub fn cancel_order(&mut self, req: CancelOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::CancelOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            ..Default::default()
        };
        self.run(cmd)
    }

    /// 对应 Java `ApiMoveOrder` / `OrderCommandType.MOVE_ORDER`。
    pub fn move_order(&mut self, req: MoveOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::MoveOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            price: req.new_price,
            ..Default::default()
        };
        self.run(cmd)
    }

    /// 对应 Java `ApiReduceOrder` / `OrderCommandType.REDUCE_ORDER`。
    pub fn reduce_order(&mut self, req: ReduceOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::ReduceOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            size: req.reduce_size,
            ..Default::default()
        };
        self.run(cmd)
    }

    /// 对应 Java `ApiPlaceOrder`（期货，带杠杆/保证金模式/只减仓标志）/ `OrderCommandType.PLACE_ORDER`。
    pub fn place_futures_order(&mut self, req: PlaceFuturesOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
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
        self.run(cmd)
    }

    /// 对应 Java `ApiClosePosition` / `OrderCommandType.CLOSE_POSITION`。
    pub fn close_position(&mut self, req: ClosePositionRequest) -> CommandResultCode {
        let cmd = OrderCommand {
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
        self.run(cmd)
    }

    pub fn margin_adjustment(&mut self, req: MarginAdjustmentRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::MarginAdjustment,
            uid: req.uid,
            symbol: req.symbol,
            action: Some(req.action),
            price: req.amount,
            margin_mode: req.margin_mode,
            order_id: req.order_id,
            ..Default::default()
        };
        self.run(cmd)
    }

    /// 对应 Java `ApiChangeLeverage` / `OrderCommandType.LEVERAGE_ADJUSTMENT`。
    pub fn leverage_adjustment(&mut self, uid: i64, symbol: i32, leverage: i32) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::LeverageAdjustment,
            uid,
            symbol,
            leverage,
            ..Default::default()
        };
        self.run(cmd)
    }

    /// 对应 Java `ApiPositionModeAdjustment` / `OrderCommandType.POSITION_MODE_ADJUSTMENT`：
    /// `hedge=true` 即双向持仓模式（借用 `action=Bid` 编码，无独立布尔字段）。
    pub fn adjust_position_mode(&mut self, uid: i64, hedge: bool) -> CommandResultCode {
        let action = if hedge { OrderAction::Bid } else { OrderAction::Ask };
        self.run(OrderCommand { command: OrderCommandType::PositionModeAdjustment, uid, action: Some(action), ..Default::default() })
    }

    /// 对应 Java `ApiMarkPriceAdjustment` / `OrderCommandType.MARKPRICE_ADJUSTMENT`：可能触发强平级联
    /// （见 `ExchangeCore::run_liquidation_cascade`）。
    pub fn set_mark_price(&mut self, symbol: i32, price: i64) -> CommandResultCode {
        self.run(OrderCommand {
            command: OrderCommandType::MarkpriceAdjustment,
            symbol,
            price,
            ..Default::default()
        })
    }

    /// 对应 Java `ApiSuspendUser` / `OrderCommandType.SUSPEND_USER`。
    pub fn suspend_user(&mut self, uid: i64) -> CommandResultCode {
        self.run(OrderCommand { command: OrderCommandType::SuspendUser, uid, ..Default::default() })
    }

    /// 对应 Java `ApiResumeUser` / `OrderCommandType.RESUME_USER`。
    pub fn resume_user(&mut self, uid: i64) -> CommandResultCode {
        self.run(OrderCommand { command: OrderCommandType::ResumeUser, uid, ..Default::default() })
    }

    /// 逃生口：直接提交任意已构造好的 `OrderCommand`，供上面没有专门包装方法的指令类型使用
    /// （例如测试或 Raft 状态机重放场景）。
    pub fn submit(&mut self, cmd: OrderCommand) -> CommandResultCode {
        self.run(cmd)
    }

    pub fn user_account(&self, uid: i64, currency: i32) -> i64 {
        self.core.ups.get(uid).map(|p| p.account(currency)).unwrap_or(0)
    }

    pub fn user_locked(&self, uid: i64, currency: i32) -> i64 {
        self.core.ups.get(uid).map(|p| p.locked(currency)).unwrap_or(0)
    }

    pub fn user_position(&self, uid: i64, symbol: i32) -> Option<&SymbolPositionRecord> {
        self.core.ups.get(uid).and_then(|p| p.positions.get(&symbol))
    }

    pub fn fees(&self, currency: i32) -> i64 {
        *self.core.risk.fees.get(&currency).unwrap_or(&0)
    }

    pub fn adjustments(&self, currency: i32) -> i64 {
        *self.core.risk.adjustments.get(&currency).unwrap_or(&0)
    }

    /// 对应 Java `ApiOrderBookRequest` / `OrderCommandType.ORDER_BOOK_REQUEST`：同步查询 L2 快照，
    /// 走完整 `process_command`（本身不改状态，只是复用统一入口以获得撮合簿只读快照）。
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

    /// 最近一次 `run`/`submit` 提交的完整命令回执；对应 Java `submitCommandAsyncFullResponse` 拿到的
    /// `OrderCommand`，这里换成同步取值。
    pub fn last_cmd(&self) -> &OrderCommand {
        self.last_cmd.as_ref().expect("no command submitted yet")
    }

    pub fn last_matcher_event(&self) -> Option<&crate::core::common::matcher_trade_event::MatcherTradeEvent> {
        self.last_cmd().matcher_event.as_deref()
    }

    pub fn last_fund_events(&self) -> &[crate::core::common::fund_event::FundEvent] {
        &self.last_cmd().fund_events
    }

    /// 上一次提交触发的强平/ADL 级联（`run_liquidation_cascade`）中，各级联子命令累积的 fund events；
    /// Java 侧对应级联命令异步经 raft 重提交、各自独立产出事件，这里因为级联同步内联完成而聚合暴露。
    pub fn cascade_fund_events(&self) -> &[crate::core::common::fund_event::FundEvent] {
        &self.core.last_cascade_events
    }

    /// 同 `cascade_fund_events`，聚合级联子命令产出的撮合事件（已 flatten，`next` 链已摘除）。
    pub fn cascade_matcher_events(&self) -> &[crate::core::common::matcher_trade_event::MatcherTradeEvent] {
        &self.core.last_cascade_matcher_events
    }

    // 底下三个直接暴露内部引擎子模块的只读访问器：Java 侧一般通过专门的 ReportQuery 间接读取，
    // 这里为测试/调试保留直接逃生口。
    pub fn ups(&self) -> &UserProfileService {
        &self.core.ups
    }

    pub fn ssp(&self) -> &SymbolSpecificationProvider {
        &self.core.ssp
    }

    pub fn risk(&self) -> &RiskEngine {
        &self.core.risk
    }

    // 以下报表查询转发到 `reports.rs`，对应 Java `common/api/reports/*ReportQuery`
    // + `ReportQueriesHandler`/`RiskEngine` 侧聚合逻辑，见该文件顶部注释。

    /// 对应 Java `TotalCurrencyBalanceReportQuery`。
    pub fn total_balance(&self) -> crate::core::reports::TotalCurrencyBalanceReport {
        self.core.query_total_balance()
    }

    /// 对应 Java `SingleUserReportQuery`。
    pub fn single_user(&self, uid: i64, now_ms: i64) -> crate::core::reports::SingleUserReport {
        self.core.query_single_user(uid, now_ms)
    }

    /// 对应 Java `InsuranceFundReportQuery`。
    pub fn insurance_fund(&self) -> crate::core::reports::InsuranceFundReport {
        self.core.query_insurance_fund()
    }

    /// 对应 Java `SymbolCurrencyReportQuery`。
    pub fn symbol_currency(&self) -> crate::core::reports::SymbolCurrencyReport {
        self.core.query_symbol_currency()
    }

    /// 对应 Java `FeeReportQuery`。
    pub fn fee_report(&self) -> crate::core::reports::FeeReport {
        self.core.query_fee_report()
    }

    /// 对应 Java `LoanPlatformReportQuery`。
    pub fn loan_platform(&self) -> crate::core::reports::LoanPlatformReport {
        self.core.query_loan_platform()
    }

    /// 对应 Java `StateHashReportQuery`：用于跨节点/跨语言一致性对拍（见 CONSISTENCY.md）。
    pub fn state_hash(&self) -> crate::core::reports::StateHashReport {
        self.core.query_state_hash()
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
        let rc = api.add_symbol(spot_spec_fixed_fee(0, 0));
        assert_eq!(rc, CommandResultCode::InvalidSymbol);
        assert!(api.ssp().get_symbol(SYMBOL).is_none());

        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(0, 0)), CommandResultCode::Success);
    }

    #[test]
    fn spot_ask_bid_full_match_settles_balances_and_conserves_globally() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(10, 5)), CommandResultCode::Success);

        assert_eq!(api.add_user(SELLER), CommandResultCode::Success);
        assert_eq!(api.add_user(BUYER), CommandResultCode::Success);

        assert_eq!(
            api.balance_adjustment(SELLER, BASE, 1_000, 1),
            CommandResultCode::Success
        );
        assert_eq!(
            api.balance_adjustment(BUYER, QUOTE, 100_000, 2),
            CommandResultCode::Success
        );

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

        assert_eq!(api.user_account(SELLER, BASE), 0);
        assert_eq!(api.user_locked(SELLER, BASE), 0);
        assert_eq!(api.user_account(SELLER, QUOTE), 45_000);

        assert_eq!(api.user_account(BUYER, BASE), 1_000);
        assert_eq!(api.user_account(BUYER, QUOTE), 40_000);
        assert_eq!(api.user_locked(BUYER, QUOTE), 0);

        assert_eq!(api.fees(QUOTE), 15_000);
        assert_eq!(api.fees(BASE), 0);

        assert_eq!(api.adjustments(BASE), -1_000);
        assert_eq!(api.adjustments(QUOTE), -100_000);

        let base_sum = api.user_account(SELLER, BASE) + api.user_account(BUYER, BASE)
            + api.adjustments(BASE)
            + api.fees(BASE);
        assert_eq!(base_sum, 0, "base conserved");

        let quote_sum = api.user_account(SELLER, QUOTE) + api.user_account(BUYER, QUOTE)
            + api.adjustments(QUOTE)
            + api.fees(QUOTE);
        assert_eq!(quote_sum, 0, "quote conserved");

        let l2 = api.request_l2(SYMBOL, 10);
        assert!(l2.bid_prices.is_empty(), "buyer fully filled, no residual resting order");
        assert!(l2.ask_prices.is_empty(), "seller ASK fully consumed");
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
        assert!(api.ssp().get_symbol(SYMBOL).is_none(), "rejected symbol must not be registered");
    }

    #[test]
    fn futures_long_short_full_match_then_close_settles_pnl_and_conserves_globally() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(
            api.add_futures_symbol(futures_spec_fixed_fee(10, 5)),
            CommandResultCode::Success
        );

        assert_eq!(api.add_user(LONG_USER), CommandResultCode::Success);
        assert_eq!(api.add_user(SHORT_USER), CommandResultCode::Success);

        assert_eq!(api.balance_adjustment(LONG_USER, QUOTE, 10_000, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(SHORT_USER, QUOTE, 10_000, 2), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);

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

        let long_pos = api.user_position(LONG_USER, FUT_SYMBOL).expect("long position record must exist after opening");
        assert_eq!(long_pos.direction, PositionDirection::Long);
        assert_eq!(long_pos.open_volume, 10);
        assert_eq!(long_pos.open_init_margin_sum, 1_000);
        assert_eq!(long_pos.open_price_sum, 1_000);
        assert_eq!(long_pos.profit, 0);

        let short_pos = api.user_position(SHORT_USER, FUT_SYMBOL).expect("short position record must exist after opening");
        assert_eq!(short_pos.direction, PositionDirection::Short);
        assert_eq!(short_pos.open_volume, 10);
        assert_eq!(short_pos.open_init_margin_sum, 1_000);
        assert_eq!(short_pos.open_price_sum, 1_000);
        assert_eq!(short_pos.profit, 0);

        assert_eq!(api.user_account(LONG_USER, QUOTE), 10_000 - 100, "taker fee = size(10)*taker_fee(10)");
        assert_eq!(api.user_account(SHORT_USER, QUOTE), 10_000 - 50, "maker fee = size(10)*maker_fee(5)");
        assert_eq!(api.user_locked(LONG_USER, QUOTE), 0, "futures margin does not occupy locked (pure virtual position field)");
        assert_eq!(api.user_locked(SHORT_USER, QUOTE), 0);
        assert_eq!(api.fees(QUOTE), 150);

        let conserved = |api: &ExchangeApi| {
            api.user_account(LONG_USER, QUOTE) + api.user_account(SHORT_USER, QUOTE)
                + api.adjustments(QUOTE)
                + api.fees(QUOTE)
        };
        assert_eq!(conserved(&api), 0, "quote conserved after opening position");

        assert_eq!(api.set_mark_price(FUT_SYMBOL, 150), CommandResultCode::Success);

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

        assert!(api.user_position(LONG_USER, FUT_SYMBOL).is_none(), "long position record must be torn down after full close");
        assert!(api.user_position(SHORT_USER, FUT_SYMBOL).is_none(), "short position record must be torn down after full close");

        assert_eq!(
            api.user_account(LONG_USER, QUOTE),
            10_000 - 100  - 100  + 500,
        );
        assert_eq!(
            api.user_account(SHORT_USER, QUOTE),
            10_000 - 50  - 50  - 500,
        );
        assert_eq!(api.fees(QUOTE), 150 + 100 + 50, "four fees accumulated: 150(open)+100+50(close)");

        assert_eq!(conserved(&api), 0, "quote still conserved after close settles PnL");

        let l2 = api.request_l2(FUT_SYMBOL, 10);
        assert!(l2.bid_prices.is_empty());
        assert!(l2.ask_prices.is_empty());
    }

    #[test]
    fn add_currencies_and_symbols_batch() {
        use crate::core::common::core_currency_specification::CoreCurrencySpecification;
        let mut api = ExchangeApi::new();
        api.add_currencies([
            CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() },
            CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() },
        ]);
        assert!(api.ssp().get_currency(BASE).is_some());
        assert!(api.ssp().get_currency(QUOTE).is_some());
        let s1 = CoreSymbolSpecification { symbol_id: 100, symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE, quote_currency: QUOTE, base_scale_k: 1, quote_scale_k: 1, ..Default::default() };
        let mut s2 = s1.clone(); s2.symbol_id = 101; s2.base_currency = QUOTE; s2.quote_currency = BASE;
        api.add_symbols([s1, s2]);
        assert!(api.ssp().get_symbol(100).is_some());
        assert!(api.ssp().get_symbol(101).is_some());
    }

    #[test]
    fn add_symbols_margin_gate_blocks_futures_when_disabled() {
        use crate::core::common::core_currency_specification::CoreCurrencySpecification;
        let mut api = ExchangeApi::new();
        api.add_currencies([
            CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() },
            CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() },
        ]);
        api.core.risk.cfg_margin_trading_enabled = false;
        let fut = CoreSymbolSpecification { symbol_id: 200, symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE, quote_currency: QUOTE, base_scale_k: 1, quote_scale_k: 1, ..Default::default() };
        let spot = CoreSymbolSpecification { symbol_id: 201, symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE, quote_currency: QUOTE, base_scale_k: 1, quote_scale_k: 1, ..Default::default() };
        api.add_symbols([fut, spot]);
        assert!(api.ssp().get_symbol(200).is_none(), "futures symbol gated/skipped when margin trading disabled (aligned with Java)");
        assert!(api.ssp().get_symbol(201).is_some(), "spot symbol always passes through");
    }

    #[test]
    fn add_accounts_batch_seeds_balance_and_conserves() {
        use crate::core::common::core_currency_specification::CoreCurrencySpecification;
        let mut api = ExchangeApi::new();
        api.add_currencies([CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() }]);
        api.add_accounts([(10i64, vec![(QUOTE, 1000i64)]), (11i64, vec![(QUOTE, 500i64)])]);
        assert_eq!(api.ups().get(10).unwrap().accounts.get(&QUOTE).copied().unwrap_or(0), 1000);
        assert_eq!(api.ups().get(11).unwrap().accounts.get(&QUOTE).copied().unwrap_or(0), 500);
        assert!(api.total_balance().is_global_zero(), "globally conserved after seeding (accounts +1500 / adjustments bucket -1500)");
        api.add_accounts([(10i64, vec![(QUOTE, 9999i64)])]);
        assert_eq!(api.ups().get(10).unwrap().accounts.get(&QUOTE).copied().unwrap_or(0), 1000, "existing uid skipped, not overwritten");
    }
}
