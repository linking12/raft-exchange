package exchange.core2.core.processors;

import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.tuple.primitive.LongObjectPair;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.ConcurrentHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;


public class GlobalADLService {

    /**
     * shardId -> symbol -> [(factor,position)...]
     * 用于“候选提示”
     */
    private final ConcurrentHashMap<Integer, IntObjectHashMap<MutableList<LongObjectPair<SymbolPositionRecord>>>> snapshots = new ConcurrentHashMap<>();

    /**
     * 强平扫描线程调用：整体替换某 shard 的仓位列表
     */
    public void updateShardSnapshot(int shardId, IntObjectHashMap<MutableList<LongObjectPair<SymbolPositionRecord>>> snapshot) {
        snapshots.put(shardId, snapshot);
    }

    /**
     * R1 调用：获取本 shard、某 symbol 下的候选仓位列表
     */
    public MutableList<LongObjectPair<SymbolPositionRecord>> getShardCandidates(int shardId, int symbol) {
        IntObjectHashMap<MutableList<LongObjectPair<SymbolPositionRecord>>> m = snapshots.get(shardId);
        return m == null ? FastList.newList() : m.getIfAbsent(symbol, FastList::new);
    }

    /**
     * | 63........32 | 31......12 | 11 | 10......0 |
     * |    symbol    |  uidHash   | s  |  tsPart   |
     */
    public static long generateADLOrderId(SymbolPositionRecord pos) {
        long uidHash = (pos.uid * 31 + 17) & 0xFFFFF; // 取前 20 bit
        long sideBit = (pos.direction == PositionDirection.SHORT) ? 1L : 0L;
        long tsPart = (System.currentTimeMillis() / 1000) & 0xFFF; // 取后11bit，支持2048秒 ≈ 34分钟内不重复
        return ((long) pos.symbol << 32) | (uidHash << 12) | (sideBit << 11) | tsPart;
    }

    public static long riskScore(SymbolPositionRecord pos, long bankruptcyPrice) {
        int sign = pos.direction.getMultiplier();
        long unrealizedPnl = sign * (bankruptcyPrice * pos.openVolume - pos.openPriceSum);
        return pos.getLeverage() * unrealizedPnl;
    }
}
