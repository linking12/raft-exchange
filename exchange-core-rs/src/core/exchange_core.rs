use std::cell::RefCell;
use std::rc::Rc;

use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
#[cfg(test)]
use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::margin_mode::MarginMode;
use crate::core::processors::liquidation::scheduler::LiquidationScheduler;
use crate::core::processors::matching_engine_router::MatchingEngineRouter;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::snapshot::serialization_processor::{
    InMemorySerializationProcessor, SerializationProcessor, SerializedModuleType,
};

/// 逐命令结果回调签名（= Java `ObjLongConsumer<OrderCommand> resultsConsumer`）：主命令与每条级联
/// 子命令处理完各触发一次。`SimpleEventsProcessor` 只读 `ssp`(取 spec/scale)+ `ups`(查持仓模式),
/// 故只透传这两块引擎片段(不相交借用),不吃整个 `&ExchangeCore`。
type ResultsConsumer = Box<dyn FnMut(&OrderCommand, i64, &SymbolSpecificationProvider, &UserProfileService)>;

/// 对应 Java `ExchangeCore`：Java 版是把 RiskEngine(R1 预处理/R2 风控释放)、MatchingEngineRouter(ME)
/// 通过 LMAX Disruptor 组装成多阶段流水线（G 分组 → [J 落盘] ‖ R1 → ME → R2 → E 结果处理，
/// 各阶段可多 shard 并行），并负责 disruptor 生命周期(startup/shutdown)与线程池装配。
/// Rust 版把整条流水线塌缩为单线程、单 shard 的确定性管线：一次 `process_command` 内同步依次跑
/// R1(`risk.pre_process_command`) → ME(`matching.process_order`) → R2(`risk.handler_risk_release`)，
/// 不经过队列/线程边界，天然满足 Raft 状态机对确定性重放的要求；`RiskEngine`/`MatchingEngineRouter`
/// 是本 crate 内被塌缩的等价物，字段语义仍与 Java 逐一对应。
pub struct ExchangeCore {
    pub risk: RiskEngine,
    pub matching: MatchingEngineRouter,
    pub ups: UserProfileService,
    pub ssp: SymbolSpecificationProvider,
    pending_commands: Rc<RefCell<Vec<OrderCommand>>>,
    ser_proc: Box<dyn SerializationProcessor>,
    results_consumer: Option<ResultsConsumer>,
    results_seq: i64,
    /// 周期强平发令器（对应 Java `LiquidationEngine extends LiquidationScheduledService` 里"每 tick 发什么
    /// 命令"的确定性部分）。由引擎驱动：`start/stop_liquidation_scheduler` 是 leader 门控（= Java
    /// `start()/stop()`），`tick_liquidation_scheduler(now)` 是每个调度 tick 的驱动（= Java `runOneIteration`）。
    /// 墙钟脉冲由外层 server 每 interval 调一次 tick 提供（`!Send` 引擎内不放时钟线程）；scheduler 的
    /// `command_submitter` 与引擎共用同一 sink（单节点 pending / 集群 raft）。
    liquidation_scheduler: LiquidationScheduler,
}

impl Default for ExchangeCore {
    fn default() -> Self {
        ExchangeCore::new()
    }
}

impl ExchangeCore {
    // ──────────────── 构造 & 注册（new 装默认，with_* 覆盖）────────────────

    /// 构造函数：装好默认——内存序列化后端（`InMemorySerializationProcessor`）、本地单节点命令出口
    /// （塞 `pending_commands`）。结果回调默认不装（`None`，不产事件、零开销）。外部通过下面的 `with_*`
    /// 覆盖：`with_serialization_processor`（换 Disk 后端）/ `with_command_submitter`（换 raft 出口）/
    /// `with_results_consumer`（挂 `SimpleEventsProcessor` 产事件）。
    pub fn new() -> Self {
        let mut core = ExchangeCore {
            risk: RiskEngine::new(),
            matching: MatchingEngineRouter::new(),
            ups: UserProfileService::new(),
            ssp: SymbolSpecificationProvider::new(),
            pending_commands: Rc::new(RefCell::new(Vec::new())),
            ser_proc: Box::new(InMemorySerializationProcessor::new()),
            results_consumer: None,
            results_seq: 0,
            liquidation_scheduler: LiquidationScheduler::new(10, 30, 0),
        };
        // 默认命令出口 = 本地单节点：强平引擎 + loan 子引擎 + scheduler 的 command_submitter 都塞进 pending_commands
        // （= Java 单节点 setCommandSubmitter(api::submitCommand)，api = 入 ring buffer）。集群走
        // with_command_submitter override 成 raft。
        let pending = core.pending_commands.clone();
        core.with_command_submitter(move || {
            let sink = pending.clone();
            Box::new(move |cmd| sink.borrow_mut().push(cmd))
        });
        core
    }

