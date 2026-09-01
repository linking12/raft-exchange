# P2 参考：OrderBookDirectImpl 结构与算法（供 Rust slab/arena 移植）

> Java `orderbook/OrderBookDirectImpl.java`(1101 行)。Direct 必须与 `OrderBookNaiveImpl`（已移植为 Rust `OrderBookNaiveImpl`）**外部可观测结果逐位一致**（trade/reduce/reject 事件、L2、getOrderById、单数/量）。事件构造用共享的 `OrderBookEventsHelper`——Rust 侧复用已有 MatcherTradeEvent 字段规则即可。行号指 Java。

## 1. 数据结构
- **DirectOrder 节点**(`:960-1093`)：`orderId,price,size,filled,filledNotional,reserveBidPrice,action(ASK/BID),orderType,command,uid,timestamp,userCookie` + 三个指针 `parent(Bucket), next(向撮合前端/best), prev(远离前端/更差价或更晚 FIFO)`。Rust：slab of DirectOrder，`parent/next/prev` → `Option<BucketIdx>`/`Option<OrderIdx>`(slab 索引)。
- **Bucket**(`:1096-1100`)：`volume(该桶所有 order 剩余量之和), numOrders, tail(DirectOrder=桶内最新/最差 FIFO 的那个)`。价格不存 Bucket，隐含=`tail.price`+ART key。桶无自己的 next/prev——桶间衔接全靠全局单链的 `tail.prev`/`(桶最老 order).next`。
- **单侧一条双向链（核心不变式）**：每侧(ask/bid)**恰一条**双向链穿过该侧**所有** order，按(价格优先, 同价 FIFO)。`.next` 恒指向撮合前端(best)；best 的 `.next==null`。`.prev` 指向更差价/更晚。桶内沿 `.prev` 走 = 最老→最新(=FIFO 撮合序)，`bucket.tail`=最新。`bestAskOrder`/`bestBidOrder`(`:60-61`,可空)=各侧链头=全局最优价+同价最老，是真 order 非哨兵。撮合：从 best 起反复 `.prev`。L2：不走链，直接迭代 ART 桶图。
- **索引**：`askPriceBuckets`/`bidPriceBuckets` = ART `LongAdaptiveRadixTreeMap<Bucket>` 按价升序 → Rust `BTreeMap<i64,BucketIdx>`（`getLowerValue`/`getHigherValue`=前驱/后继，`forEach`/`forEachDesc`=升/降序）。ask "更优"=更低价(新桶插入查 `getLowerValue`)，bid "更优"=更高价(查 `getHigherValue`)。`orderIdIndex`=`LongAdaptiveRadixTreeMap<DirectOrder>` 按 orderId → Rust **`BTreeMap<i64,OrderIdx>`**（点查，用 BTreeMap 保持"禁 HashMap"不变式；迭代不影响输出）。
- **Rust 落地**：一个 `Vec<DirectOrder>`(+free-list) slab、一个 `Vec<Bucket>`(+free-list)、两个 `BTreeMap<i64,BucketIdx>`、一个 `BTreeMap<i64,OrderIdx>`；**弃对象池/日志**（纯 Java perf/观测）。

## 2. newOrder / 撮合
分派 `newOrder`(`:106-126`)：GTC→newOrderPlaceGtc(148)、IOC→newOrderMatchIoc(191)、FOK_BUDGET→newOrderMatchFokBudget(204)、IOC_BUDGET→newOrderMatchIocBudget(133)；不支持类型(裸 FOK)→整单 reject。

