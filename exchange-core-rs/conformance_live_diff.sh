#!/usr/bin/env bash
# Java↔Rust 一致性「live 差分」:每次生成**新鲜随机**命令流(非入库的 47 个固定向量),
# 走 gen → Java 导出 golden → Rust replay 对拍 的完整流水线,一条命令跑完。
# 覆盖远超入库向量;用 epoch 作种子基,每次不同。JNI live-diff 的务实替代(见 CONSISTENCY.md §11)。
#
# 用法: ./conformance_live_diff.sh [seed_base]   # 省略则用当前 epoch 秒
# 前置: 已能 cargo / mvn;从 exchange-core-rs 目录运行。
set -euo pipefail

RS_DIR="$(cd "$(dirname "$0")" && pwd)"          # exchange-core-rs
REPO_DIR="$(cd "$RS_DIR/.." && pwd)"             # raft-exchange
MVN="${MVN:-mvn}"
SEED_BASE="${1:-$(date +%s)}"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/conformance_live.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "[live-diff] seed_base=$SEED_BASE  tmp=$TMP_DIR"

echo "[1/3] 生成新鲜随机向量 → 临时目录"
(cd "$RS_DIR" && cargo run --quiet --example gen_conformance_fuzz -- --out "$TMP_DIR" --seed "$SEED_BASE")

echo "[2/3] Java 导出器(oracle)对临时目录生成 golden"
(cd "$REPO_DIR" && "$MVN" -q -pl exchange-core -Dtest=ConformanceExporter \
    -Dconformance.vectors.dir="$TMP_DIR" -DfailIfNoTests=false test)

echo "[3/3] Rust replay 同一批 .stream,逐行断言 == .golden"
(cd "$RS_DIR" && CONFORMANCE_VECTORS_DIR="$TMP_DIR" cargo test --quiet --test conformance)

echo "[live-diff] PASS ✓  (seed_base=$SEED_BASE, $(ls "$TMP_DIR"/*.stream | wc -l | tr -d ' ') 个新鲜向量)"
