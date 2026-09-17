//! 对应 Java `LiquidationService`（`exchange.core2.core.processors.liquidation
//! .LiquidationService`）。强平流程的核心 service，每个分片一个实例，承担三类职责：
//!
//! - **IF 保险基金池状态**：`notionals`（available/reserved）+ IF 接管的 `positions`；
//!   经 [`crate::core::snapshot::marshalling::ChronicleMarshallable`] 序列化进 raft
//!   snapshot，跨节点强一致，并计入 `state_hash`。
//! - **强平命令 orderId 编码**：FORCE 是根，IF/ADL 从它派生（`generate_if_order_id`/
//!   `generate_adl_order_id`），位布局见 [`LiquidationService::generate_liquidation_order_id`]
//!   前的说明。
//! - **ADL 候选构造**：[`LiquidationService::compute_profitable_positions_by_symbol`]
//!   在 apply 时按需从复制态（传入的 `UserProfileService`/`SymbolSpecificationProvider`/
//!   last-price cache）现算，保证跨节点确定；评分见 `risk_score`/`unrealized_pnl`。
//!
//! `notionals`/`positions` 是序列化状态（进 snapshot）；Java 版另持有
//! `userProfileService`/`symbolSpecificationProvider`/`currencySpecificationProvider`/
//! `lastPriceCache` 等由 `updateProvider` 注入的依赖字段（不进 snapshot）。Rust 版把这些
//! 依赖改为按调用传参（见 `compute_profitable_positions_by_symbol` 的函数签名），未在
//! `LiquidationService` 结构体上保留对应字段。
//!
//! 用 [`BTreeMap`] 而非哈希表存放 `notionals`/`positions`：迭代顺序按 key 有序、
//! 跨节点确定，这是 `state_hash`/Chronicle 序列化字节级一致的前提（Java 侧用
//! `IntObjectHashMap` + `HashingUtils.stateHash`/`SerializationUtils.marshallIntHashMap`
//! 达到同样的跨节点一致目的）。
//!
//! Java 版另有 `isLiquidationOrderId` 静态方法（按 orderId 反解 symbol/uid 校验），本文件
//! 未见对应实现。

use std::collections::BTreeMap;

use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils::{mul_exact, size_price_to_currency_scale};

/// 对应 Java 内部类 `IFNotional`。IF（保险基金）单 symbol 名义资金：`available` 可动用，
/// `reserved` 是强平流程 R1 阶段的预冻结部分（R2 要么转为 `accept_if_position` 扣款，
/// 要么被 `release_reserved_if_notional` 释放）。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct IfNotional {
    pub available: i64,
    pub reserved: i64,
}

impl IfNotional {
    fn fold_hash(&self, h: i64) -> i64 {
        let h = h.wrapping_mul(31).wrapping_add(self.available);
        h.wrapping_mul(31).wrapping_add(self.reserved)
    }
}

/// 对应 Java 内部类 `IFPositionRecord`。IF 接管仓位：某 symbol+方向累计接管的持仓量
/// 与开仓成本（反向出清估值用）。key 编码见 `LiquidationService::positions`
/// 上的说明（`direction.multiplier() * symbol`，正负号区分多空，避免同 symbol
/// 多空两条记录互相覆盖）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IfPositionRecord {
    pub symbol: i32,
    pub direction: PositionDirection,
    pub open_volume: i64,
    pub open_price_sum: i64,
}

impl IfPositionRecord {
    /// 按当前 mark price 估算该 IF 持仓的名义市值（开仓成本 + 浮动盈亏），供报表侧
    /// 展示 IF 自身持仓的 mark-to-market 价值；本包 Java 版无直接对应方法。
    pub fn position_value(&self, mark: i64) -> i64 {
        let unrealized: i128 = self.direction.multiplier() as i128
            * (self.open_volume as i128 * mark as i128 - self.open_price_sum as i128);
        i64::try_from(self.open_price_sum as i128 + unrealized)
            .unwrap_or_else(|_| panic!("overflow: IF position_value open_volume={} mark={mark}", self.open_volume))
    }

    fn fold_hash(&self, h: i64) -> i64 {
        let h = h.wrapping_mul(31).wrapping_add(self.symbol as i64);
        let h = h.wrapping_mul(31).wrapping_add(self.direction.multiplier() as i64);
        let h = h.wrapping_mul(31).wrapping_add(self.open_volume);
        h.wrapping_mul(31).wrapping_add(self.open_price_sum)
    }
}

/// 对应 Java `LiquidationService` 的序列化状态部分（`notionals`/`positions` 字段）。
/// `notionals`：symbol -> IF 名义资金（available/reserved）。`positions`：
/// key = `direction.multiplier() * symbol`（+symbol 多头仓，-symbol 空头仓），
/// value 为该 symbol+方向下 IF 累计接管的仓位。
#[derive(Debug, Clone, Default)]
pub struct LiquidationService {
    pub notionals: BTreeMap<i32, IfNotional>,
    pub positions: BTreeMap<i64, IfPositionRecord>,
}

