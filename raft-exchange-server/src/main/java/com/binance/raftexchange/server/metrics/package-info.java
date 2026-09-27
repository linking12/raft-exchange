/**
 * raft-exchange Micrometer 指标定义。所有 metric 统一前缀 {@code raft.exchange.*}，按子域分嵌套类托管在 {@link
 * com.binance.raftexchange.server.metrics.RaftExchangeMetrics}。
 *
 * <h2>子域索引</h2>
 * <table>
 *   <caption>RaftExchangeMetrics 子域</caption>
 *   <tr><th>子域</th><th>负责</th><th>typical owner</th></tr>
 *   <tr><td>{@link com.binance.raftexchange.server.metrics.RaftExchangeMetrics.Snapshot}</td>
 *       <td>snapshot save / load / 完整性兜底</td><td>state machine（aeron/jraft）+ SnapshotHelper</td></tr>
 *   <tr><td>{@link com.binance.raftexchange.server.metrics.RaftExchangeMetrics.Raft}</td>
 *       <td>raft 节点状态、apply 时延、pending、stepDown、leader 切换</td><td>cluster container + state machine</td></tr>
 *   <tr><td>{@link com.binance.raftexchange.server.metrics.RaftExchangeMetrics.Grpc}</td>
 *       <td>gRPC 入口 QPS / latency / inflight / 撮合时延</td><td>UniversalInterceptor</td></tr>
 *   <tr><td>{@link com.binance.raftexchange.server.metrics.RaftExchangeMetrics.ReadBarrier}</td>
 *       <td>QueryService 进 engine 前的 ReadIndex barrier</td><td>JraftClusterContainer（aeron no-op）</td></tr>
 *   <tr><td>{@link com.binance.raftexchange.server.metrics.RaftExchangeMetrics.Sidecar}</td>
 *       <td>aeron snapshot 跨节点传输（gRPC fetch/serve）</td><td>SnapshotTransferService + SnapshotFetcherImpl</td></tr>
 *   <tr><td>{@link com.binance.raftexchange.server.metrics.RaftExchangeMetrics.Kafka}</td>
 *       <td>事件下游 kafka 投递成功/失败/lag</td><td>KafkaEventQueue</td></tr>
 * </table>
 *
 * <h2>命名规则</h2>
 * <ul>
 *   <li>前缀 {@code raft.exchange.<子域>.<指标>}</li>
 *   <li>后缀语义：
 *     {@code .count} = monotonic Counter（累计）；
 *     {@code .duration} / {@code .latency} = Timer（histogram + percentiles）；
 *     {@code .size} / {@code .bytes} = DistributionSummary（分布）；
 *     {@code .last_*_epoch_sec} = Gauge（point-in-time 时间戳）；
 *     无后缀 = Gauge / 状态值</li>
 *   <li>成功/失败用 tag {@code status=success|failure}（如 stepdown / snapshot.save / sidecar.fetch）</li>
 *   <li>per-target 维度用 tag（如 kafka.send 的 {@code topic_group}、session.close 的 {@code reason}）</li>
 * </ul>
 *
 * <h2>Backend 适用</h2>
 * <p>同进程只跑一种 container（jraft 或 aeron），所以 metric 名全局唯一。<b>container</b> 列标 {@code both} 表示两 container 都有埋点；
 * {@code aeron-only} / {@code jraft-only} 表示另一 container 上恒为 0（其底层语义不存在）。</p>
 *
 * <h2>Snapshot 子域指标</h2>
 *
 * <p>Snapshot save / load 是 raft 节点健康的唯一长周期信号源（生产 1h 周期 + 1GB log 增长门槛触发）。</p>
 *
 * <table>
 *   <caption>Snapshot 指标</caption>
 *   <tr><th>名称</th><th>类型</th><th>含义</th><th>触发位置</th><th>container</th></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.save.duration}</td>
 *       <td>Timer (p50/p99)</td>
 *       <td>单次 snapshot 落盘耗时——从 engine 状态 dump 触发 → 文件全部写出 → marker 落 raft log。
 *           典型生产 100ms–几秒（取决于 state 体积）</td>
 *       <td>aeron: {@code AeronExchangeStateMachine.onTakeSnapshot}；jraft: {@code JraftExchangeStateMachine.onSnapshotSave}</td>
 *       <td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.save.count}{@code {status=success}}</td>
 *       <td>Counter</td>
 *       <td>累计成功 snapshot 次数。配合 {@code last_success_epoch_sec} 用于"距上次成功多久"告警</td>
 *       <td>同上 save 成功路径</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.save.count}{@code {status=failure}}</td>
 *       <td>Counter</td>
 *       <td>累计失败 snapshot 次数。aeron 包括 marker offer 失败 / engine dump 异常；jraft 包括 addFile 失败 / IOException</td>
 *       <td>同上 catch 块</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.save.size.bytes}</td>
 *       <td>DistributionSummary</td>
 *       <td>单次成功 snapshot 的总字节数（所有 shard 文件加和）。趋势用于容量规划与磁盘扩容预测</td>
 *       <td>save 成功路径，{@code totalSizeBytes} 统计</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.save.last_success_epoch_sec}</td>
 *       <td>Gauge</td>
 *       <td>上次成功 save 的 Unix epoch 秒数。{@code now - x > 4h} 视为异常（snapshot 周期 1h）</td>
 *       <td>save 成功时 set 当前时间</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.load.duration}</td>
 *       <td>Timer (p50/p99)</td>
 *       <td>单次 snapshot 加载耗时——从读 marker → fetch（若需）→ engine 恢复完成。重启场景必触发</td>
 *       <td>aeron: {@code recoverFromSnapshot}；jraft: {@code onSnapshotLoad}</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.load.count}{@code {status=success}}</td>
 *       <td>Counter</td>
 *       <td>累计成功 load 次数。每次节点重启 / 跨节点 InstallSnapshot 触发</td>
 *       <td>load 成功路径</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.load.count}{@code {status=failure}}</td>
 *       <td>Counter</td>
 *       <td>累计失败 load 次数。失败通常伴随 {@code recover.halt}（进程 halt 137）</td>
 *       <td>load catch 块</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.load.last_success_epoch_sec}</td>
 *       <td>Gauge</td>
 *       <td>上次成功 load 的 Unix epoch 秒数。生产期望仅在重启或新节点加入时变化</td>
 *       <td>load 成功时 set</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.cleanup.failure.count}</td>
 *       <td>Counter</td>
 *       <td>清理旧 snapshot 文件失败次数（删除残留 shard / 老版本目录）。磁盘异常 / 权限错的早期信号</td>
 *       <td>{@code SnapshotHelper.cleanSnapshots} IOException 路径</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.size_probe.failure.count}</td>
 *       <td>Counter</td>
 *       <td>{@code Files.size(snapshot_file)} 探测失败次数。文件已被改名 / 删除 / I/O 错的早期信号</td>
 *       <td>{@code totalSizeBytes} 内 catch IOException</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.recover.halt.count}</td>
 *       <td>Counter</td>
 *       <td>snapshot 加载失败 → {@code halt(137)} 触发次数。<b>理论恒为 0</b>；非零说明至少一次进程 halt，必查</td>
 *       <td>load failure 后 halt 前 record</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.snapshot.marker_offer.failure.count}</td>
 *       <td>Counter</td>
 *       <td>aeron snapshot publication 持续 BACK_PRESSURED 超过 30s deadline。snapshot publication 反压定位</td>
 *       <td>{@code onTakeSnapshot} 内 offer loop 超时</td><td>aeron-only</td></tr>
 * </table>
 *
 * <h2>Raft 子域指标</h2>
 *
 * <p>Raft 节点的核心运行时状态：role / index / apply / leader 切换 / stepDown / aeron pending。</p>
 *
 * <table>
 *   <caption>Raft 指标</caption>
 *   <tr><th>名称</th><th>类型</th><th>含义</th><th>触发位置</th><th>container</th></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.role}</td>
 *       <td>Gauge (0/1)</td>
 *       <td>当前节点 role：{@code 1}=leader，{@code 0}=follower。集群每秒应只有一个节点为 1（多 leader = 脑裂）</td>
 *       <td>{@code register()} 时通过 supplier 暴露</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.committed_index}</td>
 *       <td>Gauge</td>
 *       <td>已被多数派确认的 raft log 位置：jraft 是 entry index（单调递增），aeron 是字节级 logPosition</td>
 *       <td>{@code register()} supplier</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.applied_index}</td>
 *       <td>Gauge</td>
 *       <td>已经过 state machine apply 的位置。落后于 committed_index 是常态，差值越大说明 apply 越慢</td>
 *       <td>{@code register()} supplier</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.replication_lag}</td>
 *       <td>Gauge</td>
 *       <td>{@code max(0, committed_index - applied_index)}，apply 滞后量。持续 &gt; 1000 报警</td>
 *       <td>{@code register()} 内自动派生</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.leader_change.count}</td>
 *       <td>Counter</td>
 *       <td>累计本节点 role 转换次数（follower↔leader）。10min &gt; 3 次 = 心跳 / 网络抖动</td>
 *       <td>aeron: {@code onRoleChange} 前后状态对比；jraft: {@code onLeaderStart/Stop}</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.leader_change.last_epoch_sec}</td>
 *       <td>Gauge</td>
 *       <td>本节点上次 role 切换的 Unix epoch 秒数。0 = 启动后未发生过切换</td>
 *       <td>同 leader_change 时机 set 当前时间</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.apply.latency}</td>
 *       <td>Timer (p50/p99, 10µs–3s)</td>
 *       <td>engine apply 时延：<br>
 *           aeron = 从 {@code onSessionMessage} 接收 → engine 处理完 / response 准备好（端到端含 raft 共识 + batch 等待 + engine 处理）<br>
 *           jraft = {@code onApply} 入口 → 出口（batch 内所有 entry 处理 + flush submit）<br>
 *           P99 &gt; 10ms 报警</td>
 *       <td>aeron: {@code fillPending}；jraft: {@code onApply} 末尾</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.apply.batch.size}</td>
 *       <td>DistributionSummary (p50/p99)</td>
 *       <td>一次 batch flush 包含的命令数。aeron 容量 128，jraft 容量 1024。P99 接近上限 = 反压，建议扩容</td>
 *       <td>{@code flushBatch} / {@code flushApplyBatch} 开头</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.submit_batch.failure.count}</td>
 *       <td>Counter</td>
 *       <td>{@code submitBatchAsync} 抛异常次数（engine 入栈失败）。<b>数据正确性兜底指标</b>，非零必查</td>
 *       <td>{@code flushBatch} / {@code flushApplyBatch} catch 块</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.submit_batch.failure.size}</td>
 *       <td>DistributionSummary</td>
 *       <td>失败时丢的 batch 大小分布。配合 failure.count 评估失败的"杀伤力"</td>
 *       <td>同上 catch 块内</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.unsupported_command.count}</td>
 *       <td>Counter</td>
 *       <td>state machine 收到无法转换为 engine 命令的 log entry 次数。灰度 / 版本兼容性 / 攻击注入信号</td>
 *       <td>aeron: dispatch 内 {@code engineCmd == null}；jraft: {@code applyNonBatchable} 非 ApiCommand 分支 / envelope 子命令不可转</td>
 *       <td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.pending.size}</td>
 *       <td>Gauge</td>
 *       <td>当前 pending response map 大小（aeron 收到但还没发回 client 的命令数）。容量 65536；持续接近上限 = 反压</td>
 *       <td>{@code registerPendingSizeSupplier} 暴露 supplier</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.pending.reject.count}</td>
 *       <td>Counter</td>
 *       <td>因 pending map 满（≥ 65536）而拒单的次数。非零即反压，需扩容或限流</td>
 *       <td>{@code onSessionMessage} 满载分支</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.drain.cleared}</td>
 *       <td>DistributionSummary</td>
 *       <td>每次 drain timer tick（100ms 周期）从 pending 清出去的 entry 数。趋势反映回应吞吐</td>
 *       <td>{@code onTimerEvent} 内 {@code drainPending()} 返回值</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.session.open.count}</td>
 *       <td>Counter</td>
 *       <td>新 client session 建立次数（{@code AeronCluster.onSessionOpen}）。客户端连接稳定性观察</td>
 *       <td>{@code onSessionOpen}</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.session.close.count}{@code {reason=*}}</td>
 *       <td>Counter</td>
 *       <td>client session 关闭次数，按 {@code CloseReason} 分 tag（{@code USER_ACTION} / {@code SERVICE_ACTION} /
 *           {@code TIMEOUT} 等）。{@code TIMEOUT} 飙升 = 客户端网络异常</td>
 *       <td>{@code onSessionClose}</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.stepdown.count}{@code {status=success}}</td>
 *       <td>Counter</td>
 *       <td>主动 stepDown 成功次数。运维触发 leader 转移 / 滚动重启场景</td>
 *       <td>{@code stepDownLeadership} 成功路径</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.stepdown.count}{@code {status=failure}}</td>
 *       <td>Counter</td>
 *       <td>stepDown 失败次数。aeron 失败会 halt(1)；jraft 失败 transferLeadership Status not OK</td>
 *       <td>{@code stepDownLeadership} 失败路径</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.stepdown.duration}</td>
 *       <td>Timer (p50/p99)</td>
 *       <td>stepDown 全程耗时。aeron 含 snapshot + 关 consensus + 等 3s + relaunch（典型秒级）；
 *           jraft 是 {@code transferLeadershipTo} sync 返回（典型 ms 级）</td>
 *       <td>{@code stepDownLeadership} 起止</td><td>both</td></tr>
 * </table>
 *
 * <h2>Grpc 子域指标</h2>
 *
 * <p>gRPC 入口的请求量 / 延迟 / 并发 / 内部撮合时延（matching latency 桶为 µs 级，grpc/raft 为 ms 级）。</p>
 *
 * <table>
 *   <caption>Grpc 指标</caption>
 *   <tr><th>名称</th><th>类型</th><th>含义</th><th>触发位置</th><th>container</th></tr>
 *
 *   <tr><td>{@code raft.exchange.grpc.server.counter}</td>
 *       <td>Counter</td>
 *       <td>gRPC 服务端累计请求数（所有 method 合计）。QPS = rate()</td>
 *       <td>{@code UniversalInterceptor.serverCall} 入口</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.grpc.latency}</td>
 *       <td>Timer (p99, 1ms–3s)</td>
 *       <td>gRPC 端到端 latency：从 server interceptor 收到 request → response 写回 socket</td>
 *       <td>{@code UniversalInterceptor.serverCall} 起止</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.raft.latency}</td>
 *       <td>Timer (p99, 1ms–3s)</td>
 *       <td>gRPC handler 视角下，raft 一致性写所占耗时（{@code requestConsensus} 起止）。<b>不是</b> Raft 子域，是 Grpc 子域</td>
 *       <td>{@code UniversalInterceptor} 内 raft handler return path</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.matching.latency}</td>
 *       <td>Timer (p99, 10µs–100ms)</td>
 *       <td>撮合 engine 处理单条命令的时延。撮合内核性能水位</td>
 *       <td>{@code UniversalInterceptor} 内 engine handler return path</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.grpc.inflight}</td>
 *       <td>Gauge (LongAdder)</td>
 *       <td>gRPC 当前 inflight 请求数（含尚未 response 的）。持续接近 inflight 限制 = 客户端反压</td>
 *       <td>{@code inflightAcquire} / {@code inflightRelease}</td><td>both</td></tr>
 * </table>
 *
 * <h2>ReadBarrier 子域指标</h2>
 *
 * <p>read 操作（query）进入 engine 前，需要 raft 一致性 barrier 保证已收齐 commit。jraft 走 ReadIndex（产生真实指标），
 * aeron 无对应概念（barrier 为 no-op，本子域恒为 0）。</p>
 *
 * <table>
 *   <caption>ReadBarrier 指标</caption>
 *   <tr><th>名称</th><th>类型</th><th>含义</th><th>触发位置</th><th>container</th></tr>
 *
 *   <tr><td>{@code raft.exchange.read.barrier.duration}</td>
 *       <td>Timer (p50/p99, 100µs–1s)</td>
 *       <td>ReadIndex barrier 完成时延。p99 &gt; 50ms = raft 心跳异常</td>
 *       <td>{@code readConsistencyBarrier} closure 回调</td><td>jraft-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.read.barrier.count}{@code {status=success}}</td>
 *       <td>Counter</td>
 *       <td>累计成功 barrier 次数</td>
 *       <td>同上 success</td><td>jraft-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.read.barrier.count}{@code {status=failure}}</td>
 *       <td>Counter</td>
 *       <td>累计失败 barrier 次数（leader 丢失 / status not OK）</td>
 *       <td>同上 failure</td><td>jraft-only</td></tr>
 * </table>
 *
 * <h2>Sidecar 子域指标</h2>
 *
 * <p>aeron 跨节点 snapshot 传输的 gRPC 通道：{@code fetch}=本节点向 peer 拉 snapshot；{@code serve}=本节点对 peer 提供 snapshot。
 * 都是兜底路径（snapshot 应主要靠 InstallSnapshot 走 raft 通道），非零应 alert。</p>
 *
 * <table>
 *   <caption>Sidecar 指标</caption>
 *   <tr><th>名称</th><th>类型</th><th>含义</th><th>触发位置</th><th>container</th></tr>
 *
 *   <tr><td>{@code raft.exchange.sidecar.fetch.duration}</td>
 *       <td>Timer (p50/p99)</td>
 *       <td>本节点 fetch peer snapshot 全程耗时（含网络传输）</td>
 *       <td>{@code SnapshotFetcherImpl.fetch} 起止</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.sidecar.fetch.count}{@code {status=success|failure}}</td>
 *       <td>Counter</td>
 *       <td>本节点拉取 snapshot 成功/失败次数</td>
 *       <td>{@code SnapshotFetcherImpl} 内 try/catch</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.sidecar.fetch.bytes}</td>
 *       <td>DistributionSummary</td>
 *       <td>单次成功 fetch 的字节数</td>
 *       <td>fetch 成功路径</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.sidecar.serve.count}{@code {status=success|failure}}</td>
 *       <td>Counter</td>
 *       <td>本节点对 peer serve snapshot 成功/失败次数</td>
 *       <td>{@code SnapshotTransferService.serve}</td><td>aeron-only</td></tr>
 *
 *   <tr><td>{@code raft.exchange.sidecar.serve.bytes}</td>
 *       <td>DistributionSummary</td>
 *       <td>单次成功 serve 的字节数</td>
 *       <td>serve 成功路径</td><td>aeron-only</td></tr>
 * </table>
 *
 * <h2>Kafka 子域指标</h2>
 *
 * <p>事件下游 kafka 投递。Counter / Timer 按 {@code topic_group} lazy 注册；启动期 eager 注册全 group 避免 dashboard 看不到。</p>
 *
 * <table>
 *   <caption>Kafka 指标</caption>
 *   <tr><th>名称</th><th>类型</th><th>含义</th><th>触发位置</th><th>container</th></tr>
 *
 *   <tr><td>{@code raft.exchange.kafka.send}{@code {topic_group, status=success|failure}}</td>
 *       <td>Counter</td>
 *       <td>每个 topic_group 的累计投递成功/失败次数。失败率持续 &gt; 0.1% 报警</td>
 *       <td>{@code KafkaEventQueue} 内 send 回调</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.kafka.send.latency}{@code {topic_group}}</td>
 *       <td>Timer (p99)</td>
 *       <td>每个 topic_group 的发送延迟</td>
 *       <td>同上 success 路径</td><td>both</td></tr>
 *
 *   <tr><td>{@code raft.exchange.kafka.queue.backlog}{@code {raft_cluster}}</td>
 *       <td>Gauge</td>
 *       <td>每个 raft_cluster 的 kafka 待投递队列长度。持续 &gt; 10k 报警</td>
 *       <td>{@code registerBacklogGauge} 暴露 supplier</td><td>both</td></tr>
 * </table>
 *
 * <h2>报警优先级速查</h2>
 * <ol>
 *   <li><b>P0 必查</b>：{@code submit_batch.failure.count} / {@code snapshot.recover.halt.count} —— 非零代表数据正确性受损或进程已 halt</li>
 *   <li><b>P0 时效</b>：{@code snapshot.save.last_success_epoch_sec} 距离 now &gt; 4h —— snapshot 体系完全停摆</li>
 *   <li><b>P1</b>：{@code replication_lag} 持续 &gt; 1000 / {@code apply.latency} P99 &gt; 10ms / {@code leader_change.count} 10min &gt; 3 次</li>
 *   <li><b>P1</b>：{@code stepdown.count{status=failure}} / {@code pending.reject.count} 非零</li>
 *   <li><b>P2</b>：{@code kafka.queue.backlog} 持续 &gt; 10k / {@code kafka.send} 失败率 &gt; 0.1%</li>
 *   <li><b>容量观察</b>：{@code apply.batch.size} P99 接近上限 / {@code snapshot.save.size.bytes} 增长趋势 / {@code grpc.inflight} 峰值</li>
 * </ol>
 *
 * <h2>预热与发现</h2>
 * <p>{@link com.binance.raftexchange.server.metrics.RaftExchangeMetrics#prewarmAll()} 在 {@code RaftExchangeApplication}
 * 启动时调用，触发各子域 static field 初始化，让 actuator / Prometheus 抓取时即可见到 gauge 初值。Kafka 子域因为 topic_group
 * 是运行时发现的，由 {@code KafkaEventQueue} 在 wire 时调
 * {@link com.binance.raftexchange.server.metrics.RaftExchangeMetrics.Kafka#prewarm(java.lang.Iterable)} eager 注册。</p>
 *
 * <h2>新增 metric 流程</h2>
 * <ol>
 *   <li>选子域 / 必要时新建嵌套 class</li>
 *   <li>按命名规则建 Counter/Timer/Gauge 字段 + record 方法 + getter for test</li>
 *   <li>在 <b>本 package-info 对应子域表</b>新增一行：名称 / 类型 / 含义 / 触发位置 / container</li>
 *   <li>报警级别若 P0/P1，同步更新报警优先级速查段</li>
 *   <li>同步 dashboard / runbook</li>
 * </ol>
 */
package com.binance.raftexchange.server.metrics;
