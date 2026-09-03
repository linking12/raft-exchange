//! 对应 Java: `exchange.core2.core.ExchangeCore`（Disruptor 五段编排入口）。
//! 设计文档 §4：本期塌缩为**单线程确定性顺序管线**——`process_command` 镜像 Java
//! disruptor 的业务阶段编排：`RiskEngine.preProcessCommand`（R1）→
//! `MatchingEngineRouter.processOrder`（ME）→ `RiskEngine.handlerRiskRelease`（R2）
//! 的单命令等价物（单 shard，无 grouping 批边界、无 Journal 落盘；`GroupingProcessor`/
//! `TwoStepMasterProcessor`/`TwoStepSlaveProcessor` 是纯 disruptor 线程编排构造，单线程
//! 确定性管道下无对应语义，刻意不建模——见 `process_command` 方法文档）。
//!
//! # Ruling P3-B（borrow 结构）
//! `ExchangeCore` 把 `risk`/`matching`/`ups`/`ssp` 都作为**平级字段**直接持有（非
//! `Rc<RefCell<_>>`）。`process_command` 里一律写 `self.risk.xxx(cmd, &mut self.ups, &self.ssp)`
//! 这种"字段直接访问 + 方法调用"的形式——Rust 借用检查器把 `self.risk`/`self.ups`/`self.ssp`
//! 识别为互不重叠的字段借用，允许同时可变/不可变借用，前提是不经过一个先拿 `&mut self`
//! 再在内部重新借用各字段的中间方法（那样借用检查器只看到一次对整个 `self` 的借用，
//! 无法证明字段级别不重叠）。因此本文件的 `process_command` 刻意不拆分成
//! `fn r1(&mut self, ..)` / `fn r2(&mut self, ..)` 这类私有辅助方法。
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::margin_mode::MarginMode;
use crate::core::processors::matching_engine_router::MatchingEngineRouter;
use crate::core::processors::risk_engine::RiskEngine;

/// 对应 Java `ExchangeCore`（现货子集，单 shard）。
#[derive(Default, serde::Serialize, serde::Deserialize)]
pub struct ExchangeCore {
    pub risk: RiskEngine,
    pub matching: MatchingEngineRouter,
    pub ups: UserProfileService,
    pub ssp: SymbolSpecificationProvider,
}

impl ExchangeCore {
    pub fn new() -> Self {
        ExchangeCore {
            risk: RiskEngine::new(),
            matching: MatchingEngineRouter::new(),
            ups: UserProfileService::new(),
            ssp: SymbolSpecificationProvider::new(),
        }
    }

    /// 确定性顺序管线（设计文档 §4）。镜像 Java `ExchangeCore` 的 disruptor 阶段编排——
    /// Grouping → R1(`RiskEngine.preProcessCommand`) → ME(`MatchingEngineRouter.processOrder`)
    /// → R2(`RiskEngine.handlerRiskRelease`) → ResultsHandler：
    /// - **Grouping**：Java 里是 disruptor 的批边界处理器（决定一批命令何时切段落盘/发布），
    ///   纯线程编排概念，单线程确定性管道下没有对应语义，本移植不建模。
    /// - **R1** [`RiskEngine::pre_process_command`]：主 switch 路由——非交易命令
    ///   （`AddUser`/`BalanceAdjustment`/...）整块委托处理；`PlaceOrder` 走风控冻结；
    ///   Cancel/Move/Reduce/OrderBookRequest 等 R1 无动作。
    /// - **ME** [`MatchingEngineRouter::process_order`]：按 symbol 路由到 order book；
    ///   非交易命令在这里 no-op 短路（不查 book、不碰 `result_code`）；`PlaceOrder` 只有
    ///   R1 放行（`result_code == ValidForMatchingEngine`）才真正撮合。
    /// - **R2** [`RiskEngine::handler_risk_release`]：结算成交/释放冻结，读 `cmd.matcher_event`
    ///   并消费之；非交易命令在这里同样 no-op 短路。
    /// - **ResultsHandler**：Java 里是 disruptor 消费者，把结果发布/回调给调用方；本移植是
    ///   同步调用模型，`cmd.result_code`/`market_data` 等字段处理完就地可读，调用方
    ///   （`ExchangeApi`）直接读 `cmd`，无需独立的结果分发阶段。
    ///
    /// 现在**所有**命令都依次流过 R1→ME→R2 这三段（不再有"非交易命令整体跳过 ME/R2"的分支），
    /// 对齐 Java 全部命令都过 disruptor 三个处理器的结构；非交易命令在 ME/R2 各自的 no-op
    /// 守卫下与旧版"直接跳过"完全等价（见 `matching_router.rs`/`risk.rs` 对应守卫的注释）。
    pub fn process_command(&mut self, cmd: &mut OrderCommand) {
        self.risk.pre_process_command(cmd, &mut self.ups, &self.ssp); // R1
        self.matching.process_order(cmd); // ME
        self.risk.handler_risk_release(cmd, &mut self.ups, &self.ssp); // R2
        self.drain_liquidation_commands();
    }

    /// P6 Task 7b：排空强平引擎的提交队列（`LiquidationEngine::pending_commands`），把检测/状态机
    /// 推进产出的 `FORCE_LIQUIDATION`/`IF_TAKEOVER`/`AUTO_DELEVERAGING` 命令依次喂回完整管线
    /// （R1→ME→R2）——替代 Java disruptor `submit` 的 ring 重入（本移植单管线无总线，见
    /// `liquidation_engine.rs` 模块文档"submit → 队列"）。
    ///
    /// **FIFO + 逐条弹出**：处理一条生成命令时可能再入队后继命令（FORCE REJECT→IF、IF REJECT→ADL），
    /// 故每轮重新检查队首而非一次性快照——保持提交顺序。**有界终止**：每条 FORCE/IF/ADL 的 R2
    /// `advance_liquidation` 至多再产出一条下一级命令（FORCE→IF→ADL→终态，ADL 恒终态），生成命令的
    /// R1 不触发 `check_positions`（只有 markprice/scan/funding 触发），故链深≤3/仓位，必然收敛。
    fn drain_liquidation_commands(&mut self) {
        while !self.risk.liquidation_engine.pending_commands.is_empty() {
            let mut generated = self.risk.liquidation_engine.pending_commands.remove(0);
            self.risk.pre_process_command(&mut generated, &mut self.ups, &self.ssp);
            self.matching.process_order(&mut generated);
            self.risk.handler_risk_release(&mut generated, &mut self.ups, &self.ssp);
        }
    }