impl LiquidationService {
    pub fn new() -> Self {
        LiquidationService::default()
    }

    /// 对应 Java `reset`：清空全部 IF 状态（测试/重建用）。
    pub fn reset(&mut self) {
        self.notionals.clear();
        self.positions.clear();
    }

    /// 对应 Java `creditLiquidationFee`：强平手续费计入 IF 可用资金池。
    pub fn credit_liquidation_fee(&mut self, symbol: i32, notional_fee: i64) {
        let n = self.notionals.entry(symbol).or_default();
        n.available += notional_fee;
    }

    /// 对应 Java `depositToInsuranceFund`：外部充值 IF 可用资金池（admin 触发）。
    /// 入参已是 notional（size*price）尺度，scale 换算由调用方（risk engine）完成；
    /// 对账闭环依赖调用方在同一条命令内对 adjustments 做反向记账。
    pub fn deposit_to_insurance_fund(&mut self, symbol: i32, notional_amount: i64) {
        let n = self.notionals.entry(symbol).or_default();
        n.available += notional_amount;
    }

    /// 对应 Java `withdrawFromInsuranceFund`：IF_WITHDRAW 支持，从 available 扣款，
    /// 含非负校验。只扣 available、不动 reserved——reserved 是正在保护某笔强平的
    /// 预冻结部分，运营提现不能拿走。返回 false 表示 notional 不存在或 available
    /// 不足以覆盖（不改状态）。
    pub fn withdraw_from_insurance_fund(&mut self, symbol: i32, notional_amount: i64) -> bool {
        let Some(n) = self.notionals.get_mut(&symbol) else {
            return false;
        };
        if n.available < notional_amount {
            return false;
        }
        n.available -= notional_amount;
        true
    }

    /// 对应 Java `reserveIFNotional`（R1）：预冻结 IF 可用名义金额，返回实际能冻结
    /// 的量。`can_cover` 钳制在当前真实可用（`available - reserved`）之内，绝不
    /// 超额承诺，保证 `available >= reserved` 恒成立。
    pub fn reserve_if_notional(&mut self, symbol: i32, request_size: i64, price: i64) -> i64 {
        let n = self.notionals.entry(symbol).or_default();
        let available = n.available - n.reserved;
        let needed = mul_exact(request_size, price);
        let can_cover = available.min(needed);
        n.reserved += can_cover;
        can_cover
    }

    /// 对应 Java `releaseReservedIFNotional`（R2）：释放 R1 预冻结的名义金额。
    pub fn release_reserved_if_notional(&mut self, symbol: i32, reserved_notional: i64) {
        if let Some(n) = self.notionals.get_mut(&symbol) {
            n.reserved -= reserved_notional;
        }
    }

    /// 对应 Java `acceptIFPosition`（R2）：IF 正式接管仓位——从 available 扣款，
    /// 累加到该 symbol+方向的持仓量与成本。要求该 symbol 此前已 reserve 过
    /// （否则 panic，见下方 `unwrap_or_else`），与 Java 版隐式假设 `notionals.get`
    /// 非空一致。
    pub fn accept_if_position(&mut self, symbol: i32, direction: PositionDirection, size: i64, price: i64) {
        let spend = mul_exact(size, price);
        let n = self
            .notionals
            .get_mut(&symbol)
            .unwrap_or_else(|| panic!("accept_if_position: no IFNotional reserved for symbol {symbol}"));
        n.available -= spend;

        let key = (direction.multiplier() as i64) * (symbol as i64);
        let pos = self.positions.entry(key).or_insert_with(|| IfPositionRecord {
            symbol,
            direction,
            open_volume: 0,
            open_price_sum: 0,
        });
        pos.open_volume += size;
        pos.open_price_sum += spend;
    }

