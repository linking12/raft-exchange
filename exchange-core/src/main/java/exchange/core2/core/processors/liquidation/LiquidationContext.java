package exchange.core2.core.processors.liquidation;

import java.util.Objects;

import exchange.core2.core.common.StateHash;
import exchange.core2.core.utils.HashingUtils;
import lombok.Getter;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 强平流程进行中的状态。仅 active 阶段持有；闭环时 {@code SymbolPositionRecord#liquidationCtx} 置 null。
 * <p>
 * 进 raft snapshot：随 {@link exchange.core2.core.common.SymbolPositionRecord} 一起序列化，
 * leader failover 时跟 snapshot 复制到新 leader，强平流程可在新 leader 上接续推进。
 */
@ToString
public final class LiquidationContext implements WriteBytesMarshallable, StateHash {

    public LiquidationState state;
    public long price;
    public long size;
    /** 原 FORCE_LIQUIDATION 的 orderId，stuck recovery 重发 IF/ADL 时派生它们的 orderId。 */
    public long originalOrderId;
    /** 上次 state 推进时的 cmd.timestamp——非 wall clock，保跨节点确定性。 */
    public long lastTransitionAt;

    public LiquidationContext(long price, long size, long originalOrderId, long lastTransitionAt) {
        this.state = LiquidationState.LIQUIDATING;
        this.price = price;
        this.size = size;
        this.originalOrderId = originalOrderId;
        this.lastTransitionAt = lastTransitionAt;
    }

    public LiquidationContext(BytesIn bytes) {
        this.state = LiquidationState.of(bytes.readByte());
        this.price = bytes.readLong();
        this.size = bytes.readLong();
        this.originalOrderId = bytes.readLong();
        this.lastTransitionAt = bytes.readLong();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeByte(state.code);
        bytes.writeLong(price);
        bytes.writeLong(size);
        bytes.writeLong(originalOrderId);
        bytes.writeLong(lastTransitionAt);
    }

    @Override
    public int stateHash() {
        return Objects.hash(HashingUtils.enumStateHash(state), price, size, originalOrderId, lastTransitionAt);
    }

    public enum LiquidationState {
        LIQUIDATING((byte) 1),
        WAIT_IF_EXECUTION((byte) 2),
        WAIT_ADL_EXECUTION((byte) 3);

        @Getter
        private final byte code;

        LiquidationState(byte code) {
            this.code = code;
        }

        public static LiquidationState of(byte code) {
            for (LiquidationState s : values()) {
                if (s.code == code)
                    return s;
            }
            throw new IllegalArgumentException("Unknown LiquidationState code: " + code);
        }
    }
}
