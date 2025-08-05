/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core.common;

import exchange.core2.core.utils.HashingUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Objects;

@Slf4j
public final class UserProfile implements WriteBytesMarshallable, StateHash {

    public final long uid;

    // 用户持仓模式，默认是单向持仓
    public final PositionMode positionMode;

    // symbol -> margin position records
    // 如果是双向持仓，symbol为正表示多头持仓，symbol为负表示空头持仓
    public final IntObjectHashMap<SymbolPositionRecord> positions;

    // protects from double adjustment
    public long adjustmentsCounter;

    // currency accounts
    // currency -> balance
    public final IntLongHashMap accounts;

    public UserStatus userStatus;

    public UserProfile(long uid, UserStatus userStatus) {
        //log.debug("New {}", uid);
        this.uid = uid;
        this.positionMode = PositionMode.ONEWAY;
        this.positions = new IntObjectHashMap<>();
        this.adjustmentsCounter = 0L;
        this.accounts = new IntLongHashMap();
        this.userStatus = userStatus;
    }

    public UserProfile(BytesIn bytesIn) {

        this.uid = bytesIn.readLong();

        // positionMode
        this.positionMode = PositionMode.of(bytesIn.readByte());

        // positions
        this.positions = SerializationUtils.readIntHashMap(bytesIn, b -> new SymbolPositionRecord(uid, b));

        // adjustmentsCounter
        this.adjustmentsCounter = bytesIn.readLong();

        // account balances
        this.accounts = SerializationUtils.readIntLongHashMap(bytesIn);

        // suspended
        this.userStatus = UserStatus.of(bytesIn.readByte());
    }

    public int getPositionRecordKey(int symbol, OrderAction orderAction) {
        if (positionMode == PositionMode.HEDGE) {
            return orderAction == OrderAction.BID ? symbol : -symbol;
        }
        return symbol;
    }

    public int getPositionRecordKey(SymbolPositionRecord position) {
        if (positionMode == PositionMode.HEDGE) {
            return position.direction.getMultiplier() * position.symbol;
        }
        return position.symbol;
    }

    public SymbolPositionRecord getPositionRecordOrThrowEx(int key) {
        final SymbolPositionRecord record = positions.get(key);
        if (record == null) {
            throw new IllegalStateException("not found position for key " + key);
        }
        return record;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {

        bytes.writeLong(uid);

        // positionMode
        bytes.writeByte(positionMode.getCode());

        // positions
        SerializationUtils.marshallIntHashMap(positions, bytes);

        // adjustmentsCounter
        bytes.writeLong(adjustmentsCounter);

        // account balances
        SerializationUtils.marshallIntLongHashMap(accounts, bytes);

        // suspended
        bytes.writeByte(userStatus.getCode());
    }


    @Override
    public String toString() {
        return "UserProfile{" +
                "uid=" + uid +
                ", positionMode=" + positionMode +
                ", positions=" + positions.size() +
                ", accounts=" + accounts +
                ", adjustmentsCounter=" + adjustmentsCounter +
                ", userStatus=" + userStatus +
                '}';
    }

    @Override
    public int stateHash() {
        return Objects.hash(
                uid,
                positionMode,
                HashingUtils.stateHash(positions),
                adjustmentsCounter,
                accounts.hashCode(),
                userStatus.hashCode());
    }
}