    /// 对应 Java `computeProfitablePositionsBySymbol`：ADL 候选构造，apply 时按需算出
    /// 全部可被 ADL 摊派的仓位（symbol -> list）。
    ///
    /// 按需算而非用缓存的原因（与 Java javadoc 一致）：ADL apply 必须在所有节点算出
    /// 相同候选，否则结果发散。若用 leader-only 维护的缓存，follower 侧为空，同一条
    /// ADL 命令在 leader/follower 上会得到不同候选。这里改为每次从传入的复制态
    /// （`ups`/`ssp`/`last_price_cache`，均来自调用方持有的、经 raft 复制的状态）
    /// 现算，保证跨节点确定——Rust 版进一步把这些依赖做成显式参数而非注入到
    /// `LiquidationService` 自身的字段，消除了「忘记 updateProvider」这一类可能导致
    /// 跨节点不一致的隐藏状态。
    pub fn compute_profitable_positions_by_symbol(
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> BTreeMap<i32, Vec<SymbolPositionRecord>> {
        let mut result: BTreeMap<i32, Vec<SymbolPositionRecord>> = BTreeMap::new();

        let uids: Vec<i64> = ups.users.keys().copied().collect();
        for uid in uids {
            let profile = match ups.users.get_mut(&uid) {
                Some(p) => p,
                None => continue,
            };
            let position_keys: Vec<i32> = profile.positions.keys().copied().collect();

            let mut cross_by_currency: BTreeMap<i32, Vec<i32>> = BTreeMap::new();
            for key in &position_keys {
                let position = &profile.positions[key];
                if position.open_volume == 0 {
                    continue;
                }
                let spec = match ssp.get_symbol(position.symbol) {
                    Some(s) => s,
                    None => continue,
                };
                if !spec.symbol_type.is_futures_contract() {
                    continue;
                }
                let mark_price = match last_price_cache.get(&position.symbol) {
                    Some(r) => r.mark_price,
                    None => continue,
                };

                if position.margin_mode == MarginMode::Isolated {
                    // ISOLATED：单仓位独立结算，没有用户级 gating——浮盈 > 0 直接入选
                    if position.estimate_unrealized_profit(mark_price) > 0 {
                        result.entry(position.symbol).or_default().push(position.clone());
                    }
                } else {
                    cross_by_currency.entry(spec.quote_currency).or_default().push(*key);
                }
            }

            // CROSS：每个 currency 走一遍聚合 + 用户级 gating + factor + 入选
            for (currency, keys) in cross_by_currency {
                Self::add_cross_positions_if_user_safe(profile, currency, &keys, ssp, last_price_cache, &mut result);
            }
        }

        result
    }

    /// 对应 Java `generateLiquidationOrderId`：强平命令 orderId 编码，FORCE 是根，
    /// IF/ADL 从它派生（保留低 56 位以便审计回溯）。FORCE_LIQUIDATION 64 位布局
    /// （与 Java javadoc 一致）：
    ///
    /// ```text
    /// bit 63..32 : symbol    (高 32 位)
    /// bit 31..12 : uid hash  (20 bit)
    /// bit 11     : side bit  (LONG=0 / SHORT=1；HEDGE 下同 symbol 同 uid 同秒两侧防撞)
    /// bit 10..0  : 秒级 ts   (11 bit ≈ 2048s ≈ 34min 内 (symbol,uid,side) 唯一)
    /// ```
    ///
    /// 时间戳必须来自命令层确定性时间（leader 盖章、各节点同值），严禁本地墙钟——
    /// 否则各节点算出的 orderId 会不一致。
    pub fn generate_liquidation_order_id(uid: i64, symbol: i32, direction: PositionDirection, timestamp: i64) -> i64 {
        let uid_hash = (uid.wrapping_mul(31).wrapping_add(17)) & 0xFFFFF;
        let side_bit: i64 = if direction == PositionDirection::Short { 1 } else { 0 };
        let ts_part = (timestamp / 1000) & 0x7FF;
        ((symbol as i64) << 32) | (uid_hash << 12) | (side_bit << 11) | ts_part
    }

    /// 对应 Java `generateIFOrderId`：IF_TAKEOVER 的 orderId，与 `generate_adl_order_id`
    /// 完全对称——高 8 位是标签（`'I'` = 0x49），低 56 位 ≡ 对应 FORCE 的低 56 位。
    /// 注意：派生会丢弃 FORCE 高 8 位的 symbol；symbol < 2^24（≈16M）时无损，实际
    /// 场景成立。
    pub fn generate_if_order_id(liquidation_order_id: i64) -> i64 {
        let if_order_tag: i64 = 0x49;
        (if_order_tag << 56) | (liquidation_order_id & 0x00FF_FFFF_FFFF_FFFF)
    }

    /// 对应 Java `generateADLOrderId`：AUTO_DELEVERAGING 的 orderId，标签为 `'A'` = 0x41，
    /// 其余布局与 `generate_if_order_id` 对称。
    pub fn generate_adl_order_id(liquidation_order_id: i64) -> i64 {
        let adl_order_tag: i64 = 0x41;
        (adl_order_tag << 56) | (liquidation_order_id & 0x00FF_FFFF_FFFF_FFFF)
    }

    /// 对应 Java `stateHash`（`Objects.hash(HashingUtils.stateHash(notionals),
    /// HashingUtils.stateHash(positions))`）：Rust 版改为对 `notionals`/`positions`
    /// 按 `BTreeMap` 的 key 升序逐项做 31 进制多项式折叠，两者语义相同——都要求
    /// 跨节点对同一状态算出同一个 hash，用于换届/快照后校验各节点状态一致。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        for (&symbol, n) in &self.notionals {
            h = h.wrapping_mul(31).wrapping_add(symbol as i64);
            h = n.fold_hash(h);
        }
        for (&key, p) in &self.positions {
            h = h.wrapping_mul(31).wrapping_add(key);
            h = p.fold_hash(h);
        }
        ((h >> 32) as i32) ^ (h as i32)
    }

    /// 对应 Java `unrealizedPnl`：按破产价估算浮动盈亏（ADL 排序用，静态纯函数）。
    /// 饱和乘法防止溢出翻转符号，见 `saturating_multiply`。
    pub fn unrealized_pnl(pos: &SymbolPositionRecord, bankruptcy_price: i64) -> i64 {
        let sign = pos.direction.multiplier() as i64;
        let notional = saturating_multiply(bankruptcy_price, pos.open_volume);
        saturating_multiply(sign, notional - pos.open_price_sum)
    }

    /// 对应 Java `riskScore`：ADL 排序键 = 浮盈 × 实际杠杆 × 资格因子，越大越优先
    /// 被摊派。全程饱和乘法。
    pub fn risk_score(pos: &SymbolPositionRecord, bankruptcy_price: i64) -> i64 {
        let sign = pos.direction.multiplier() as i64;
        let notional = saturating_multiply(bankruptcy_price, pos.open_volume);
        let unrealized_pnl = saturating_multiply(sign, notional - pos.open_price_sum);
        let actual_leverage = pos.open_price_sum / pos.open_init_margin_sum;
        saturating_multiply(saturating_multiply(actual_leverage, unrealized_pnl), pos.adl_eligibility)
    }

    /// 对应 Java `addCrossPositionsIfUserSafe`：Cross 用户单 currency 的 ADL 候选
    /// 构造——聚合 + 用户级 gating + factor + 入选一次性完成。
    ///
    /// Gating（账户必须足够安全且净盈利才有资格被 ADL 吃）：
    /// - `total_profit > 0`（账户在本 currency 净盈利）
    /// - `equity >= 1.2 × total_maintenance`（离强平线还有 20%+ 余量）
    ///
    /// factor 语义：账户离强平线越远 factor 越大，ADL 排序时越优先被吃；clamp 到
    /// [0, 100]。
    fn add_cross_positions_if_user_safe(
        profile: &mut UserProfile,
        currency: i32,
        keys: &[i32],
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        result: &mut BTreeMap<i32, Vec<SymbolPositionRecord>>,
    ) {
        let currency_spec = match ssp.get_currency(currency) {
            Some(c) => c.clone(),
            None => return,
        };

        let mut total_profit: i64 = 0;
        let mut total_maintenance: i64 = 0;
        for &key in keys {
            let position = &profile.positions[&key];
            let spec = match ssp.get_symbol(position.symbol) {
                Some(s) => s,
                None => continue,
            };
            let mark_price = match last_price_cache.get(&position.symbol) {
                Some(r) => r.mark_price,
                None => continue,
            };
            let maintenance = position.calculate_maintenance_margin(spec, mark_price);
            if maintenance == 0 {
                continue;
            }
            let pnl = position.estimate_pnl(mark_price);
            total_profit += size_price_to_currency_scale(pnl, spec.base_scale_k, spec.quote_scale_k, currency_spec.currency_scale_k);
            total_maintenance +=
                size_price_to_currency_scale(maintenance, spec.base_scale_k, spec.quote_scale_k, currency_spec.currency_scale_k);
        }

        if total_maintenance <= 0 || total_profit <= 0 {
            return;
        }

        let equity = profile.account(currency) - profile.locked(currency) + total_profit;
        let warning_threshold = mul_exact(total_maintenance, 6) / 5;
        if equity < warning_threshold {
            return;
        }

        let factor = (mul_exact(equity - total_maintenance, 100) / total_maintenance).clamp(0, 100);

        for &key in keys {
            let symbol = profile.positions[&key].symbol;
            let mark_price = match last_price_cache.get(&symbol) {
                Some(r) => r.mark_price,
                None => continue,
            };
            if profile.positions[&key].estimate_unrealized_profit(mark_price) <= 0 {
                continue;
            }
            profile.positions.get_mut(&key).unwrap().adl_eligibility = factor;
            let snapshot = profile.positions[&key].clone();
            result.entry(symbol).or_default().push(snapshot);
        }
    }
}

