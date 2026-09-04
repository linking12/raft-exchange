//! P2 Task 7：`OrderBookDirectImpl` ↔ `OrderBookNaiveImpl` 差分对拍（proptest + 定向场景）。
//!
//! 对应参考文档 `docs/superpowers/specs/2026-09-01-p2-orderbook-direct-reference.md` §7/§8；
//! 唯一记录在案的内部差异（FOK_BUDGET BID 价界复用）已由 Ruling P2-1 收敛：Direct 镜像 Naive，
//! 不复刻 Java "复用 cmd.price 当每单价上限"的巧合。
//!
//! 构造约定（Ruling P2-3）：两簿均用 `new()` 且 `symbol_spec=None`，Direct 现货 BID 移价风控
//! 守卫天然惰性，同 Naive 无此风控，不构成分歧源。
//!
//! 命令生成器故意排除裸 `OrderType::Fok`：Java Direct 未实现（TODO），Naive 已补齐 IOC 价格
//! 过滤语义，属已知且刻意的行为差异，不纳入本任务差分范围。
//!
//! 数值边界：`price ∈ [1, 100_000]`、`size ∈ [1, 1_000]`，BUDGET `cmd.price ∈ [1, 20_000_000]`，
//! 最大 notional 远低于 `i64::MAX/4`，避免触碰 Task 4 的溢出饱和路径（Direct 饱和/Naive
//! wrap，故意不等价，不该被本测试意外命中）。

use std::panic;

use proptest::prelude::*;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::orderbook::i_order_book::IOrderBook;
use crate::core::orderbook::order_book_direct_impl::OrderBookDirectImpl;
use crate::core::orderbook::order_book_naive_impl::OrderBookNaiveImpl;

// ============================================================================================
// 命令生成器
// ============================================================================================

/// 随机命令：`Place` 覆盖 GTC/IOC/FOK_BUDGET/IOC_BUDGET（裸 FOK 排除，见模块头）；
/// `Cancel`/`Reduce`/`Move` 的 `target_idx` 按取模选取此前签发过的 GTC 订单（同 `e2e_tests.rs`）。
#[derive(Debug, Clone, Copy)]
enum GenCmd {
    Place { uid_idx: usize, is_bid: bool, order_type: OrderType, price: i64, size: i64, reserve_extra: i64 },
    Cancel { target_idx: usize },
    Reduce { target_idx: usize, reduce_size: i64 },
    Move { target_idx: usize, new_price: i64 },
}

/// GTC/IOC：`price` 为逐单单价；`reserve_extra` 使 BID 非预算单 `reserve_bid_price = price +
/// reserve_extra`，满足 §2 的 `reserve_bid_price >= price`。
fn gen_place_unit_priced(n_users: usize, order_type: OrderType) -> impl Strategy<Value = GenCmd> {
    (0..n_users, any::<bool>(), 1i64..=100_000i64, 1i64..=1_000i64, 0i64..=1_000i64).prop_map(
        move |(uid_idx, is_bid, price, size, reserve_extra)| GenCmd::Place {
            uid_idx,
            is_bid,
            order_type,
            price,
            size,
            reserve_extra,
        },
    )
}

/// FOK_BUDGET / IOC_BUDGET：`price` 字段编码预算总额；BID 预算单 `reserve_bid_price == price`，
/// ASK 恒 `reserve_bid_price = 0`（见 `step_place`）。
fn gen_place_budget(n_users: usize, order_type: OrderType) -> impl Strategy<Value = GenCmd> {
    (0..n_users, any::<bool>(), 1i64..=20_000_000i64, 1i64..=1_000i64).prop_map(
        move |(uid_idx, is_bid, budget, size)| GenCmd::Place {
            uid_idx,
            is_bid,
            order_type,
            price: budget,
            size,
            reserve_extra: 0,
        },
    )
}

