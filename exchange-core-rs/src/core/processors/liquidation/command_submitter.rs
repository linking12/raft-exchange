//! 命令提交出口回调（对应 Java `LiquidationCommandSubmitter` 函数式接口）：强平引擎、loan 子引擎、
//! scheduler 三个发令方共用同一形态——生成 FORCE/IF/ADL/scan/reprice 时经 `submit(cmd)` → 回调。
//! 单节点回调塞 `ExchangeCore.pending_commands`、集群回调提交 raft、单测回调 collector。

use crate::core::common::cmd::order_command::OrderCommand;

/// 系统自生成命令的提交出口。包一层 newtype 是因为 `Box<dyn FnMut>` 既非 `Debug` 也非 `Clone`——由本
/// 类型统一提供 `Debug`（打印为 `<fn>`），三个发令结构体即可保留 `#[derive(Debug, Default)]`。
#[derive(Default)]
pub struct CommandSubmitter(Option<Box<dyn FnMut(OrderCommand)>>);

impl CommandSubmitter {
    /// 注册出口回调（= Java `setCommandSubmitter`）。
    pub fn set(&mut self, cb: Box<dyn FnMut(OrderCommand)>) {
        self.0 = Some(cb);
    }

    /// 提交一条命令（= Java `submit(cmd)`）；未注册出口时静默丢弃。
    pub fn submit(&mut self, cmd: OrderCommand) {
        if let Some(cb) = &mut self.0 {
            cb(cmd);
        }
    }
}

impl std::fmt::Debug for CommandSubmitter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_tuple("CommandSubmitter")
            .field(&self.0.as_ref().map(|_| "<fn>"))
            .finish()
    }
}