    // ========================================================================
    // Snapshot 序列化（对应 Java 各处理器的 `writeMarshallable`/`BytesIn` + `updateProvider`
    // 重建）。本移植用 serde derive + bincode 一次性序列化整个 `ExchangeCore` 复制态；跨节点一致性
    // 由 `state_hash` 保证，快照只需 **round-trip 保真**（序列化→反序列化→状态等价），不要求与 Java
    // 字节格式互通（本移植是独立实现）。
    //
    // # 非复制状态的处理（Ruling P6-E）
    // `RiskEngine.liquidation_engine`（含 loan 扫描器）整体 `#[serde(skip)]`；`SymbolPositionRecord`
    // 的 `liquidation_flow`/`adl_eligibility`/`pending_adl_size` 三个 scratch 字段 `#[serde(skip)]`。
    // 反序列化后经 [`Self::restore_non_replicated_state`] 复原到"换届后新 leader"语义（等价 Java
    // `updateProvider`）：非复制字段重置/按 margin_mode 归一，索引从复原的 `ups` 重建。
    // ========================================================================

    /// 把整个复制态序列化成快照字节（bincode）。非复制 leader-local 状态经 `#[serde(skip)]` 自动
    /// 排除（`liquidation_engine` + 三个 SPR scratch 字段）。
    pub fn to_snapshot_bytes(&self) -> Vec<u8> {
        bincode::serialize(self).expect("snapshot serialize must not fail on in-memory state")
    }

    /// 从快照字节恢复 `ExchangeCore`。反序列化后调用 [`Self::restore_non_replicated_state`] 复原
    /// 非复制状态（等价 Java 快照恢复经 `updateProvider` 重建 scanner 索引 + 非复制字段）。
    pub fn from_snapshot_bytes(bytes: &[u8]) -> Self {
        let mut core: ExchangeCore =
            bincode::deserialize(bytes).expect("snapshot deserialize must not fail on trusted snapshot");
        core.restore_non_replicated_state();
        core
    }

    /// 复原非复制 leader-local 状态到"换届后新 leader"语义（对应 Java `updateProvider` 在快照恢复
    /// 时做的事）：
    /// 1. 每个仓位的 `adl_eligibility` 按 `margin_mode` 归一（ISOLATED=100 / CROSS=0，同 `new`/
    ///    `initialize`）——`#[serde(skip)]` 反序列化后为 `0`，ISOLATED 必须补回 `100` 否则
    ///    `risk_score` 恒 0、ADL 永远选不中它。`pending_adl_size`/`liquidation_flow` 已是 Default
    ///    （`0`/`None`），即"无预留、无进行中流程"的 fresh-leader 态。
    /// 2. 重建 `liquidation_engine` 的两类 targeted 索引（`symbol_to_users` + loan 扫描器双索引）
    ///    ——`liquidation_engine` 整体是 `Default`（`is_running=false` follower 起步），索引从复原的
    ///    `ups` 扫出（futures 持仓 → `on_position_opened`；loan → `rebuild_indices`）。
    fn restore_non_replicated_state(&mut self) {
        // 1. 仓位非复制 scratch 字段归一。
        for up in self.ups.users.values_mut() {
            for pos in up.positions.values_mut() {
                pos.adl_eligibility = if pos.margin_mode == MarginMode::Isolated { 100 } else { 0 };
                pos.pending_adl_size = 0;
                pos.liquidation_flow = None;
            }
        }
        // 2. 重建 liquidation_engine 索引（futures symbol_to_users）。
        let le = &mut self.risk.liquidation_engine;
        for up in self.ups.users.values() {
            for pos in up.positions.values() {
                if pos.open_volume == 0 {
                    continue;
                }
                if let Some(spec) = self.ssp.get_symbol(pos.symbol) {
                    if spec.symbol_type.is_futures_contract() {
                        le.on_position_opened(up.uid, pos.symbol);
                    }
                }
            }
        }
        // loan 扫描器双索引重建。
        le.loan_liquidation_engine.rebuild_indices(&self.ups);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::cmd::order_command::OrderCommand;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn seeded_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&spot_spec());
        core
    }

    #[test]
    fn non_trading_add_user_does_not_touch_matching_router() {
        let mut core = seeded_core();
        let mut cmd =
            OrderCommand { command: OrderCommandType::AddUser, uid: 1, ..Default::default() };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert!(core.ups.get(1).is_some());
        // 非交易命令不进 ME：cmd.market_data/matcher_event 均未被触碰。
        assert!(cmd.matcher_event.is_none());
        assert!(cmd.market_data.is_none());
    }

    #[test]
    fn non_trading_balance_adjustment_credits_account_and_hedges_adjustments() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        let mut cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 1,
            symbol: QUOTE,
            price: 500,
            order_id: 42,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(1).unwrap().account(QUOTE), 500);
        assert_eq!(*core.risk.adjustments.get(&QUOTE).unwrap(), -500);
    }

    #[test]
    fn trading_place_order_risk_rejected_never_reaches_book() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        // 无余额充值：BID 下单必 NSF。
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::RiskNsf));
        // R2 应为 no-op（无 matcher_event）；locked 应保持 0（R1 拒绝，未冻结）。
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 0);

        // 用 OrderBookRequest 确认簿仍为空（ME 没有把这笔拒绝单挂上去）。
        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: SYMBOL,
            size: 10,
            ..Default::default()
        };
        core.process_command(&mut req);
        let md = req.market_data.unwrap();
        assert!(md.bid_prices.is_empty());
    }

    #[test]
    fn trading_place_order_valid_reaches_book_and_locks_funds() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        core.ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_000_000);
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        // 无对手盘：resting on book，全额冻结（taker_fee=0 → 冻结额=notional=50000）。
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 50_000);

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: SYMBOL,
            size: 10,
            ..Default::default()
        };
        core.process_command(&mut req);
        let md = req.market_data.unwrap();
        assert_eq!(md.bid_prices, vec![50]);
        assert_eq!(md.bid_volumes, vec![1000]);
    }

    #[test]
    fn cancel_order_is_r1_no_op_and_releases_lock_via_r2() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        core.ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_000_000);
        let mut place = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };
        core.process_command(&mut place);
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 50_000);

        let mut cancel = OrderCommand {
            command: OrderCommandType::CancelOrder,
            order_id: 1,
            symbol: SYMBOL,
            uid: 1,
            ..Default::default()
        };
        core.process_command(&mut cancel);

        assert_eq!(cancel.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 0, "R2 应释放全部冻结");
    }
}