fn gen_cmd(n_users: usize) -> impl Strategy<Value = GenCmd> {
    let cancel = (0usize..64).prop_map(|target_idx| GenCmd::Cancel { target_idx });
    let reduce = (0usize..64, 1i64..=1_000i64)
        .prop_map(|(target_idx, reduce_size)| GenCmd::Reduce { target_idx, reduce_size });
    let mv = (0usize..64, 1i64..=100_000i64)
        .prop_map(|(target_idx, new_price)| GenCmd::Move { target_idx, new_price });
    prop_oneof![
        4 => gen_place_unit_priced(n_users, OrderType::Gtc),
        3 => gen_place_unit_priced(n_users, OrderType::Ioc),
        2 => gen_place_budget(n_users, OrderType::FokBudget),
        2 => gen_place_budget(n_users, OrderType::IocBudget),
        2 => cancel,
        2 => reduce,
        2 => mv,
    ]
}

fn scenario_strategy() -> impl Strategy<Value = (usize, Vec<GenCmd>)> {
    (2usize..=5usize).prop_flat_map(|n_users| {
        let cmds = prop::collection::vec(gen_cmd(n_users), 10..80);
        (Just(n_users), cmds)
    })
}

// ============================================================================================
// 差分断言 helper
// ============================================================================================

/// 逐节点逐字段走两条 `MatcherTradeEvent` 单链表，第一处分歧返回描述（含链长不一致），
/// 便于 proptest 收缩后定位"第几个事件、哪个字段"。
fn matcher_events_diff(
    a: &Option<Box<MatcherTradeEvent>>,
    b: &Option<Box<MatcherTradeEvent>>,
) -> Option<String> {
    let mut ai = a.as_deref();
    let mut bi = b.as_deref();
    let mut idx = 0usize;
    loop {
        match (ai, bi) {
            (None, None) => return None,
            (Some(x), None) => {
                return Some(format!(
                    "event chain length mismatch at idx {idx}: naive has more events (next={:?}), direct ran out",
                    x
                ))
            }
            (None, Some(y)) => {
                return Some(format!(
                    "event chain length mismatch at idx {idx}: direct has more events (next={:?}), naive ran out",
                    y
                ))
            }
            (Some(x), Some(y)) => {
                if x.event_type != y.event_type {
                    return Some(format!(
                        "idx {idx}: event_type differs: naive={:?} direct={:?}",
                        x.event_type, y.event_type
                    ));
                }
                if x.active_order_completed != y.active_order_completed {
                    return Some(format!(
                        "idx {idx}: active_order_completed differs: naive={} direct={}",
                        x.active_order_completed, y.active_order_completed
                    ));
                }
                if x.maker_order_id != y.maker_order_id {
                    return Some(format!(
                        "idx {idx}: maker_order_id differs: naive={} direct={}",
                        x.maker_order_id, y.maker_order_id
                    ));
                }
                if x.maker_order_completed != y.maker_order_completed {
                    return Some(format!(
                        "idx {idx}: maker_order_completed differs: naive={} direct={}",
                        x.maker_order_completed, y.maker_order_completed
                    ));
                }
                if x.price != y.price {
                    return Some(format!(
                        "idx {idx}: price differs: naive={} direct={}",
                        x.price, y.price
                    ));
                }
                if x.size != y.size {
                    return Some(format!(
                        "idx {idx}: size differs: naive={} direct={}",
                        x.size, y.size
                    ));
                }
                if x.bid_gt_ask != y.bid_gt_ask {
                    return Some(format!(
                        "idx {idx}: bid_gt_ask differs: naive={} direct={}",
                        x.bid_gt_ask, y.bid_gt_ask
                    ));
                }
                if x.bidder_hold_price != y.bidder_hold_price {
                    return Some(format!(
                        "idx {idx}: bidder_hold_price differs: naive={} direct={}",
                        x.bidder_hold_price, y.bidder_hold_price
                    ));
                }
                if x.matched_order_uid != y.matched_order_uid {
                    return Some(format!(
                        "idx {idx}: matched_order_uid differs: naive={} direct={}",
                        x.matched_order_uid, y.matched_order_uid
                    ));
                }
                if x.matched_order_command_type != y.matched_order_command_type {
                    return Some(format!(
                        "idx {idx}: matched_order_command_type differs: naive={:?} direct={:?}",
                        x.matched_order_command_type, y.matched_order_command_type
                    ));
                }
                ai = x.next.as_deref();
                bi = y.next.as_deref();
                idx += 1;
            }
        }
    }
}

// ============================================================================================
// 差分执行 harness：两簿并行喂同一条命令流，每步后比对全部可观测面。
// ============================================================================================

