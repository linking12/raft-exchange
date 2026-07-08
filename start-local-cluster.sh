#!/bin/bash
# 本地启动 3 节点 raft-exchange 集群，注册到 QA Eureka
# 用法: ./start-local-cluster.sh [start|stop|status] [full|spot] [aeron|jraft]
#   full (默认): 启用 margin trading，支持现货 + 期货
#   spot:        关闭 margin trading，仅现货（RiskEngine 拒绝 FUTURES_* symbol，更轻量）
#   jraft (默认): 用 SOFA-JRaft 做 raft 共识
#   aeron:        用 Aeron Cluster 做 raft 共识
# 注意：两个 backend 共用 raft-exchange-server/target/RAFT-EXCHANGE-DATA/，切 backend 前需 stop + 清数据目录。

JAR="raft-exchange-server/target/raft-exchange-server-1.0.0-SNAPSHOT.jar"
APP_HOME_ROOT="$(cd "$(dirname "$JAR")" && pwd)"
PID_DIR="/tmp/raft-exchange-local"

mkdir -p "$PID_DIR"

LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')

http_port() { echo $((8079 + $1)); }
mgmt_port() { echo $((28080 + $1)); }
raft_port() { echo $((7790 + $1 * 10)); }  # Aeron 每节点占 basePort..basePort+5 共 6 个 UDP，需间距 ≥6；这里给 10 留缓冲
grpc_port() { echo $((5000 + $1)); }

STATE_FILE="$PID_DIR/cluster.state"

start_node() {
    local n=$1
    local margin_enabled=$2
    local consensus=$3
    local pid_file="$PID_DIR/node${n}.pid"
    local http=$(http_port $n) mgmt=$(mgmt_port $n) raft=$(raft_port $n) grpc=$(grpc_port $n)

    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "[node${n}] already running (pid=$(cat "$pid_file"))"
        return
    fi

    echo "[node${n}] starting — HTTP=$http mgmt=$mgmt raft=$raft grpc=$grpc margin=$margin_enabled consensus=$consensus"

    java \
        -Xmx1g -Xms512m \
        --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
        --add-opens java.base/java.nio=ALL-UNNAMED \
        --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
        --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
        -Dserver.port=$http \
        -Dmanagement.server.port=$mgmt \
        -Draft.port=$raft \
        -Dgrpc.port=$grpc \
        -Draftexchange.cluster.startupNodes=3 \
        -Dexchange.marginTrading.enabled=$margin_enabled \
        -Draftexchange.consensus=$consensus \
        -Draftexchange.kafka.enabled=${KAFKA_ENABLED:-false} \
        -Draftexchange.batch.enabled=${BATCH_ENABLED:-false} \
        -Draftexchange.snapshot.logIndexMargin=${SNAPSHOT_MARGIN:-10000000} \
        -jar "$JAR" \
        > "$PID_DIR/node${n}.log" 2>&1 &

    echo $! > "$pid_file"
    echo "[node${n}] pid=$!"
}

stop_node() {
    local n=$1
    local pid_file="$PID_DIR/node${n}.pid"
    if [ -f "$pid_file" ]; then
        local pid
        pid=$(cat "$pid_file")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid"
            # Aeron MediaDriver 的 close 偶尔卡住，graceful shutdown 拖死整个 JVM；等 5s 不退就 SIGKILL
            for _ in 1 2 3 4 5; do
                kill -0 "$pid" 2>/dev/null || break
                sleep 1
            done
            if kill -0 "$pid" 2>/dev/null; then
                kill -9 "$pid"
                echo "[node${n}] force-killed (pid=$pid)"
            else
                echo "[node${n}] stopped (pid=$pid)"
            fi
        else
            echo "[node${n}] not running"
        fi
        rm -f "$pid_file"
    else
        echo "[node${n}] no pid file found"
    fi
}

status_node() {
    local n=$1
    local pid_file="$PID_DIR/node${n}.pid"
    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "[node${n}] RUNNING pid=$(cat "$pid_file") HTTP=$(http_port $n) raft=$(raft_port $n) grpc=$(grpc_port $n)"
    else
        echo "[node${n}] STOPPED"
    fi
}

case "${1:-start}" in
    start)
        mode="${2:-full}"
        consensus="${3:-jraft}"
        case "$mode" in
            full) margin_enabled=true ;;
            spot) margin_enabled=false ;;
            *) echo "Unknown mode: $mode (expect full|spot)"; exit 1 ;;
        esac
        case "$consensus" in
            aeron|jraft) ;;
            *) echo "Unknown consensus: $consensus (expect aeron|jraft)"; exit 1 ;;
        esac
        {
            echo "mode=$mode"
            echo "consensus=$consensus"
        } > "$STATE_FILE"
        echo "[cluster] mode=$mode consensus=$consensus (margin $margin_enabled)"
        for n in 1 2 3; do start_node $n $margin_enabled $consensus; done
        echo ""
        echo "Logs: tail -f $APP_HOME_ROOT/logs/*.log"
        echo "Stop: $0 stop"
        ;;
    stop)
        for n in 1 2 3; do stop_node $n; done
        rm -f "$STATE_FILE"
        ;;
    status)
        if [ -f "$STATE_FILE" ]; then
            # shellcheck disable=SC1090
            source "$STATE_FILE"
            echo "[cluster] mode=$mode consensus=$consensus"
        fi
        for n in 1 2 3; do status_node $n; done
        ;;
    *)
        echo "Usage: $0 [start|stop|status] [full|spot] [aeron|jraft]"
        exit 1
        ;;
esac