// ============================================================================
// P5 Task 7：LOAN_FORCE_LIQUIDATE / LOAN_CROSS_FORCE_LIQUIDATE 全链路（R1 pre-move → ME 撮合
// → R2 结算/LIF 接管）集成测试。参考文档 §2.5/§2.10/§5.2/§6.3。
//
// 单独一个 `mod`（而非塞进上面既有 `mod tests`）：需要专属的借贷 loan_config/collateral_weight
// 治具，且大量用例要跨 R1→ME→R2 三段（`core.process_command`），与上面 `mod tests` 的现货/期货
// 基础流水线用例关注点不同。
// ============================================================================
#[cfg(test)]
mod loan_force_liquidate_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::cross_loan_record::CrossLoanRecord;
    use crate::core::common::isolated_loan_record::IsolatedLoanRecord;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;

    const BASE: i32 = 1; // collateral / Cross selling currency
    const QUOTE: i32 = 2; // loan currency (Isolated + Cross target)
    const SYMBOL: i32 = 100; // base=BASE/quote=QUOTE
    const BORROWER: i64 = 10;
    const MAKER: i64 = 20;
    const LOAN_ID: i64 = 42;

    fn loan_spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn seeded_loan_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(loan_spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&loan_spot_spec());
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(MAKER);
        core
    }

    /// 借出 `principal` QUOTE 给借款人、锁 `collateral` BASE 抵押，登记进 `isolated_loans`；
    /// 池子桶同步走一遍 disburse 记账，供守恒断言使用（对应 `handleLoanCreate` 的记账副作用，
    /// 这里跳过 LTV/pool-capacity 校验直接构造，聚焦本任务的强平结算逻辑）。
    fn open_isolated_loan(core: &mut ExchangeCore, loan_id: i64, collateral: i64, principal: i64, rate_bps: i32, opened_at_ts: i64) {
        core.risk.loan_service.add_to_loan_pool_available(QUOTE, 1_000_000);
        {
            let borrower = core.ups.get_mut(BORROWER).unwrap();
            borrower.add_to_account(BASE, collateral);
            let mut loan = IsolatedLoanRecord::new(BORROWER, loan_id, SYMBOL, BASE, QUOTE, rate_bps, opened_at_ts);
            loan.outstanding_principal = principal;
            loan.collateral_amount = collateral;
            borrower.isolated_loans.insert(loan_id, loan);
        }
        let borrower = core.ups.get_mut(BORROWER).unwrap();
        core.risk.loan_service.disburse_loan(borrower, QUOTE, principal);
    }

    fn fund_maker_and_rest_bid(core: &mut ExchangeCore, order_id: i64, price: i64, size: i64) {
        core.ups.get_mut(MAKER).unwrap().add_to_account(QUOTE, 1_000_000_000);
        let mut maker_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            symbol: SYMBOL,
            price,
            size,
            reserve_bid_price: price,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: MAKER,
            timestamp: 1_000,
            ..Default::default()
        };
        core.process_command(&mut maker_cmd);
        assert_eq!(maker_cmd.result_code, Some(CommandResultCode::Success));
    }

    fn force_liquidate_cmd(order_id: i64, loan_id: i64, price: i64, lots: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanForceLiquidate,
            order_id,
            symbol: SYMBOL,
            price,
            size: lots,
            reserve_bid_price: loan_id,
            uid: BORROWER,
            timestamp: ts,
            ..Default::default()
        }
    }

    /// 全局守恒（参考文档 §6.2 telescoped 恒等式）：`Σ_user accounts[c] + loanPoolAvailable[c] +
    /// interestRevenue[c] + loanInsuranceFund[c] + fees[c] + adjustments[c]` 在任意操作前后必须
    /// 相等（`loanPoolBorrowed` 是 tracker，明确排除；`exchangeLocked`/`loanCollateral` 是
    /// `accounts` 内部的记账细分，telescope 后天然抵消，不需要单独出现在等式里）。
    fn conserved_total(core: &ExchangeCore, currency: i32) -> i64 {
        let accounts_sum: i64 = core.ups.users.values().map(|u| u.account(currency)).sum();
        accounts_sum
            + core.risk.loan_service.get_loan_pool_available(currency)
            + core.risk.loan_service.get_interest_revenue(currency)
            + core.risk.loan_service.get_loan_insurance_fund(currency)
            + *core.risk.fees.get(&currency).unwrap_or(&0)
            + *core.risk.adjustments.get(&currency).unwrap_or(&0)
    }

    // ================================================================
    // Isolated
    // ================================================================

    #[test]
    fn isolated_force_liquidate_full_fill_removes_loan_and_conserves() {
        let mut core = seeded_loan_core();
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 0, 1_000);
        fund_maker_and_rest_bid(&mut core, 1, 1, 2_000);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success)); // ME always reports Success once routed
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "fully repaid loan removed");
        assert_eq!(borrower.account(BASE), 0); // all collateral sold
        assert_eq!(borrower.locked(BASE), 0);
        // received_quote=1000, liqFee=ceil(1000*200/10000)=20 -> LIF, principal=500 fully repaid,
        // 480 overpay stays with the borrower on top of the 500 originally disbursed.
        assert_eq!(borrower.account(QUOTE), 500 + 480);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000); // 999_500 + 500 repaid
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    #[test]
    fn isolated_force_liquidate_partial_fill_keeps_loan_with_updated_snapshot() {
        let mut core = seeded_loan_core();
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 0, 1_000);
        fund_maker_and_rest_bid(&mut core, 1, 1, 400); // maker can only absorb 400 of the 1000 requested

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        let loan = borrower.isolated_loans.get(&LOAN_ID).expect("partial fill keeps the loan open");
        // received_quote=400, liqFee=ceil(400*200/10000)=8, principal_part=min(392,500)=392.
        assert_eq!(loan.outstanding_principal, 500 - 392);
        assert_eq!(loan.accumulated_interest, 0);
        assert_eq!(loan.collateral_amount, 600); // 600 lots rejected, refunded back onto the loan
        assert_eq!(borrower.account(BASE), 600); // 1000 - 400 sold; matches the still-locked collateral
        assert_eq!(borrower.locked(BASE), 0); // reject + trade together released the full R1 pre-move lock
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 8); // fee skim only, no takeover
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 999_500 + 392);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    /// 全 REJECT（无对手盘）：collateral 全额退回 loan.collateralAmount；`accrue_to` 补计的 pending
    /// 利息（10% 年化，满 1 年）被正确纳入 remainDebt；`tradedSize==0 && remainDebt>0` 触发 LIF
    /// 接管——LIF 两币变化（QUOTE 变负=已垫资，BASE 变正=收到抵押），collateral 从 `accounts`
    /// 真实物理扣除，全局守恒仍为零。
    #[test]
    fn isolated_force_liquidate_all_reject_refunds_collateral_accrues_interest_then_takes_over() {
        let mut core = seeded_loan_core();
        // 10% annual rate, opened exactly one YEAR_MS before the liquidation timestamp.
        const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 1_000, 1_000);
        // No maker order at all: the book is empty, so the IOC ASK is rejected in full.

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(1, LOAN_ID, 1, 1_000, 1_000 + YEAR_MS);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "taken over -> removed");
        assert_eq!(borrower.locked(BASE), 0); // reject released the R1 pre-move lock
        assert_eq!(borrower.account(BASE), 0); // then LIF physically debited the refunded collateral
        assert_eq!(borrower.account(QUOTE), 500); // unchanged: no trade, no proceeds, no repayment

        // remainDebt = principal(500) + accrued interest (10% * 1yr on 500 = 50) = 550.
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), -550);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(BASE), 1_000); // full collateral taken
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 50);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 999_500 + 500);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    /// 卖不动（sub-lot 尘埃）+ remainDebt>0 → LIF 接管，且这一次是通过 `sellableLots==0` 分支
    /// （不是 `tradedSize==0`——本例确实成交了）。用 `base_scale_k=1 < currency_scale_k=100`
    /// 制造"1 lot = 100 currency 单位"的粒度：初始抵押 1050（10 lots + 50 尘埃），本轮只申请
    /// 卖 10 lots（=1000 单位），全部成交后残留的 50 单位是不足一张的死尘埃，
    /// `collateral_amount_to_lots(50,...) == 0`，即便还有余值也永远卖不动。
    #[test]
    fn isolated_force_liquidate_dust_after_partial_debt_coverage_triggers_takeover_via_sellable_lots_zero() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1, // 1 lot = 100 currency units of BASE (100/1)
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(MAKER);

        // principal=2000 far exceeds what a single trade can cover, so remainDebt>0 survives settlement.
        open_isolated_loan(&mut core, LOAN_ID, 1_050, 2_000, 0, 1_000);
        // Maker rests at price=100 so the notional is meaningful relative to the 2000 principal.
        fund_maker_and_rest_bid(&mut core, 1, 100, 20);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        // Request exactly the 10 sellable lots (1050/100 truncated) -> sellAmount=1000, leaving 50 dust.
        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 100, 10, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "taken over -> removed");
        // Traded 10 lots @100 -> notional=1000 -> receivedQuote=1000 -> liqFee=ceil(1000*200/10000)=20
        // -> principal_part=min(980,2000)=980 -> remaining principal=1020, all absorbed by LIF.
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(BASE), 50); // only the sub-lot dust
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 2_000 + 980 + 1_020);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        // 1050 initial - 1000 sold (physically debited by the trade) - 50 dust (physically taken by LIF) = 0.
        assert_eq!(borrower.account(BASE), 0);
        assert_eq!(borrower.locked(BASE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    // ================================================================
    // Cross
    // ================================================================

    const SELL_CUR: i32 = 3; // Cross collateral / selling currency (distinct from Isolated's BASE=1)

    fn cross_seeded_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: SELL_CUR, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: SELL_CUR,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(MAKER);
        core.risk.loan_service.global_config.numeraire_currency = QUOTE;
        core.risk.last_price_cache.insert(SYMBOL, 1); // markPrice=1, scale-identity valueInNumeraire
        core
    }

    /// 开一笔 Cross 债务（loanCurrency=QUOTE），并把 `collateral` SELL_CUR 记进
    /// `crossLoanCollateral`（账户级）；池子桶同步走一遍 disburse 记账。`collateral_weight_bps`
    /// 由调用方在此之前/之后自行设置在 `ssp.currencies` 上（本函数不假设任何值）。
    fn open_cross_loan(core: &mut ExchangeCore, loan_id: i64, collateral: i64, principal: i64) {
        core.risk.loan_service.add_to_loan_pool_available(QUOTE, 1_000_000);
        {
            let borrower = core.ups.get_mut(BORROWER).unwrap();
            borrower.add_to_account(SELL_CUR, collateral);
            borrower.add_to_cross_loan_collateral(SELL_CUR, collateral);
            let mut loan = CrossLoanRecord::new(BORROWER, loan_id, SYMBOL, QUOTE, 0, 1_000);
            loan.outstanding_principal = principal;
            borrower.cross_loans.insert(loan_id, loan);
        }
        let borrower = core.ups.get_mut(BORROWER).unwrap();
        core.risk.loan_service.disburse_loan(borrower, QUOTE, principal);
    }

    fn cross_force_liquidate_cmd(order_id: i64, target_loan_id: i64, price: i64, lots: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossForceLiquidate,
            order_id,
            symbol: SYMBOL,
            price,
            size: lots,
            reserve_bid_price: target_loan_id,
            uid: BORROWER,
            timestamp: ts,
            ..Default::default()
        }
    }

    /// 结构性不可卖（`collateralWeightBps==0`）→ 目标 loan 被 LIF 接管，即便这次强平确实成交
    /// （`tradedSize>0`，走的是 `allCollateralExhausted` 分支而非 `tradedSize==0`）。本次提交只
    /// 申请卖出账户级抵押池 2000 中的 1000（模拟"这轮只打算卖这么多"），剩下未触及的 1000 因为
    /// weight=0 永久不算数——`isStructurallySellable` 直接因 amount>0 但 weight<=0 短路为
    /// false，与"这轮刚好全卖光了所以 amount==0"是两回事。
    #[test]
    fn cross_force_liquidate_structurally_unsellable_triggers_target_takeover() {
        let mut core = cross_seeded_core();
        core.ssp.currencies.get_mut(&SELL_CUR).unwrap().collateral_weight_bps = 0; // structurally ineligible
        open_cross_loan(&mut core, LOAN_ID, 2_000, 2_000); // principal far exceeds what this trade can cover
        fund_maker_and_rest_bid(&mut core, 1, 1, 2_000);

        let before_quote = conserved_total(&core, QUOTE);
        let before_sell = conserved_total(&core, SELL_CUR);

        let mut cmd = cross_force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.cross_loans.contains_key(&LOAN_ID), "taken over -> removed");
        // Untouched remainder of the selling-currency pool (2000-1000 requested this round) stays put:
        // weight=0 means it was never eligible collateral in the first place, so the LIF takes none of it.
        assert_eq!(borrower.cross_loan_collateral(SELL_CUR), 1_000);
        // received_quote=1000, liqFee=20, principal_part=min(980,2000)=980 -> remaining debt=1020,
        // fully absorbed by LIF with zero collateral recovery (weight=0 -> totalCollateralInNum=0).
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(SELL_CUR), 0);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);

        assert_eq!(conserved_total(&core, QUOTE), before_quote);
        assert_eq!(conserved_total(&core, SELL_CUR), before_sell);
    }

    /// 全抵押结构性耗尽 → 目标 loan 之外，账户其余未偿 Cross 债务（loanId 50、90）也按升序一并
    /// 交给 LIF——`BTreeMap` 天然升序迭代对齐 Java 显式 `Arrays.sort` 后的效果。三笔债务全部清零
    /// 移除，`cross_loans` 归空；weight=0 使 LIF 不额外拿抵押，但债务侧的池子/LIF 记账仍需守恒。
    #[test]
    fn cross_force_liquidate_all_exhausted_sweeps_remaining_loans_in_ascending_order() {
        let mut core = cross_seeded_core();
        core.ssp.currencies.get_mut(&SELL_CUR).unwrap().collateral_weight_bps = 0;
        open_cross_loan(&mut core, LOAN_ID, 2_000, 2_000); // target: 71-equivalent id=42
        // Two more untouched Cross debts on the same account, deliberately inserted out of order
        // to prove the sweep itself imposes ascending loanId order rather than insertion order.
        {
            let borrower = core.ups.get_mut(BORROWER).unwrap();
            let mut loan90 = CrossLoanRecord::new(BORROWER, 90, SYMBOL, QUOTE, 0, 1_000);
            loan90.outstanding_principal = 700;
            borrower.cross_loans.insert(90, loan90);
            let mut loan50 = CrossLoanRecord::new(BORROWER, 50, SYMBOL, QUOTE, 0, 1_000);
            loan50.outstanding_principal = 300;
            borrower.cross_loans.insert(50, loan50);
        }
        core.risk.loan_service.add_to_loan_pool_borrowed(QUOTE, 700 + 300);
        fund_maker_and_rest_bid(&mut core, 1, 1, 2_000);

        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = cross_force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(borrower.cross_loans.is_empty(), "target + both remaining loans all swept");

        // Target: 2000 principal, 980 repaid by trade proceeds -> 1020 taken over.
        // Loan 50: 300 taken over whole (no accrual, rate=0). Loan 90: 700 taken over whole.
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020 - 300 - 700);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 0);

        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }
}