/// 两簿 + 共享的 GTC 已签发订单登记表（供 Cancel/Reduce/Move 选取目标）；两簿均不带交易对
/// spec（见模块头），Direct 现货 BID 移价风控守卫（Ruling P2-3）惰性，不构成分歧源。
struct DiffHarness {
    naive: OrderBookNaiveImpl,
    direct: OrderBookDirectImpl,
    uids: Vec<i64>,
    /// (order_id, uid)，仅 GTC Place 返回 `Success` 时登记；命中已消失（成交/撤销）的旧 id 时
    /// 两簿同返回 `MatchingUnknownOrderId`，视为合式覆盖。
    issued: Vec<(i64, i64)>,
    next_order_id: i64,
}

impl DiffHarness {
    fn new(uids: Vec<i64>) -> Self {
        Self {
            naive: OrderBookNaiveImpl::new(),
            direct: OrderBookDirectImpl::new(),
            uids,
            issued: Vec::new(),
            next_order_id: 1,
        }
    }

    /// 应用一条命令到两簿，比对 `result_code`/`matcher_event`/`cmd.action` 与 `fill_l2`/
    /// `state_hash`，并跑 `direct.validate_internal_state()`；任何分歧返回 `Err(描述)`。
    fn step(&mut self, step_idx: usize, gen: &GenCmd) -> Result<(), String> {
        match *gen {
            GenCmd::Place { uid_idx, is_bid, order_type, price, size, reserve_extra } => {
                self.step_place(step_idx, uid_idx, is_bid, order_type, price, size, reserve_extra)?;
            }
            GenCmd::Cancel { target_idx } => self.step_cancel(step_idx, target_idx)?,
            GenCmd::Reduce { target_idx, reduce_size } => {
                self.step_reduce(step_idx, target_idx, reduce_size)?
            }
            GenCmd::Move { target_idx, new_price } => self.step_move(step_idx, target_idx, new_price)?,
        }
        self.check_invariants(step_idx)
    }

    #[allow(clippy::too_many_arguments)]
    fn step_place(
        &mut self,
        step_idx: usize,
        uid_idx: usize,
        is_bid: bool,
        order_type: OrderType,
        price: i64,
        size: i64,
        reserve_extra: i64,
    ) -> Result<(), String> {
        let uid = self.uids[uid_idx % self.uids.len()];
        let action = if is_bid { OrderAction::Bid } else { OrderAction::Ask };
        // reserve_bid_price 约定（见生成器文档）：BID 预算单 == 预算本身，BID 非预算单 >= price，ASK 恒 0。
        let reserve_bid_price = match (is_bid, order_type) {
            (true, OrderType::FokBudget) | (true, OrderType::IocBudget) => price,
            (true, _) => price + reserve_extra,
            (false, _) => 0,
        };
        let order_id = self.next_order_id;
        self.next_order_id += 1;

        let mut cmd_n = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            symbol: 1,
            price,
            size,
            reserve_bid_price,
            action: Some(action),
            order_type: Some(order_type),
            uid,
            timestamp: step_idx as i64,
            ..Default::default()
        };
        let mut cmd_d = cmd_n.clone();

        let rc_n = self.naive.new_order(&mut cmd_n);
        let rc_d = self.direct.new_order(&mut cmd_d);

        Self::diff_cmd(step_idx, "PLACE", rc_n, rc_d, &cmd_n, &cmd_d)?;

