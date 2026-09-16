//! 翻译自 Java `exchange.core2.tests.integration.ITLoanFailoverSnapshot`（2 个 @Test）——loan 的 failover /
//! snapshot 幂等：① loan 状态（loan records + LoanService 池子/收入/LIF 桶 + 动态利率）完整进 raft snapshot；
//! ② 新 leader 从快照恢复出**字节一致**的状态，故其 scanner（纯状态函数）做相同决策，不会双重/过量强平。
//!
//! `ExchangeApi` 无 snapshot / ADD_LOAN 配置入口，故直连 `ExchangeCore`（对齐 in-crate `loan_e2e_tests.rs`）：
//! 真实 bincode round-trip 走 `to_snapshot_bytes()` / `from_snapshot_bytes()`。Java `requestStateHash` 逐字节
//! 一致的等价判据 = 复制态全 `BTreeMap`（禁 HashMap）故 bincode 确定性 ⇒ 直接断言
//! `snapshot == recovered.to_snapshot_bytes()`（`liquidation_engine` 等 leader-local 是 `#[serde(skip)]`，
//! 不入字节比较，恢复时经 `restore_non_replicated_state` 重建）。利率曲线 / numeraire 走 `apply_add_loan`
//! 直配（Java `BatchAddLoanCommand.ofRateCurve/ofGlobalNumeraire`），reprice 走 `REPRICE_LOAN_RATES` 命令
//! （同步管线 R1→R2 一次做完，无需 Java 的 ApiNop 屏障）。
//!
//! **移植偏差（钉死并说明）**：Java 场景一用「空簿全拒强平」制造 stuck loan 保持原样，但当前 Rust 引擎对
//! 全拒（traded_size==0 且 remainDebt>0）走 LIF 终局接管（见 `loan_e2e_tests` 的
//! `scenario_isolated_force_liquidate_lif_takeover_undercollateralized` 与 `post_process_loan_force_liquidate`
//! 的 `lif_takeover` 分支）——isolated loan1 因此被 LIF 接管并移除，留下负 LIF / 归还池子桶。这不削弱本测试意图，
//! 反而让 snapshot round-trip 覆盖更丰富的桶态（负 LIF + cross loan + 池子），故保留 Java 的命令序列、按 Rust
//! 实际终态断言。