/// 对应 Java `saturatingMultiply`：饱和乘法，溢出时钳到 `i64::MAX`/`i64::MIN`
/// （按结果符号）。WHY：ADL 排序键若用普通乘法，溢出截断会翻转符号导致排序反转，
/// 饱和后仍保持单调（见下方 `risk_score` 相关测试）。
fn saturating_multiply(a: i64, b: i64) -> i64 {
    match i64::try_from(a as i128 * b as i128) {
        Ok(v) => v,
        Err(_) => {
            if (a ^ b) < 0 {
                i64::MIN
            } else {
                i64::MAX
            }
        }
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for IfNotional {
    /// 字段写入顺序：`available`、`reserved`。与 Java `IFNotional.writeMarshallable`
    /// （`bytes.writeLong(available); bytes.writeLong(reserved);`）顺序一致。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i64(self.available);
        w.write_i64(self.reserved);
    }
    /// 镜像 `chronicle_write`，读取顺序与 Java `IFNotional(BytesIn)` 构造器一致：
    /// `available`、`reserved`。
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(IfNotional { available: r.read_i64()?, reserved: r.read_i64()? })
    }
}

impl ChronicleMarshallable for IfPositionRecord {
    /// 字段写入顺序：`symbol`、`direction`（编码为 1 字节）、`open_volume`、
    /// `open_price_sum`。与 Java `IFPositionRecord.writeMarshallable`
    /// （`writeInt(symbol); writeByte((byte)direction.getMultiplier()); writeLong(openVolume);
    /// writeLong(openPriceSum);`）顺序一致；`direction.code()` 与 Java 的
    /// `direction.getMultiplier()` 数值相同（见 `position_direction.rs` 的
    /// `multiplier_and_code_match_java` 测试），因此字节内容也一致。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.symbol);
        w.write_u8(self.direction.code() as u8);
        w.write_i64(self.open_volume);
        w.write_i64(self.open_price_sum);
    }
    /// 镜像 `chronicle_write`，读取顺序与 Java `IFPositionRecord(BytesIn)` 构造器一致：
    /// `symbol`、`direction`、`open_volume`、`open_price_sum`。
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(IfPositionRecord {
            symbol: r.read_i32()?,
            direction: PositionDirection::of_code(r.read_u8()? as i8),
            open_volume: r.read_i64()?,
            open_price_sum: r.read_i64()?,
        })
    }
}