### 2.1 tryMatchInstantly(`:253-372`)（撮合核心，GTC/IOC/FOK_BUDGET/move 共用）
`long[] tryMatchInstantly(taker, triggerCmd)` 返回 `{filledSize, filledNotional}` 或 EMPTY。
1. `isBid = taker.action==BID`。
2. `limitPrice` = `taker.price`；**特例**：`PLACE_ORDER && FOK_BUDGET && ASK` 时 `limitPrice=0`（ASK FOK_BUDGET 无每单价上限）。**BID FOK_BUDGET 不特判**（见 §8 决策）。
3. `makerOrder = isBid ? bestAskOrder : bestBidOrder`；空或价越限(BID: maker.price>limit / ASK: <limit) → EMPTY(无事件无改动)。
4. `remainingSize = taker.size-taker.filled`；0→EMPTY。
5. `priceBucketTail = makerOrder.parent.tail`(首桶哨兵)。`takerReserveBidPrice = taker.reserveBidPrice`。
6. 循环(每次一个 maker，之后 `.prev`)：
   a. `tradeSize=min(remaining, maker.size-maker.filled)`；`tradePrice=maker.price`(**成交在 maker 价**)。
   b. 更新 taker 累计 + maker.filled/filledNotional。
   c. `maker.parent.volume -= tradeSize`(立即)。
   d. `makerCompleted = maker.size==maker.filled`；若是 `maker.parent.numOrders--`(发事件前)。
   e. `remaining -= tradeSize`(在发事件前，`:306`)；发 TRADE 事件 `sendTradeEvent(maker, makerCompleted, takerCompleted=(remaining==0), tradeSize, takerFilled, takerFilledNotional, bidderHoldPrice, spec)`。
   f. 追加到 `cmd.matcherEvent` 链(首次赋头，否则接尾)。
   g. `!makerCompleted` → break(maker 部分成交留簿，taker 已满)。
   h. makerCompleted：`orderIdIndex.remove(maker.id)`+释放 order（Rust：slab free-list，但**推迟复用**到循环后 best 指针/`next=null` 更新之后）。
   i. `maker==priceBucketTail`：从 ART 删该桶+释放 Bucket；`maker.prev!=null` 则 `priceBucketTail = maker.prev.parent.tail`。
   j. `maker = maker.prev`。while `maker!=null && remaining>0 && (isBid? price<=limit : price>=limit)`。
7. 循环后：`maker!=null` 则 `maker.next=null`(`:357`，它成新链头)。
8. `bestAsk/BidOrder = maker`(可空)。返回 `{takerFilled, takerFilledNotional}`。
- 要点：填满的 maker 在(h)+(i)当场从 index/pool/桶图摘除，中间节点的 `.prev/.next` 直接弃用(不再读)，唯一显式指针修复=循环后 `maker.next=null`+best 更新。

### 2.2 newOrderPlaceGtc(`:148-189`)
tryMatchInstantly；`filled==size` 全成→**不挂**；`orderIdIndex.get(id)!=null` 重复 id→reject 剩余(已发成交不回滚)；否则建 DirectOrder(filled=matched)、`orderIdIndex.put`、`insertOrder(order,null)`。

### 2.3 insertOrder(`:638-715`)（挂单入链+桶）
`(order, freeBucket)`，freeBucket 仅 moveOrder 传(复用已摘桶)。`toBucket = buckets.get(price)`：
- **A 桶已存在**(`:646-669`)：新 order 成桶 tail(FIFO 最新)：`oldTail=toBucket.tail; prev=oldTail.prev; toBucket.tail=order; oldTail.prev=order; prev.next=order(若非空); order.next=oldTail; order.prev=prev; order.parent=toBucket`；`volume+=remaining; numOrders++`。
- **B 无桶**(`:670-714`)：建/复用 Bucket(tail=order,volume=remaining,numOrders=1,order.parent=newBucket)，`buckets.put(price,newBucket)`。查邻桶 `neighbor = isAsk? getLowerValue(price) : getHigherValue(price)`(最近更优桶)：
  - 找到(`:683-694`)：插到该更优桶边界前：`lt=neighbor.tail; prev=lt.prev; lt.prev=order; prev.next=order(若非空); order.next=lt; order.prev=prev`（best 不动）。
  - 没找到(`:696-713`)：成新全局 best：`oldBest=bestAsk/Bid; oldBest.next=order(若非空); bestAsk/Bid=order; order.next=null; order.prev=oldBest`。

