//! 对应 Java: `exchange.core2.core.processors.liquidation.LiquidationScheduledService`（`LiquidationEngine`
//! 的抽象父类里的定时器 harness 部分）+ `coveredByScanSlice` 静态切片函数。参考文档 §7、§11.4。
//!
//! # 移植偏差（Ruling P6-F）
//! Java 用 `ScheduledExecutorService.scheduleWithFixedDelay` 起后台线程周期 tick；本移植（纯库、
//! 无 server raft 集成）简化为**可手动 tick 的 harness**——[`LiquidationScheduler::run_one_iteration`]
//! 由外部（server 侧定时器 / 测试）驱动，产出的命令进 [`LiquidationScheduler::pending_commands`]
//! 队列（同 `liquidation_engine.rs` 的 submit→队列偏差），由 driver 排空喂回管线。`is_running`
//! （leader 门）+ shard-0 门与 Java 契约一致；start/stop 的 raft leadership 接线在 exchange-core 外。
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;

/// 对应 Java `LiquidationScheduledService.coveredByScanSlice(cmd, uid)`（`:129-134`）：**静态、
/// 期货 + 借贷扫描器共用**（§7.3）。非 `LIQUIDATION_SCAN` 或 `size<=0`（切片数无效 = 全扫，
/// legacy/replay）恒 `true`；否则 `floorMod(uid, sliceCount) == scanSlice`（`cmd.size` = sliceCount，
/// `cmd.uid` = scanSlice，见 §7.4 `ApiLiquidationScan` 映射）。`floorMod` 用 `rem_euclid`
/// （= Java `Math.floorMod`，对负 uid 也返非负余数）。
pub fn covered_by_scan_slice(cmd: &OrderCommand, uid: i64) -> bool {
    if cmd.command != OrderCommandType::LiquidationScan || cmd.size <= 0 {
        return true;
    }
    uid.rem_euclid(cmd.size) == cmd.uid
}

/// 对应 Java `LiquidationScheduledService`（定时器 harness 部分）：shard-0-only 的强平扫描 tick。
/// 只持有 leader-local 调度状态（`scan_tick`/切片数/reprice 周期/leader 门 + 提交队列），非复制
/// ——切片选择虽由非确定的 tick 时钟产出，但**落进 `LIQUIDATION_SCAN.scan_slice` 命令字段经 raft
/// 复制**，故所有副本看到同一切片（§7.2/§11.4）。
#[derive(Debug)]
pub struct LiquidationScheduler {
    /// 本地递增 tick 计数（非复制）。切片号 = `scan_tick mod scan_slice_count`。
    pub scan_tick: i64,
    /// 扫描切片总数（round-robin 把全体用户分成这么多片，每 tick 扫一片）。
    pub scan_slice_count: i64,
    /// 每 N tick 提交一次 `REPRICE_LOAN_RATES`（loan 利率重定价，P5 §4.2）。
    pub reprice_every_n_ticks: i64,
    /// shard id：只有 shard 0 跑调度器（其提交的命令经 raft 到达所有 shard，§7.2）。
    pub shard_id: i32,
    /// leader 门（同 `LiquidationEngine::is_running`）：`false` 时 `run_one_iteration` no-op。
    pub is_running: bool,
    /// 提交队列（替代 Java disruptor `submit`）：`run_one_iteration` 产出的 `LIQUIDATION_SCAN`/
    /// `REPRICE_LOAN_RATES` 命令，由 driver 排空喂回管线。
    pub pending_commands: Vec<OrderCommand>,
}

impl LiquidationScheduler {
    /// `scan_slice_count`/`reprice_every_n_ticks` 对应 Java 系统属性默认（`raftexchange.liquidation
    /// .scanSlices=10`、`raftexchange.loanReprice.everyNTicks=30`）；`shard_id`/`is_running` 由
    /// server 侧按 raft leadership 设置（默认 shard 0、follower 起步）。
    pub fn new(scan_slice_count: i64, reprice_every_n_ticks: i64, shard_id: i32) -> Self {
        LiquidationScheduler {
            scan_tick: 0,
            scan_slice_count,
            reprice_every_n_ticks,
            shard_id,
            is_running: false,
            pending_commands: Vec::new(),
        }
    }