    /// 覆盖序列化后端（默认 `InMemorySerializationProcessor`）。对应 Java 由
    /// `SerializationConfiguration.serializationProcessorFactory` 注入 `ISerializationProcessor`
    /// （Disk/Memory/Dummy）；`persist`/`recover` 经它按 per-(module, instanceId) 落盘/加载。
    pub fn with_serialization_processor(&mut self, ser_proc: Box<dyn SerializationProcessor>) {
        self.ser_proc = ser_proc;
    }

    /// 覆盖系统自生成命令（scan / FORCE / IF / ADL / reprice）的提交出口（= Java
    /// `liquidationEngine.setCommandSubmitter(...)`）。`new` 装的是本地默认出口（塞 `pending_commands`）;
    /// **集群下外层调本方法 override 成 raft 提交**——与 Java 单节点/集群切换 `setCommandSubmitter` 目标
    /// (ring buffer / raft)一致,每条命令各自提交。强平引擎、loan 子引擎、scheduler 各需一份捕获同一 sink
    /// 的回调,而 `Box<dyn FnMut>` 不可 clone,故收工厂 `make`,给每个发令方各铸一份。
    pub fn with_command_submitter<F>(&mut self, make: F)
    where
        F: Fn() -> Box<dyn FnMut(OrderCommand)>,
    {
        self.liquidation_scheduler.set_command_submitter(make());
        self.risk.liquidation_engine.set_command_submitter(make);
    }

    /// 覆盖逐命令结果回调（= Java `resultsConsumer`，通常是 `SimpleEventsProcessor`）。
    pub fn with_results_consumer(&mut self, consumer: ResultsConsumer) {
        self.results_consumer = Some(consumer);
    }

    // ─────────────────────────── 命令处理流水线 ───────────────────────────

    /// 对应 Java `ExchangeCore` 流水线核心：R1(`RiskEngine.preProcessCommand`) → ME
    /// (`MatchingEngineRouter.processOrder`) → R2(`RiskEngine.handlerRiskRelease`)。Java 里三段分别
    /// 跑在 Disruptor 不同 handler 阶段（可能不同线程/shard），此处塌缩为一次函数调用内的三步同步执行，
    /// 保证同一条命令的处理结果在任意节点、任意时刻重放都完全确定（Raft 状态机要求）。
    /// `RESET` 单独短路：不经过 R1/ME/R2，直接清空全部业务状态后返回（对应 Java RiskEngine/
    /// MatchingEngineRouter 各自 `case RESET` 分支）。主命令处理完后 inline 自驱 `pending_commands`：
    /// 单节点回调塞了 FORCE/IF/ADL → 逐条再走 R1→ME→R2 直到清空；集群回调走 raft → pending 恒空、
    /// 循环 no-op（级联由 raft 回流每条各自一次 `process_command` 驱动）。每条命令在 `apply_one` 末尾触发
    /// `results_consumer`（= Java disruptor 对每条 ring buffer 命令触发 `resultsConsumer`）。
    pub fn process_command(&mut self, cmd: &mut OrderCommand) {
        log::trace!(
            "process_command enter: cmd={:?} uid={} symbol={} order_id={}",
            cmd.command, cmd.uid, cmd.symbol, cmd.order_id
        );

        if cmd.command == crate::core::common::cmd::order_command_type::OrderCommandType::Reset {
            self.reset();
            cmd.result_code = Some(crate::core::common::cmd::command_result_code::CommandResultCode::Success);
            log::debug!("process_command: RESET cleared all engine business state");
            return;
        }

        self.apply_one(cmd);

        log::trace!(
            "process_command: R1->ME->R2 done cmd={:?} result={:?}",
            cmd.command, cmd.result_code
        );

        self.drive_pending();
    }

    /// 驱动 `pending_commands`（单节点自驱 / 集群 no-op）：每弹一批逐条走 R1→ME→R2，新生成的又进队列，
    /// 直到清空。单节点回调塞了 FORCE/IF/ADL/scan → 排空即级联；集群回调走 raft → pending 恒空、循环 no-op。
    fn drive_pending(&mut self) {
        loop {
            let batch: Vec<OrderCommand> = std::mem::take(&mut *self.pending_commands.borrow_mut());
            if batch.is_empty() {
                break;
            }
            for mut c in batch {
                self.apply_one(&mut c);
            }
        }
    }