impl ChronicleMarshallable for LiquidationService {
    /// 字段写入顺序：`notionals`（int-keyed map）、`positions`（int-keyed map，key
    /// 从 `i64` 截断为 `i32` 写出）。与 Java `LiquidationService.writeMarshallable`
    /// （`marshallIntHashMap(notionals, bytes); marshallIntHashMap(positions, bytes);`）
    /// 顺序一致。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_int_keyed_map(
            &self.notionals,
            |vw, v| v.chronicle_write(vw),
        );
        let positions_i32: BTreeMap<i32, &IfPositionRecord> = self.positions.iter().map(|(&k, v)| (k as i32, v)).collect();
        w.write_int_keyed_map(&positions_i32, |vw, v| v.chronicle_write(vw));
    }
    /// 镜像 `chronicle_write`，读取顺序与 Java `LiquidationService(BytesIn)` 构造器
    /// 一致：`notionals`、`positions`（positions 的 key 读回后从 `i32` 还原为
    /// `i64`，与 `positions: BTreeMap<i64, IfPositionRecord>` 的存储类型对齐）。
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let notionals = crate::core::snapshot::marshalling::to_btree_i32(r.read_int_keyed_map(IfNotional::chronicle_read)?);
        let positions_i32 = r.read_int_keyed_map(IfPositionRecord::chronicle_read)?;
        let positions = positions_i32.into_iter().map(|(k, v)| (k as i64, v)).collect();
        Ok(LiquidationService { notionals, positions })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_liquidation_order_id_encodes_symbol_uid_side_ts() {
        let long_id = LiquidationService::generate_liquidation_order_id(1, 200, PositionDirection::Long, 5_000);
        let short_id = LiquidationService::generate_liquidation_order_id(1, 200, PositionDirection::Short, 5_000);
        assert_eq!((long_id >> 32) as i32, 200);
        assert_eq!((long_id >> 11) & 1, 0);
        assert_eq!((short_id >> 11) & 1, 1);
        assert_eq!(long_id & 0x7FF, 5);
        assert_eq!((long_id >> 12) & 0xFFFFF, (1i64 * 31 + 17) & 0xFFFFF);
    }

    #[test]
    fn generate_liquidation_order_id_distinct_uids_no_collision_same_scan() {
        let a = LiquidationService::generate_liquidation_order_id(1, 200, PositionDirection::Long, 5_000);
        let b = LiquidationService::generate_liquidation_order_id(2, 200, PositionDirection::Long, 5_000);
        assert_ne!(a, b);
    }

    #[test]
    fn generate_if_and_adl_order_id_tag_high_byte_and_preserve_low_bits() {
        let root = LiquidationService::generate_liquidation_order_id(1, 200, PositionDirection::Long, 5_000);
        let if_id = LiquidationService::generate_if_order_id(root);
        let adl_id = LiquidationService::generate_adl_order_id(root);
        assert_eq!((if_id >> 56) & 0xFF, 0x49, "'I' tag");
        assert_eq!((adl_id >> 56) & 0xFF, 0x41, "'A' tag");
        assert_eq!(if_id & 0x00FF_FFFF_FFFF_FFFF, root & 0x00FF_FFFF_FFFF_FFFF);
        assert_eq!(adl_id & 0x00FF_FFFF_FFFF_FFFF, root & 0x00FF_FFFF_FFFF_FFFF);
        assert_ne!(if_id, adl_id, "different IF/ADL tags -> different orderId");
    }

    #[test]
    fn credit_liquidation_fee_accumulates_into_available_not_reserved() {
        let mut s = LiquidationService::new();
        s.credit_liquidation_fee(1, 100);
        s.credit_liquidation_fee(1, 50);
        assert_eq!(s.notionals[&1], IfNotional { available: 150, reserved: 0 });
    }

    #[test]
    fn deposit_to_insurance_fund_accumulates_into_available() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        s.deposit_to_insurance_fund(1, 500);
        assert_eq!(s.notionals[&1].available, 1_500);
    }

    #[test]
    fn withdraw_from_insurance_fund_debits_available_only() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        s.reserve_if_notional(1, 10, 10);
        assert!(s.withdraw_from_insurance_fund(1, 300));
        assert_eq!(s.notionals[&1], IfNotional { available: 700, reserved: 100 }, "only available is debited, reserved is untouched");
    }

    #[test]
    fn withdraw_from_insurance_fund_rejects_when_missing_or_insufficient() {
        let mut s = LiquidationService::new();
        assert!(!s.withdraw_from_insurance_fund(1, 1), "symbol never deposited -> false");

        s.deposit_to_insurance_fund(2, 100);
        assert!(!s.withdraw_from_insurance_fund(2, 101), "insufficient available -> false, cannot overdraw");
        assert_eq!(s.notionals[&2].available, 100, "a rejected withdrawal does not change state");
    }

    #[test]
    fn reserve_if_notional_caps_at_available_never_over_promises() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        let cover = s.reserve_if_notional(1, 100, 20);
        assert_eq!(cover, 1_000, "can only reserve the currently truly available amount, never over-promises");
        assert_eq!(s.notionals[&1], IfNotional { available: 1_000, reserved: 1_000 });
    }

    #[test]
    fn reserve_if_notional_never_goes_negative_across_repeated_reserves() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 500);
        let c1 = s.reserve_if_notional(1, 10, 40);
        assert_eq!(c1, 400);
        let c2 = s.reserve_if_notional(1, 10, 40);
        assert_eq!(c2, 100, "the second reserve can only take the remaining available amount (self-limiting, never drives available negative)");
        assert!(s.notionals[&1].available - s.notionals[&1].reserved >= 0, "IF is never negative");
    }

    #[test]
    fn reserve_if_notional_exact_cover_when_sufficient() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 10_000);
        let cover = s.reserve_if_notional(1, 5, 100);
        assert_eq!(cover, 500);
    }

    #[test]
    fn release_reserved_if_notional_is_symmetric_with_reserve() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        let cover = s.reserve_if_notional(1, 5, 100);
        s.release_reserved_if_notional(1, cover);
        assert_eq!(s.notionals[&1], IfNotional { available: 1_000, reserved: 0 }, "reserved returns to zero after release, available is unaffected");
    }

    #[test]
    fn release_reserved_if_notional_missing_symbol_is_noop() {
        let mut s = LiquidationService::new();
        s.release_reserved_if_notional(99, 100);
        assert!(!s.notionals.contains_key(&99));
    }

    #[test]
    fn accept_if_position_debits_available_and_accumulates_position() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 10_000);
        s.reserve_if_notional(1, 5, 100);
        s.accept_if_position(1, PositionDirection::Long, 5, 100);

        assert_eq!(s.notionals[&1].available, 10_000 - 500, "available is debited by the real size*price amount");
        let key = 1i64;
        assert_eq!(s.positions[&key], IfPositionRecord { symbol: 1, direction: PositionDirection::Long, open_volume: 5, open_price_sum: 500 });
    }

    #[test]
    fn accept_if_position_long_and_short_same_symbol_do_not_collide() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(7, 100_000);
        s.reserve_if_notional(7, 10, 50);
        s.accept_if_position(7, PositionDirection::Long, 10, 50);
        s.reserve_if_notional(7, 4, 50);
        s.accept_if_position(7, PositionDirection::Short, 4, 50);

        assert_eq!(s.positions[&7i64].open_volume, 10);
        assert_eq!(s.positions[&(-7i64)].open_volume, 4);
        assert_eq!(s.positions.len(), 2, "the sign-encoded key prevents the long and short records from overwriting each other");
    }

    #[test]
    fn accept_if_position_accumulates_across_multiple_calls_same_direction() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 100_000);
        s.reserve_if_notional(1, 5, 100);
        s.accept_if_position(1, PositionDirection::Long, 5, 100);
        s.reserve_if_notional(1, 3, 100);
        s.accept_if_position(1, PositionDirection::Long, 3, 100);

        let key = 1i64;
        assert_eq!(s.positions[&key].open_volume, 8);
        assert_eq!(s.positions[&key].open_price_sum, 800);
    }

    #[test]
    #[should_panic(expected = "no IFNotional reserved")]
    fn accept_if_position_without_prior_reserve_panics() {
        let mut s = LiquidationService::new();
        s.accept_if_position(1, PositionDirection::Long, 1, 1);
    }

    #[test]
    fn reset_clears_both_buckets() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 100);
        s.reserve_if_notional(1, 1, 10);
        s.accept_if_position(1, PositionDirection::Long, 1, 10);
        s.reset();
        assert!(s.notionals.is_empty());
        assert!(s.positions.is_empty());
    }

    #[test]
    fn state_hash_deterministic_for_same_state() {
        let mut a = LiquidationService::new();
        a.deposit_to_insurance_fund(1, 100);
        let mut b = LiquidationService::new();
        b.deposit_to_insurance_fund(1, 100);
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_when_notionals_change() {
        let base = LiquidationService::new();
        let h0 = base.state_hash();

        let mut deposited = LiquidationService::new();
        deposited.deposit_to_insurance_fund(1, 1);
        assert_ne!(h0, deposited.state_hash(), "changes in available must be reflected in the hash");

        let mut reserved = LiquidationService::new();
        reserved.deposit_to_insurance_fund(1, 100);
        let h_before_reserve = reserved.state_hash();
        reserved.reserve_if_notional(1, 1, 1);
        assert_ne!(h_before_reserve, reserved.state_hash(), "changes in reserved must be reflected in the hash (even when available is unchanged)");
    }

    #[test]
    fn state_hash_changes_when_positions_change() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 10_000);
        let h0 = s.state_hash();
        s.reserve_if_notional(1, 5, 100);
        let h1 = s.state_hash();
        s.accept_if_position(1, PositionDirection::Long, 5, 100);
        let h2 = s.state_hash();
        assert_ne!(h0, h1, "the reserve step changes the notionals hash first");
        assert_ne!(h1, h2, "the accept step adding a new positions entry must change the hash");
    }

    fn pos(direction: PositionDirection, open_volume: i64, open_price_sum: i64, open_init_margin_sum: i64, adl_eligibility: i64) -> SymbolPositionRecord {
        let mut p = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 1);
        p.direction = direction;
        p.open_volume = open_volume;
        p.open_price_sum = open_price_sum;
        p.open_init_margin_sum = open_init_margin_sum;
        p.adl_eligibility = adl_eligibility;
        p
    }

    #[test]
    fn unrealized_pnl_long_positive_when_bankruptcy_price_above_avg_cost() {
        let p = pos(PositionDirection::Long, 10, 900, 1, 100);
        assert_eq!(LiquidationService::unrealized_pnl(&p, 100), 100, "(100*10-900)*+1=100");
    }

    #[test]
    fn unrealized_pnl_short_positive_when_bankruptcy_price_below_avg_cost() {
        let p = pos(PositionDirection::Short, 10, 1100, 1, 100);
        assert_eq!(LiquidationService::unrealized_pnl(&p, 100), 100, "(100*10-1100)*-1=100");
    }

    #[test]
    fn risk_score_positive_and_ordered_by_eligibility_when_other_terms_equal() {
        let low = pos(PositionDirection::Long, 10, 900, 90, 10);
        let high = pos(PositionDirection::Long, 10, 900, 90, 90);
        assert!(LiquidationService::risk_score(&high, 100) > LiquidationService::risk_score(&low, 100));
    }

    #[test]
    fn risk_score_saturating_overflow_does_not_flip_sign() {
        let mut p = pos(PositionDirection::Long, 1, i64::MAX / 2, 1, 100);
        p.open_price_sum = 4_000_000_000_000_000_000;
        let score = LiquidationService::risk_score(&p, 1);
        assert!(score == i64::MAX || score == i64::MIN, "overflow must clamp to a saturation boundary, never wrap into an intermediate value");
    }

    #[test]
    fn risk_score_saturating_overflow_preserves_ranking_direction() {
        let normal = pos(PositionDirection::Long, 10, 900, 90, 50);
        let overflow_pos = pos(PositionDirection::Long, 1, 2_000_000_000_000_000_000, 1, 100);
        let normal_score = LiquidationService::risk_score(&normal, 100);
        let overflow_score = LiquidationService::risk_score(&overflow_pos, 4_000_000_000_000_000_000);
        assert_eq!(overflow_score, i64::MAX, "positive PnL + high-leverage overflow must clamp to i64::MAX (same-sign saturation upper bound)");
        assert!(overflow_score > normal_score, "after clamping to MAX on overflow, ranking must still overwhelmingly prioritize it, not get pushed back or even turn negative due to wrapping");
    }

    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::processors::user_profile_service::UserProfileService;

    const SYMBOL: i32 = 100;
    const BASE: i32 = 1;
    const QUOTE: i32 = 2;

    fn futures_ssp() -> SymbolSpecificationProvider {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(CoreSymbolSpecification {
                symbol_id: SYMBOL,
                symbol_type: SymbolType::FuturesContractPerpetual,
                base_currency: BASE,
                quote_currency: QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                ..Default::default()
            }),
            crate::core::common::cmd::command_result_code::CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        ssp
    }

    #[test]
    fn isolated_profitable_position_is_eligible_with_default_100() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        ups.get_mut(1).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 900,
                ..SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Isolated, 1)
            },
        );
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(100));

        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);

        let candidates = result.get(&SYMBOL).expect("symbol must have a candidate list");
        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0].adl_eligibility, 100, "ISOLATED default eligibility factor = 100 (normalized at construction, the function itself does not rewrite it)");
    }

    #[test]
    fn isolated_losing_position_is_not_eligible() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        ups.get_mut(1).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 1_100,
                ..SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Isolated, 1)
            },
        );
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(100));

        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(result.get(&SYMBOL).is_none() || result[&SYMBOL].is_empty());
    }

    #[test]
    fn cross_position_gated_in_writes_clamped_factor_and_is_eligible() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        ups.get_mut(1).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 900,
                ..SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Cross, 1)
            },
        );
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(100));
        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(result.get(&SYMBOL).is_none() || result[&SYMBOL].is_empty(), "equity below 1.2x maintenance -> gating fails, not selected");
        assert_eq!(ups.get(1).unwrap().positions[&SYMBOL].adl_eligibility, 0, "gating fails, adl_eligibility stays at the CROSS default of 0");

        ups.get_mut(1).unwrap().add_to_account(QUOTE, 2_000);
        let result2 = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        let candidates = result2.get(&SYMBOL).expect("there must be a candidate once gating passes");
        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0].adl_eligibility, 100, "clamped to the upper bound of 100");
        assert_eq!(ups.get(1).unwrap().positions[&SYMBOL].adl_eligibility, 100, "must be written back to the live record, not just the snapshot");
    }

    #[test]
    fn cross_position_gated_in_with_partial_factor_is_clamped_correctly() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        ups.get_mut(1).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 900,
                ..SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Cross, 1)
            },
        );
        ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_300);
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(100));

        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert_eq!(result[&SYMBOL][0].adl_eligibility, 40);
    }

    #[test]
    fn cross_position_non_positive_total_profit_is_not_eligible() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        ups.get_mut(1).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 1_100,
                ..SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Cross, 1)
            },
        );
        ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_000_000);
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(100));

        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(result.get(&SYMBOL).is_none() || result[&SYMBOL].is_empty());
    }

    #[test]
    fn zero_open_volume_and_missing_mark_price_and_non_futures_are_all_skipped() {
        let mut ssp = futures_ssp();
        assert_eq!(
            ssp.add_symbol(CoreSymbolSpecification {
                symbol_id: 999,
                symbol_type: SymbolType::CurrencyExchangePair,
                base_currency: BASE,
                quote_currency: QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                ..Default::default()
            }),
            crate::core::common::cmd::command_result_code::CommandResultCode::Success
        );

        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Isolated, 1));
        ups.add_empty_user_profile(2);
        ups.get_mut(2).unwrap().positions.insert(
            999,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 100,
                ..SymbolPositionRecord::new(2, 999, QUOTE, MarginMode::Isolated, 1)
            },
        );
        ups.add_empty_user_profile(3);
        ups.get_mut(3).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 100,
                ..SymbolPositionRecord::new(3, SYMBOL, QUOTE, MarginMode::Isolated, 1)
            },
        );

        let last_price_cache = BTreeMap::new();
        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(result.is_empty(), "empty position / non-futures / missing mark price are all skipped, producing no candidates");
    }

    #[test]
    fn compute_profitable_positions_recomputes_every_call_not_cached() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        ups.get_mut(1).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 900,
                ..SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Isolated, 1)
            },
        );
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(100));

        let first = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert_eq!(first[&SYMBOL].len(), 1);

        ups.get_mut(1).unwrap().positions.get_mut(&SYMBOL).unwrap().open_volume = 0;
        let second = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(second.get(&SYMBOL).is_none() || second[&SYMBOL].is_empty(), "recomputation must reflect the latest state, not a cached result from the first call");
    }
}