    /// 对应 Java `runOneIteration`（`:57-67`）：**shard-0-only** 一次 tick——提交本片
    /// `LIQUIDATION_SCAN`（`symbol=-1`、`uid=slice`、`size=sliceCount`，§7.4）；每
    /// `reprice_every_n_ticks` 个 tick 额外提交一次 `REPRICE_LOAN_RATES`；`scan_tick++`。
    /// `is_running=false`（follower）或非 shard 0 → no-op（不 tick、不提交）。
    ///
    /// `timestamp` 由调用方传入（server 侧定时器的复制时钟 / 测试固定值）——命令入队后经管线时
    /// 用它做确定性的时间相关计算（同其它命令）。
    pub fn run_one_iteration(&mut self, timestamp: i64) {
        if !self.is_running || self.shard_id != 0 {
            return;
        }
        let slice = self.scan_tick.rem_euclid(self.scan_slice_count.max(1));
        self.pending_commands.push(OrderCommand {
            command: OrderCommandType::LiquidationScan,
            symbol: -1,
            uid: slice,
            size: self.scan_slice_count,
            timestamp,
            ..Default::default()
        });
        if self.reprice_every_n_ticks > 0 && self.scan_tick % self.reprice_every_n_ticks == 0 {
            self.pending_commands.push(OrderCommand {
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

    fn scan_cmd(uid: i64, size: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid, size, ..Default::default() }
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
        // sliceCount=10, scanSlice=3 -> 只覆盖 uid mod 10 == 3。
        let cmd = scan_cmd(3, 10);
        assert!(covered_by_scan_slice(&cmd, 3));
        assert!(covered_by_scan_slice(&cmd, 13));
        assert!(covered_by_scan_slice(&cmd, 103));
        assert!(!covered_by_scan_slice(&cmd, 4));
        assert!(!covered_by_scan_slice(&cmd, 12));
    }

    #[test]
    fn covered_by_scan_slice_negative_uid_uses_floor_mod() {
        // floorMod(-7, 10) == 3（Rust rem_euclid），与 Java Math.floorMod 一致。
        let cmd = scan_cmd(3, 10);
        assert!(covered_by_scan_slice(&cmd, -7));
    }

    #[test]
    fn run_one_iteration_leader_gate_off_is_noop() {
        let mut s = LiquidationScheduler::new(10, 30, 0);
        // is_running 默认 false。
        s.run_one_iteration(1_000);
        assert!(s.pending_commands.is_empty());
        assert_eq!(s.scan_tick, 0);
    }

    #[test]
    fn run_one_iteration_non_shard_zero_is_noop() {
        let mut s = LiquidationScheduler::new(10, 30, 1); // shard 1
        s.is_running = true;
        s.run_one_iteration(1_000);
        assert!(s.pending_commands.is_empty());
        assert_eq!(s.scan_tick, 0);
    }

    #[test]
    fn run_one_iteration_emits_scan_with_slice_and_advances_tick() {
        let mut s = LiquidationScheduler::new(3, 30, 0);
        s.is_running = true;
        // tick0: slice=0；tick0 % 30 == 0 -> 也发 reprice。
        s.run_one_iteration(1_000);
        assert_eq!(s.pending_commands.len(), 2, "tick0：LIQUIDATION_SCAN + REPRICE_LOAN_RATES");
        let scan = &s.pending_commands[0];
        assert_eq!(scan.command, OrderCommandType::LiquidationScan);
        assert_eq!(scan.symbol, -1);
        assert_eq!(scan.uid, 0, "slice = tick0 mod 3 = 0");
        assert_eq!(scan.size, 3, "sliceCount");
        assert_eq!(s.pending_commands[1].command, OrderCommandType::RepriceLoanRates);
        assert_eq!(s.scan_tick, 1);

        s.pending_commands.clear();
        // tick1: slice=1；1 % 30 != 0 -> 不发 reprice。
        s.run_one_iteration(2_000);
        assert_eq!(s.pending_commands.len(), 1, "tick1：只 LIQUIDATION_SCAN");
        assert_eq!(s.pending_commands[0].uid, 1, "slice = tick1 mod 3 = 1");
        assert_eq!(s.scan_tick, 2);

        s.pending_commands.clear();
        // tick2: slice=2。
        s.run_one_iteration(3_000);
        assert_eq!(s.pending_commands[0].uid, 2);

        s.pending_commands.clear();
        // tick3: slice = 3 mod 3 = 0（round-robin 回绕）。
        s.run_one_iteration(4_000);
        assert_eq!(s.pending_commands[0].uid, 0, "slice round-robin 回绕到 0");
    }

    #[test]
    fn run_one_iteration_reprice_every_n_ticks() {
        let mut s = LiquidationScheduler::new(100, 2, 0); // 每 2 tick reprice
        s.is_running = true;
        let mut reprice_ticks = Vec::new();
        for t in 0..6 {
            s.run_one_iteration(t);
            if s.pending_commands.iter().any(|c| c.command == OrderCommandType::RepriceLoanRates) {
                reprice_ticks.push(t);
            }
            s.pending_commands.clear();
        }
        assert_eq!(reprice_ticks, vec![0, 2, 4], "tick 0/2/4 各发一次 reprice（scan_tick % 2 == 0）");
    }
}