// ============================================================================
// P6 Task 7b：期货强平全链路 e2e（markprice 触发检测 → FORCE→IF→ADL 状态机 → 队列排空重喂 →
// collect_liquidation_fee + advance_liquidation + normalize + 索引维护）。参考文档 §1。
//
// 直接对 `ExchangeCore` 写（而非 `ExchangeApi`）：需要 `core.risk.liquidation_engine.is_running`
// 的 leader 门开关与 `liquidation_service`（IFNotional）的可变/只读访问，`ExchangeApi` 未暴露。
// ============================================================================
#[cfg(test)]
mod liquidation_engine_e2e_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_type::SymbolType;
    use std::collections::BTreeMap;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const FUT: i32 = 400;
    const BORROWER: i64 = 10; // 被强平者
    const M1: i64 = 20; // 开仓对手（借款人开 LONG 时的 maker SHORT）
    const M2: i64 = 30; // 强平吸单方（FORCE ASK 的 BID 对手）

    /// 期货 spec：MM 单档 5%（rate=500, scale=10000）；base/quote scale=1（恒等缩放，守恒可直接
    /// 加 IFNotional.available）。
    fn fut_spec() -> CoreSymbolSpecification {
        let mut mm = BTreeMap::new();
        mm.insert(i64::MAX, 500);
        CoreSymbolSpecification {
            symbol_id: FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 10_000,
            liquidation_fee: 200, // 2%（proportional，fee_scale_k=10000）
            ..Default::default()
        }
    }

    fn seeded() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        let spec = CoreSymbolSpecification { fee_scale_k: 10_000, ..fut_spec() };
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        for uid in [BORROWER, M1, M2] {
            core.ups.add_empty_user_profile(uid);
            core.ups.get_mut(uid).unwrap().add_to_account(QUOTE, 10_000_000);
        }
        core.risk.liquidation_engine.is_running = true; // leader
        core
    }

    fn fut_order(order_id: i64, uid: i64, price: i64, size: i64, action: OrderAction, order_type: OrderType, leverage: i32) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            uid,
            symbol: FUT,
            price,
            size,
            reserve_bid_price: price,
            action: Some(action),
            order_type: Some(order_type),
            leverage,
            margin_mode: MarginMode::Isolated,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    fn markprice(price: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price, timestamp: ts, ..Default::default() }
    }

    /// 守恒（含 IF，Ruling P6-I）：Σaccounts + fees + adjustments + Σ(仓位 estimate_pnl(mark)+
    /// extra_margin) + Σ IFNotional.available + Σ IF 接管仓 estimate_pnl(mark)。scale 全 1 故 IF
    /// 项（notional 单位）可直接相加。
    fn conserved(core: &ExchangeCore) -> i64 {
        let cur = QUOTE;
        let mark = *core.risk.last_price_cache.get(&FUT).unwrap_or(&0);
        let mut total: i64 = core.ups.users.values().map(|u| u.account(cur)).sum();
        total += *core.risk.fees.get(&cur).unwrap_or(&0);
        total += *core.risk.adjustments.get(&cur).unwrap_or(&0);
        for u in core.ups.users.values() {
            for p in u.positions.values() {
                if p.currency == cur {
                    total += p.estimate_pnl(mark) + p.extra_margin;
                }
            }
        }
        for n in core.risk.liquidation_service.notionals.values() {
            total += n.available;
        }
        for ifp in core.risk.liquidation_service.positions.values() {
            let sign = ifp.direction.multiplier() as i64;
            total += sign * (mark * ifp.open_volume - ifp.open_price_sum);
        }
        total
    }

    /// 开借款人 LONG 10@100、leverage 10（margin=100）：M1 挂 ASK@100（开 SHORT），借款人 BID@100 吃单。
    fn open_borrower_long(core: &mut ExchangeCore) {
        let mut m1 = fut_order(1, M1, 100, 10, OrderAction::Ask, OrderType::Gtc, 10);
        core.process_command(&mut m1);
        assert_eq!(m1.result_code, Some(CommandResultCode::Success));
        let mut b = fut_order(2, BORROWER, 100, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut b);
        assert_eq!(b.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(BORROWER).unwrap().positions[&FUT].direction, PositionDirection::Long);
        assert_eq!(core.ups.get(BORROWER).unwrap().positions[&FUT].open_volume, 10);
    }

    #[test]
    fn on_position_opened_indexes_borrower_and_makers() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000)); // 健康，无触发
        open_borrower_long(&mut core);
        // 借款人 LONG + M1 SHORT 都应进 symbol_to_users 索引（新仓 commit 时登记）。
        let holders = core.risk.liquidation_engine.symbol_to_users.get(&FUT).expect("索引应有该 symbol");
        assert!(holders.contains(&BORROWER));
        assert!(holders.contains(&M1));
    }

    #[test]
    fn markprice_drop_triggers_full_liquidation_collects_fee_to_if_and_conserves() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        // 破产价 = ceil_mul_div(open_price_sum - margin_base, fee_scale_k, open_volume*(fee_scale_k -
        // total_fee)) = ceil_mul_div(1000-100, 10000, 10*(10000-200)) = ceil(9000000/98000) = 92。
        // M2 挂 BID@92 size10（resting），恰好吸收 FORCE ASK@92。
        let mut m2 = fut_order(3, M2, 92, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);
        assert_eq!(m2.result_code, Some(CommandResultCode::Success));

        // mark 跌到 94：借款人 LONG（avg100/lev10/margin100）equity=100-60=40 < MM=47 -> 触发强平。
        let before = conserved(&core);
        core.process_command(&mut markprice(94, 2_000));

        // FORCE 已由 markprice 钩子生成并被 drain_liquidation_commands 排空重喂、成交平仓。
        assert!(
            core.risk.liquidation_engine.pending_commands.is_empty(),
            "队列必须被排空（生成的 FORCE 已处理）"
        );
        assert!(
            !core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT),
            "借款人 LONG 被 FORCE 全平，仓位移除"
        );
        // 清算费进 IFNotional.available（成交 10@92 -> notional 920 -> fee=ceil(920*200/10000)=19）。
        // 精确 fee 公式由 Task 1 `calculate_liquidation_fee` 测试覆盖，这里只验证"费用确实计入 IF"。
        let if_available: i64 = core.risk.liquidation_service.notionals.values().map(|n| n.available).sum();
        assert!(if_available > 0, "清算费必须计入 IFNotional.available");
        // 全局守恒（含 IF 项）在强平前后不变。
        assert_eq!(conserved(&core), before, "强平（含清算费转入 IF）全局守恒");
    }

    #[test]
    fn direct_force_size_clamped_by_normalize() {
        // 直接投递一条 size 远超 open_volume 的 FORCE（模拟陈旧/换届命令）——normalize 必须夹到
        // open_volume，绝不超平。走 advance_liquidation 的 flow=None + FORCE recovery 路径。
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core); // 借款人 LONG 10
        let mut m2 = fut_order(3, M2, 90, 100, OrderAction::Bid, OrderType::Gtc, 10); // 充足 BID 流动性
        core.process_command(&mut m2);

        let before = conserved(&core);
        // FORCE ASK size=999（>> 10），限价 90。normalize 夹到 10。
        let mut force = OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id: 42,
            uid: BORROWER,
            symbol: FUT,
            price: 90,
            size: 999,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Ioc),
            timestamp: 3_000,
            ..Default::default()
        };
        core.process_command(&mut force);
        assert_eq!(force.size, 10, "normalize 必须把 cmd.size 夹到 open_volume=10，不得超平");
        assert!(!core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT), "10 手全平后仓位移除");
        assert_eq!(conserved(&core), before, "夹取后正常成交，守恒");
    }

    #[test]
    fn force_with_no_liquidity_cascades_force_if_adl_without_panic_and_conserves() {
        // 无吸单流动性：FORCE 全 REJECT → 状态机转 WAIT_IF 并入队 IF → IF 池空 REJECT → 转 WAIT_ADL
        // 入队 ADL → 无 ADL 候选 → 终态。整条级联经队列排空自动跑完，不 panic，守恒。
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core); // 借款人 LONG 10；M1 SHORT 10（唯一对手，不挂 BID）

        let before = conserved(&core);
        core.process_command(&mut markprice(94, 2_000)); // 触发，但无 BID 吸单

        // 级联跑完、队列排空、不 panic。
        assert!(core.risk.liquidation_engine.pending_commands.is_empty(), "FORCE→IF→ADL 级联后队列排空");
        // 无任何流动性/IF 池/ADL 候选 -> 借款人仓位仍在（没被平掉），但流程已推进到终态或等待。
        // 关键断言：整个过程不 panic 且守恒（无凭空造钱）。
        assert_eq!(conserved(&core), before, "无成交的级联不改变任何余额，守恒");
    }
}

