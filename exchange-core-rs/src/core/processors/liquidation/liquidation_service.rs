//! 对应 Java `LiquidationService`，参考文档 §2.1/§2.3。保险基金（IF）复制状态：per-shard 单例，`RiskEngine` 持有；`notionals`/`positions` 进 state_hash/snapshot（Ruling P6-E）。
//! ADL 候选构造 + 排序键（`unrealized_pnl`/`risk_score`/`compute_profitable_positions_by_symbol`/`add_cross_positions_if_user_safe`，逐字对齐 Java `:191-321`）已落地，provider 传参不持有（同 P3-B），`RiskEngine::adl_collect` 消费。

use std::collections::BTreeMap;

use crate::core::common::margin_mode::MarginMode;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils::size_price_to_currency_scale;

/// 对应 Java `Math.multiplyExact(long, long)`：局部私有重复一份（同仓库既有 helper 风格）。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `LiquidationService.IFNotional`：IF 单 symbol 名义资金——`available` 可动用，`reserved` 为强平预冻结（R1/R2 独立记账线）。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct IfNotional {
    pub available: i64,
    pub reserved: i64,
}

impl IfNotional {
    /// 折叠进 `state_hash` 的 rolling hash（`h=h*31+field`，风格对齐 `UserProfile`/`LoanService`，不要求与 Java `Objects.hash` 数值相等）。
    fn fold_hash(&self, h: i64) -> i64 {
        let h = h.wrapping_mul(31).wrapping_add(self.available);
        h.wrapping_mul(31).wrapping_add(self.reserved)
    }
}

/// 对应 Java `LiquidationService.IFPositionRecord`：IF 自身接管仓位——某 symbol+方向累计持仓量与开仓成本（反向出清估值用）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct IfPositionRecord {
    pub symbol: i32,
    pub direction: PositionDirection,
    pub open_volume: i64,
    pub open_price_sum: i64,
}

impl IfPositionRecord {
    fn fold_hash(&self, h: i64) -> i64 {
        // direction 用 multiplier() 保持跨节点/版本稳定（同 Java 注释）。
        let h = h.wrapping_mul(31).wrapping_add(self.symbol as i64);
        let h = h.wrapping_mul(31).wrapping_add(self.direction.multiplier() as i64);
        let h = h.wrapping_mul(31).wrapping_add(self.open_volume);
        h.wrapping_mul(31).wrapping_add(self.open_price_sum)
    }
}

/// 对应 Java `LiquidationService`（IF 状态子集）：`notionals: symbol -> IFNotional`（sizePrice scale 可动用余额），`positions: (direction.multiplier()*symbol) -> IFPositionRecord`（符号编码 key 区分多空）。
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
pub struct LiquidationService {
    pub notionals: BTreeMap<i32, IfNotional>,
    pub positions: BTreeMap<i64, IfPositionRecord>,
}

impl LiquidationService {
    pub fn new() -> Self {
        LiquidationService::default()
    }

    /// 对应 Java `generateLiquidationOrderId`（`LiquidationService.java:182-188`）：生成根 orderId，位布局 `symbol<<32|uidHash<<12|sideBit<<11|tsPart`；tsPart 刻意改用 `cmd.timestamp` 而非 wall-clock 以保确定性。
    pub fn generate_liquidation_order_id(uid: i64, symbol: i32, direction: PositionDirection, timestamp: i64) -> i64 {
        let uid_hash = (uid.wrapping_mul(31).wrapping_add(17)) & 0xFFFFF; // 20 bit
        let side_bit: i64 = if direction == PositionDirection::Short { 1 } else { 0 };
        let ts_part = (timestamp / 1000) & 0x7FF; // 11 bit
        ((symbol as i64) << 32) | (uid_hash << 12) | (side_bit << 11) | ts_part
    }

    /// 对应 Java `generateIFOrderId(long)`（`:190-193`）：IF 命令的 orderId 由根强平 orderId 派生，
    /// 高位打 `'I'`（`0x49`）标签。
    pub fn generate_if_order_id(liquidation_order_id: i64) -> i64 {
        let if_order_tag: i64 = 0x49; // 'I'
        (if_order_tag << 56) | (liquidation_order_id & 0x00FF_FFFF_FFFF_FFFF)
    }

