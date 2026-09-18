//! 对应 Java `exchange.core2.core.common.UserProfile`。单用户账户状态快照,聚合该 uid 在各业务线上的
//! 资金与持仓:通用余额(accounts)、现货挂单冻结(exchange_locked)、期货持仓(positions,HEDGE 模式下
//! 用 ±symbol 区分多空)、借贷(isolated_loans 逐仓单笔 / cross_loans+cross_loan_collateral 全仓债务
//! 凭证+账户级抵押池)。
//! **该结构体整体进 raft snapshot 并参与 `state_hash`**:跨节点必须逐字段收敛。

use std::collections::BTreeMap;

use crate::core::common::time_window_dedup_set::TimeWindowDedupSet;

use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::cross_loan_record::CrossLoanRecord;
use crate::core::common::isolated_loan_record::IsolatedLoanRecord;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_status::UserStatus;
use crate::core::utils::core_arithmetic_utils as arithmetic;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserProfile {
    pub uid: i64,
    /// 对应 Java `userStatus`:ACTIVE/SUSPENDED;SUSPEND 命令 apply 前守卫要求 accounts/locks/loans 全空。
    pub user_status: UserStatus,
    /// 对应 Java `accounts`:currency -> 物理余额总额(未拆锁定),scale = currency_scale_k。
    pub accounts: BTreeMap<i32, i64>,
    /// 对应 Java `exchangeLocked`:currency -> 现货挂单冻结额,scale 与 accounts 一致。
    /// accounts 是"真实持有总额",exchange_locked 是其中"已被现货挂单冻结、不可立即支配"的部分;
    /// RiskEngine 计算 locked 时会用到,使 free + locked = accounts。
    pub exchange_locked: BTreeMap<i32, i64>,
    /// 对应 Java `processedTransactionIds`:外部触发命令(BALANCE_ADJUSTMENT/MARGIN_ADJUSTMENT/
    /// LOAN_* / INTERNAL_TRANSFER 等)共用的幂等表,按命令时间做时间窗去重。
    pub processed_tx_ids: TimeWindowDedupSet,
    /// 对应 Java `positionMode`:单向/双向持仓;双向时 positions key 用 ±symbol 区分多空。
    pub position_mode: PositionMode,
    /// 对应 Java `positions`:symbol -> margin position record;HEDGE 模式下正 symbol=多头、负 symbol=空头。
    pub positions: BTreeMap<i32, SymbolPositionRecord>,

    /// 对应 Java `isolatedLoans`:loan_id -> record(loan_id 客户端提供、per-user 唯一,与 Cross 命名空间独立);
    /// 抵押与单笔 loan 绑定,收集在 record.collateral_amount。
    pub isolated_loans: BTreeMap<i64, IsolatedLoanRecord>,
    /// 对应 Java `crossLoanCollateral`:Cross 借贷账户级多币种抵押池,currency -> amount,与单笔 debt 解耦。
    pub cross_loan_collateral: BTreeMap<i32, i64>,
    /// 对应 Java `crossLoans`:Cross 借贷单笔债务凭证,loan_id -> record;无抵押字段,抵押走 cross_loan_collateral。
    pub cross_loans: BTreeMap<i64, CrossLoanRecord>,
}

impl UserProfile {

    /// 对应 Java 构造器 `UserProfile(long uid, UserStatus userStatus)`。
    pub fn new(uid: i64, user_status: UserStatus) -> Self {
        UserProfile {
            uid,
            user_status,
            accounts: BTreeMap::new(),
            exchange_locked: BTreeMap::new(),
            processed_tx_ids: TimeWindowDedupSet::new(),
            position_mode: PositionMode::default(),
            positions: BTreeMap::new(),
            isolated_loans: BTreeMap::new(),
            cross_loan_collateral: BTreeMap::new(),
            cross_loans: BTreeMap::new(),
        }
    }