## 3. IOC / FOK_BUDGET / IOC_BUDGET
- **IOC**(`:191-202`)：tryMatchInstantly；未成(`size-filled`)→attachRejectEvent；**从不挂**。
- **FOK_BUDGET**(`:204-250`)：`checkBudgetToFill(action,size)` 从对侧链头**按桶粒度**走(`availableSize=bucket.volume`，`budget+=avail*price` 用 multiplyExact 防溢，`size<=avail` 时返回 `budget+size*price`；跳桶 `maker=bucket.tail.prev`；链尽未满返回 `Long.MAX_VALUE`)。`isBudgetLimitSatisfied`：BID iff `calc<=limit`，ASK iff `calc>=limit`。满足→调**同一** tryMatchInstantly、**之后不 reject**(靠"满足即全成"不变式)；不满足→**整单** reject。
- **IOC_BUDGET**(`:133-145,381-488`)：**仅 BID**(ASK 整单 reject+warn，同 Naive)。结构同 tryMatchInstantly，但 `limitPrice=taker.price` 恒；每次加 `affordableSize = tradePrice==0? MAX : remainingBudget/tradePrice`(整除)，`tradeSize=min(remaining, maker 剩余, affordable)`；`tradeSize==0`(预算不够 1 单位)→break；`remainingBudget -= tradeSize*tradePrice`。剩余→reject(**允许部分成交**、从不挂)。`remainingBudget` 初值=`cmd.price`(product-scale 总预算)。

## 4. cancel / reduce / move
- **cancelOrder**(`:491-512`)：查 id + `order.uid==cmd.uid` 否则 `MATCHING_UNKNOWN_ORDER_ID`；`orderIdIndex.remove`+释放；`removeOrder(order)`(§4.4)；`cmd.action=order.action`；`cmd.matcherEvent = sendReduceEvent(order, order.size-order.filled, completed=true, spec)`。
- **reduceOrder**(`:515-553`)：`req>0` 否则 `MATCHING_REDUCE_FAILED_WRONG_SIZE`；查 id+uid；`remaining=size-filled; reduceBy=min(remaining,req); canRemove=(reduceBy==remaining)`。canRemove→同 cancel 全删；否则(**部分减、留簿**)：`order.size -= reduceBy`(改 size 非 filled)、`order.parent.volume -= reduceBy`(不动 numOrders/链，FIFO 位置价格不变)。`sendReduceEvent(order, reduceBy, canRemove, spec)`；`cmd.action=order.action`。
- **moveOrder**(`:556-596`)：查 id+uid；现货风控：`type==CURRENCY_EXCHANGE_PAIR && BID && cmd.price>reserveBidPrice` → `MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT`(不改任何状态)；`freeBucket=removeOrder(order)`(摘链+旧桶)；`order.price=cmd.price`(原地改，复用同 slot)；`cmd.action=order.action`；**作为 taker** `tryMatchInstantly(order, cmd)` 于新价；`filled==size` 全成→`orderIdIndex.remove`+释放、返回 SUCCESS（不重挂）；否则 `order.filled=filled`、`insertOrder(order, freeBucket)`（同 order 对象重挂新价，orderIdIndex 不动）。= remove→新价撮合(taker)→(全成:丢弃)/(部分:重挂)。与 Naive move 结构同。
- **removeOrder**(`:599-635`)（cancel/reduce/move 共用摘链）：`bucket=order.parent; bucket.volume -= (size-filled); bucket.numOrders--`。若 `bucket.tail==order`：`order.next==null || order.next.parent!=bucket`(桶内仅剩它)→从 ART 删桶、返回该 Bucket(caller 释放)；否则 `bucket.tail=order.next`。通用摘链：`if order.next!=null order.next.prev=order.prev; if order.prev!=null order.prev.next=order.next`。best 修复：`if order==bestAsk bestAsk=order.prev; else if order==bestBid bestBid=order.prev`。返回摘掉的 Bucket 或 null。

## 5. L2（fillAsks/fillBids `:916-935`）
`fillAsks(size,data)`：`askPriceBuckets.forEach((price,bucket)→{ askPrices[i]=bucket.tail.price; askVolumes[i]=bucket.volume; askOrders[i]=bucket.numOrders }, size)` 升序(最优 ask 先)取前 size 档。`fillBids`：`bidPriceBuckets.forEachDesc(...)` 降序。**纯桶图迭代、不走链**。与 Naive fillAsks/fillBids 三值同序同截断→逐位一致。

