//! 差分模糊:确定性 PRNG 批量生成随机命令流 `.stream`(现货撮合),写入 tests/conformance_vectors/。
//! 生成后:Java `mvn -Dtest=ConformanceExporter test` 产 golden → Rust `cargo test --test conformance` 对拍。
//! 种子固定 → 可复现;向量 + golden 一并入库。跑:`cargo run --example gen_conformance_fuzz`。
//!
//! v1 只随机**现货 PLACE**(多用户单 symbol,各类 order_type/价/量/方向),对拍 result_code + 最终状态。
//! 无异步/无清算,最稳;已能把撮合引擎(crossing/partial/IOC/FOK/NSF/dup)压得很满。

use std::fmt::Write as _;
use std::fs;
use std::path::Path;

/// xorshift64 —— 无依赖确定性 PRNG。
struct Rng(u64);
impl Rng {
    fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
    /// [lo, hi) 闭开区间。
    fn range(&mut self, lo: i64, hi: i64) -> i64 {
        lo + (self.next_u64() % ((hi - lo) as u64)) as i64
    }
}

/// 由 seed + 域分隔常量派生 PRNG 初值：SplitMix 风格 mix 后叠加 `domain`，使现货/期货/清算三条生成流即便同 seed 也不相关。
fn seeded_rng(seed: u64, domain: u64) -> Rng {
    Rng(seed.wrapping_mul(0x9E3779B97F4A7C15).wrapping_add(0xD1B54A32D192ED03).wrapping_add(domain))
}

const N_USERS: i64 = 4;
const CMDS_PER_VEC: usize = 60;
const N_VECTORS: usize = 16;
const N_FUT_VECTORS: usize = 8;
const N_LIQ_VECTORS: usize = 6;

fn gen_vector(seed: u64) -> String {
    let mut rng = seeded_rng(seed, 0);
    let mut s = String::new();
    writeln!(s, "# 差分模糊生成(seed={seed}):现货多用户随机 PLACE。勿手改;由 gen_conformance_fuzz 生成。").unwrap();
    // 无费现货对,便于状态对拍(费用差异在 IT/fee 向量另测);两币 digit 0。
    writeln!(s, "CUR id=1 digit=0").unwrap();
    writeln!(s, "CUR id=2 digit=0").unwrap();
    writeln!(s, "SYM_SPOT id=100 base=1 quote=2 baseScale=1 quoteScale=1 taker=0 maker=0").unwrap();
    let mut txid = 1;
    for uid in 1..=N_USERS {
        writeln!(s, "USER uid={uid}").unwrap();
        // 每用户 base + quote 都充足额,可任意买卖;偶发 NSF 由大随机量触发。
        writeln!(s, "BAL uid={uid} cur=1 amount=1000000000 txid={txid}").unwrap();
        txid += 1;
        writeln!(s, "BAL uid={uid} cur=2 amount=1000000000000 txid={txid}").unwrap();
        txid += 1;
    }
    let mut oid = 1000;
    for _ in 0..CMDS_PER_VEC {
        oid += 1;
        let uid = rng.range(1, N_USERS + 1);
        // 按 uid 奇偶固定买/卖方,杜绝自成交(self-trade 是两侧刻意差异,单独对拍;见 README 归一化清单)。
        let action = if uid % 2 == 1 { "BID" } else { "ASK" };
        // 价格围绕 10000 上下浮动 → 制造 crossing + resting 混合。
        let price = rng.range(9_500, 10_501);
        // 偶发大 size 触发 NSF(两侧结果码应一致)。
        let size = if rng.range(0, 20) == 0 { rng.range(1, 900_000_000) } else { rng.range(1, 8) };
        // GTC + IOC:两侧逐值一致(IOC 靠 exporter 每命令 flush 消除 Java R1/R2 lag)。
        // **不随机普通 FOK(OrderType.FOK)**:仅这一种 Java 未实现(Naive/Direct 均 `// TODO FOK support`,
        // default 整单 reject),Rust 已正确实现 → 能成交时两侧分歧(Java 功能缺口,非 bug)。
        // 注:FOK_BUDGET / IOC_BUDGET 两侧都已实现且对拍一致(手写向量 fok_budget/ioc_budget),此处未随机仅为简化。
        let ot = if rng.range(0, 2) == 0 { "GTC" } else { "IOC" };
        if action == "BID" {
            // 现货 BID 需 reserve ≥ price;给足冗余。
            let reserve = price + rng.range(0, 600);
            writeln!(s, "PLACE oid={oid} uid={uid} sym=100 price={price} size={size} action=BID type={ot} reserve={reserve}").unwrap();
        } else {
            writeln!(s, "PLACE oid={oid} uid={uid} sym=100 price={price} size={size} action=ASK type={ot}").unwrap();
        }
    }
    s
}