// ============================================================================
// P6 Task 8：loan 清算扫描器全链路 e2e——LIQUIDATION_SCAN → check_positions 尾部委托 checkLoans →
// 检出越线 isolated loan → 提交 LOAN_FORCE_LIQUIDATE → 队列排空 → P5 handler 结算 → 守恒。
// ============================================================================
#[cfg(test)]
mod loan_scanner_e2e_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::isolated_loan_record::IsolatedLoanRecord;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
    use crate::core::common::symbol_type::SymbolType;

    const COLL: i32 = 1; // 抵押币 = spot base
    const LOANC: i32 = 2; // 借款币 = spot quote
    const SYMBOL: i32 = 100;
    const BORROWER: i64 = 10;
    const MAKER: i64 = 20;
    const LOAN_ID: i64 = 42;

    fn loan_spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: COLL,
            quote_currency: LOANC,
            base_scale_k: 1,
            quote_scale_k: 1,
            loan_config: SymbolLoanSpecification {
                initial_ltv_bps: 5000,
                liquidation_ltv_bps: 8000, // 80%
                margin_call_ltv_bps: 7000,
                max_amount: 0,
                max_term_days: 0,
            },
            ..Default::default()
        }
    }

    fn conserved(core: &ExchangeCore, cur: i32) -> i64 {
        let accounts: i64 = core.ups.users.values().map(|u| u.account(cur)).sum();
        accounts
            + core.risk.loan_service.get_loan_pool_available(cur)
            + core.risk.loan_service.get_interest_revenue(cur)
            + core.risk.loan_service.get_loan_insurance_fund(cur)
            + *core.risk.fees.get(&cur).unwrap_or(&0)
            + *core.risk.adjustments.get(&cur).unwrap_or(&0)
    }

    #[test]
    fn liquidation_scan_triggers_isolated_loan_force_liquidate_and_conserves() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(loan_spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&loan_spot_spec());
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(MAKER);
        core.risk.last_price_cache.insert(SYMBOL, 1); // markPrice 1
        core.risk.liquidation_engine.is_running = true; // leader

        // 直接建一笔越线 isolated loan：抵押 1000 COLL、本金 900 LOANC（LTV 90% >= 80%）。
        core.risk.loan_service.add_to_loan_pool_available(LOANC, 1_000_000);
        {
            let b = core.ups.get_mut(BORROWER).unwrap();
            b.add_to_account(COLL, 1_000);
            let mut loan = IsolatedLoanRecord::new(BORROWER, LOAN_ID, SYMBOL, COLL, LOANC, 0, 0);
            loan.outstanding_principal = 900;
            loan.collateral_amount = 1_000;
            b.isolated_loans.insert(LOAN_ID, loan);
        }
        let b = core.ups.get_mut(BORROWER).unwrap();
        core.risk.loan_service.disburse_loan(b, LOANC, 900);

        // maker 挂 BID@1 size2000（吸收 force-sell ASK@破产价1）。
        core.ups.get_mut(MAKER).unwrap().add_to_account(LOANC, 1_000_000_000);
        let mut mk = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            uid: MAKER,
            symbol: SYMBOL,
            price: 1,
            size: 2_000,
            reserve_bid_price: 1,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            timestamp: 1_000,
            ..Default::default()
        };
        core.process_command(&mut mk);
        assert_eq!(mk.result_code, Some(CommandResultCode::Success));

        let before_coll = conserved(&core, COLL);
        let before_loanc = conserved(&core, LOANC);

        // LIQUIDATION_SCAN（symbol=-1、全扫）→ check_positions 尾部委托 checkLoans → 检出越线 loan →
        // 提交 LOAN_FORCE_LIQUIDATE → drain 排空重喂 → P5 handler 结算。
        let mut scan = OrderCommand {
            command: OrderCommandType::LiquidationScan,
            symbol: -1,
            uid: 0,
            size: 0, // sliceCount<=0 = 全扫
            timestamp: 2_000,
            ..Default::default()
        };
        core.process_command(&mut scan);

        assert!(core.risk.liquidation_engine.pending_commands.is_empty(), "扫描生成的 force-liquidate 已排空处理");
        assert!(
            !core.ups.get(BORROWER).unwrap().isolated_loans.contains_key(&LOAN_ID),
            "越线 loan 被强平（1000 抵押全卖、900 本金还清、loan 移除）"
        );
        // 守恒（借贷全局恒等式，含 pool/interest/LIF/fee/adjustment）。
        assert_eq!(conserved(&core, COLL), before_coll, "COLL 守恒");
        assert_eq!(conserved(&core, LOANC), before_loanc, "LOANC 守恒");
    }
}