    /// 对应 Java `generateADLOrderId(long)`（`:195-198`）：ADL 命令的 orderId 由根强平 orderId 派生，
    /// 高位打 `'A'`（`0x41`）标签。
    pub fn generate_adl_order_id(liquidation_order_id: i64) -> i64 {
        let adl_order_tag: i64 = 0x41; // 'A'
        (adl_order_tag << 56) | (liquidation_order_id & 0x00FF_FFFF_FFFF_FFFF)
    }

    /// 对应 Java `creditLiquidationFee`：强平手续费计入 IF 可用资金池（Task 7
    /// `collectLiquidationFee` 消费；本 Task 只落地这个记账原语本身）。
    pub fn credit_liquidation_fee(&mut self, symbol: i32, notional_fee: i64) {
        let n = self.notionals.entry(symbol).or_default();
        n.available += notional_fee;
    }

    /// 对应 Java `depositToInsuranceFund`：外部充值 IF 可用资金池（admin `IF_DEPOSIT`）。入参已是
    /// notional（size*price）尺度，scale 换算由调用方（`RiskEngine::if_deposit`）完成。
    pub fn deposit_to_insurance_fund(&mut self, symbol: i32, notional_amount: i64) {
        let n = self.notionals.entry(symbol).or_default();
        n.available += notional_amount;
    }

    /// 对应 Java `withdrawFromInsuranceFund`：`IF_WITHDRAW` 支持——从 `available` 扣款，含非负
    /// 校验。只扣 `available`、不动 `reserved`（reserved 是正在保护某笔强平的预冻结部分，运营不能
    /// 拿走）。`false` = notional 不存在或 `available` 不足以覆盖（调用方据此返回
    /// `RiskIfInsufficient`）。
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

    /// 对应 Java `reserveIFNotional`（R1）：预冻结 IF 可用名义金额，返回实际能冻结的量
    /// （`min(available - reserved, requestSize * price)`）——**自限、永不为负**：不管请求多大，
    /// 最多只冻结当前真实可用的部分，caller 永远不会把 IF 推向负数（对比 loan LIF 允许为负，
    /// 参考文档 §2.3 "natural braking"）。
    pub fn reserve_if_notional(&mut self, symbol: i32, request_size: i64, price: i64) -> i64 {
        let n = self.notionals.entry(symbol).or_default();
        let available = n.available - n.reserved;
        let needed = mul_exact(request_size, price);
        let can_cover = available.min(needed);
        n.reserved += can_cover;
        can_cover
    }

    /// 对应 Java `releaseReservedIFNotional`（R2 finalize）：释放 R1 预冻结的名义金额，与
    /// `reserve_if_notional` 对称——`IFCommandProcessor::finalize` 无论接管成功/全拒都调用它
    /// （参考文档 §2.2 "always release"）。
    pub fn release_reserved_if_notional(&mut self, symbol: i32, reserved_notional: i64) {
        if let Some(n) = self.notionals.get_mut(&symbol) {
            n.reserved -= reserved_notional;
        }
    }

    /// 对应 Java `acceptIFPosition`（R2 per-event）：IF 正式接管仓位——从 `available` 扣款，累加到
    /// 该 symbol+方向 的持仓量与成本。要求 `notionals[symbol]` 已存在（由同一条命令的 R1
    /// `reserve_if_notional` 保证，同 Java `notionals.get(symbol)` 的隐式非空契约——若在此之前从未
    /// reserve 过，说明调用方违反了 R1→R2 顺序契约，panic 而非静默创建虚假余额）。
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

    /// 对应 Java `reset`：清空全部 IF 状态（测试/重建用）。
    pub fn reset(&mut self) {
        self.notionals.clear();
        self.positions.clear();
    }

    /// 对应 Java `stateHash`：`notionals`/`positions` 都进复制态 hash（Ruling P6-E）。风格对齐
    /// `LoanService::state_hash`（`h=h*31+field` 滚动折叠 + 高低 32 位异或收窄）。
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

    // ================================================================
    // P6 Task 6：ADL 候选构造 + 排序键 —— 对应 Java `:191-321`
    // ================================================================