    /// 一条命令走完 R1→ME→R2 并触发结果回调（主命令与每条级联子命令共用同一步序）。末尾逐命令触发
    /// `results_consumer`（= Java `resultsHandler.onEvent(cmd, seq)`）：`results_consumer`(可变借用)与
    /// `ssp`/`ups`(只读借用)是不相交字段,借用检查器允许同时借。
    fn apply_one(&mut self, cmd: &mut OrderCommand) {
        self.risk.pre_process_command(cmd, &mut self.ups, &self.ssp);
        self.matching.process_order(cmd);
        self.risk.handler_risk_release(cmd, &mut self.ups, &self.ssp);
        let seq = self.results_seq;
        self.results_seq += 1;
        if let Some(h) = self.results_consumer.as_mut() {
            h(cmd, seq, &self.ssp, &self.ups);
        }
    }

    // ─────────── 周期强平发令器（引擎驱动，leader 门控，= Java LiquidationScheduledService）───────────

    /// leader 上线：启动周期发令（= Java `LiquidationScheduledService.start()` 置 `running=true`）。外层
    /// server 在成为 leader 时调；之后每个墙钟 tick 调 `tick_liquidation_scheduler`。
    pub fn start_liquidation_scheduler(&mut self) {
        self.liquidation_scheduler.is_running = true;
    }

    /// leader 下台：停止周期发令（= Java `stop()`）。
    pub fn stop_liquidation_scheduler(&mut self) {
        self.liquidation_scheduler.is_running = false;
    }

    /// 每个调度 tick 驱动一次（= Java `runOneIteration`，由外层 server 的墙钟每 interval 调）。leader-gated：
    /// 非 leader（`is_running=false`）no-op。leader 上产 `LIQUIDATION_SCAN`/`REPRICE_LOAN_RATES` → scheduler
    /// 的 `command_submitter` 回调 → 单节点塞 `pending`（随即自驱排空 = 就地跑扫描/级联）/ 集群提交 raft。
    pub fn tick_liquidation_scheduler(&mut self, now: i64) {
        self.liquidation_scheduler.run_one_iteration(now);
        self.drive_pending();
    }

    // 对应 Java `RiskEngine.reset()`(清 userProfileService/liquidationService/loanService/
    // symbolSpecificationProvider/currencySpecificationProvider/lastPriceCache/fees/adjustments/
    // suspends) + `MatchingEngineRouter` 的 `case RESET`(清 orderBooks)。Rust 侧 SSP/UPS/RiskEngine/
    // MatchingEngineRouter 各自持有独立状态，这里逐一清空后重建现货对索引（Java 无此索引，Rust 的
    // spot_pair_index 是塌缩单 shard 后新增的辅助结构，清空后必须显式 rebuild 而非留脏）。
    fn reset(&mut self) {
        self.risk.reset();
        self.ups.users.clear();
        self.ssp.symbols.clear();
        self.ssp.currencies.clear();
        self.ssp.rebuild_spot_pair_index();
        self.matching.reset();
    }

    // ─────────────────────────── 快照 persist / recover ───────────────────────────

    /// 对应 Java `RiskEngine`/`MatchingEngineRouter` 各自实现 `WriteBytesMarshallable`（Chronicle Wire
    /// 二进制编码）产生 RE/ME 两个独立快照模块。RE(risk-engine) 模块覆盖 symbol/currency specs、
    /// UserProfileService(账户/仓位/挂单)、RiskEngine 自身(fees/adjustments/suspends/last_price_cache/
    /// loan_service/liquidation_service) 等*复制态*；ME(matching-engine) 模块覆盖撮合簿(order_books)。
    /// 两段独立编码、独立传输，与 Java 侧 `PERSIST_STATE_RISK`/`PERSIST_STATE_MATCHING` 两条独立持久化
    /// 指令的模块划分一致，供上层 Raft 快照分别落盘/传输。
    pub fn persist(&mut self, snapshot_id: i64, instance_id: i32) -> bool {
        use crate::core::snapshot::marshalling::ChronicleMarshallable;
        // 模块层只产 raw payload；framing 由 processor 负责（对齐 Java storeData 内部 WireToOutputStream）。
        let re = crate::core::processors::risk_engine::write_risk_engine_payload(self);
        let mut w = crate::core::snapshot::chronicle_writer::ChronicleWriter::new();
        self.matching.chronicle_write(&mut w);
        let me = w.into_bytes();
        let ok_re = self.ser_proc.store_data(snapshot_id, 0, 0, SerializedModuleType::RiskEngine, instance_id, &re);
        let ok_me =
            self.ser_proc.store_data(snapshot_id, 0, 0, SerializedModuleType::MatchingEngineRouter, instance_id, &me);
        ok_re && ok_me
    }

