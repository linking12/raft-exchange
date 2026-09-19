use std::cell::RefCell;
use std::rc::Rc;

use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::processors::liquidation::command_submitter::{CommandSubmitter, CommandSubmitterHandle};

pub fn covered_by_scan_slice(cmd: &OrderCommand, uid: i64) -> bool {
    if cmd.command != OrderCommandType::LiquidationScan || cmd.size <= 0 {
        return true;
    }
    uid.rem_euclid(cmd.size) == cmd.uid
}

#[derive(Debug, Default)]
pub struct LiquidationScheduler {
    pub scan_tick: i64,
    pub scan_slice_count: i64,
    pub reprice_every_n_ticks: i64,
    pub shard_id: i32,
    pub is_running: bool,
    command_submitter: CommandSubmitterHandle,
}

impl LiquidationScheduler {
    pub fn new(scan_slice_count: i64, reprice_every_n_ticks: i64, shard_id: i32) -> Self {
        LiquidationScheduler {
            scan_tick: 0,
            scan_slice_count,
            reprice_every_n_ticks: reprice_every_n_ticks.max(1),
            shard_id,
            is_running: false,
            command_submitter: CommandSubmitterHandle::default(),
        }
    }

    pub fn set_command_submitter(&mut self, submitter: Rc<RefCell<dyn CommandSubmitter>>) {
        self.command_submitter.set(submitter);
    }

    pub fn run_one_iteration(&mut self, timestamp: i64) {
        if !self.is_running || self.shard_id != 0 {
            return;
        }
        let slice = self.scan_tick.rem_euclid(self.scan_slice_count.max(1));
        self.command_submitter.submit(OrderCommand {
            command: OrderCommandType::LiquidationScan,
            symbol: -1,
            uid: slice,
            size: self.scan_slice_count,
            timestamp,
            ..Default::default()
        });
        if self.reprice_every_n_ticks > 0 && self.scan_tick % self.reprice_every_n_ticks == 0 {
            self.command_submitter.submit(OrderCommand {
                command: OrderCommandType::RepriceLoanRates,
                timestamp,
                ..Default::default()
            });
        }
        self.scan_tick += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::rc::Rc;

    fn scan_cmd(uid: i64, size: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid, size, ..Default::default() }
    }

    fn attach_collector(s: &mut LiquidationScheduler) -> Rc<RefCell<Vec<OrderCommand>>> {
        let collected = Rc::new(RefCell::new(Vec::new()));
        s.set_command_submitter(Rc::new(RefCell::new(crate::core::processors::liquidation::command_submitter::VecCommandSink(collected.clone()))));
        collected
    }

    #[test]
    fn covered_by_scan_slice_non_scan_always_covered() {
        let markprice = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: 1, ..Default::default() };
        assert!(covered_by_scan_slice(&markprice, 12345));
    }

    #[test]
    fn covered_by_scan_slice_zero_slice_count_is_full_scan() {
        assert!(covered_by_scan_slice(&scan_cmd(0, 0), 999));
    }

    #[test]
    fn covered_by_scan_slice_floor_mod_filters_uid() {
        let cmd = scan_cmd(3, 10);
        assert!(covered_by_scan_slice(&cmd, 3));
        assert!(covered_by_scan_slice(&cmd, 13));
        assert!(covered_by_scan_slice(&cmd, 103));
        assert!(!covered_by_scan_slice(&cmd, 4));
        assert!(!covered_by_scan_slice(&cmd, 12));
    }

    #[test]
    fn covered_by_scan_slice_negative_uid_uses_floor_mod() {
        let cmd = scan_cmd(3, 10);
        assert!(covered_by_scan_slice(&cmd, -7));
    }

    #[test]
    fn run_one_iteration_leader_gate_off_is_noop() {
        let mut s = LiquidationScheduler::new(10, 30, 0);
        let out = attach_collector(&mut s);
        s.run_one_iteration(1_000);
        assert!(out.borrow().is_empty());
        assert_eq!(s.scan_tick, 0);
    }

    #[test]
    fn run_one_iteration_non_shard_zero_is_noop() {
        let mut s = LiquidationScheduler::new(10, 30, 1);
        s.is_running = true;
        let out = attach_collector(&mut s);
        s.run_one_iteration(1_000);
        assert!(out.borrow().is_empty());
        assert_eq!(s.scan_tick, 0);
    }

    #[test]
    fn run_one_iteration_emits_scan_with_slice_and_advances_tick() {
        let mut s = LiquidationScheduler::new(3, 30, 0);
        s.is_running = true;
        let out = attach_collector(&mut s);
        s.run_one_iteration(1_000);
        assert_eq!(out.borrow().len(), 2, "tick0: LIQUIDATION_SCAN + REPRICE_LOAN_RATES");
        let scan = out.borrow()[0].clone();
        assert_eq!(scan.command, OrderCommandType::LiquidationScan);
        assert_eq!(scan.symbol, -1);
        assert_eq!(scan.uid, 0, "slice = tick0 mod 3 = 0");
        assert_eq!(scan.size, 3, "sliceCount");
        assert_eq!(out.borrow()[1].command, OrderCommandType::RepriceLoanRates);
        assert_eq!(s.scan_tick, 1);

        out.borrow_mut().clear();
        s.run_one_iteration(2_000);
        assert_eq!(out.borrow().len(), 1, "tick1: LIQUIDATION_SCAN only");
        assert_eq!(out.borrow()[0].uid, 1, "slice = tick1 mod 3 = 1");
        assert_eq!(s.scan_tick, 2);

        out.borrow_mut().clear();
        s.run_one_iteration(3_000);
        assert_eq!(out.borrow()[0].uid, 2);

        out.borrow_mut().clear();
        s.run_one_iteration(4_000);
        assert_eq!(out.borrow()[0].uid, 0, "slice round-robin wraps back to 0");
    }

    #[test]
    fn run_one_iteration_reprice_every_n_ticks() {
        let mut s = LiquidationScheduler::new(100, 2, 0);
        s.is_running = true;
        let out = attach_collector(&mut s);
        let mut reprice_ticks = Vec::new();
        for t in 0..6 {
            s.run_one_iteration(t);
            if out.borrow().iter().any(|c| c.command == OrderCommandType::RepriceLoanRates) {
                reprice_ticks.push(t);
            }
            out.borrow_mut().clear();
        }
        assert_eq!(reprice_ticks, vec![0, 2, 4], "reprice fires once at tick 0/2/4 (scan_tick % 2 == 0)");
    }
}