    /// 对应 Java `unrealizedPnl(SymbolPositionRecord, long bankruptcyPrice)`（`:191-195`）：按
    /// 破产价估算浮动盈亏（ADL 排序/筛选用，静态纯函数）。**全程饱和乘法**（`saturating_multiply`）
    /// ——溢出时钳到 `i64::MIN`/`MAX` 而非 wrap，防止符号翻转。
    pub fn unrealized_pnl(pos: &SymbolPositionRecord, bankruptcy_price: i64) -> i64 {
        let sign = pos.direction.multiplier() as i64;
        let notional = saturating_multiply(bankruptcy_price, pos.open_volume);
        saturating_multiply(sign, notional - pos.open_price_sum)
    }

    /// 对应 Java `riskScore(SymbolPositionRecord, long bankruptcyPrice)`（`:197-203`）：ADL 排序键
    /// = 浮盈 × 实际杠杆 × 资格因子，越大越优先被摊派。**全程饱和乘法**——溢出翻转符号会直接
    /// 反转排序结果，这是 load-bearing 正确性，不是防御性写法（参考文档 §3.1/§11.1）。
    /// `actual_leverage = open_price_sum / open_init_margin_sum`：普通整除，非饱和（Java 同样是
    /// 普通 `/`，`openInitMarginSum==0` 时与 Java 一样整数除零 panic——按 R1 filter 的前置条件
    /// `open_volume>0`，正常持仓路径下 `open_init_margin_sum` 恒为正，不可达）。
    pub fn risk_score(pos: &SymbolPositionRecord, bankruptcy_price: i64) -> i64 {
        let sign = pos.direction.multiplier() as i64;
        let notional = saturating_multiply(bankruptcy_price, pos.open_volume);
        let unrealized_pnl = saturating_multiply(sign, notional - pos.open_price_sum);
        let actual_leverage = pos.open_price_sum / pos.open_init_margin_sum;
        saturating_multiply(saturating_multiply(actual_leverage, unrealized_pnl), pos.adl_eligibility)
    }

    /// 对应 Java `computeProfitablePositionsBySymbol()`（`:225-321`）：ADL 候选构造——按需从复制态
    /// （`ups`/`ssp`/`last_price_cache`）现算出全部可被 ADL 摊派的仓位（symbol -> 候选列表），
    /// **每次重算，不缓存**——leader-only 缓存会让 follower 在同一条 ADL 命令上看到不同候选，
    /// 破坏确定性重放（Java 原版同一条 WHY 注释，逐字保留结论）。
    ///
    /// ISOLATED 仓位直接判"浮盈 > 0"即入选（`adl_eligibility` 已由
    /// [`SymbolPositionRecord::new`]/`initialize`/`reset` 按 margin_mode 归一为 `100`，本函数
    /// 不再重复写它——对齐 Java `addProfitablePosition` 对 ISOLATED 分支同样不碰 `adlEligibility`
    /// 字段，纯粹依赖构造时已设好的默认值）；CROSS 仓位先按 `quote_currency` 分组，交给
    /// [`Self::add_cross_positions_if_user_safe`] 做账户级门 + factor + 入选（该函数会写回
    /// `adl_eligibility`）。
    ///
    /// # Rust 所有权改造：clone 返回值 + 调用方写回，取代 Java 的活引用列表
    /// Java 版本 `IntObjectHashMap<MutableList<SymbolPositionRecord>>` 里存的是**活对象引用**——
    /// 调用方（`ADLCommandProcessor.collectInput`）后续 `pos.pendingADLSize += canTake` 直接改的
    /// 就是这个引用指向的同一份仓位记录，不需要二次查找。Rust 不能安全地把
    /// "多个不同 `UserProfile` 的 `&mut SymbolPositionRecord`" 塞进一个跨越整个 `ups` 借用的返回值
    /// 里，因此本函数返回的是**克隆快照**（`Vec<SymbolPositionRecord>`）；调用方
    /// （`RiskEngine::adl_collect`）选中某候选后，必须用 `up.create_positions_key(...)` 重新查活
    /// 记录再写 `pending_adl_size`（见 `adl_command_processor.rs` 模块文档）。这不改变可观察行为
    /// ——同一条 ADL 命令的候选列表里每个元素对应**不同的 uid**（不会出现同一仓位在列表里出现两次
    /// 从而需要"看到前一次选取造成的副作用"的情形），冻结快照与活引用在本函数的调用场景下行为
    /// 等价，只是把"何时读取"从"扫描时"挪到"选取时"（选取发生在同一次 `adl_collect` 调用内、扫描
    /// 之后几行代码，中间没有任何会改变这些字段的操作）。
    pub fn compute_profitable_positions_by_symbol(
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
    ) -> BTreeMap<i32, Vec<SymbolPositionRecord>> {
        let mut result: BTreeMap<i32, Vec<SymbolPositionRecord>> = BTreeMap::new();

        // uid 升序遍历：BTreeMap 天然确定序，无需额外排序（对应 Java `forEachValue` 在
        // `IntObjectHashMap` 上迭代序不确定的问题——本移植全程 BTreeMap，规避该风险）。
        let uids: Vec<i64> = ups.users.keys().copied().collect();
        for uid in uids {
            let profile = match ups.users.get_mut(&uid) {
                Some(p) => p,
                None => continue, // 不可达（uid 刚从同一个 map 采集），防御性跳过
            };
            let position_keys: Vec<i32> = profile.positions.keys().copied().collect();

            // 第一遍：ISOLATED 直接判定入选；CROSS 按 quote_currency 分组，交给第二遍聚合。
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
                    Some(&p) => p,
                    None => continue,
                };

                if position.margin_mode == MarginMode::Isolated {
                    if position.estimate_unrealized_profit(mark_price) > 0 {
                        result.entry(position.symbol).or_default().push(position.clone());
                    }
                } else {
                    cross_by_currency.entry(spec.quote_currency).or_default().push(*key);
                }
            }