    /// 对应 Java `RiskEngine.recoverStateBySnapshot`/`MatchingEngineRouter.recoverStateBySnapshot`：
    /// 经持有的 `ISerializationProcessor.loadData` 按 per-(module, instanceId) 取回两模块 payload、
    /// 反序列化进 risk/matching/ups/ssp 复制态，再原子生效。解出复制态后必须调用
    /// `restore_non_replicated_state` 重建快照里*没有*编码的派生索引/临时字段——这一步 Java/Rust
    /// 都需要（Java 见 `LiquidationEngine.updateProvider`），因为这些字段要么是纯内存缓存（重放开销
    /// 小于序列化成本），要么在快照写入时被有意排除（如 ADL 资格/待 ADL 量/清算流水这类瞬时状态）。
    pub fn recover(&mut self, snapshot_id: i64, instance_id: i32) {
        use crate::core::snapshot::chronicle_reader::ChronicleReader;
        use crate::core::snapshot::marshalling::ChronicleMarshallable;
        let re = self
            .ser_proc
            .load_data(snapshot_id, SerializedModuleType::RiskEngine, instance_id)
            .expect("RE snapshot module not found");
        crate::core::processors::risk_engine::read_risk_engine_payload(&re, self).expect("RE payload parse failed");
        let me = self
            .ser_proc
            .load_data(snapshot_id, SerializedModuleType::MatchingEngineRouter, instance_id)
            .expect("ME snapshot module not found");
        self.matching =
            MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&me)).expect("ME payload parse failed");
        self.restore_non_replicated_state();
    }

    // 对应 Java 快照恢复后的派生状态重建：
    // 1) SSP 的现货对唯一性索引本就是 Rust 侧新增的辅助结构（Java 无此索引），恒需 rebuild。
    // 2) 逐仓/全仓 ADL 资格(adl_eligibility)、待 ADL 量(pending_adl_size)、清算流水(liquidation_flow)
    //    复位为"刚重启"的初值——对应 Java `SymbolPositionRecord` 构造函数/`reset()` 里的默认值
    //    （ISOLATED=100 全仓=0），这三个字段要么是运行期缓存要么是本地进行中状态，快照里不落盘。
    // 3) 期货持仓量>0 的用户重新灌入 `LiquidationEngine.symbol_to_users` 索引（symbol→持仓人集合），
    //    对应 Java `LiquidationEngine.updateProvider` 遍历全体 UserProfile 重建同一索引的逻辑；
    //    该索引用于清算扫描定位候选人，快照里同样不落盘（纯粹可从复制态推导，重建比序列化更省）。
    // 4) loan 侧同理委托 `loan_liquidation_engine.rebuild_indices` 重建 isolated/cross loan 的
    //    symbol→borrower 索引。
    fn restore_non_replicated_state(&mut self) {
        self.ssp.rebuild_spot_pair_index();
        for up in self.ups.users.values_mut() {
            for pos in up.positions.values_mut() {
                pos.adl_eligibility = if pos.margin_mode == MarginMode::Isolated { 100 } else { 0 };
                pos.pending_adl_size = 0;
                pos.liquidation_flow = None;
            }
        }
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
        assert!(cmd.matcher_event.is_none());
        assert!(cmd.market_data.is_none());
    }

    #[test]
    fn reset_wipes_all_engine_state() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        core.risk.set_mark_price(spot_spec().symbol_id, 100);
        core.ups.get_mut(1).unwrap().add_to_account(QUOTE, 5_000);
        *core.risk.fees.entry(QUOTE).or_insert(0) += 7;
        let mut place = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 10,
            uid: 1,
            symbol: spot_spec().symbol_id,
            price: 100,
            size: 5,
            action: Some(crate::core::common::order_action::OrderAction::Bid),
            order_type: Some(crate::core::common::order_type::OrderType::Gtc),
            reserve_bid_price: 100,
            ..Default::default()
        };
        core.process_command(&mut place);
        assert!(!core.ups.users.is_empty() && !core.ssp.symbols.is_empty());

        let mut reset = OrderCommand { command: OrderCommandType::Reset, ..Default::default() };
        core.process_command(&mut reset);

        assert_eq!(reset.result_code, Some(CommandResultCode::Success));
        assert!(core.ups.users.is_empty(), "users cleared");
        assert!(core.ssp.symbols.is_empty() && core.ssp.currencies.is_empty(), "specs cleared");
        assert!(core.risk.fees.is_empty() && core.risk.adjustments.is_empty() && core.risk.suspends.is_empty(), "fee/adjustment/suspend buckets cleared");
        assert!(core.risk.last_price_cache.is_empty(), "price cache cleared");
        assert_eq!(core.matching.order_books_state_hash(), 17, "order books cleared (empty hash seed 17)");
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
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 0);

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
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 0, "R2 must release all locked funds");
    }
}

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

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
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

    fn conserved_total(core: &ExchangeCore, currency: i32) -> i64 {
        let accounts_sum: i64 = core.ups.users.values().map(|u| u.account(currency)).sum();
        accounts_sum
            + core.risk.loan_service.get_loan_pool_available(currency)
            + core.risk.loan_service.get_interest_revenue(currency)
            + core.risk.loan_service.get_loan_insurance_fund(currency)
            + *core.risk.fees.get(&currency).unwrap_or(&0)
            + *core.risk.adjustments.get(&currency).unwrap_or(&0)
    }

    #[test]
    fn isolated_force_liquidate_full_fill_removes_loan_and_conserves() {
        let mut core = seeded_loan_core();
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 0, 1_000);
        fund_maker_and_rest_bid(&mut core, 1, 1, 2_000);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "fully repaid loan removed");
        assert_eq!(borrower.account(BASE), 0);
        assert_eq!(borrower.locked(BASE), 0);
        assert_eq!(borrower.account(QUOTE), 500 + 480);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    #[test]
    fn isolated_force_liquidate_partial_fill_keeps_loan_with_updated_snapshot() {
        let mut core = seeded_loan_core();
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 0, 1_000);
        fund_maker_and_rest_bid(&mut core, 1, 1, 400);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        let loan = borrower.isolated_loans.get(&LOAN_ID).expect("partial fill keeps the loan open");
        assert_eq!(loan.outstanding_principal, 500 - 392);
        assert_eq!(loan.accumulated_interest, 0);
        assert_eq!(loan.collateral_amount, 600);
        assert_eq!(borrower.account(BASE), 600);
        assert_eq!(borrower.locked(BASE), 0);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 8);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 999_500 + 392);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    #[test]
    fn isolated_force_liquidate_all_reject_refunds_collateral_accrues_interest_then_takes_over() {
        let mut core = seeded_loan_core();
        const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 1_000, 1_000);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(1, LOAN_ID, 1, 1_000, 1_000 + YEAR_MS);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "taken over -> removed");
        assert_eq!(borrower.locked(BASE), 0);
        assert_eq!(borrower.account(BASE), 0);
        assert_eq!(borrower.account(QUOTE), 500);

        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), -550);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(BASE), 1_000);
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 50);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 999_500 + 500);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

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

        open_isolated_loan(&mut core, LOAN_ID, 1_050, 2_000, 0, 1_000);
        fund_maker_and_rest_bid(&mut core, 1, 100, 20);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 100, 10, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "taken over -> removed");
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(BASE), 50);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 2_000 + 980 + 1_020);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        assert_eq!(borrower.account(BASE), 0);
        assert_eq!(borrower.locked(BASE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    const SELL_CUR: i32 = 3;

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
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
        core
    }

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

    #[test]
    fn cross_force_liquidate_structurally_unsellable_triggers_target_takeover() {
        let mut core = cross_seeded_core();
        core.ssp.currencies.get_mut(&SELL_CUR).unwrap().collateral_weight_bps = 0;
        open_cross_loan(&mut core, LOAN_ID, 2_000, 2_000);
        fund_maker_and_rest_bid(&mut core, 1, 1, 2_000);

        let before_quote = conserved_total(&core, QUOTE);
        let before_sell = conserved_total(&core, SELL_CUR);

        let mut cmd = cross_force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.cross_loans.contains_key(&LOAN_ID), "taken over -> removed");
        assert_eq!(borrower.cross_loan_collateral(SELL_CUR), 1_000);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(SELL_CUR), 0);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);

        assert_eq!(conserved_total(&core, QUOTE), before_quote);
        assert_eq!(conserved_total(&core, SELL_CUR), before_sell);
    }

    #[test]
    fn cross_force_liquidate_all_exhausted_sweeps_remaining_loans_in_ascending_order() {
        let mut core = cross_seeded_core();
        core.ssp.currencies.get_mut(&SELL_CUR).unwrap().collateral_weight_bps = 0;
        open_cross_loan(&mut core, LOAN_ID, 2_000, 2_000);
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

        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020 - 300 - 700);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 0);

        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }
}

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
    const BORROWER: i64 = 10;
    const M1: i64 = 20;
    const M2: i64 = 30;

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
            liquidation_fee: 200,
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
        core.risk.liquidation_engine.is_running = true;
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

    fn conserved(core: &ExchangeCore) -> i64 {
        let cur = QUOTE;
        let mark = core.risk.last_price_cache.get(&FUT).map(|r| r.mark_price).unwrap_or(0);
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
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let holders = core.risk.liquidation_engine.symbol_to_users.get(&FUT).expect("index should have this symbol");
        assert!(holders.contains(&BORROWER));
        assert!(holders.contains(&M1));
    }

    #[test]
    fn markprice_drop_triggers_full_liquidation_collects_fee_to_if_and_conserves() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let mut m2 = fut_order(3, M2, 92, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);
        assert_eq!(m2.result_code, Some(CommandResultCode::Success));

        let before = conserved(&core);
        core.process_command(&mut markprice(94, 2_000));

        assert!(
            core.pending_commands.borrow().is_empty(),
            "队列必须被排空（生成的 FORCE 已处理）"
        );
        assert!(
            !core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT),
            "借款人 LONG 被 FORCE 全平，仓位移除"
        );
        let if_available: i64 = core.risk.liquidation_service.notionals.values().map(|n| n.available).sum();
        assert!(if_available > 0, "liquidation fee must be credited to IFNotional.available");
        assert_eq!(conserved(&core), before, "globally conserved after force liquidation (incl. liquidation fee transferred to IF)");
    }

    // 集群模式（command_submitter 注册成 collector = raft 提交出口）：markprice 触发生成的 FORCE 被交给
    // submitter（外层去走 raft 共识），引擎本进程内不 apply——对照上面的单节点用例（同一场景下 FORCE 会
    // 就地排空、借款人仓位被平掉）。
    #[test]
    fn cluster_mode_hands_cascade_to_submitter_without_inline_apply() {
        use std::cell::RefCell;
        use std::rc::Rc;
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let mut m2 = fut_order(3, M2, 92, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);

        let captured: Rc<RefCell<Vec<OrderCommand>>> = Rc::new(RefCell::new(Vec::new()));
        let cap = captured.clone();
        core.with_command_submitter(move || {
            let sink = cap.clone();
            Box::new(move |cmd| sink.borrow_mut().push(cmd))
        });

        core.process_command(&mut markprice(94, 2_000));

        assert!(
            captured.borrow().iter().any(|c| c.command == OrderCommandType::ForceLiquidation && c.uid == BORROWER),
            "borrower 的 FORCE_LIQUIDATION 应被交给 submitter（去走 raft 共识）"
        );
        assert!(
            core.pending_commands.borrow().is_empty(),
            "pending 已全部交出，引擎内不残留"
        );
        assert!(
            core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT),
            "集群模式下 FORCE 尚未回流 apply，借款人仓位仍在（未就地平仓）"
        );
    }

    // 模拟 raft 全程：submitter 把二级命令收进队列（= 提交进共识），逐条"共识后回流"再 apply；每条 apply
    // 又可能生成下一条（IF/ADL）进队列。验证 raft 回流路径最终与单节点同一结果（借款人被强平、全局守恒）。
    #[test]
    fn cluster_mode_cascade_completes_across_rounds_conserves() {
        use std::cell::RefCell;
        use std::collections::VecDeque;
        use std::rc::Rc;
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let mut m2 = fut_order(3, M2, 92, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);
        let before = conserved(&core);

        let queue: Rc<RefCell<VecDeque<OrderCommand>>> = Rc::new(RefCell::new(VecDeque::new()));
        let q = queue.clone();
        core.with_command_submitter(move || {
            let sink = q.clone();
            Box::new(move |cmd| sink.borrow_mut().push_back(cmd))
        });

        core.process_command(&mut markprice(94, 2_000));

        let mut rounds = 0;
        while let Some(mut cmd) = { let n = queue.borrow_mut().pop_front(); n } {
            core.process_command(&mut cmd);
            rounds += 1;
        }

        assert!(rounds > 0, "至少回流处理了 FORCE");
        assert!(queue.borrow().is_empty());
        assert!(core.pending_commands.borrow().is_empty());
        assert!(
            !core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT),
            "raft 回流 apply 后借款人被强平（与单节点同一终态）"
        );
        assert_eq!(conserved(&core), before, "raft 回流路径全局守恒");
    }

    #[test]
    fn direct_force_size_clamped_by_normalize() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let mut m2 = fut_order(3, M2, 90, 100, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);

        let before = conserved(&core);
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
        assert_eq!(force.size, 10, "normalize must clamp cmd.size to open_volume=10, must not over-close");
        assert!(!core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT), "position removed after full close of 10 lots");
        assert_eq!(conserved(&core), before, "conserved after normal fill following clamp");
    }

    #[test]
    fn force_with_no_liquidity_cascades_force_if_adl_without_panic_and_conserves() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);

        let before = conserved(&core);
        core.process_command(&mut markprice(94, 2_000));

        assert!(core.pending_commands.borrow().is_empty(), "queue drained after FORCE→IF→ADL cascade");
        assert_eq!(conserved(&core), before, "cascade with no fills does not change any balance, conserved");
    }
}

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

    const COLL: i32 = 1;
    const LOANC: i32 = 2;
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
                liquidation_ltv_bps: 8000,
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
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
        core.risk.liquidation_engine.is_running = true;

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

        let mut scan = OrderCommand {
            command: OrderCommandType::LiquidationScan,
            symbol: -1,
            uid: 0,
            size: 0,
            timestamp: 2_000,
            ..Default::default()
        };
        core.process_command(&mut scan);

        assert!(core.pending_commands.borrow().is_empty(), "force-liquidate generated by scan has been drained and processed");
        assert!(
            !core.ups.get(BORROWER).unwrap().isolated_loans.contains_key(&LOAN_ID),
            "越线 loan 被强平（1000 抵押全卖、900 本金还清、loan 移除）"
        );
        assert_eq!(conserved(&core, COLL), before_coll, "COLL conserved");
        assert_eq!(conserved(&core, LOANC), before_loanc, "LOANC conserved");
    }
}

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
    const SPOT: i32 = 100;
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

    fn build_rich_core(ser_proc: Box<dyn SerializationProcessor>) -> ExchangeCore {
        let mut core = ExchangeCore::new(); core.with_serialization_processor(ser_proc);
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

        let mut mp = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price: 100, timestamp: 1_000, ..Default::default() };
        core.process_command(&mut mp);
        let mut a = fut_order(1, U_SHORT, 100, 10, false, 10);
        core.process_command(&mut a);
        let mut b = fut_order(2, U_LONG, 100, 10, true, 10);
        core.process_command(&mut b);
        let mut resting = fut_order(3, U_MAKER, 80, 5, true, 10);
        core.process_command(&mut resting);

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
        core.risk.liquidation_engine.loan_liquidation_engine.on_isolated_loan_opened(BORROWER, SPOT);

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
        // 共享内存后端:core persist → fresh restored recover(模拟 failover 跨实例)。
        let shared = InMemorySerializationProcessor::new();
        let mut core = build_rich_core(Box::new(shared.clone()));
        assert!(core.persist(1, 0));
        let mut restored = ExchangeCore::new(); restored.with_serialization_processor(Box::new(shared.clone()));
        restored.recover(1, 0);

        // 字节相等:restored 重新 persist 到快照 2,与快照 1 的两模块 payload 逐字节比对。
        assert!(restored.persist(2, 0));
        let m_re = SerializedModuleType::RiskEngine;
        let m_me = SerializedModuleType::MatchingEngineRouter;
        assert_eq!(shared.load_data(2, m_re, 0), shared.load_data(1, m_re, 0), "RE module snapshot round-trip must be byte-equal");
        assert_eq!(shared.load_data(2, m_me, 0), shared.load_data(1, m_me, 0), "ME module snapshot round-trip must be byte-equal");

        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].open_volume, 10);
        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].direction, PositionDirection::Long);
        assert_eq!(restored.ups.get(BORROWER).unwrap().isolated_loans[&99].outstanding_principal, 300);
        assert_eq!(restored.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 300);
        assert_eq!(restored.risk.liquidation_service.notionals[&FUT].available, 500);
        let mut ob = OrderCommand { command: OrderCommandType::OrderBookRequest, symbol: FUT, size: 10, ..Default::default() };
        let mut restored2 = ExchangeCore::new(); restored2.with_serialization_processor(Box::new(shared.clone()));
        restored2.recover(1, 0);
        restored2.process_command(&mut ob);
        let md = ob.market_data.unwrap();
        assert!(md.bid_prices.contains(&80), "resting order book state must be restored with the snapshot");

        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].adl_eligibility, 100, "ISOLATED position adl_eligibility restored to 100");
        assert!(restored.ups.get(U_LONG).unwrap().positions[&FUT].liquidation_flow.is_none());
        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].pending_adl_size, 0);

        let holders = restored.risk.liquidation_engine.symbol_to_users.get(&FUT).expect("futures index rebuilt");
        assert!(holders.contains(&U_LONG) && holders.contains(&U_SHORT));
        assert!(!holders.contains(&U_MAKER), "users who only rest orders without opening a position are filtered by open_volume>0 and excluded from the rebuilt index (aligned with Java)");
        assert!(
            restored.risk.liquidation_engine.loan_liquidation_engine.isolated_loan_symbol_to_users.get(&SPOT).unwrap().contains(&BORROWER),
            "loan 索引重建"
        );
        assert!(!restored.risk.liquidation_engine.is_running);
    }

    #[test]
    fn restored_core_liquidation_works_via_rebuilt_index() {
        let shared = InMemorySerializationProcessor::new();
        let mut core = build_rich_core(Box::new(shared.clone()));
        assert!(core.persist(1, 0));
        let mut restored = ExchangeCore::new(); restored.with_serialization_processor(Box::new(shared.clone()));
        restored.recover(1, 0);
        restored.risk.liquidation_engine.is_running = true;

        let mut mk = fut_order(50, U_MAKER, 92, 10, true, 10);
        restored.process_command(&mut mk);

        let mut mp = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price: 94, timestamp: 5_000, ..Default::default() };
        restored.process_command(&mut mp);

        assert!(restored.pending_commands.borrow().is_empty(), "force-liquidation cascade drains normally after recovery");
        assert!(
            !restored.ups.get(U_LONG).unwrap().positions.contains_key(&FUT),
            "恢复后 targeted 索引生效，U_LONG 被强平平仓"
        );
    }
}