        if rc_n == CommandResultCode::Success && order_type == OrderType::Gtc {
            self.issued.push((order_id, uid));
        }
        Ok(())
    }

    fn step_cancel(&mut self, step_idx: usize, target_idx: usize) -> Result<(), String> {
        if self.issued.is_empty() {
            return Ok(());
        }
        let (order_id, uid) = self.issued[target_idx % self.issued.len()];
        let mut cmd_n =
            OrderCommand { command: OrderCommandType::CancelOrder, order_id, uid, ..Default::default() };
        let mut cmd_d = cmd_n.clone();
        let rc_n = self.naive.cancel_order(&mut cmd_n);
        let rc_d = self.direct.cancel_order(&mut cmd_d);
        Self::diff_cmd(step_idx, "CANCEL", rc_n, rc_d, &cmd_n, &cmd_d)
    }

    fn step_reduce(&mut self, step_idx: usize, target_idx: usize, reduce_size: i64) -> Result<(), String> {
        if self.issued.is_empty() {
            return Ok(());
        }
        let (order_id, uid) = self.issued[target_idx % self.issued.len()];
        let mut cmd_n = OrderCommand {
            command: OrderCommandType::ReduceOrder,
            order_id,
            uid,
            size: reduce_size,
            ..Default::default()
        };
        let mut cmd_d = cmd_n.clone();
        let rc_n = self.naive.reduce_order(&mut cmd_n);
        let rc_d = self.direct.reduce_order(&mut cmd_d);
        Self::diff_cmd(step_idx, "REDUCE", rc_n, rc_d, &cmd_n, &cmd_d)
    }

    fn step_move(&mut self, step_idx: usize, target_idx: usize, new_price: i64) -> Result<(), String> {
        if self.issued.is_empty() {
            return Ok(());
        }
        let (order_id, uid) = self.issued[target_idx % self.issued.len()];
        let mut cmd_n = OrderCommand {
            command: OrderCommandType::MoveOrder,
            order_id,
            uid,
            price: new_price,
            ..Default::default()
        };
        let mut cmd_d = cmd_n.clone();
        let rc_n = self.naive.move_order(&mut cmd_n);
        let rc_d = self.direct.move_order(&mut cmd_d);
        Self::diff_cmd(step_idx, "MOVE", rc_n, rc_d, &cmd_n, &cmd_d)
    }

    fn diff_cmd(
        step_idx: usize,
        label: &str,
        rc_n: CommandResultCode,
        rc_d: CommandResultCode,
        cmd_n: &OrderCommand,
        cmd_d: &OrderCommand,
    ) -> Result<(), String> {
        if rc_n != rc_d {
            return Err(format!(
                "step {step_idx} [{label}]: result_code mismatch: naive={:?} direct={:?}",
                rc_n, rc_d
            ));
        }
        if let Some(diff) = matcher_events_diff(&cmd_n.matcher_event, &cmd_d.matcher_event) {
            return Err(format!("step {step_idx} [{label}]: matcher_event {diff}"));
        }
        if cmd_n.action != cmd_d.action {
            return Err(format!(
                "step {step_idx} [{label}]: cmd.action mismatch: naive={:?} direct={:?}",
                cmd_n.action, cmd_d.action
            ));
        }
        Ok(())
    }

    /// 每步之后比对 `fill_l2`/`state_hash` 逐位相等，并跑 `direct.validate_internal_state()`
    /// （`catch_unwind` 转 `Result`，校验 §7 的 10 条不变式）；Naive 无等价方法，不比对。
    fn check_invariants(&self, step_idx: usize) -> Result<(), String> {
        let l2_n = self.naive.fill_l2(-1);
        let l2_d = self.direct.fill_l2(-1);
        if l2_n != l2_d {
            return Err(format!(
                "step {step_idx}: fill_l2 mismatch: naive={:?} direct={:?}",
                l2_n, l2_d
            ));
        }

        let hn = self.naive.state_hash();
        let hd = self.direct.state_hash();
        if hn != hd {
            return Err(format!("step {step_idx}: state_hash mismatch: naive={hn} direct={hd}"));
        }

        let direct_ref = &self.direct;
        let validated =
            panic::catch_unwind(panic::AssertUnwindSafe(|| direct_ref.validate_internal_state()));
        if validated.is_err() {
            return Err(format!(
                "step {step_idx}: direct.validate_internal_state() panicked (see captured panic message above)"
            ));
        }

        Ok(())
    }
}

// ============================================================================================
// proptest：随机命令流差分对拍
// ============================================================================================

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    /// P2 Task 7 核心：随机命令流逐步喂两簿，`result_code`/`matcher_event`/`fill_l2`/
    /// `state_hash` 须逐位相等，且 Direct 的 `validate_internal_state` 全程成立；任何分歧都是
    /// 真实的 Direct 移植 bug（Naive 是 source of truth）。
    #[test]
    fn direct_matches_naive_for_random_command_stream((n_users, cmds) in scenario_strategy()) {
        let uids: Vec<i64> = (1..=n_users as i64).collect();
        let mut harness = DiffHarness::new(uids);
        for (step_idx, gen) in cmds.iter().enumerate() {
            if let Err(msg) = harness.step(step_idx, gen) {
                prop_assert!(false, "{}", msg);
            }
        }
    }
}