            // 第二遍：CROSS 按 currency 聚合 + 账户级门 + factor + 入选（+ 写回 adl_eligibility）。
            for (currency, keys) in cross_by_currency {
                Self::add_cross_positions_if_user_safe(profile, currency, &keys, ssp, last_price_cache, &mut result);
            }
        }

        result
    }

    /// 对应 Java `addCrossPositionsIfUserSafe`（`:293-312`）：CROSS 用户单 currency 的 ADL 候选
    /// 构造——聚合 + 用户级 gating + factor + 入选一次性完成。
    ///
    /// Gating（账户必须足够安全且净盈利才有资格被 ADL 吃）：`totalProfit > 0` 且
    /// `equity >= 1.2 × totalMaintenance`（离强平线还有 20%+ 余量）。factor 语义：账户离强平线
    /// 越远 factor 越大，`clamp` 到 `[0, 100]`，写回每条入选仓位的 `adl_eligibility`。
    ///
    /// `total_profit`/`total_maintenance`/`equity` 用普通 `+`/`-`（不用 `*_exact`）——逐字对齐
    /// Java 的 `totalProfit +=`/`totalMaintenance +=`（Java 原版这几处确实不是
    /// `Math.addExact`，只有 `warningThreshold`/`factor` 两处乘法用了 `Math.multiplyExact`，见下）；
    /// 同一模式已见于 `UserProfile::cross_margin_base_allocation`（P4）的 `total_upnl`/`total_mm`
    /// 累加，本函数与其保持同一套算术纪律。
    fn add_cross_positions_if_user_safe(
        profile: &mut UserProfile,
        currency: i32,
        keys: &[i32],
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
        result: &mut BTreeMap<i32, Vec<SymbolPositionRecord>>,
    ) {
        let currency_spec = match ssp.get_currency(currency) {
            Some(c) => c.clone(),
            None => return, // currency spec 缺失，整组跳过（无法做 scale 换算）
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
                Some(&p) => p,
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
            return; // 用户级 gating 不过：本 currency 组下全部 CROSS 仓位维持默认不入选（adl_eligibility=0）
        }

        let equity = profile.account(currency) - profile.locked(currency) + total_profit;
        let warning_threshold = mul_exact(total_maintenance, 6) / 5; // ×1.2，逐字对齐 Java 的乘除顺序
        if equity < warning_threshold {
            return;
        }

        let factor = (mul_exact(equity - total_maintenance, 100) / total_maintenance).clamp(0, 100);

        for &key in keys {
            let symbol = profile.positions[&key].symbol;
            let mark_price = match last_price_cache.get(&symbol) {
                Some(&p) => p,
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

/// 对应 Java `saturatingMultiply(long, long)`（`LiquidationService.java:217-222`）：饱和乘法——
/// 溢出时钳到 `i64::MAX`/`i64::MIN`（按符号）而非 wrap。WHY：ADL 排序键若用普通乘法，溢出截断
/// 会翻转符号导致排序反转；饱和后仍保持单调，不改变排序语义。用 `i128` 中间精度检测溢出，
/// 检测到后按 Java 版本 `((a ^ b) < 0) ? Long.MIN_VALUE : Long.MAX_VALUE` 的符号规则钳位
/// （异或符号位判断两数是否异号——异号则乘积应为负，钳到 `MIN`；同号钳到 `MAX`）。
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

#[cfg(test)]
mod tests {
    use super::*;

    // ---- orderId 编码（generate_liquidation/if/adl_order_id）----

    #[test]
    fn generate_liquidation_order_id_encodes_symbol_uid_side_ts() {
        let long_id = LiquidationService::generate_liquidation_order_id(1, 200, PositionDirection::Long, 5_000);
        let short_id = LiquidationService::generate_liquidation_order_id(1, 200, PositionDirection::Short, 5_000);
        // 高 32 位 = symbol。
        assert_eq!((long_id >> 32) as i32, 200);
        // sideBit（bit 11）：LONG=0 / SHORT=1。
        assert_eq!((long_id >> 11) & 1, 0);
        assert_eq!((short_id >> 11) & 1, 1);
        // tsPart = (ts/1000)&0x7FF = 5。
        assert_eq!(long_id & 0x7FF, 5);
        // uidHash（bit 12-31）= (uid*31+17)&0xFFFFF。
        assert_eq!((long_id >> 12) & 0xFFFFF, (1i64 * 31 + 17) & 0xFFFFF);
    }

    #[test]
    fn generate_liquidation_order_id_distinct_uids_no_collision_same_scan() {
        // 同 symbol/side/ts、不同 uid -> uidHash 不同 -> orderId 不同（不碰撞）。
        let a = LiquidationService::generate_liquidation_order_id(1, 200, PositionDirection::Long, 5_000);
        let b = LiquidationService::generate_liquidation_order_id(2, 200, PositionDirection::Long, 5_000);
        assert_ne!(a, b);
    }

    #[test]
    fn generate_if_and_adl_order_id_tag_high_byte_and_preserve_low_bits() {
        let root = LiquidationService::generate_liquidation_order_id(1, 200, PositionDirection::Long, 5_000);
        let if_id = LiquidationService::generate_if_order_id(root);
        let adl_id = LiquidationService::generate_adl_order_id(root);
        assert_eq!((if_id >> 56) & 0xFF, 0x49, "'I' 标签");
        assert_eq!((adl_id >> 56) & 0xFF, 0x41, "'A' 标签");
        // 低 56 位保留根 orderId（root 本身 < 2^56，故完整保留）。
        assert_eq!(if_id & 0x00FF_FFFF_FFFF_FFFF, root & 0x00FF_FFFF_FFFF_FFFF);
        assert_eq!(adl_id & 0x00FF_FFFF_FFFF_FFFF, root & 0x00FF_FFFF_FFFF_FFFF);
        assert_ne!(if_id, adl_id, "IF/ADL 标签不同 -> orderId 不同");
    }

    // ---- credit_liquidation_fee / deposit / withdraw ----

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
        s.reserve_if_notional(1, 10, 10); // reserved = 100, available 不变
        assert!(s.withdraw_from_insurance_fund(1, 300));
        assert_eq!(s.notionals[&1], IfNotional { available: 700, reserved: 100 }, "只扣 available，不动 reserved");
    }

    #[test]
    fn withdraw_from_insurance_fund_rejects_when_missing_or_insufficient() {
        let mut s = LiquidationService::new();
        assert!(!s.withdraw_from_insurance_fund(1, 1), "从未 deposit 过的 symbol -> false");

        s.deposit_to_insurance_fund(2, 100);
        assert!(!s.withdraw_from_insurance_fund(2, 101), "available 不足 -> false，不能透支");
        assert_eq!(s.notionals[&2].available, 100, "拒绝的提取不改状态");
    }

    // ---- reserve_if_notional：min 上限 + IF 永不为负 ----

    #[test]
    fn reserve_if_notional_caps_at_available_never_over_promises() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000); // available=1000
        let cover = s.reserve_if_notional(1, 100, 20); // needed = 2000 > available
        assert_eq!(cover, 1_000, "只能冻结当前真实可用的部分，不会超额承诺");
        assert_eq!(s.notionals[&1], IfNotional { available: 1_000, reserved: 1_000 });
    }

    #[test]
    fn reserve_if_notional_never_goes_negative_across_repeated_reserves() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 500);
        let c1 = s.reserve_if_notional(1, 10, 40); // needed=400, cover=min(500,400)=400
        assert_eq!(c1, 400);
        let c2 = s.reserve_if_notional(1, 10, 40); // available-reserved=100, needed=400, cover=100
        assert_eq!(c2, 100, "第二次 reserve 只能拿走剩余可用部分（自限，不会让 available 变负）");
        assert!(s.notionals[&1].available - s.notionals[&1].reserved >= 0, "IF 永不为负");
    }

    #[test]
    fn reserve_if_notional_exact_cover_when_sufficient() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 10_000);
        let cover = s.reserve_if_notional(1, 5, 100); // needed = 500 <= available
        assert_eq!(cover, 500);
    }

    // ---- release_reserved_if_notional ----

    #[test]
    fn release_reserved_if_notional_is_symmetric_with_reserve() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        let cover = s.reserve_if_notional(1, 5, 100);
        s.release_reserved_if_notional(1, cover);
        assert_eq!(s.notionals[&1], IfNotional { available: 1_000, reserved: 0 }, "释放后 reserved 归零，available 不受影响");
    }

    #[test]
    fn release_reserved_if_notional_missing_symbol_is_noop() {
        let mut s = LiquidationService::new();
        s.release_reserved_if_notional(99, 100); // 不 panic，静默 no-op（对应 Java notionals.get==null 时 NPE 前置不可达，这里防御性放行）
        assert!(!s.notionals.contains_key(&99));
    }

    // ---- accept_if_position ----

    #[test]
    fn accept_if_position_debits_available_and_accumulates_position() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 10_000);
        s.reserve_if_notional(1, 5, 100); // reserved=500
        s.accept_if_position(1, PositionDirection::Long, 5, 100);

        assert_eq!(s.notionals[&1].available, 10_000 - 500, "available 按 size*price 真实扣款");
        let key = 1i64; // Long.multiplier()=1 * symbol=1
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
        assert_eq!(s.positions.len(), 2, "符号编码 key 防止多空两条记录互相覆盖");
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

    // ---- reset ----

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

    // ---- state_hash ----

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
        assert_ne!(h0, deposited.state_hash(), "available 变化必须反映到 hash");

        let mut reserved = LiquidationService::new();
        reserved.deposit_to_insurance_fund(1, 100);
        let h_before_reserve = reserved.state_hash();
        reserved.reserve_if_notional(1, 1, 1);
        assert_ne!(h_before_reserve, reserved.state_hash(), "reserved 变化必须反映到 hash（即使 available 不变）");
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
        assert_ne!(h0, h1, "reserve 阶段先改变 notionals hash");
        assert_ne!(h1, h2, "accept 阶段新增 positions 条目必须改变 hash");
    }

    // ================================================================
    // P6 Task 6：unrealized_pnl / risk_score（saturating 乘法）
    // ================================================================

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
        let p = pos(PositionDirection::Long, 10, 900, 1, 100); // avg cost 90
        assert_eq!(LiquidationService::unrealized_pnl(&p, 100), 100, "(100*10-900)*+1=100");
    }

    #[test]
    fn unrealized_pnl_short_positive_when_bankruptcy_price_below_avg_cost() {
        let p = pos(PositionDirection::Short, 10, 1100, 1, 100); // avg cost 110
        assert_eq!(LiquidationService::unrealized_pnl(&p, 100), 100, "(100*10-1100)*-1=100");
    }

    #[test]
    fn risk_score_positive_and_ordered_by_eligibility_when_other_terms_equal() {
        let low = pos(PositionDirection::Long, 10, 900, 90, 10); // leverage=10, uPnl=100
        let high = pos(PositionDirection::Long, 10, 900, 90, 90);
        assert!(LiquidationService::risk_score(&high, 100) > LiquidationService::risk_score(&low, 100));
    }

    #[test]
    fn risk_score_saturating_overflow_does_not_flip_sign() {
        // 精心构造一个会在 (actual_leverage * unrealized_pnl) 这步溢出 i64 的场景：
        // actual_leverage 巨大（open_price_sum 大、open_init_margin_sum=1）、unrealized_pnl 也巨大。
        // 若用普通 wrapping 乘法，溢出截断会把结果的符号翻转成负数，直接反转排序；
        // saturating 必须钳在 i64::MAX（因为两个乘数同号，符号规则决定钳到 MAX 而非 MIN）。
        let mut p = pos(PositionDirection::Long, 1, i64::MAX / 2, 1, 100);
        p.open_price_sum = 4_000_000_000_000_000_000; // 巨大成本基 -> actual_leverage 巨大
        let score = LiquidationService::risk_score(&p, 1); // bankruptcy_price=1，产生一个巨大的负 unrealizedPnl
        // 无论怎么组合，钳位后必须落在 i64 合法范围内，且不能因为溢出 wrap 出一个"看起来对但符号
        // 翻转"的值——这里直接断言落在饱和边界之一，验证没有发生 silent wrap。
        assert!(score == i64::MAX || score == i64::MIN, "溢出必须钳到饱和边界，不能 wrap 出中间值");
    }

    #[test]
    fn risk_score_saturating_overflow_preserves_ranking_direction() {
        // 两个候选：一个正常范围内正分值，一个会触发饱和溢出但语义上"更该被优先选中"（浮盈更大、
        // 杠杆更高）——溢出后必须仍然排在前面（钳到 i64::MAX，天然大于任何未溢出的正常分值），
        // 不能因为 wrap 截断而变成一个更小甚至负数从而被错误地排到后面。
        //
        // 夹具必须真正构造"巨大**正**浮盈 + 高杠杆"：Long 且 bankruptcy_price*open_volume >>
        // open_price_sum（破产价远高于成本基 -> 浮盈为正），否则若破产价低于成本基，uPnl 为负、
        // 乘积饱和到 i64::MIN，那才是数学上正确的结果（巨亏本就该垫底），测不出"该优先"的语义。
        // overflow_pos: Long, vol=1, cost=2e18, margin=1 -> leverage=2e18；bankruptcy=4e18 ->
        // uPnl=+1*(4e18-2e18)=2e18；2e18*2e18 溢出、同号 -> i64::MAX；再 *100 仍 i64::MAX。
        let normal = pos(PositionDirection::Long, 10, 900, 90, 50); // 正常范围
        let overflow_pos = pos(PositionDirection::Long, 1, 2_000_000_000_000_000_000, 1, 100);
        let normal_score = LiquidationService::risk_score(&normal, 100);
        let overflow_score = LiquidationService::risk_score(&overflow_pos, 4_000_000_000_000_000_000);
        assert_eq!(overflow_score, i64::MAX, "正浮盈 + 高杠杆溢出必须钳到 i64::MAX（同号饱和上界）");
        assert!(overflow_score > normal_score, "溢出后钳到 MAX，排序上必须仍然是压倒性优先，不能因 wrap 被打到后面甚至变负");
    }

    // ================================================================
    // P6 Task 6：compute_profitable_positions_by_symbol —— ISOLATED / CROSS 资格构造
    // ================================================================

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
        // Long, avg cost 90, mark=100 -> unrealized profit=100*10-900=100>0，ISOLATED 直接入选。
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
        last_price_cache.insert(SYMBOL, 100);

        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);

        let candidates = result.get(&SYMBOL).expect("symbol must have a candidate list");
        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0].adl_eligibility, 100, "ISOLATED 默认资格因子=100（构造时归一，函数本身不重复写）");
    }

    #[test]
    fn isolated_losing_position_is_not_eligible() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        // Long, avg cost 110, mark=100 -> unrealized profit=1000-1100=-100<=0，不入选。
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
        last_price_cache.insert(SYMBOL, 100);

        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(result.get(&SYMBOL).is_none() || result[&SYMBOL].is_empty());
    }

    #[test]
    fn cross_position_gated_in_writes_clamped_factor_and_is_eligible() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        // CROSS Long: open_volume=10, open_price_sum=900(avg 90), mark=100 -> maintenance 由
        // 未配置分档表兜底 = notional = 1000（见 CoreSymbolSpecification::calculate_maintenance_margin
        // 文档）；pnl = estimate_pnl = 100（无 realized profit）。
        ups.get_mut(1).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 900,
                ..SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Cross, 1)
            },
        );
        // equity = balance(0) + total_profit(100) = 100; totalMaintenance=1000 -> equity(100) <
        // warning_threshold(1200)，门不过——先验证这条路径会被拒（下面单独测通过的场景）。
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, 100);
        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(result.get(&SYMBOL).is_none() || result[&SYMBOL].is_empty(), "equity 不足 1.2x maintenance -> gating 不过，不入选");
        assert_eq!(ups.get(1).unwrap().positions[&SYMBOL].adl_eligibility, 0, "gating 不过，adl_eligibility 保持 CROSS 默认值 0");

        // 充值账户余额，把 equity 抬到 gating 线以上：balance=2000 -> equity=2100 >= 1200。
        ups.get_mut(1).unwrap().add_to_account(QUOTE, 2_000);
        let result2 = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        let candidates = result2.get(&SYMBOL).expect("gating 通过后必须有候选");
        assert_eq!(candidates.len(), 1);
        // factor = clamp((equity-maintenance)*100/maintenance, 0, 100) = clamp((2100-1000)*100/1000,0,100) = clamp(110,0,100) = 100
        assert_eq!(candidates[0].adl_eligibility, 100, "clamp 到 100 上限");
        assert_eq!(ups.get(1).unwrap().positions[&SYMBOL].adl_eligibility, 100, "必须写回活记录，不只是快照");
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
        // balance=1_300 -> equity = 1300+100 = 1400 >= threshold(1200)。
        // factor = clamp((1400-1000)*100/1000, 0, 100) = clamp(40, 0, 100) = 40（非边界值，验证非
        // 只有 0/100 两个极端 clamp 分支）。
        ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_300);
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, 100);

        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert_eq!(result[&SYMBOL][0].adl_eligibility, 40);
    }

    #[test]
    fn cross_position_non_positive_total_profit_is_not_eligible() {
        let ssp = futures_ssp();
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(1);
        // Long avg cost 110, mark=100 -> pnl = -100 <= 0 -> totalProfit 门不过，直接拒（不看 equity）。
        ups.get_mut(1).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 1_100,
                ..SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Cross, 1)
            },
        );
        ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_000_000); // 余额充足也救不了：totalProfit<=0 就直接拒
        let mut last_price_cache = BTreeMap::new();
        last_price_cache.insert(SYMBOL, 100);

        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(result.get(&SYMBOL).is_none() || result[&SYMBOL].is_empty());
    }

    #[test]
    fn zero_open_volume_and_missing_mark_price_and_non_futures_are_all_skipped() {
        let mut ssp = futures_ssp();
        // 现货 symbol：非期货，必须被跳过。
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
        // 空仓：open_volume=0 -> 跳过。
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, SymbolPositionRecord::new(1, SYMBOL, QUOTE, MarginMode::Isolated, 1));
        ups.add_empty_user_profile(2);
        // 现货 symbol 上开的"仓位"（人为构造，验证非期货被过滤，不代表真实可达状态）。
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
        // 无 mark price 缓存的期货仓位 -> 跳过（last_price_cache 里没有这个 symbol）。
        ups.get_mut(3).unwrap().positions.insert(
            SYMBOL,
            SymbolPositionRecord {
                direction: PositionDirection::Long,
                open_volume: 10,
                open_price_sum: 100,
                ..SymbolPositionRecord::new(3, SYMBOL, QUOTE, MarginMode::Isolated, 1)
            },
        );

        let last_price_cache = BTreeMap::new(); // 空缓存
        let result = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(result.is_empty(), "空仓/非期货/无 mark price 三种情形全部跳过，不产生任何候选");
    }

    #[test]
    fn compute_profitable_positions_recomputes_every_call_not_cached() {
        // WHY 按需算而非缓存：同一 UserProfileService 状态在两次独立调用之间发生变化，第二次调用
        // 必须反映最新状态——证明没有偷偷缓存第一次的结果。
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
        last_price_cache.insert(SYMBOL, 100);

        let first = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert_eq!(first[&SYMBOL].len(), 1);

        // 状态变化：仓位被完全平掉（open_volume=0）。
        ups.get_mut(1).unwrap().positions.get_mut(&SYMBOL).unwrap().open_volume = 0;
        let second = LiquidationService::compute_profitable_positions_by_symbol(&mut ups, &ssp, &last_price_cache);
        assert!(second.get(&SYMBOL).is_none() || second[&SYMBOL].is_empty(), "重算必须反映最新状态，不是第一次调用的缓存结果");
    }
}