/// 期货随机流:匹配 maker/taker 成对开仓(无清算 → events-on 确定)。leverage 1..5 恒 ≤ lev_table 上限;
/// taker ≠ maker 杜绝自成交;同价对手方保证成交。对拍 result_code + 最终 POS/账户 + 结算类事件(开仓事件被归一化排除)。
fn gen_futures_vector(seed: u64) -> String {
    let mut rng = seeded_rng(seed, 0xF00D);
    let mut s = String::new();
    writeln!(s, "# 差分模糊(seed={seed}):期货多用户匹配 maker/taker 随机开仓。勿手改;由 gen_conformance_fuzz 生成。").unwrap();
    writeln!(s, "CUR id=1 digit=0").unwrap();
    writeln!(s, "CUR id=2 digit=0").unwrap();
    writeln!(s, "SYM_FUT id=10000 kind=PERP base=1 quote=2 baseScale=1 quoteScale=1 taker=0 maker=0 feeScale=0 initMargin=1 initMarginScaleK=100").unwrap();
    writeln!(s, "MARK sym=10000 price=10000").unwrap();
    let mut txid = 1;
    for uid in 1..=N_USERS {
        writeln!(s, "USER uid={uid}").unwrap();
        // 仅 quote 充值(期货保证金走 quote),额度足以任意开仓。
        writeln!(s, "BAL uid={uid} cur=2 amount=1000000000000 txid={txid}").unwrap();
        txid += 1;
    }
    let mut oid = 2000;
    for _ in 0..20 {
        let price = rng.range(9_800, 10_201);
        let size = rng.range(1, 5);
        let lev = rng.range(1, 6);
        let maker_uid = rng.range(1, N_USERS + 1);
        let mut taker_uid = rng.range(1, N_USERS + 1);
        if taker_uid == maker_uid {
            taker_uid = (taker_uid % N_USERS) + 1; // 避免自成交
        }
        let maker_bid = rng.range(0, 2) == 0;
        let (m_act, t_act) = if maker_bid { ("BID", "ASK") } else { ("ASK", "BID") };
        let margin_m = if rng.range(0, 2) == 0 { "ISOLATED" } else { "CROSS" };
        let margin_t = if rng.range(0, 2) == 0 { "ISOLATED" } else { "CROSS" };
        oid += 1;
        writeln!(s, "PLACE_FUT oid={oid} uid={maker_uid} sym=10000 price={price} size={size} action={m_act} type=GTC leverage={lev} margin={margin_m}").unwrap();
        oid += 1;
        // taker 同价对手方 → 必成交开仓。
        writeln!(s, "PLACE_FUT oid={oid} uid={taker_uid} sym=10000 price={price} size={size} action={t_act} type=GTC leverage={lev} margin={margin_t}").unwrap();
    }
    s
}