    /// 对应 Java `processPositionRecord`:用 consumer 处理指定 symbol 下的所有仓位记录
    /// (ONEWAY 模式只有 symbol 一条;HEDGE 模式再处理 -symbol 对侧仓位)。
    pub fn process_position_record<F>(&mut self, symbol: i32, mut consumer: F)
    where
        F: FnMut(&mut SymbolPositionRecord),
    {
        if let Some(long_record) = self.positions.get_mut(&symbol) {
            consumer(long_record);
        }
        if self.position_mode == PositionMode::Hedge {
            if let Some(short_record) = self.positions.get_mut(&-symbol) {
                consumer(short_record);
            }
        }
    }

    /// 对应 Java `processedTransactionIds.tryClaim` 的调用点。
    pub fn try_claim_tx(&mut self, tx_id: i64, now_ms: i64) -> bool {
        self.processed_tx_ids.try_claim(tx_id, now_ms)
    }

    pub fn add_to_account(&mut self, currency: i32, delta: i64) {
        *self.accounts.entry(currency).or_insert(0) += delta;
    }

    pub fn add_to_locked(&mut self, currency: i32, delta: i64) {
        *self.exchange_locked.entry(currency).or_insert(0) += delta;
    }

    pub fn add_to_cross_loan_collateral(&mut self, currency: i32, delta: i64) {
        *self.cross_loan_collateral.entry(currency).or_insert(0) += delta;
    }

    /// 对应 Java `createPositionsKey(int symbol, OrderAction orderAction, OrderCommandType command)`。
    /// ONEWAY 模式恒返回 symbol 本身;HEDGE 模式按下单方向取 ±symbol,平仓/强平命令要翻转到对侧仓位的 key。
    pub fn create_positions_key(&self, symbol: i32, action: OrderAction, command: OrderCommandType) -> i32 {
        if self.position_mode == PositionMode::Hedge {
            let key = if action == OrderAction::Bid { symbol } else { -symbol };
            if command == OrderCommandType::ClosePosition || command == OrderCommandType::ForceLiquidation {
                // 平仓/强平时翻转到对侧仓位。
                return -key;
            }
            key
        } else {
            symbol
        }
    }

    /// 对应 Java `createPositionsKey(SymbolPositionRecord position)`:已知仓位记录时,HEDGE 模式
    /// 直接用 `direction` 的符号乘子还原其 positions key。
    pub fn create_positions_key_of(&self, position: &SymbolPositionRecord) -> i32 {
        if self.position_mode == PositionMode::Hedge {
            position.direction.multiplier() * position.symbol
        } else {
            position.symbol
        }
    }

    /// 对应 Java `countPositionRecord`:统计指定 symbol 下满足 predicate 的仓位记录数量
    /// (ONEWAY 最多 1、HEDGE 最多 2,即 symbol 与 -symbol 两条)。
    pub fn count_position_record<F>(&self, symbol: i32, predicate: F) -> i32
    where
        F: Fn(&SymbolPositionRecord) -> bool,
    {
        let mut count = 0;
        if let Some(long_record) = self.positions.get(&symbol) {
            if predicate(long_record) {
                count += 1;
            }
        }
        if self.position_mode == PositionMode::Hedge {
            if let Some(short_record) = self.positions.get(&-symbol) {
                if predicate(short_record) {
                    count += 1;
                }
            }
        }
        count
    }