// ============================================================================
// Snapshot 序列化 round-trip 测试（对应 Java writeMarshallable/BytesIn + updateProvider 重建）。
// 构建富复制态（期货持仓 + 挂单簿 + isolated loan + 池子 + fees/adjustments），快照→恢复，
// 验证：①复制态 round-trip 字节等价 ②非复制态复原（adl_eligibility 归一 + 索引重建）
// ③恢复后功能正常（markprice 触发强平走 targeted 索引）。
// ============================================================================
#[cfg(test)]
mod snapshot_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::isolated_loan_record::IsolatedLoanRecord;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
    use crate::core::common::symbol_type::SymbolType;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const FUT: i32 = 700;
    const SPOT: i32 = 100; // BASE/QUOTE 现货（供 isolated loan）
    const U_LONG: i64 = 10;
    const U_SHORT: i64 = 11;
    const U_MAKER: i64 = 12;
    const BORROWER: i64 = 13;

    fn fut_spec() -> CoreSymbolSpecification {
        let mut mm = std::collections::BTreeMap::new();
        mm.insert(i64::MAX, 500);
        CoreSymbolSpecification {
            symbol_id: FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 10_000,
            liquidation_fee: 200,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 10_000,
            ..Default::default()
        }
    }

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SPOT,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            loan_config: SymbolLoanSpecification {
                initial_ltv_bps: 5000,
                liquidation_ltv_bps: 8000,
                margin_call_ltv_bps: 7000,
                max_amount: 0,
                max_term_days: 0,
            },
            ..Default::default()
        }
    }

    /// 构建一个覆盖所有序列化组件的富复制态。
    fn build_rich_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(fut_spec()), CommandResultCode::Success);
        assert_eq!(core.ssp.add_symbol(spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&fut_spec());
        core.matching.add_symbol(&spot_spec());
        for uid in [U_LONG, U_SHORT, U_MAKER, BORROWER] {
            core.ups.add_empty_user_profile(uid);
            core.ups.get_mut(uid).unwrap().add_to_account(QUOTE, 10_000_000);
        }
        core.risk.liquidation_engine.is_running = true;

        // markprice + 期货持仓（U_SHORT ASK@100 开空，U_LONG BID@100 吃单开多）。
        let mut mp = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price: 100, timestamp: 1_000, ..Default::default() };
        core.process_command(&mut mp);
        let mut a = fut_order(1, U_SHORT, 100, 10, false, 10);
        core.process_command(&mut a);
        let mut b = fut_order(2, U_LONG, 100, 10, true, 10);
        core.process_command(&mut b);
        // 一张 resting 期货挂单（未成交，制造 order book state）。
        let mut resting = fut_order(3, U_MAKER, 80, 5, true, 10);
        core.process_command(&mut resting);

        // isolated loan（现货，制造 loan + 池子 state + loan 索引）。
        core.risk.loan_service.add_to_loan_pool_available(QUOTE, 1_000_000);
        {
            let bp = core.ups.get_mut(BORROWER).unwrap();
            bp.add_to_account(BASE, 1_000);
            let mut loan = IsolatedLoanRecord::new(BORROWER, 99, SPOT, BASE, QUOTE, 0, 0);
            loan.outstanding_principal = 300;
            loan.collateral_amount = 1_000;
            bp.isolated_loans.insert(99, loan);
        }
        let bp = core.ups.get_mut(BORROWER).unwrap();
        core.risk.loan_service.disburse_loan(bp, QUOTE, 300);
        // 索引维护（模拟 dispatcher 的 reconcile）。
        core.risk.liquidation_engine.loan_liquidation_engine.on_isolated_loan_opened(BORROWER, SPOT);

        // IF 池子 state。
        core.risk.liquidation_service.credit_liquidation_fee(FUT, 500);
        core
    }

    fn fut_order(order_id: i64, uid: i64, price: i64, size: i64, bid: bool, leverage: i32) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            uid,
            symbol: FUT,
            price,
            size,
            reserve_bid_price: price,
            action: Some(if bid { OrderAction::Bid } else { OrderAction::Ask }),
            order_type: Some(OrderType::Gtc),
            leverage,
            margin_mode: crate::core::common::margin_mode::MarginMode::Isolated,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    #[test]
    fn snapshot_roundtrip_preserves_replicated_state_and_rebuilds_non_replicated() {
        let core = build_rich_core();
        let bytes = core.to_snapshot_bytes();
        let restored = ExchangeCore::from_snapshot_bytes(&bytes);

        // ① 复制态 round-trip 字节等价（serialize(deserialize(x)) == serialize(x)）。
        assert_eq!(restored.to_snapshot_bytes(), bytes, "复制态快照 round-trip 必须字节等价");

        // 抽查关键复制态被复原。
        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].open_volume, 10);
        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].direction, PositionDirection::Long);
        assert_eq!(restored.ups.get(BORROWER).unwrap().isolated_loans[&99].outstanding_principal, 300);
        assert_eq!(restored.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 300);
        assert_eq!(restored.risk.liquidation_service.notionals[&FUT].available, 500);
        // order book：U_MAKER 的 resting BID@80 仍在。
        let mut ob = OrderCommand { command: OrderCommandType::OrderBookRequest, symbol: FUT, size: 10, ..Default::default() };
        let mut restored2 = ExchangeCore::from_snapshot_bytes(&bytes);
        restored2.process_command(&mut ob);
        let md = ob.market_data.unwrap();
        assert!(md.bid_prices.contains(&80), "resting 挂单簿状态必须随快照复原");

        // ② 非复制态复原：adl_eligibility 按 margin_mode 归一（ISOLATED 期货仓 = 100）。
        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].adl_eligibility, 100, "ISOLATED 仓 adl_eligibility 复原为 100");
        assert!(restored.ups.get(U_LONG).unwrap().positions[&FUT].liquidation_flow.is_none());
        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].pending_adl_size, 0);

        // ② 索引重建：futures symbol_to_users 含有开仓的 U_LONG/U_SHORT。U_MAKER 只挂单未开仓
        // （open_volume==0），rebuild 按 open_volume>0 过滤跳过它——**与 Java updateProvider 的
        // `openVolume==0 return` 过滤一致**（在线维护 on_position_opened 在下单时就登记是 superset，
        // rebuild 是 open-position-only 子集；resting-only 用户无仓可强平，scan-slice 兜底）。
        let holders = restored.risk.liquidation_engine.symbol_to_users.get(&FUT).expect("futures 索引重建");
        assert!(holders.contains(&U_LONG) && holders.contains(&U_SHORT));
        assert!(!holders.contains(&U_MAKER), "只挂单未开仓的用户按 open_volume>0 过滤，不入重建索引（对齐 Java）");
        assert!(
            restored.risk.liquidation_engine.loan_liquidation_engine.isolated_loan_symbol_to_users.get(&SPOT).unwrap().contains(&BORROWER),
            "loan 索引重建"
        );
        // is_running 复原为 follower（false）——leader 门由 server 侧 raft 重新置位。
        assert!(!restored.risk.liquidation_engine.is_running);
    }

    #[test]
    fn restored_core_liquidation_works_via_rebuilt_index() {
        // 恢复后功能验证：markprice 暴跌触发 U_LONG（ISOLATED 期货多头）强平——依赖重建的
        // symbol_to_users targeted 索引 + 归一的 adl_eligibility。
        let core = build_rich_core();
        let bytes = core.to_snapshot_bytes();
        let mut restored = ExchangeCore::from_snapshot_bytes(&bytes);
        restored.risk.liquidation_engine.is_running = true; // server 侧重新成为 leader

        // 给强平 FORCE ASK 备好吸单 BID（U_MAKER 已有 BID@80，破产价≈92>80 不够，另挂 BID@92）。
        let mut mk = fut_order(50, U_MAKER, 92, 10, true, 10);
        restored.process_command(&mut mk);

        let mut mp = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price: 94, timestamp: 5_000, ..Default::default() };
        restored.process_command(&mut mp);

        assert!(restored.risk.liquidation_engine.pending_commands.is_empty(), "恢复后强平级联正常排空");
        assert!(
            !restored.ups.get(U_LONG).unwrap().positions.contains_key(&FUT),
            "恢复后 targeted 索引生效，U_LONG 被强平平仓"
        );
    }
}