/// 清算随机流:开一笔 isolated 长仓(薄保证金)+ 对手方挂 BID 吸收强平卖单,随后 mark 暴跌触发强平级联。
/// 异步清算 → 只对拍 result_code + 最终 STATE(`#!events=off`,对齐现有 liquidation/adl 向量的稳妥口径)。
/// 随机化:仓位 size、杠杆、暴跌深度;结构固定以保证可靠触发强平。
fn gen_liquidation_vector(seed: u64) -> String {
    let mut rng = seeded_rng(seed, 0xBEEF);
    let mut s = String::new();
    writeln!(s, "#!events=off").unwrap();
    writeln!(s, "# 差分模糊(seed={seed}):随机 isolated 长仓被暴跌强平。异步清算→仅对拍 STATE。勿手改。").unwrap();
    writeln!(s, "CUR id=1 digit=0").unwrap();
    writeln!(s, "CUR id=2 digit=0").unwrap();
    writeln!(s, "SYM_FUT id=10000 kind=PERP base=1 quote=2 baseScale=1 quoteScale=1 taker=20 maker=10 feeScale=0 initMargin=1 initMarginScaleK=100").unwrap();
    writeln!(s, "MARK sym=10000 price=10000").unwrap();
    let size = rng.range(5, 15);
    let lev = rng.range(1, 4);
    // uid=1 薄保证金多头(会被强平);uid=2 富裕对手方(开空 + 事后挂 BID 接强平卖单)。
    writeln!(s, "USER uid=1").unwrap();
    writeln!(s, "USER uid=2").unwrap();
    writeln!(s, "BAL uid=1 cur=2 amount=40000 txid=1").unwrap();
    writeln!(s, "BAL uid=2 cur=2 amount=100000000 txid=2").unwrap();
    // 开仓:uid=1 BID @10000 与 uid=2 ASK @10000 成交 → uid=1 长仓 size。
    writeln!(s, "PLACE_FUT oid=1 uid=1 sym=10000 price=10000 size={size} action=BID type=GTC leverage={lev} margin=ISOLATED").unwrap();
    writeln!(s, "PLACE_FUT oid=2 uid=2 sym=10000 price=10000 size={size} action=ASK type=GTC leverage=1 margin=CROSS").unwrap();
    // 对手方在低位挂 BID 吸收强平卖单(size 足够覆盖强平量)。
    let absorb_price = rng.range(4_000, 6_001);
    writeln!(s, "PLACE_FUT oid=3 uid=2 sym=10000 price={absorb_price} size={} action=BID type=GTC leverage=1 margin=CROSS", size + 5).unwrap();
    writeln!(s, "ENABLE_LIQ").unwrap();
    // 暴跌:mark 10000 → 随机低位,uid=1 多头保证金被击穿 → 强平。
    let crash = rng.range(4_000, 6_001);
    writeln!(s, "MARK_AT sym=10000 price={crash} ts=2000").unwrap();
    writeln!(s, "SCAN").unwrap();
    s
}

fn main() {
    // 默认写入库目录、种子=向量序号(可复现,入库);live-diff 编排传 `--out <dir> --seed <base>` 写临时目录、
    // 用新鲜种子(如 epoch)每次生成不同随机流,覆盖远超 47 个入库向量。
    let args: Vec<String> = std::env::args().collect();
    let arg = |flag: &str| -> Option<String> {
        args.iter().position(|a| a == flag).and_then(|i| args.get(i + 1).cloned())
    };
    let dir = arg("--out")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/conformance_vectors"));
    let seed_base: u64 = arg("--seed").and_then(|s| s.parse().ok()).unwrap_or(0);
    fs::create_dir_all(&dir).unwrap();
    for i in 0..N_VECTORS {
        let name = format!("fuzz_{i:02}.stream");
        fs::write(dir.join(&name), gen_vector(seed_base.wrapping_add(i as u64))).unwrap();
        println!("生成 {name}");
    }
    for i in 0..N_FUT_VECTORS {
        let name = format!("fut_{i:02}.stream");
        fs::write(dir.join(&name), gen_futures_vector(seed_base.wrapping_add(i as u64))).unwrap();
        println!("生成 {name}");
    }
    for i in 0..N_LIQ_VECTORS {
        let name = format!("liq_{i:02}.stream");
        fs::write(dir.join(&name), gen_liquidation_vector(seed_base.wrapping_add(i as u64))).unwrap();
        println!("生成 {name}");
    }
    println!(
        "\n完成 {} 个模糊向量(seed_base={seed_base}) → {}\n下一步:\n  1) cd ../exchange-core && mvn -q -Dtest=ConformanceExporter -DfailIfNoTests=false test\n  2) cargo test --test conformance",
        N_VECTORS + N_FUT_VECTORS + N_LIQ_VECTORS,
        dir.display()
    );
}