// ============================================================================================
// 定向场景测试（非 proptest，作为快速回归锚点）
// ============================================================================================

#[cfg(test)]
mod scenario_tests {
    use super::*;

    fn run_scenario(uids: Vec<i64>, cmds: &[GenCmd]) {
        let mut harness = DiffHarness::new(uids);
        for (step_idx, gen) in cmds.iter().enumerate() {
            if let Err(msg) = harness.step(step_idx, gen) {
                panic!("{msg}");
            }
        }
    }

    fn place(
        uid_idx: usize,
        is_bid: bool,
        order_type: OrderType,
        price: i64,
        size: i64,
        reserve_extra: i64,
    ) -> GenCmd {
        GenCmd::Place { uid_idx, is_bid, order_type, price, size, reserve_extra }
    }

    /// 多桶扫单：多价位挂 ASK，一笔大 BID GTC 依次扫过若干桶（含吃穿、部分吃穿留簿），
    /// 覆盖跨桶循环、`insertOrder` 情形 A/B、桶释放（§4.1(i)）与挂簿路径。
    #[test]
    fn multi_bucket_sweep_matches_naive() {
        let uids = vec![1, 2, 3, 4];
        let cmds = vec![
            // 卖方在 100/101/101/102/105 五档挂单（101 两笔同价 FIFO，验证桶内序）。
            place(0, false, OrderType::Gtc, 100, 10, 0),
            place(1, false, OrderType::Gtc, 101, 5, 0),
            place(1, false, OrderType::Gtc, 101, 7, 0),
            place(2, false, OrderType::Gtc, 102, 20, 0),
            place(2, false, OrderType::Gtc, 105, 1, 0),
            // 买方一笔大单扫过 100/101(x2)/102，留一部分在 105 之前（105 桶不吃，留簿新单）。
            place(3, true, OrderType::Gtc, 104, 41, 5),
            // 再补一笔挂在更差价位，确认挂单/桶结构仍然一致。
            place(0, true, OrderType::Gtc, 90, 3, 0),
        ];
        run_scenario(uids, &cmds);
    }

    /// cancel + move：撤中间一笔 BID，另一笔移到能撮合对手 ASK 的新价（move 作为 taker
    /// 重新撮合路径；两簿均不带 symbol_spec，Direct BID 移价守卫惰性，不构成分歧源）。
    #[test]
    fn cancel_and_move_matches_naive() {
        let uids = vec![1, 2, 3];
        let cmds = vec![
            place(0, true, OrderType::Gtc, 50, 10, 0),  // idx0 -> issued[0]
            place(1, true, OrderType::Gtc, 51, 8, 0),   // idx1 -> issued[1]
            place(2, true, OrderType::Gtc, 49, 6, 0),   // idx2 -> issued[2]
            GenCmd::Cancel { target_idx: 1 },           // 撤 issued[1]（51@8）
            place(1, false, OrderType::Gtc, 60, 4, 0),  // 挂一笔 ASK@60，供 move 撮合
            GenCmd::Move { target_idx: 0, new_price: 60 }, // 把 issued[0]（50@10）移到 60，吃掉 ASK@60(4)，剩 6 挂 60
            GenCmd::Move { target_idx: 2, new_price: 55 }, // 再把 issued[2]（49@6）移到 55（无对手，纯挂新价）
        ];
        run_scenario(uids, &cmds);
    }