## 6. 事件（用共享 OrderBookEventsHelper，Rust 复用 Naive 的字段规则）
- TRADE `sendTradeEvent`(`:52-95`)：`activeOrderCompleted=takerCompleted`；matched* 全取 **maker**(更新后值)；`price=maker.price`；`size=tradeSize`；`filled/filledNotional=taker 累计`；**bidderHoldPrice**=BID 那侧的 reserveBidPrice(taker=BID 用 taker，taker=ASK 用 maker)——同 Naive。
- REDUCE `sendReduceEvent`(`:97-116`)：`matchedOrderId=0, matchedOrderCompleted=false`；`price=order.price; size=reduceSize; filled=order.filled; bidderHoldPrice=order.reserveBidPrice; activeOrderCompleted=completed`。
- REJECT `attachRejectEvent`(`:119-144`)：`activeOrderCompleted=true, matchedOrderId=0`；`price=cmd.price; size=rejectedSize; bidderHoldPrice=cmd.reserveBidPrice`；**前插**(`event.next=cmd.matcherEvent; cmd.matcherEvent=event`)→部分成交时链序 `[REJECT, TRADE1, TRADE2,...]`（与 Naive 同）。

## 7. 不变式（validateInternalState `:738-849`——Rust 端的差分/属性测试 oracle）
1. best.next==null(非空时)。2. 从 best 沿 `.prev` 遍历访问该侧每个 order 恰一次、严格(价,时)序；`X.prev==Y ⟺ Y.next==X`。3. `order.parent.tail.price==order.price`。4. 跨桶边界价严格单调(ask 升/bid 降)、无同价相邻异桶。5. 在 `order==tail` 处 `bucket.volume==Σ(size-filled)` 且 `numOrders==count`。6. tail 边界的 `.prev` 价不同。7. 每价↔恰一 Bucket，ART 图与链可达桶 1:1。8. `orderIdIndex` 与两链并集 order 集**完全相同**(无孤儿)。9. 链内 order 的 action 一致。10. 各链最差 order 确是其桶 tail。**Rust 移一个等价 validate_internal_state 作为对拍/属性测试基石。**

## 8. 与 Naive 的行为差异（差分测试重点）
**外部结果不应有任何差异**（drop-in 等价）。一处微妙内部差异（不外露但靠非平凡不变式）：
- **FOK_BUDGET BID 的价界复用**：Naive 对 BID FOK_BUDGET 用**整张 askBuckets 无价上限**匹配；Direct 不特判 BID，`limitPrice=cmd.price`(总预算被当每单价上限用在 `maker.price<=limit`)。只因 `checkBudgetToFill` 已先证整量可成(`calc<=cmd.price`)、单价 `price_i<=calc<=cmd.price`(qty>=1)才恰好等价。**Rust 决策（Ruling）：不复刻这个巧合——FOK_BUDGET 撮合镜像 Naive、不加价上限**（最简最稳）。专测：BID FOK_BUDGET 小总预算 vs 高价 ask（总预算 500、ask 价 1000+ 但可成量≥1），确认 Direct/Naive 一致。
- 对象池/日志：弃(纯 Java perf/观测)，只保留 order/bucket 逻辑死点(alloc/free 时机=上面标注处)。
- ART vs TreeMap → Rust BTreeMap 等价。
- checkBudgetToFill 的桶级跳与 Naive 桶迭代同粒度、同结果。
其余 newOrder*/cancel/reduce/move 与 Naive 结构逐行平行、事件参数/结果码同。

## 9. 构造签名（Rust 只需 spec）
Java 构造收 `(CoreSymbolSpecification, ObjectsPool, OrderBookEventsHelper, LoggingConfiguration)`。**只有 `symbolSpec` 有功能依赖**（唯一读处：moveOrder 的 `type==CURRENCY_EXCHANGE_PAIR` 风控 `:565`；+ getSymbolSpec 访问器）。`objectsPool`/`loggingCfg` 弃。`eventsHelper` 的**逻辑**(§6)保留、对象/池弃。