    /// 对应 Java `calculateCrossAvailable`。cross 可支配余额(currency scale)=
    /// `accounts − exchange_locked − Σ 同 currency 逐仓(ISOLATED)虚拟锁定 margin`。
    ///
    /// open_init_margin_sum 开仓时未从 accounts 物理扣除(只在仓位记录里虚拟锁定),要显式剥离;
    /// extra_margin 已在 MARGIN_ADJUSTMENT 时从 accounts 扣走,不重复减。逐仓 pending 单占用(含 pending fee)
    /// 由 `SymbolPositionRecord::calculate_required_margin_for_futures` 一并扣除。
    ///
    /// 三条路径共享同一口径:强平触发、账户报表、事件下发——避免客户端展示的 LP/marginRatio 跟真实强平点脱节。
    /// spec 缺失时该仓不计入扣项(宁可 equity 略高估,不 panic)。
    pub fn calculate_cross_available<'a, F>(
        &self,
        currency: i32,
        currency_spec: &CoreCurrencySpecification,
        symbol_spec_lookup: F,
    ) -> i64
    where
        F: Fn(i32) -> Option<&'a CoreSymbolSpecification>,
    {
        let mut cross_available = self.account(currency) - self.locked(currency);
        for iso in self.positions.values() {
            if iso.margin_mode != MarginMode::Isolated || iso.currency != currency {
                continue;
            }
            let iso_spec = match symbol_spec_lookup(iso.symbol) {
                Some(s) => s,
                None => continue,
            };
            cross_available -= arithmetic::size_price_to_currency_scale(
                iso.calculate_required_margin_for_futures(iso_spec),
                iso_spec.base_scale_k,
                iso_spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
        }
        cross_available
    }

    /// 对应 Java `crossMarginBaseAllocation`。一次算好整账户所有 CROSS 仓的破产价基础 `margin_base`
    /// (pos -> margin_base,size_price scale,与 `open_init_margin_sum` 同 scale),直接喂
    /// `SymbolPositionRecord::calculate_bankruptcy_price` 的 CROSS 回调;承接原 Java
    /// `LiquidationEngine.calculateCrossBpMarginBaseAllocation` 的账户级分摊逻辑。
    ///
    /// 按 currency 分组,组内账户级 marginBalance 按 MM 占比分给每个 CROSS 仓:
    /// `marginBalance = cross_available + Σ UPnL`;
    /// `allocated_i = marginBalance × mm_i / Σ MM`(按 MM 占比分账户余额);
    /// `margin_base_i = allocated_i − UPnL_i`(EP 基础换算,currency scale),再转回 size_price scale。
    ///
    /// **守恒不变式**:`Σ margin_base_i(currency scale) = cross_available`;单仓时 margin_base = cross_available。
    /// 边界:某 currency 组 `Σ MM = 0` 时该组不入 map(caller 取默认 0);spec/price 缺失的仓跳过、其余照分。
    pub fn cross_margin_base_allocation<'a, FS, FC, FM>(
        &self,
        symbol_spec_lookup: FS,
        currency_spec_lookup: FC,
        mark_price_lookup: FM,
    ) -> BTreeMap<i32, i64>
    where
        FS: Fn(i32) -> Option<&'a CoreSymbolSpecification>,
        FC: Fn(i32) -> Option<&'a CoreCurrencySpecification>,
        FM: Fn(i32) -> Option<i64>,
    {
        let mut margin_base_by_pos: BTreeMap<i32, i64> = BTreeMap::new();

        // 账户级 marginBalance 分摊在单一 currency 内闭合,先按 currency 分组。
        let mut cross_by_currency: BTreeMap<i32, Vec<i32>> = BTreeMap::new();
        for (&key, p) in self.positions.iter() {
            if p.margin_mode == MarginMode::Cross {
                cross_by_currency.entry(p.currency).or_default().push(key);
            }
        }

        for (&currency, keys) in cross_by_currency.iter() {
            let currency_spec = match currency_spec_lookup(currency) {
                Some(c) => c,
                None => continue,
            };

            let mut upnl_by_pos: BTreeMap<i32, i64> = BTreeMap::new();
            let mut mm_by_pos: BTreeMap<i32, i64> = BTreeMap::new();
            let mut total_upnl: i64 = 0;
            let mut total_mm: i64 = 0;

            for &key in keys {
                let p = &self.positions[&key];
                let p_spec = match symbol_spec_lookup(p.symbol) {
                    Some(s) => s,
                    None => continue,
                };
                let p_price = match mark_price_lookup(p.symbol) {
                    Some(price) => price,
                    None => continue,
                };
                let pnl = arithmetic::size_price_to_currency_scale(
                    p.estimate_pnl(p_price),
                    p_spec.base_scale_k,
                    p_spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                let mm = arithmetic::size_price_to_currency_scale(
                    p.calculate_maintenance_margin(p_spec, p_price),
                    p_spec.base_scale_k,
                    p_spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                upnl_by_pos.insert(key, pnl);
                mm_by_pos.insert(key, mm);
                total_upnl += pnl;
                total_mm += mm;
            }

            if total_mm == 0 {
                // 该 currency 组无仓位承担维持保证金,不入 map(对应 Java 的 `return` 跳过 forEachKeyValue)。
                continue;
            }

            let cross_available = self.calculate_cross_available(currency, currency_spec, &symbol_spec_lookup);
            let margin_balance =
                i64::try_from(cross_available as i128 + total_upnl as i128)
                    .unwrap_or_else(|_| panic!("overflow: crossAvailable + totalUpnl for currency {currency}"));

            for (&key, &mm) in mm_by_pos.iter() {
                let allocated = arithmetic::trunc_mul_div(margin_balance, mm, total_mm);
                let margin_base_currency = arithmetic::sub_exact(allocated, upnl_by_pos[&key]);
                // 累加阶段已用同一 key 校验过 spec 存在,分配阶段不会消失;expect 兜底纯防御性。
                let pos_spec = symbol_spec_lookup(self.positions[&key].symbol)
                    .expect("symbol spec disappeared between accumulation and allocation loops");
                margin_base_by_pos.insert(
                    key,
                    // currency scale -> size_price scale,供 SymbolPositionRecord::calculate_bankruptcy_price 使用。
                    arithmetic::currency_to_size_price_scale(
                        margin_base_currency,
                        pos_spec.base_scale_k,
                        pos_spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    ),
                );
            }
        }

        margin_base_by_pos
    }

    pub fn account(&self, currency: i32) -> i64 {
        *self.accounts.get(&currency).unwrap_or(&0)
    }

    pub fn locked(&self, currency: i32) -> i64 {
        *self.exchange_locked.get(&currency).unwrap_or(&0)
    }

    pub fn cross_loan_collateral(&self, currency: i32) -> i64 {
        *self.cross_loan_collateral.get(&currency).unwrap_or(&0)
    }

    /// 对应 Java `stateHash`:逐字段累积哈希;`processed_tx_ids`/`positions` 各条目/`isolated_loans`/
    /// `cross_loans` 均调各自的 state_hash 而非 derive(Hash),因为其内部容器布局(如 VecDeque 物理偏移)
    /// 不参与状态等价性判定,只看逻辑内容——保证快照恢复后布局不同但逻辑等价的状态在各节点产生相同 hash。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.uid);
        h = h.wrapping_mul(31).wrapping_add(self.user_status.code() as i64);
        h = self.processed_tx_ids.fold_hash(h);
        for (&cur, &amt) in &self.accounts {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.exchange_locked {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        h = h.wrapping_mul(31).wrapping_add(self.position_mode.code() as i64);
        for (&key, record) in &self.positions {
            h = h.wrapping_mul(31).wrapping_add(key as i64);
            h = h.wrapping_mul(31).wrapping_add(record.state_hash() as i64);
        }
        for (&loan_id, loan) in &self.isolated_loans {
            h = h.wrapping_mul(31).wrapping_add(loan_id);
            h = h.wrapping_mul(31).wrapping_add(loan.state_hash() as i64);
        }
        for (&cur, &amt) in &self.cross_loan_collateral {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&loan_id, loan) in &self.cross_loans {
            h = h.wrapping_mul(31).wrapping_add(loan_id);
            h = h.wrapping_mul(31).wrapping_add(loan.state_hash() as i64);
        }
        ((h >> 32) as i32) ^ (h as i32)
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

/// 对应 Java `writeMarshallable`/`UserProfile(BytesIn)` 构造器的字段读写顺序:通用(uid/status/
/// processed_tx_ids/accounts)→现货(exchange_locked)→期货(position_mode/positions)→借贷
/// (isolated_loans/cross_loan_collateral/cross_loans)。
impl ChronicleMarshallable for UserProfile {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i64(self.uid);
        w.write_u8(self.user_status.code() as u8);
        self.processed_tx_ids.chronicle_write(w);
        w.write_int_long_map(&self.accounts);
        w.write_int_long_map(&self.exchange_locked);
        w.write_u8(self.position_mode.code() as u8);
        w.write_int_keyed_map(
            &self.positions,
            |vw, v| v.chronicle_write(vw),
        );
        w.write_long_keyed_map(
            &self.isolated_loans,
            |vw, v| v.chronicle_write(vw),
        );
        w.write_int_long_map(&self.cross_loan_collateral);
        w.write_long_keyed_map(
            &self.cross_loans,
            |vw, v| v.chronicle_write(vw),
        );
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let uid = r.read_i64()?;
        let user_status = UserStatus::of_code(r.read_u8()? as i8);
        let processed_tx_ids = TimeWindowDedupSet::chronicle_read(r)?;
        let accounts = crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?);
        let exchange_locked = crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?);
        let position_mode = PositionMode::of_code(r.read_u8()? as i8);
        // 对应 Java `new SymbolPositionRecord(uid, bytesIn)`:子记录自身不落盘 uid,读回后从父 UserProfile 回填。
        let positions: BTreeMap<i32, SymbolPositionRecord> = r
            .read_int_keyed_map(SymbolPositionRecord::chronicle_read)?
            .into_iter()
            .map(|(k, mut spr)| {
                spr.uid = uid;
                (k, spr)
            })
            .collect();
        let isolated_loans: BTreeMap<i64, IsolatedLoanRecord> = r
            .read_long_keyed_map(IsolatedLoanRecord::chronicle_read)?
            .into_iter()
            .map(|(k, mut lr)| {
                lr.uid = uid;
                (k, lr)
            })
            .collect();
        let cross_loan_collateral = crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?);
        let cross_loans: BTreeMap<i64, CrossLoanRecord> = r
            .read_long_keyed_map(CrossLoanRecord::chronicle_read)?
            .into_iter()
            .map(|(k, mut lr)| {
                lr.uid = uid;
                (k, lr)
            })
            .collect();
        Ok(UserProfile {
            uid,
            user_status,
            accounts,
            exchange_locked,
            processed_tx_ids,
            position_mode,
            positions,
            isolated_loans,
            cross_loan_collateral,
            cross_loans,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn account_and_locked_default_to_zero() {
        let p = UserProfile::new(1, UserStatus::Active);
        assert_eq!(p.account(1), 0);
        assert_eq!(p.locked(1), 0);
    }

    #[test]
    fn add_to_account_accumulates_from_zero() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.add_to_account(1, 100);
        p.add_to_account(1, -30);
        assert_eq!(p.account(1), 70);
        assert_eq!(p.account(2), 0);
    }

    #[test]
    fn add_to_locked_accumulates_from_zero() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.add_to_locked(1, 50);
        p.add_to_locked(1, 25);
        assert_eq!(p.locked(1), 75);
    }

    #[test]
    fn state_hash_deterministic_for_same_state() {
        let mut a = UserProfile::new(7, UserStatus::Active);
        a.add_to_account(1, 100);
        a.add_to_locked(1, 10);
        let mut b = UserProfile::new(7, UserStatus::Active);
        b.add_to_account(1, 100);
        b.add_to_locked(1, 10);
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_with_different_state() {
        let base = UserProfile::new(7, UserStatus::Active);
        let h0 = base.state_hash();

        let diff_uid = UserProfile::new(8, UserStatus::Active);
        assert_ne!(h0, diff_uid.state_hash());

        let diff_status = UserProfile::new(7, UserStatus::Suspended);
        assert_ne!(h0, diff_status.state_hash());

        let mut diff_account = UserProfile::new(7, UserStatus::Active);
        diff_account.add_to_account(1, 1);
        assert_ne!(h0, diff_account.state_hash());

        let mut diff_locked = UserProfile::new(7, UserStatus::Active);
        diff_locked.add_to_locked(1, 1);
        assert_ne!(h0, diff_locked.state_hash());
    }

    #[test]
    fn new_defaults_to_oneway_with_empty_positions() {
        let p = UserProfile::new(1, UserStatus::Active);
        assert_eq!(p.position_mode, PositionMode::OneWay);
        assert!(p.positions.is_empty());
    }

    #[test]
    fn create_positions_key_oneway_is_always_raw_symbol() {
        let p = UserProfile::new(1, UserStatus::Active);
        assert_eq!(p.create_positions_key(100, OrderAction::Bid, OrderCommandType::PlaceOrder), 100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::PlaceOrder), 100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::ClosePosition), 100);
    }

    #[test]
    fn create_positions_key_hedge_place_order_uses_action_sign() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        assert_eq!(p.create_positions_key(100, OrderAction::Bid, OrderCommandType::PlaceOrder), 100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::PlaceOrder), -100);
    }

    #[test]
    fn create_positions_key_hedge_close_or_force_liquidation_flips_sign() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        assert_eq!(p.create_positions_key(100, OrderAction::Bid, OrderCommandType::ClosePosition), -100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::ClosePosition), 100);
        assert_eq!(p.create_positions_key(100, OrderAction::Bid, OrderCommandType::ForceLiquidation), -100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::ForceLiquidation), 100);
    }

    #[test]
    fn create_positions_key_of_record_oneway_is_raw_symbol() {
        let p = UserProfile::new(1, UserStatus::Active);
        let mut rec = SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1);
        rec.direction = crate::core::common::position_direction::PositionDirection::Short;
        assert_eq!(p.create_positions_key_of(&rec), 100);
    }

    #[test]
    fn create_positions_key_of_record_hedge_uses_direction_multiplier() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        let mut rec = SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1);

        rec.direction = crate::core::common::position_direction::PositionDirection::Long;
        assert_eq!(p.create_positions_key_of(&rec), 100);

        rec.direction = crate::core::common::position_direction::PositionDirection::Short;
        assert_eq!(p.create_positions_key_of(&rec), -100);

        rec.direction = crate::core::common::position_direction::PositionDirection::Empty;
        assert_eq!(p.create_positions_key_of(&rec), 0);
    }

    #[test]
    fn count_position_record_oneway_counts_only_raw_symbol_key() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.positions.insert(100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));
        assert_eq!(p.count_position_record(100, |_| true), 1);
        assert_eq!(p.count_position_record(200, |_| true), 0);
    }

    #[test]
    fn count_position_record_hedge_counts_both_legs_matching_predicate() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        p.positions.insert(100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 5));
        p.positions.insert(-100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 3));

        assert_eq!(p.count_position_record(100, |_| true), 2);
        assert_eq!(p.count_position_record(100, |r| r.leverage == 5), 1);
        assert_eq!(p.count_position_record(100, |r| r.leverage == 99), 0);
    }

    #[test]
    fn process_position_record_oneway_visits_only_raw_symbol() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.positions.insert(100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));

        let mut visited = 0;
        p.process_position_record(100, |r| {
            r.profit = 42;
            visited += 1;
        });
        assert_eq!(visited, 1);
        assert_eq!(p.positions.get(&100).unwrap().profit, 42);
    }

    #[test]
    fn process_position_record_hedge_visits_both_legs() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        p.positions.insert(100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));
        p.positions.insert(-100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));

        let mut visited = 0;
        p.process_position_record(100, |r| {
            r.profit = 7;
            visited += 1;
        });
        assert_eq!(visited, 2);
        assert_eq!(p.positions.get(&100).unwrap().profit, 7);
        assert_eq!(p.positions.get(&-100).unwrap().profit, 7);
    }

    #[test]
    fn state_hash_changes_with_position_mode_and_positions() {
        let base = UserProfile::new(7, UserStatus::Active);
        let h0 = base.state_hash();

        let mut diff_mode = UserProfile::new(7, UserStatus::Active);
        diff_mode.position_mode = PositionMode::Hedge;
        assert_ne!(h0, diff_mode.state_hash());

        let mut diff_positions = UserProfile::new(7, UserStatus::Active);
        diff_positions
            .positions
            .insert(100, SymbolPositionRecord::new(7, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));
        assert_ne!(h0, diff_positions.state_hash());
    }

    fn currency_spec_scale1(currency: i32) -> CoreCurrencySpecification {
        CoreCurrencySpecification { currency, currency_scale_k: 1, ..Default::default() }
    }

    fn symbol_spec_scale1(symbol_id: i32, base_currency: i32, quote_currency: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            base_currency,
            quote_currency,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn open_position(
        uid: i64,
        symbol: i32,
        currency: i32,
        margin_mode: MarginMode,
        direction: crate::core::common::position_direction::PositionDirection,
        open_volume: i64,
        open_price_sum: i64,
        open_init_margin_sum: i64,
    ) -> SymbolPositionRecord {
        let mut p = SymbolPositionRecord::new(uid, symbol, currency, margin_mode, 1);
        p.direction = direction;
        p.open_volume = open_volume;
        p.open_price_sum = open_price_sum;
        p.open_init_margin_sum = open_init_margin_sum;
        p
    }

    #[test]
    fn calculate_cross_available_subtracts_isolated_margin_in_same_currency() {
        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        up.add_to_locked(2, 5_000);
        let iso = open_position(
            1,
            100,
            2,
            MarginMode::Isolated,
            crate::core::common::position_direction::PositionDirection::Long,
            10,
            10_000,
            10_000,
        );
        up.positions.insert(100, iso);

        let spec = symbol_spec_scale1(100, 1, 2);
        let currency_spec = currency_spec_scale1(2);
        let available = up.calculate_cross_available(2, &currency_spec, |s| if s == 100 { Some(&spec) } else { None });

        assert_eq!(available, 100_000 - 5_000 - 10_000);
    }

    #[test]
    fn calculate_cross_available_does_not_subtract_cross_position_margin() {
        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let cross = open_position(
            1,
            100,
            2,
            MarginMode::Cross,
            crate::core::common::position_direction::PositionDirection::Long,
            10,
            10_000,
            10_000,
        );
        up.positions.insert(100, cross);

        let spec = symbol_spec_scale1(100, 1, 2);
        let currency_spec = currency_spec_scale1(2);
        let available = up.calculate_cross_available(2, &currency_spec, |s| if s == 100 { Some(&spec) } else { None });

        assert_eq!(available, 100_000);
    }

    #[test]
    fn calculate_cross_available_ignores_isolated_position_in_other_currency() {
        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let iso_other_cur = open_position(
            1,
            100,
            3,
            MarginMode::Isolated,
            crate::core::common::position_direction::PositionDirection::Long,
            10,
            10_000,
            10_000,
        );
        up.positions.insert(100, iso_other_cur);

        let spec = symbol_spec_scale1(100, 1, 3);
        let currency_spec = currency_spec_scale1(2);
        let available = up.calculate_cross_available(2, &currency_spec, |s| if s == 100 { Some(&spec) } else { None });

        assert_eq!(available, 100_000);
    }

    #[test]
    fn calculate_cross_available_skips_isolated_position_with_missing_spec() {
        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let iso = open_position(
            1,
            100,
            2,
            MarginMode::Isolated,
            crate::core::common::position_direction::PositionDirection::Long,
            10,
            10_000,
            10_000,
        );
        up.positions.insert(100, iso);

        let currency_spec = currency_spec_scale1(2);
        let available =
            up.calculate_cross_available(2, &currency_spec, |_s: i32| -> Option<&CoreSymbolSpecification> { None });

        assert_eq!(available, 100_000);
    }

    #[test]
    fn cross_margin_base_allocation_invariant_sum_equals_cross_available_with_even_mm_split() {
        use crate::core::common::position_direction::PositionDirection;

        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);

        let p1 = open_position(1, 100, 2, MarginMode::Cross, PositionDirection::Long, 10, 900, 0);
        let p2 = open_position(1, 200, 2, MarginMode::Cross, PositionDirection::Short, 20, 1100, 0);
        up.positions.insert(100, p1);
        up.positions.insert(200, p2);

        let spec1 = symbol_spec_scale1(100, 1, 2);
        let spec2 = symbol_spec_scale1(200, 1, 2);
        let currency_spec = currency_spec_scale1(2);

        let symbol_spec_lookup = |s: i32| -> Option<&CoreSymbolSpecification> {
            if s == 100 {
                Some(&spec1)
            } else if s == 200 {
                Some(&spec2)
            } else {
                None
            }
        };
        let currency_spec_lookup = |c: i32| -> Option<&CoreCurrencySpecification> {
            if c == 2 {
                Some(&currency_spec)
            } else {
                None
            }
        };
        let mark_price_lookup = |s: i32| -> Option<i64> {
            match s {
                100 => Some(100),
                200 => Some(50),
                _ => None,
            }
        };

        let allocation = up.cross_margin_base_allocation(symbol_spec_lookup, currency_spec_lookup, mark_price_lookup);

        assert_eq!(*allocation.get(&100).unwrap(), 50_000);
        assert_eq!(*allocation.get(&200).unwrap(), 50_000);

        let cross_available = up.calculate_cross_available(2, &currency_spec, symbol_spec_lookup);
        let sum: i64 = allocation.values().sum();
        assert_eq!(sum, cross_available);
        assert_eq!(cross_available, 100_000);
    }

    #[test]
    fn cross_margin_base_allocation_splits_proportionally_to_maintenance_margin() {
        use crate::core::common::position_direction::PositionDirection;

        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);

        let p1 = open_position(1, 100, 2, MarginMode::Cross, PositionDirection::Long, 10, 1_000, 0);
        let p2 = open_position(1, 200, 2, MarginMode::Cross, PositionDirection::Long, 20, 2_000, 0);
        up.positions.insert(100, p1);
        up.positions.insert(200, p2);

        let spec1 = symbol_spec_scale1(100, 1, 2);
        let spec2 = symbol_spec_scale1(200, 1, 2);
        let currency_spec = currency_spec_scale1(2);

        let symbol_spec_lookup = |s: i32| -> Option<&CoreSymbolSpecification> {
            if s == 100 {
                Some(&spec1)
            } else if s == 200 {
                Some(&spec2)
            } else {
                None
            }
        };
        let currency_spec_lookup = |c: i32| -> Option<&CoreCurrencySpecification> {
            if c == 2 {
                Some(&currency_spec)
            } else {
                None
            }
        };
        let mark_price_lookup = |_s: i32| -> Option<i64> { Some(100) };

        let allocation = up.cross_margin_base_allocation(symbol_spec_lookup, currency_spec_lookup, mark_price_lookup);

        assert_eq!(*allocation.get(&100).unwrap(), 33_333);
        assert_eq!(*allocation.get(&200).unwrap(), 66_666);

        let cross_available = up.calculate_cross_available(2, &currency_spec, symbol_spec_lookup);
        let sum: i64 = allocation.values().sum();
        assert_eq!(cross_available - sum, 1);
    }

    #[test]
    fn cross_margin_base_allocation_omits_currency_group_with_zero_total_mm() {
        use crate::core::common::position_direction::PositionDirection;

        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let empty_cross = open_position(1, 100, 2, MarginMode::Cross, PositionDirection::Empty, 0, 0, 0);
        up.positions.insert(100, empty_cross);

        let spec = symbol_spec_scale1(100, 1, 2);
        let currency_spec = currency_spec_scale1(2);
        let allocation = up.cross_margin_base_allocation(
            |s| if s == 100 { Some(&spec) } else { None },
            |c| if c == 2 { Some(&currency_spec) } else { None },
            |_s| Some(100),
        );

        assert!(allocation.is_empty());
    }

    #[test]
    fn cross_margin_base_allocation_skips_position_with_missing_mark_price_but_keeps_others() {
        use crate::core::common::position_direction::PositionDirection;

        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let p1 = open_position(1, 100, 2, MarginMode::Cross, PositionDirection::Long, 10, 1_000, 0);
        let p2 = open_position(1, 200, 2, MarginMode::Cross, PositionDirection::Long, 10, 1_000, 0);
        up.positions.insert(100, p1);
        up.positions.insert(200, p2);

        let spec1 = symbol_spec_scale1(100, 1, 2);
        let spec2 = symbol_spec_scale1(200, 1, 2);
        let currency_spec = currency_spec_scale1(2);

        let allocation = up.cross_margin_base_allocation(
            |s| if s == 100 {
                Some(&spec1)
            } else if s == 200 {
                Some(&spec2)
            } else {
                None
            },
            |c| if c == 2 { Some(&currency_spec) } else { None },
            |s| if s == 100 { Some(100) } else { None },
        );

        assert!(allocation.contains_key(&100));
        assert!(!allocation.contains_key(&200));
        assert_eq!(*allocation.get(&100).unwrap(), 100_000);
    }
}