    /// move 到能立即成交的新价：验证 move 后作为 taker 撮合，部分成交重挂新价/全部成交不重挂两条分支。
    ///
    /// `issued` 下标提醒：ASK 是 `issued[0]`、BID 是 `issued[1]`，`target_idx` 须用 `1` 才能移动
    /// BID 与 ASK 交叉（早期误用 0 挪动了 ASK 导致测试未测到预期路径，复现了与 proptest 相同的
    /// `bidder_hold_price` 分歧，见 `p2-task7-report.md`）。
    #[test]
    fn move_into_crossing_price_matches_naive() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 200, 5, 0), // ASK@200 size5 -> issued[0]
            place(1, true, OrderType::Gtc, 100, 10, 0), // BID@100 size10 -> issued[1]
            // 把 BID 移到 200：正好吃满对手 5，剩 5 挂在新价 200。
            GenCmd::Move { target_idx: 1, new_price: 200 },
            // 挂一笔 ASK@200 size3：此刻 best_bid 就在 200，GTC 下单即刻撮合（不经 move），
            // BID 剩余 5-3=2 留在簿上。
            place(0, false, OrderType::Gtc, 200, 3, 0),
            // 再把 BID 移到 300：无对手，纯挂新价，剩余量不变（2）。
            GenCmd::Move { target_idx: 1, new_price: 300 },
        ];
        run_scenario(uids, &cmds);
    }

    /// IOC：流动性不足部分成交 + 完全无流动性整单 reject 两条路径。
    #[test]
    fn ioc_matches_naive() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 100, 4, 0),
            place(1, true, OrderType::Ioc, 100, 10, 0), // 只能吃 4，剩 6 reject
            place(1, true, OrderType::Ioc, 100, 5, 0),  // 此刻对手侧已空 -> 整单 reject
        ];
        run_scenario(uids, &cmds);
    }

    /// FOK_BUDGET：满足/不满足两条路径，含 §8 专测——小预算恰好覆盖高价 ASK，验证 Ruling P2-1
    /// （Direct 镜像 Naive、不设每单价上限）下两簿判定一致；及预算不足的整单 reject 对照。
    #[test]
    fn fok_budget_matches_naive_including_ruling_p2_1_case() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 480, 1, 0), // 唯一一档 ASK@480 size1
            // 预算 500 >= 480*1，应整单成交（Ruling P2-1 场景）。
            place(1, true, OrderType::FokBudget, 500, 1, 0),
            // 重新铺一档更贵的 ASK，验证预算不足的 reject 路径。
            place(0, false, OrderType::Gtc, 1_000, 2, 0),
            place(1, true, OrderType::FokBudget, 100, 2, 0), // 100 远不够 2*1000 -> reject
            // ASK 方向 FOK_BUDGET 也走一遍（对手是 BID）。
            place(0, true, OrderType::Gtc, 300, 5, 0),
            place(1, false, OrderType::FokBudget, 1_400, 5, 0), // 5*300=1500 > 1400 收入门槛不满足(ASK 要求 calc>=limit)
            place(1, false, OrderType::FokBudget, 1_000, 5, 0), // 1500>=1000 满足 -> 整单成交
        ];
        run_scenario(uids, &cmds);
    }

    /// IOC_BUDGET：仅 BID 有意义；覆盖预算跨价位不延续的批次边界（见 `match_against_budget_ioc`），
    /// 以及 ASK 整单 reject、预算不够买 1 单位的 reject。
    #[test]
    fn ioc_budget_matches_naive_across_bucket_boundary() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 10, 3, 0),  // ASK@10 size3
            place(0, false, OrderType::Gtc, 20, 100, 0), // ASK@20 size100（充足流动性但更贵）
            // 预算 50：第一档 3*10=30，剩预算 20，第二档 20/20=1，命中"批次归零重新计算"路径（不跨桶延续）。
            place(1, true, OrderType::IocBudget, 50, 100, 0),
            // ASK 方向：整单 reject。
            place(1, false, OrderType::IocBudget, 50, 10, 0),
            // 预算太小买不起 1 单位（最低价 20，预算 5）。
            place(1, true, OrderType::IocBudget, 5, 10, 0),
        ];
        run_scenario(uids, &cmds);
    }

    /// reduce：部分减量留簿 + 减量超过剩余量退化为整单撤销，两条路径都对拍。
    #[test]
    fn reduce_matches_naive() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, true, OrderType::Gtc, 70, 10, 0), // issued[0]
            GenCmd::Reduce { target_idx: 0, reduce_size: 4 }, // 部分减量，留簿 6
            GenCmd::Reduce { target_idx: 0, reduce_size: 100 }, // 超过剩余 -> 整单撤销
            GenCmd::Reduce { target_idx: 0, reduce_size: 1 }, // 此刻已不存在 -> 两簿同返回 UnknownOrderId
        ];
        run_scenario(uids, &cmds);
    }
}
