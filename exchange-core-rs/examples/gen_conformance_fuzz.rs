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

const N_USERS: i64 = 4;
const CMDS_PER_VEC: usize = 60;
const N_VECTORS: usize = 16;

fn gen_vector(seed: u64) -> String {
    let mut rng = Rng(seed.wrapping_mul(0x9E3779B97F4A7C15).wrapping_add(0xD1B54A32D192ED03));
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
        // **不随机普通 FOK**:Java 未实现现货普通 FOK(Naive/Direct 均 `// TODO FOK support`,default 整单 reject),
        // Rust 已正确实现 → 能成交时两侧分歧(Java 功能缺口,非 bug)。FOK 的可用用例由手写向量覆盖。
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

fn main() {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/conformance_vectors");
    fs::create_dir_all(&dir).unwrap();
    for i in 0..N_VECTORS {
        let name = format!("fuzz_{i:02}.stream");
        let content = gen_vector(i as u64);
        fs::write(dir.join(&name), content).unwrap();
        println!("生成 {name}");
    }
    println!(
        "\n完成 {N_VECTORS} 个模糊向量 → {}\n下一步:\n  1) cd ../exchange-core && mvn -q -Dtest=ConformanceExporter -DfailIfNoTests=false test\n  2) cargo test --test conformance",
        dir.display()
    );
}
