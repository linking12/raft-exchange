//! 对应 Java: `exchange.core2.core.processors.InternalTransferProcessor`（`TwoStepCommandProcessor`
//! 的一个薄实例，全文移植，106 行）。`INTERNAL_TRANSFER` 两步处理器：用户间同币种原子转账。
//!
//! 参考文档 §5。字段映射（`ExchangeApi.java:1216-1226`）：`cmd.uid = fromUid`、
//! `cmd.size = toUid`（**overloaded**——`size` 承载一个 uid，不是金额）、`cmd.symbol =
//! currency`、`cmd.price = amount`、`cmd.order_id = transactionId`。
//!
//! 三段式（同 `LoanRatePricingProcessor` 骨架）：
//! - R1 [`Self::collect_input`]：仅 from-shard 校验，顺序 self→amount<=0→from 缺失→NSF（复用
//!   [`RiskEngine::withdrawable_balance`]，与提现同口径，含借贷抵押/期货保证金锁）→幂等
//!   （[`UserProfile::try_claim_tx`]，claim-and-keep：失败不撤销已 claim 的 id）。成功后**立即**
//!   `from.accounts[currency] -= amount`——扣款是终局的，不等 merge/R2（借款方没有"待合并"的
//!   东西）。
//! - merge [`Self::build_matcher_events`]：trivial 1:1 直传，无跨 shard 归并（不像
//!   ADL/IF/Funding 那样真的要合并多个 shard 的提案）——只是把 `(to_uid, currency, amount)`
//!   打包进一条载体。
//! - R2 [`Self::apply_event`]：to-shard 入账。`to` 从未见过 → 自动建 `SUSPENDED` 档
//!   （[`UserProfileService::get_or_add_suspended`]）——转给未上线/已注销账户的资金不丢，只是
//!   被挂起直到账户正常化。
//!
//! 守恒：`from -= amount; to += amount`，不碰 `adjustments`/`fees` 桶，恒中性。
//!
//! # 事件载体的移植偏差（同 P5 `LoanRatePricingProcessor` 先例，Ruling P6-A/P6-C）
//! Java 用 `MatcherEventType::INTERNAL_TRANSFER_EVENT`（撮合引擎共享事件类型）在
//! R1→ME(merge)→R2 之间传递 `(toUid, currency, amount)`（`matchedOrderUid`/`price`/`size` 三
//! 字段分别承载）。本移植的 `MatcherEventType`/`MatcherTradeEvent` 是撮合引擎的共享类型
//! （`risk_engine.rs::handle_matcher_event_margin` 等多处对其做穷尽 `match`），且
//! `MatchingEngineRouter`（ME 层）不持有 `UserProfileService`——不适合塞一个和撮合无关的
//! "转账事件"变体进去。因此改用 `OrderCommand.internal_transfer_event: Option<(i64, i32,
//! i64)>`（`(to_uid, currency, amount)`）作为本命令类型专属的 R1→R2 载体。单 shard 下
//! （Ruling P6-C）"跨 shard 归并"是恒等操作，`RiskEngine::internal_transfer_collect` 一次性
//! 完成 R1 [`Self::collect_input`] + merge [`Self::build_matcher_events`]，结果写入
//! `cmd.internal_transfer_event`；`RiskEngine::handler_risk_release` 的专属分支
//! （`internal_transfer_apply`）读取并消费它，驱动 R2 [`Self::apply_event`]。数值/顺序/语义与
//! Java 完全一致，只是数据搬运的物理载体不同。
//!
//! 无状态处理器——所有方法都是关联函数，不持有任何字段（同 `LoanRatePricingProcessor` 先例：
//! Java 版本持有 `riskEngine`/`eventsHelper` 两个可选引用只是为了在 R1/R2 实例与 merge 实例
//! 之间做运行时门禁校验，本移植单线程同步调用模型不需要这层门禁）。
//!
//! `emitSnapshot`（Java `:101-104`，转账后余额快照事件）未移植——纯粹是外部事件下发，全 port
//! 无事件总线（Ruling P6-B，同 P1-P5 既定），不影响账户结算正确性。

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;

/// 对应 Java `InternalTransferProcessor`（`TwoStepCommandProcessor` 子类）。零大小类型，见模块
/// 文档。
pub struct InternalTransferProcessor;

impl InternalTransferProcessor {
    /// R1：对应 Java `collectInput`（`:37-69`）。校验顺序：`from_uid == to_uid` →
    /// `InternalTransferInvalidSelf`；`amount <= 0` → `RiskInvalidAmount`；`from` 档案缺失 →
    /// `AuthInvalidUser`；NSF（`engine.withdrawable_balance(from, currency, ssp) < amount`）→
    /// `RiskNsf`（与提现同口径，respect 借贷抵押 + 期货保证金锁）；幂等
    /// （`from.try_claim_tx(order_id)`，claim-and-keep，失败不撤销已 claim 的 id）→
    /// `UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame`（复用 Java 同一个码，非转账专属
    /// 变体——与 `RiskEngine::balance_adjustment` 幂等失败同一码，见其文档）。
    ///
    /// 成功：`from.accounts[currency] -= amount`（立即、终局），返回 `Success`。
    #[allow(clippy::too_many_arguments)]
    pub fn collect_input(
        engine: &RiskEngine,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        from_uid: i64,
        to_uid: i64,
        currency: i32,
        amount: i64,
        order_id: i64,
    ) -> CommandResultCode {
        if from_uid == to_uid {
            return CommandResultCode::InternalTransferInvalidSelf;
        }
        if amount <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        let from = match ups.get_mut(from_uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        if engine.withdrawable_balance(from, currency, ssp) < amount {
            return CommandResultCode::RiskNsf;
        }
        if !from.try_claim_tx(order_id) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }
        from.add_to_account(currency, -amount);
        CommandResultCode::Success
    }

    /// merge：对应 Java `buildMatcherEvents`（`:72-81`）。trivial 1:1 直传——只在调用方
    /// （`RiskEngine::internal_transfer_collect`）确认 R1 成功之后才调用，故这里不重复校验。
    pub fn build_matcher_events(to_uid: i64, currency: i32, amount: i64) -> (i64, i32, i64) {
        (to_uid, currency, amount)
    }

    /// R2：对应 Java `applyEvent`（`:84-98`）。`to` 从未见过 → 自动建 `SUSPENDED` 档
    /// （`get_or_add_suspended`），随后入账。
    pub fn apply_event(ups: &mut UserProfileService, to_uid: i64, currency: i32, amount: i64) {
        let to = ups.get_or_add_suspended(to_uid);
        to.add_to_account(currency, amount);
    }
}