#[cfg(test)]
mod settle_pnl_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const DELIV: i32 = 800;
    const PERP: i32 = 801;
    const U_LONG: i64 = 10;
    const U_SHORT: i64 = 11;

    fn deliv_spec() -> CoreSymbolSpecification {
        let mut mm = std::collections::BTreeMap::new();
        mm.insert(i64::MAX, 500);
        CoreSymbolSpecification {
            symbol_id: DELIV,
            symbol_type: SymbolType::FuturesContractDelivery,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 10_000,
            ..Default::default()
        }
    }

    fn conserved(core: &ExchangeCore) -> i64 {
        let cur = QUOTE;
        let mark = core.risk.last_price_cache.get(&DELIV).map(|r| r.mark_price).unwrap_or(0);
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
        total
    }

    fn order(oid: i64, uid: i64, symbol: i32, price: i64, size: i64, bid: bool) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: oid,
            uid,
            symbol,
            price,
            size,
            reserve_bid_price: price,
            action: Some(if bid { OrderAction::Bid } else { OrderAction::Ask }),
            order_type: Some(OrderType::Gtc),
            leverage: 10,
            margin_mode: MarginMode::Isolated,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    fn seeded() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(deliv_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&deliv_spec());
        for uid in [U_LONG, U_SHORT] {
            core.ups.add_empty_user_profile(uid);
            core.ups.get_mut(uid).unwrap().add_to_account(QUOTE, 1_000_000);
        }
        core
    }

    #[test]
    fn settle_pnl_closes_all_positions_at_delivery_price_and_conserves() {
        let mut core = seeded();
        core.process_command(&mut OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: DELIV, price: 100, timestamp: 1_000, ..Default::default() });
        core.process_command(&mut order(1, U_SHORT, DELIV, 100, 10, false));
        core.process_command(&mut order(2, U_LONG, DELIV, 100, 10, true));
        assert_eq!(core.ups.get(U_LONG).unwrap().positions[&DELIV].open_volume, 10);
        assert_eq!(core.ups.get(U_SHORT).unwrap().positions[&DELIV].open_volume, 10);

        let long_acct0 = core.ups.get(U_LONG).unwrap().account(QUOTE);
        let short_acct0 = core.ups.get(U_SHORT).unwrap().account(QUOTE);
        let before = conserved(&core);

        let mut settle = OrderCommand { command: OrderCommandType::SettlePnl, symbol: DELIV, price: 105, timestamp: 2_000, ..Default::default() };
        core.process_command(&mut settle);
        assert_eq!(settle.result_code, Some(CommandResultCode::Success));

        assert!(!core.ups.get(U_LONG).unwrap().positions.contains_key(&DELIV), "LONG delivery close removes position");
        assert!(!core.ups.get(U_SHORT).unwrap().positions.contains_key(&DELIV), "SHORT delivery close removes position");
        assert_eq!(core.ups.get(U_LONG).unwrap().account(QUOTE) - long_acct0, 50, "LONG delivery profit (105-100)*10=+50");
        assert_eq!(core.ups.get(U_SHORT).unwrap().account(QUOTE) - short_acct0, -50, "SHORT delivery loss (100-105)*10=-50");
        assert_eq!(conserved(&core), before, "globally conserved after delivery settlement");
    }

    #[test]
    fn settle_pnl_on_non_delivery_symbol_is_invalid() {
        let mut core = seeded();
        let perp = CoreSymbolSpecification { symbol_id: PERP, symbol_type: SymbolType::FuturesContractPerpetual, ..deliv_spec() };
        assert_eq!(core.ssp.add_symbol(perp.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&perp);

        let mut settle = OrderCommand { command: OrderCommandType::SettlePnl, symbol: PERP, price: 105, timestamp: 2_000, ..Default::default() };
        core.process_command(&mut settle);
        assert_eq!(settle.result_code, Some(CommandResultCode::InvalidSymbol), "SETTLE_PNL is only valid for delivery contracts");
    }
}