#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::batch_add_loan_command::{BatchAddLoanCommand, RateCurveConfig};
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::isolated_loan_record::LoanRateMode;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_core::ExchangeCore;

    fn submit(core: &mut ExchangeCore, mut cmd: OrderCommand) -> CommandResultCode {
        core.process_command(&mut cmd);
        cmd.result_code.expect("every command produces a result code")
    }

    fn spot_loan_spec(symbol_id: i32, base: i32, quote: i32, initial: i32, liquidation: i32, margin_call: i32) -> CoreSymbolSpecification {
        let mut spec = CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: base,
            quote_currency: quote,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        spec.loan_config.update(initial, liquidation, margin_call, i64::MAX, 365);
        spec
    }

    fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
    }

    fn cmd_balance_adjustment(order_id: i64, uid: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::BalanceAdjustment, order_id, uid, symbol: currency, price: amount, ..Default::default() }
    }

    #[allow(clippy::too_many_arguments)]
    fn cmd_loan_create(order_id: i64, uid: i64, symbol: i32, loan_id: i64, collateral: i64, principal: i64, floating: bool, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCreate,
            order_id,
            uid,
            symbol,
            size: collateral,
            price: principal,
            reserve_bid_price: loan_id,
            user_cookie: if floating { LoanRateMode::Floating.code() as i32 } else { 0 },
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cmd_loan_force_liquidate(order_id: i64, uid: i64, symbol: i32, loan_id: i64, price: i64, lots: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanForceLiquidate,
            order_id,
            uid,
            symbol,
            price,
            size: lots,
            reserve_bid_price: loan_id,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cmd_loan_cross_add_collateral(order_id: i64, uid: i64, currency: i32, amount: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanCrossAddCollateral, order_id, uid, symbol: currency, size: amount, timestamp: ts, ..Default::default() }
    }

    fn cmd_loan_cross_borrow(order_id: i64, uid: i64, symbol: i32, loan_id: i64, principal: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossBorrow,
            order_id,
            uid,
            symbol,
            price: principal,
            reserve_bid_price: loan_id,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cmd_reprice(ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::RepriceLoanRates, timestamp: ts, ..Default::default() }
    }

    // ============================================================================================
    // 场景一：loan 记录 + 池子/LIF 桶随快照存活，恢复后逐字节一致 + 守恒。
    // ============================================================================================

    const WBTC: i32 = 720; // base，digit 2 → currencyScaleK=100（与 baseScaleK=1 scale 错配）
    const USDT: i32 = 721; // quote / numeraire，digit 0 → scale 1
    const SYMBOL: i32 = 72010;
    const MARK: i64 = 50_000;
    const POOL_FUND: i64 = 10_000_000;
    const BORROWER: i64 = 9001;
    const LP: i64 = 9002;

    #[test]
    fn loan_state_survives_snapshot_restore_identical_bytes_and_conserved() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: WBTC, currency_scale_k: 100, collateral_weight_bps: 10_000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: USDT, currency_scale_k: 1, ..Default::default() });
        let spec = spot_loan_spec(SYMBOL, WBTC, USDT, 6_000, 8_500, 7_500);
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.risk.last_price_cache.insert(SYMBOL, MARK);
        core.risk.loan_service.global_config.numeraire_currency = USDT;

        assert_eq!(submit(&mut core, cmd_pool_deposit(5000, USDT, POOL_FUND)), CommandResultCode::Success);
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(LP);
        assert_eq!(submit(&mut core, cmd_balance_adjustment(1, BORROWER, WBTC, 1_000)), CommandResultCode::Success); // 10 WBTC
        assert_eq!(submit(&mut core, cmd_balance_adjustment(2, LP, USDT, POOL_FUND)), CommandResultCode::Success);

        // Isolated：抵押 3 WBTC(=300) 借 80k，然后无对手盘强平（空簿全拒）。
        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_002, BORROWER, SYMBOL, 1, 300, 80_000, false, 1_000)),
            CommandResultCode::Success
        );
        // 空簿 IOC 全拒 → Rust 走 LIF 终局接管（见文件头移植偏差），loan1 被接管移除。
        assert_eq!(
            submit(&mut core, cmd_loan_force_liquidate(2, BORROWER, SYMBOL, 1, MARK, 3, 1_000)),
            CommandResultCode::Success
        );
        assert!(!core.ups.get(BORROWER).unwrap().isolated_loans.contains_key(&1), "全拒 → LIF 接管 → loan1 移除");

        // Cross：账户级抵押 3 WBTC 借 60k，保持开仓（丰富 crossLoans / crossLoanCollateral / 池子桶）。
        assert_eq!(submit(&mut core, cmd_loan_cross_add_collateral(1_000_003, BORROWER, WBTC, 300, 1_000)), CommandResultCode::Success);
        assert_eq!(submit(&mut core, cmd_loan_cross_borrow(1_000_004, BORROWER, SYMBOL, 2, 60_000, 1_000)), CommandResultCode::Success);

        assert!(core.query_total_balance().is_global_zero(), "快照前应守恒");
        let snapshot = core.to_snapshot_bytes();

        // ===== 新 leader：从快照恢复，比对逐字节一致 + 守恒 =====
        let recovered = ExchangeCore::from_snapshot_bytes(&snapshot);
        assert_eq!(
            snapshot,
            recovered.to_snapshot_bytes(),
            "恢复后复制态必须与原 leader 逐字节一致（loan records + 池子/LIF 桶都在快照里）"
        );
        assert!(recovered.query_total_balance().is_global_zero(), "恢复后应守恒");

        // 关键状态点抽查：cross loan2 原样存活，isolated loan1 已被 LIF 接管移除。
        let up = recovered.ups.get(BORROWER).unwrap();
        assert_eq!(up.cross_loans.get(&2).map(|l| l.outstanding_principal), Some(60_000), "cross loan2 本金随快照存活");
        assert!(!up.isolated_loans.contains_key(&1));
        // 负 LIF（平台垫资）也须随快照存活。
        assert_eq!(recovered.risk.loan_service.get_loan_insurance_fund(USDT), -80_000, "接管产生的负 LIF 随快照存活");
    }

    // ============================================================================================
    // 场景二：reprice 后的动态利率状态（currentRateBps / lastRepriceTs）随快照存活；
    //         恢复后新开 FLOATING 贷款仍按曲线现值 240 开仓（非回退 base 200）。
    // ============================================================================================

    const RC_BTC: i32 = 730; // 抵押币，digit 0
    const RC_USDT: i32 = 731; // 借出币 / numeraire，digit 0
    const RC_SYMBOL: i32 = 73010;
    const RC_MARK: i64 = 50_000;
    const RC_POOL: i64 = 10_000_000;
    const RC_BORROWER: i64 = 9101;
    // kinked 曲线 base=200 / kink=8000 / slope1=400 / slope2=6000；util=800_000/10_000_000=800bps(<kink)
    // → 200 + 400×800/8000 = 240。
    const RC_EXPECTED: i32 = 240;

    fn floating_loan_rate_bps(core: &ExchangeCore, loan_id: i64) -> i32 {
        core.ups.get(RC_BORROWER).unwrap().isolated_loans.get(&loan_id).expect("isolated loan not found").rate_bps
    }

    #[test]
    fn loan_rate_state_survives_snapshot_restore_repriced_curve_rate_preserved() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: RC_BTC, currency_scale_k: 1, collateral_weight_bps: 10_000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: RC_USDT, currency_scale_k: 1, ..Default::default() });
        let spec = spot_loan_spec(RC_SYMBOL, RC_BTC, RC_USDT, 6_000, 8_500, 7_500);
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.risk.last_price_cache.insert(RC_SYMBOL, RC_MARK);
        core.risk.loan_service.global_config.numeraire_currency = RC_USDT;
        // 非默认利率曲线（对齐 ITLoanDynamicRate）。
        core.risk.apply_add_loan(
            &BatchAddLoanCommand {
                global: None,
                symbol: None,
                rate_curve: Some(RateCurveConfig { base_bps: 200, kink_util_bps: 8_000, slope1_bps: 400, slope2_bps: 6_000, locked_rate_adjust_bps: 0 }),
            },
            &mut core.ssp,
        );

        assert_eq!(submit(&mut core, cmd_pool_deposit(5000, RC_USDT, RC_POOL)), CommandResultCode::Success);
        core.ups.add_empty_user_profile(RC_BORROWER);
        assert_eq!(submit(&mut core, cmd_balance_adjustment(1, RC_BORROWER, RC_BTC, 400)), CommandResultCode::Success); // 够 3 笔 100 抵押

        // loan1 借 800_000 → util = 800_000 / 10_000_000 = 800 bps，制造非零利用率。
        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_102, RC_BORROWER, RC_SYMBOL, 1, 100, 800_000, true, 1_000)),
            CommandResultCode::Success
        );

        // reprice：util=800(<kink) → 240 写入 currentRateBps[USDT]，lastRepriceTs 也变非默认。
        assert_eq!(submit(&mut core, cmd_reprice(1_000)), CommandResultCode::Success);

        // 快照前就地证明 currentRateBps 已非默认：reprice 后新开 FLOATING 率 = curve(util) = 240（≠ base 200）。
        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_103, RC_BORROWER, RC_SYMBOL, 2, 100, 100_000, true, 1_000)),
            CommandResultCode::Success
        );
        assert_eq!(floating_loan_rate_bps(&core, 2), RC_EXPECTED, "快照前：reprice 后新 FLOATING 率 = curve(util) = 240");

        assert!(core.query_total_balance().is_global_zero(), "快照前应守恒");
        let snapshot = core.to_snapshot_bytes();

        // ===== 新 leader：从快照恢复，比对逐字节一致 + 用恢复后新贷款率验证 currentRateBps 存活 =====
        let mut r = ExchangeCore::from_snapshot_bytes(&snapshot);
        assert_eq!(
            snapshot,
            r.to_snapshot_bytes(),
            "恢复后复制态必须逐字节一致（currentRateBps / lastRepriceTs 都在快照里）"
        );

        // 关键判据：恢复后新开 FLOATING 贷款仍按曲线现值 240 开仓 —— 若 currentRateBps 未随快照恢复（重置为空），
        // 回退曲线 base 只会得 200。得 240 证明 reprice 后的动态利率状态穿过 failover 存活，未被重置为 base。
        assert_eq!(
            submit(&mut r, cmd_loan_create(1_000_104, RC_BORROWER, RC_SYMBOL, 3, 100, 100_000, true, 1_000)),
            CommandResultCode::Success
        );
        assert_eq!(
            floating_loan_rate_bps(&r, 3),
            RC_EXPECTED,
            "恢复后新 FLOATING 率 = curve(util) = 240（证明 currentRateBps 随快照存活，未重置为 base 200）"
        );
        assert!(r.query_total_balance().is_global_zero(), "恢复后应守恒");
    }
}
