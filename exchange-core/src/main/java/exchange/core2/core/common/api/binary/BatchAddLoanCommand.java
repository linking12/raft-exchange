/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package exchange.core2.core.common.api.binary;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;

@Getter
@EqualsAndHashCode
@ToString
public final class BatchAddLoanCommand implements BinaryDataCommand {
    static final int BPS_FULL = 10_000;
    private final GlobalLoanConfig global;
    private final SymbolLoanConfig symbol;

    public BatchAddLoanCommand(GlobalLoanConfig global, SymbolLoanConfig symbol) {
        if (global == null && symbol == null) {
            throw new IllegalArgumentException("BatchAddLoanCommand needs at least one of global/symbol");
        }
        this.global = global;
        this.symbol = symbol;
    }

    public BatchAddLoanCommand(BytesIn bytes) {
        this.global = bytes.readByte() != 0 ? new GlobalLoanConfig(bytes) : null;
        this.symbol = bytes.readByte() != 0 ? new SymbolLoanConfig(bytes) : null;
    }

    public boolean hasGlobal() {
        return global != null;
    }

    public boolean hasSymbol() {
        return symbol != null;
    }

    public static BatchAddLoanCommand ofGlobal(int numeraireCurrency, int crossLiquidationLtvBps,
        int crossMarginCallLtvBps, int loanPoolUtilizationCapBps, int loanLiquidationFeeBps) {
        return new BatchAddLoanCommand(new GlobalLoanConfig(numeraireCurrency, crossLiquidationLtvBps,
            crossMarginCallLtvBps, loanPoolUtilizationCapBps, loanLiquidationFeeBps), null);
    }

    public static BatchAddLoanCommand ofGlobalNumeraire(int numeraireCurrency) {
        return ofGlobal(numeraireCurrency, 0, 0, 0, 0);
    }

    public static BatchAddLoanCommand ofSymbol(int symbolId, int loanInitialLtvBps, int loanLiquidationLtvBps,
        int loanMarginCallLtvBps, long loanMaxAmount, int loanMaxTermDays, int collateralWeightBps) {
        return new BatchAddLoanCommand(null, new SymbolLoanConfig(symbolId, loanInitialLtvBps, loanLiquidationLtvBps,
            loanMarginCallLtvBps, loanMaxAmount, loanMaxTermDays, collateralWeightBps));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeByte((byte)(global != null ? 1 : 0));
        if (global != null) {
            global.write(bytes);
        }
        bytes.writeByte((byte)(symbol != null ? 1 : 0));
        if (symbol != null) {
            symbol.write(bytes);
        }
    }

    @Override
    public int getBinaryCommandTypeCode() {
        return BinaryCommandType.ADD_LOAN.getCode();
    }

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    @ToString
    public static final class GlobalLoanConfig {

        private final int numeraireCurrency; // Cross 估值基准币（需 currencySpec 存在，RiskEngine 另行校验）
        private final int crossLiquidationLtvBps; // Cross 账户级强平线（bps）
        private final int crossMarginCallLtvBps; // Cross 账户级预警线（bps）
        private final int loanPoolUtilizationCapBps; // 借贷池利用率上限（bps）
        private final int loanLiquidationFeeBps; // 强平专项费率（bps）

        GlobalLoanConfig(BytesIn bytes) {
            this.numeraireCurrency = bytes.readInt();
            this.crossLiquidationLtvBps = bytes.readInt();
            this.crossMarginCallLtvBps = bytes.readInt();
            this.loanPoolUtilizationCapBps = bytes.readInt();
            this.loanLiquidationFeeBps = bytes.readInt();
        }

        void write(BytesOut bytes) {
            bytes.writeInt(numeraireCurrency);
            bytes.writeInt(crossLiquidationLtvBps);
            bytes.writeInt(crossMarginCallLtvBps);
            bytes.writeInt(loanPoolUtilizationCapBps);
            bytes.writeInt(loanLiquidationFeeBps);
        }

        public boolean thresholdsValidGivenCurrent(int currentCrossLiquidationLtvBps,
            int currentCrossMarginCallLtvBps) {
            final int effLiquidation =
                crossLiquidationLtvBps > 0 ? crossLiquidationLtvBps : currentCrossLiquidationLtvBps;
            final int effMarginCall = crossMarginCallLtvBps > 0 ? crossMarginCallLtvBps : currentCrossMarginCallLtvBps;
            return effMarginCall > 0 && effMarginCall < effLiquidation && effLiquidation < BPS_FULL
                && (loanPoolUtilizationCapBps <= 0 || loanPoolUtilizationCapBps <= BPS_FULL)
                && (loanLiquidationFeeBps <= 0 || loanLiquidationFeeBps < BPS_FULL);
        }
    }

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    @ToString
    public static final class SymbolLoanConfig {

        private final int symbolId;
        private final int loanInitialLtvBps; // 0 = 关闭 loan
        private final int loanLiquidationLtvBps;
        private final int loanMarginCallLtvBps; // 0 = 关预警
        private final long loanMaxAmount; // 0 = 无上限
        private final int loanMaxTermDays; // 0 = 无期限
        private final int collateralWeightBps; // 0 = 该 currency 不作 Cross 抵押

        SymbolLoanConfig(BytesIn bytes) {
            this.symbolId = bytes.readInt();
            this.loanInitialLtvBps = bytes.readInt();
            this.loanLiquidationLtvBps = bytes.readInt();
            this.loanMarginCallLtvBps = bytes.readInt();
            this.loanMaxAmount = bytes.readLong();
            this.loanMaxTermDays = bytes.readInt();
            this.collateralWeightBps = bytes.readInt();
        }

        void write(BytesOut bytes) {
            bytes.writeInt(symbolId);
            bytes.writeInt(loanInitialLtvBps);
            bytes.writeInt(loanLiquidationLtvBps);
            bytes.writeInt(loanMarginCallLtvBps);
            bytes.writeLong(loanMaxAmount);
            bytes.writeInt(loanMaxTermDays);
            bytes.writeInt(collateralWeightBps);
        }

        public boolean fieldsValid() {
            return loanInitialLtvBps >= 0 && loanInitialLtvBps < BPS_FULL
                && (loanInitialLtvBps == 0 || (loanLiquidationLtvBps > loanInitialLtvBps
                    && loanLiquidationLtvBps < BPS_FULL
                    && (loanMarginCallLtvBps == 0
                        || (loanMarginCallLtvBps > loanInitialLtvBps && loanMarginCallLtvBps < loanLiquidationLtvBps))))
                && loanMaxAmount >= 0 && loanMaxTermDays >= 0 && collateralWeightBps >= 0
                && collateralWeightBps <= BPS_FULL;
        }
    }
}
