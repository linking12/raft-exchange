//! 对应 Java `exchange.core2.core.common.MatcherEventType`。撮合引擎产出的事件类别。
//! 仅移植 Java 枚举里撮合器实际会产的核心子集;Java 侧另有 IF_EVENT/ADL_EVENT/
//! FUNDING_EVENT/RESET_FEE_EVENT/LOAN_REPRICE_EVENT/INTERNAL_TRANSFER_EVENT 等
//! 由风控/清算等阶段产生的类别,在本枚举外单独承载。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MatcherEventType {
    /// 对应 Java `TRADE`:成交事件(place / move 命令均可触发)。
    Trade,
    /// 对应 Java `REJECT`:市价单因流动性不足被拒(订单簿该侧已空);被拒前可能已部分成交。
    Reject,
    /// 对应 Java `REDUCE`:撤单/减量后,风控据此解锁相应保证金。
    Reduce,
    /// 对应 Java `BINARY_EVENT`:附带自定义二进制数据。
    BinaryEvent,
}
