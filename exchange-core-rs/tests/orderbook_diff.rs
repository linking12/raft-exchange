use std::panic;

use proptest::prelude::*;

use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
use exchange_core_rs::core::common::matcher_trade_event::MatcherTradeEvent;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::orderbook::i_order_book::IOrderBook;
use exchange_core_rs::core::orderbook::order_book_direct_impl::OrderBookDirectImpl;
use exchange_core_rs::core::orderbook::order_book_naive_impl::OrderBookNaiveImpl;

#[derive(Debug, Clone, Copy)]
enum GenCmd {
    Place { uid_idx: usize, is_bid: bool, order_type: OrderType, price: i64, size: i64, reserve_extra: i64 },
    Cancel { target_idx: usize },
    Reduce { target_idx: usize, reduce_size: i64 },
    Move { target_idx: usize, new_price: i64 },
}

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
        2 => gen_place_unit_priced(n_users, OrderType::Fok),
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

struct DiffHarness {
    naive: OrderBookNaiveImpl,
    direct: OrderBookDirectImpl,
    uids: Vec<i64>,
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

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

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

    #[test]
    fn multi_bucket_sweep_matches_naive() {
        let uids = vec![1, 2, 3, 4];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 100, 10, 0),
            place(1, false, OrderType::Gtc, 101, 5, 0),
            place(1, false, OrderType::Gtc, 101, 7, 0),
            place(2, false, OrderType::Gtc, 102, 20, 0),
            place(2, false, OrderType::Gtc, 105, 1, 0),
            place(3, true, OrderType::Gtc, 104, 41, 5),
            place(0, true, OrderType::Gtc, 90, 3, 0),
        ];
        run_scenario(uids, &cmds);
    }

    #[test]
    fn cancel_and_move_matches_naive() {
        let uids = vec![1, 2, 3];
        let cmds = vec![
            place(0, true, OrderType::Gtc, 50, 10, 0),
            place(1, true, OrderType::Gtc, 51, 8, 0),
            place(2, true, OrderType::Gtc, 49, 6, 0),
            GenCmd::Cancel { target_idx: 1 },
            place(1, false, OrderType::Gtc, 60, 4, 0),
            GenCmd::Move { target_idx: 0, new_price: 60 },
            GenCmd::Move { target_idx: 2, new_price: 55 },
        ];
        run_scenario(uids, &cmds);
    }

    #[test]
    fn move_into_crossing_price_matches_naive() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 200, 5, 0),
            place(1, true, OrderType::Gtc, 100, 10, 0),
            GenCmd::Move { target_idx: 1, new_price: 200 },
            place(0, false, OrderType::Gtc, 200, 3, 0),
            GenCmd::Move { target_idx: 1, new_price: 300 },
        ];
        run_scenario(uids, &cmds);
    }

    #[test]
    fn ioc_matches_naive() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 100, 4, 0),
            place(1, true, OrderType::Ioc, 100, 10, 0),
            place(1, true, OrderType::Ioc, 100, 5, 0),
        ];
        run_scenario(uids, &cmds);
    }

    #[test]
    fn fok_budget_matches_naive_including_ruling_p2_1_case() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 480, 1, 0),
            place(1, true, OrderType::FokBudget, 500, 1, 0),
            place(0, false, OrderType::Gtc, 1_000, 2, 0),
            place(1, true, OrderType::FokBudget, 100, 2, 0),
            place(0, true, OrderType::Gtc, 300, 5, 0),
            place(1, false, OrderType::FokBudget, 1_400, 5, 0),
            place(1, false, OrderType::FokBudget, 1_000, 5, 0),
        ];
        run_scenario(uids, &cmds);
    }

    #[test]
    fn fok_matches_naive() {
        let uids = vec![1, 2, 3];
        let cmds = vec![
            place(0, true, OrderType::Fok, 100, 5, 0),
            place(1, false, OrderType::Gtc, 100, 3, 0),
            place(1, false, OrderType::Gtc, 101, 2, 0),
            place(0, true, OrderType::Fok, 101, 5, 0),
            place(1, false, OrderType::Gtc, 100, 4, 0),
            place(0, true, OrderType::Fok, 100, 5, 0),
            place(1, false, OrderType::Gtc, 200, 5, 0),
            place(0, true, OrderType::Fok, 100, 5, 0),
            place(0, true, OrderType::Fok, 200, 9, 0),
            place(1, true, OrderType::Gtc, 50, 10, 0),
            place(0, false, OrderType::Fok, 50, 6, 0),
            place(0, false, OrderType::Fok, 50, 10, 0),
        ];
        run_scenario(uids, &cmds);
    }

    #[test]
    fn ioc_budget_matches_naive_across_bucket_boundary() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, false, OrderType::Gtc, 10, 3, 0),
            place(0, false, OrderType::Gtc, 20, 100, 0),
            place(1, true, OrderType::IocBudget, 50, 100, 0),
            place(1, false, OrderType::IocBudget, 50, 10, 0),
            place(1, true, OrderType::IocBudget, 5, 10, 0),
        ];
        run_scenario(uids, &cmds);
    }

    #[test]
    fn reduce_matches_naive() {
        let uids = vec![1, 2];
        let cmds = vec![
            place(0, true, OrderType::Gtc, 70, 10, 0),
            GenCmd::Reduce { target_idx: 0, reduce_size: 4 },
            GenCmd::Reduce { target_idx: 0, reduce_size: 100 },
            GenCmd::Reduce { target_idx: 0, reduce_size: 1 },
        ];
        run_scenario(uids, &cmds);
    }
}
