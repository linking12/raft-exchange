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
package exchange.core2.core;

import exchange.core2.core.common.BalanceAdjustmentType;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.api.reports.ApiReportQuery;
import exchange.core2.core.common.api.reports.ReportQuery;
import exchange.core2.core.common.api.reports.ReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.BinaryCommandsProcessor;
import exchange.core2.core.utils.SerializationUtils;

import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.RingBuffer;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.lz4.LZ4Compressor;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import net.openhft.chronicle.wire.Wire;
import org.agrona.collections.LongLongConsumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

@Slf4j
public final class ExchangeApi {
    private static final class PromiseBuffer {
        private final AtomicReferenceArray<Consumer<OrderCommand>> buffer;
        private final int mask;

        public PromiseBuffer(int sizePowerOfTwo) {
            if (Integer.bitCount(sizePowerOfTwo) != 1) {
                throw new IllegalArgumentException("Size must be power of 2");
            }
            this.buffer = new AtomicReferenceArray<>(sizePowerOfTwo);
            this.mask = sizePowerOfTwo - 1;
        }

        public void put(long seq, Consumer<OrderCommand> c) {
            int index = (int)(seq & mask);
            boolean ok = buffer.compareAndSet(index, null, c);
            if (!ok) {
                throw new IllegalStateException("Slot already occupied! seq=" + seq + ", index=" + index);
            }
        }

        public Consumer<OrderCommand> remove(long seq) {
            int index = (int)(seq & mask);
            Consumer<OrderCommand> c = buffer.get(index);
            buffer.set(index, null); // ensure GC and reuse
            return c;
        }
    }

    private final RingBuffer<OrderCommand> ringBuffer;
    private final LZ4Compressor lz4Compressor;
    private final PromiseBuffer promises;

    public static final int LONGS_PER_MESSAGE = 5;

    public ExchangeApi(RingBuffer<OrderCommand> ringBuffer, LZ4Compressor lz4Compressor) {
        this.ringBuffer = ringBuffer;
        this.lz4Compressor = lz4Compressor;
        this.promises = new PromiseBuffer(ringBuffer.getBufferSize());
    }

    public void processResult(final long seq, final OrderCommand cmd) {
        final Consumer<OrderCommand> consumer = promises.remove(seq);
        if (consumer != null) {
            consumer.accept(cmd);
        }
    }

    public void submitCommand(ApiCommand cmd) {
        if (cmd instanceof ApiMoveOrder) {
            ringBuffer.publishEvent(MOVE_ORDER_TRANSLATOR, (ApiMoveOrder)cmd);
        } else if (cmd instanceof ApiPlaceOrder) {
            ringBuffer.publishEvent(PLACE_ORDER_TRANSLATOR, (ApiPlaceOrder)cmd);
        } else if (cmd instanceof ApiCancelOrder) {
            ringBuffer.publishEvent(CANCEL_ORDER_TRANSLATOR, (ApiCancelOrder)cmd);
        } else if (cmd instanceof ApiReduceOrder) {
            ringBuffer.publishEvent(REDUCE_ORDER_TRANSLATOR, (ApiReduceOrder)cmd);
        } else if (cmd instanceof ApiClosePosition) {
            ringBuffer.publishEvent(CLOSE_POSITION_TRANSLATOR, (ApiClosePosition)cmd);
        } else if (cmd instanceof ApiOrderBookRequest) {
            ringBuffer.publishEvent(ORDER_BOOK_REQUEST_TRANSLATOR, (ApiOrderBookRequest)cmd);
        } else if (cmd instanceof ApiAddUser) {
            ringBuffer.publishEvent(ADD_USER_TRANSLATOR, (ApiAddUser)cmd);
        } else if (cmd instanceof ApiAdjustUserBalance) {
            ringBuffer.publishEvent(ADJUST_USER_BALANCE_TRANSLATOR, (ApiAdjustUserBalance)cmd);
        } else if (cmd instanceof ApiResumeUser) {
            ringBuffer.publishEvent(RESUME_USER_TRANSLATOR, (ApiResumeUser)cmd);
        } else if (cmd instanceof ApiSuspendUser) {
            ringBuffer.publishEvent(SUSPEND_USER_TRANSLATOR, (ApiSuspendUser)cmd);
        } else if (cmd instanceof ApiLiquidationOrder) {
            ringBuffer.publishEvent(LIQUIDATION_ORDER_TRANSLATOR, (ApiLiquidationOrder)cmd);
        } else if (cmd instanceof ApiLoanForceLiquidate) {
            ringBuffer.publishEvent(LOAN_FORCE_LIQUIDATE_TRANSLATOR, (ApiLoanForceLiquidate)cmd);
        } else if (cmd instanceof ApiLoanCrossForceLiquidate) {
            ringBuffer.publishEvent(LOAN_CROSS_FORCE_LIQUIDATE_TRANSLATOR, (ApiLoanCrossForceLiquidate)cmd);
        } else if (cmd instanceof ApiAdjustLeverage) {
            ringBuffer.publishEvent(ADJUST_LEVERAGE_TRANSLATOR, (ApiAdjustLeverage)cmd);
        } else if (cmd instanceof ApiAdjustPositionMode) {
            ringBuffer.publishEvent(ADJUST_POSITION_MODE_TRANSLATOR, (ApiAdjustPositionMode)cmd);
        } else if (cmd instanceof ApiAdjustMargin) {
            ringBuffer.publishEvent(ADJUST_MARGIN_TRANSLATOR, (ApiAdjustMargin)cmd);
        } else if (cmd instanceof ApiAdjustMarkPrice) {
            ringBuffer.publishEvent(ADJUST_MARK_PRICE_TRANSLATOR, (ApiAdjustMarkPrice)cmd);
        } else if (cmd instanceof ApiSettleFundingFees) {
            ringBuffer.publishEvent(SETTLE_FUNDING_FEES_TRANSLATOR, (ApiSettleFundingFees)cmd);
        } else if (cmd instanceof ApiSettlePNL) {
            ringBuffer.publishEvent(SETTLE_PNL_TRANSLATOR, (ApiSettlePNL)cmd);
        } else if (cmd instanceof ApiResetFee) {
            ringBuffer.publishEvent(RESET_FEE_TRANSLATOR, (ApiResetFee)cmd);
        } else if (cmd instanceof ApiReset) {
            ringBuffer.publishEvent(RESET_TRANSLATOR, (ApiReset)cmd);
        } else if (cmd instanceof ApiNop) {
            ringBuffer.publishEvent(NOP_TRANSLATOR, (ApiNop)cmd);
        } else if (cmd instanceof ApiSystemLiquidationNotify) {
            ringBuffer.publishEvent(SYSTEM_LIQUIDATION_NOTIFY_TRANSLATOR, (ApiSystemLiquidationNotify)cmd);
        } else if (cmd instanceof ApiIFTakeOver) {
            ringBuffer.publishEvent(IF_TAKEOVER_TRANSLATOR, (ApiIFTakeOver)cmd);
        } else if (cmd instanceof ApiAutoDeleveraging) {
            ringBuffer.publishEvent(ADL_TRANSLATOR, (ApiAutoDeleveraging)cmd);
        } else if (cmd instanceof ApiInsuranceFundDeposit) {
            ringBuffer.publishEvent(IF_DEPOSIT_TRANSLATOR, (ApiInsuranceFundDeposit)cmd);
        } else if (cmd instanceof ApiInsuranceFundWithdraw) {
            ringBuffer.publishEvent(IF_WITHDRAW_TRANSLATOR, (ApiInsuranceFundWithdraw)cmd);
        } else if (cmd instanceof ApiLoanCreate) {
            ringBuffer.publishEvent(LOAN_CREATE_TRANSLATOR, (ApiLoanCreate)cmd);
        } else if (cmd instanceof ApiLoanRepay) {
            ringBuffer.publishEvent(LOAN_REPAY_TRANSLATOR, (ApiLoanRepay)cmd);
        } else if (cmd instanceof ApiLoanAddCollateral) {
            ringBuffer.publishEvent(LOAN_ADD_COLLATERAL_TRANSLATOR, (ApiLoanAddCollateral)cmd);
        } else if (cmd instanceof ApiLoanReleaseCollateral) {
            ringBuffer.publishEvent(LOAN_RELEASE_COLLATERAL_TRANSLATOR, (ApiLoanReleaseCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossAddCollateral) {
            ringBuffer.publishEvent(LOAN_CROSS_ADD_COLLATERAL_TRANSLATOR, (ApiLoanCrossAddCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossWithdrawCollateral) {
            ringBuffer.publishEvent(LOAN_CROSS_WITHDRAW_COLLATERAL_TRANSLATOR, (ApiLoanCrossWithdrawCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossBorrow) {
            ringBuffer.publishEvent(LOAN_CROSS_BORROW_TRANSLATOR, (ApiLoanCrossBorrow)cmd);
        } else if (cmd instanceof ApiLoanCrossRepay) {
            ringBuffer.publishEvent(LOAN_CROSS_REPAY_TRANSLATOR, (ApiLoanCrossRepay)cmd);
        } else if (cmd instanceof ApiPoolDeposit) {
            ringBuffer.publishEvent(POOL_DEPOSIT_TRANSLATOR, (ApiPoolDeposit)cmd);
        } else if (cmd instanceof ApiPoolWithdraw) {
            ringBuffer.publishEvent(POOL_WITHDRAW_TRANSLATOR, (ApiPoolWithdraw)cmd);
        } else if (cmd instanceof ApiPoolAbsorbBadDebt) {
            ringBuffer.publishEvent(POOL_ABSORB_BAD_DEBT_TRANSLATOR, (ApiPoolAbsorbBadDebt)cmd);
        } else if (cmd instanceof ApiBinaryDataCommand) {
            publishBinaryData((ApiBinaryDataCommand)cmd, seq -> {
            });
        } else if (cmd instanceof ApiPersistState) {
            publishPersistCmd((ApiPersistState)cmd, (seq1, seq2) -> {
            });
        } else if (cmd instanceof ApiRecoverState) {
            publishRecoverCmd((ApiRecoverState)cmd, (seq1, seq2) -> {
            });
        } else {
            throw new IllegalArgumentException("Unsupported command type: " + cmd.getClass().getSimpleName());
        }
    }

    public CompletableFuture<CommandResultCode> submitCommandAsync(ApiCommand cmd) {
        if (cmd instanceof ApiMoveOrder) {
            return submitCommandAsync(MOVE_ORDER_TRANSLATOR, (ApiMoveOrder)cmd);
        } else if (cmd instanceof ApiPlaceOrder) {
            return submitCommandAsync(PLACE_ORDER_TRANSLATOR, (ApiPlaceOrder)cmd);
        } else if (cmd instanceof ApiCancelOrder) {
            return submitCommandAsync(CANCEL_ORDER_TRANSLATOR, (ApiCancelOrder)cmd);
        } else if (cmd instanceof ApiReduceOrder) {
            return submitCommandAsync(REDUCE_ORDER_TRANSLATOR, (ApiReduceOrder)cmd);
        } else if (cmd instanceof ApiClosePosition) {
            return submitCommandAsync(CLOSE_POSITION_TRANSLATOR, (ApiClosePosition)cmd);
        } else if (cmd instanceof ApiOrderBookRequest) {
            return submitCommandAsync(ORDER_BOOK_REQUEST_TRANSLATOR, (ApiOrderBookRequest)cmd);
        } else if (cmd instanceof ApiAddUser) {
            return submitCommandAsync(ADD_USER_TRANSLATOR, (ApiAddUser)cmd);
        } else if (cmd instanceof ApiAdjustUserBalance) {
            return submitCommandAsync(ADJUST_USER_BALANCE_TRANSLATOR, (ApiAdjustUserBalance)cmd);
        } else if (cmd instanceof ApiResumeUser) {
            return submitCommandAsync(RESUME_USER_TRANSLATOR, (ApiResumeUser)cmd);
        } else if (cmd instanceof ApiSuspendUser) {
            return submitCommandAsync(SUSPEND_USER_TRANSLATOR, (ApiSuspendUser)cmd);
        } else if (cmd instanceof ApiLiquidationOrder) {
            return submitCommandAsync(LIQUIDATION_ORDER_TRANSLATOR, (ApiLiquidationOrder)cmd);
        } else if (cmd instanceof ApiLoanForceLiquidate) {
            return submitCommandAsync(LOAN_FORCE_LIQUIDATE_TRANSLATOR, (ApiLoanForceLiquidate)cmd);
        } else if (cmd instanceof ApiLoanCrossForceLiquidate) {
            return submitCommandAsync(LOAN_CROSS_FORCE_LIQUIDATE_TRANSLATOR, (ApiLoanCrossForceLiquidate)cmd);
        } else if (cmd instanceof ApiAdjustLeverage) {
            return submitCommandAsync(ADJUST_LEVERAGE_TRANSLATOR, (ApiAdjustLeverage)cmd);
        } else if (cmd instanceof ApiAdjustPositionMode) {
            return submitCommandAsync(ADJUST_POSITION_MODE_TRANSLATOR, (ApiAdjustPositionMode)cmd);
        } else if (cmd instanceof ApiAdjustMargin) {
            return submitCommandAsync(ADJUST_MARGIN_TRANSLATOR, (ApiAdjustMargin)cmd);
        } else if (cmd instanceof ApiAdjustMarkPrice) {
            return submitCommandAsync(ADJUST_MARK_PRICE_TRANSLATOR, (ApiAdjustMarkPrice)cmd);
        } else if (cmd instanceof ApiSettleFundingFees) {
            return submitCommandAsync(SETTLE_FUNDING_FEES_TRANSLATOR, (ApiSettleFundingFees)cmd);
        } else if (cmd instanceof ApiSettlePNL) {
            return submitCommandAsync(SETTLE_PNL_TRANSLATOR, (ApiSettlePNL)cmd);
        } else if (cmd instanceof ApiResetFee) {
            return submitCommandAsync(RESET_FEE_TRANSLATOR, (ApiResetFee)cmd);
        } else if (cmd instanceof ApiReset) {
            return submitCommandAsync(RESET_TRANSLATOR, (ApiReset)cmd);
        } else if (cmd instanceof ApiNop) {
            return submitCommandAsync(NOP_TRANSLATOR, (ApiNop)cmd);
        } else if (cmd instanceof ApiSystemLiquidationNotify) {
            return submitCommandAsync(SYSTEM_LIQUIDATION_NOTIFY_TRANSLATOR, (ApiSystemLiquidationNotify)cmd);
        } else if (cmd instanceof ApiIFTakeOver) {
            return submitCommandAsync(IF_TAKEOVER_TRANSLATOR, (ApiIFTakeOver)cmd);
        } else if (cmd instanceof ApiAutoDeleveraging) {
            return submitCommandAsync(ADL_TRANSLATOR, (ApiAutoDeleveraging)cmd);
        } else if (cmd instanceof ApiInsuranceFundDeposit) {
            return submitCommandAsync(IF_DEPOSIT_TRANSLATOR, (ApiInsuranceFundDeposit)cmd);
        } else if (cmd instanceof ApiInsuranceFundWithdraw) {
            return submitCommandAsync(IF_WITHDRAW_TRANSLATOR, (ApiInsuranceFundWithdraw)cmd);
        } else if (cmd instanceof ApiLoanCreate) {
            return submitCommandAsync(LOAN_CREATE_TRANSLATOR, (ApiLoanCreate)cmd);
        } else if (cmd instanceof ApiLoanRepay) {
            return submitCommandAsync(LOAN_REPAY_TRANSLATOR, (ApiLoanRepay)cmd);
        } else if (cmd instanceof ApiLoanAddCollateral) {
            return submitCommandAsync(LOAN_ADD_COLLATERAL_TRANSLATOR, (ApiLoanAddCollateral)cmd);
        } else if (cmd instanceof ApiLoanReleaseCollateral) {
            return submitCommandAsync(LOAN_RELEASE_COLLATERAL_TRANSLATOR, (ApiLoanReleaseCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossAddCollateral) {
            return submitCommandAsync(LOAN_CROSS_ADD_COLLATERAL_TRANSLATOR, (ApiLoanCrossAddCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossWithdrawCollateral) {
            return submitCommandAsync(LOAN_CROSS_WITHDRAW_COLLATERAL_TRANSLATOR, (ApiLoanCrossWithdrawCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossBorrow) {
            return submitCommandAsync(LOAN_CROSS_BORROW_TRANSLATOR, (ApiLoanCrossBorrow)cmd);
        } else if (cmd instanceof ApiLoanCrossRepay) {
            return submitCommandAsync(LOAN_CROSS_REPAY_TRANSLATOR, (ApiLoanCrossRepay)cmd);
        } else if (cmd instanceof ApiPoolDeposit) {
            return submitCommandAsync(POOL_DEPOSIT_TRANSLATOR, (ApiPoolDeposit)cmd);
        } else if (cmd instanceof ApiPoolWithdraw) {
            return submitCommandAsync(POOL_WITHDRAW_TRANSLATOR, (ApiPoolWithdraw)cmd);
        } else if (cmd instanceof ApiPoolAbsorbBadDebt) {
            return submitCommandAsync(POOL_ABSORB_BAD_DEBT_TRANSLATOR, (ApiPoolAbsorbBadDebt)cmd);
        } else if (cmd instanceof ApiBinaryDataCommand) {
            return submitBinaryDataAsync(((ApiBinaryDataCommand)cmd).data);
        } else if (cmd instanceof ApiPersistState) {
            return submitPersistCommandAsync((ApiPersistState)cmd);
        } else if (cmd instanceof ApiRecoverState) {
            return submitRecoverCommandAsync((ApiRecoverState)cmd);
        } else {
            throw new IllegalArgumentException("Unsupported command type: " + cmd.getClass().getSimpleName());
        }
    }

    public CompletableFuture<OrderCommand> submitCommandAsyncFullResponse(ApiCommand cmd) {
        if (cmd instanceof ApiMoveOrder) {
            return submitCommandAsyncFullResponse(MOVE_ORDER_TRANSLATOR, (ApiMoveOrder)cmd);
        } else if (cmd instanceof ApiPlaceOrder) {
            return submitCommandAsyncFullResponse(PLACE_ORDER_TRANSLATOR, (ApiPlaceOrder)cmd);
        } else if (cmd instanceof ApiCancelOrder) {
            return submitCommandAsyncFullResponse(CANCEL_ORDER_TRANSLATOR, (ApiCancelOrder)cmd);
        } else if (cmd instanceof ApiReduceOrder) {
            return submitCommandAsyncFullResponse(REDUCE_ORDER_TRANSLATOR, (ApiReduceOrder)cmd);
        } else if (cmd instanceof ApiClosePosition) {
            return submitCommandAsyncFullResponse(CLOSE_POSITION_TRANSLATOR, (ApiClosePosition)cmd);
        } else if (cmd instanceof ApiOrderBookRequest) {
            return submitCommandAsyncFullResponse(ORDER_BOOK_REQUEST_TRANSLATOR, (ApiOrderBookRequest)cmd);
        } else if (cmd instanceof ApiAddUser) {
            return submitCommandAsyncFullResponse(ADD_USER_TRANSLATOR, (ApiAddUser)cmd);
        } else if (cmd instanceof ApiAdjustUserBalance) {
            return submitCommandAsyncFullResponse(ADJUST_USER_BALANCE_TRANSLATOR, (ApiAdjustUserBalance)cmd);
        } else if (cmd instanceof ApiResumeUser) {
            return submitCommandAsyncFullResponse(RESUME_USER_TRANSLATOR, (ApiResumeUser)cmd);
        } else if (cmd instanceof ApiSuspendUser) {
            return submitCommandAsyncFullResponse(SUSPEND_USER_TRANSLATOR, (ApiSuspendUser)cmd);
        } else if (cmd instanceof ApiLiquidationOrder) {
            return submitCommandAsyncFullResponse(LIQUIDATION_ORDER_TRANSLATOR, (ApiLiquidationOrder)cmd);
        } else if (cmd instanceof ApiLoanForceLiquidate) {
            return submitCommandAsyncFullResponse(LOAN_FORCE_LIQUIDATE_TRANSLATOR, (ApiLoanForceLiquidate)cmd);
        } else if (cmd instanceof ApiLoanCrossForceLiquidate) {
            return submitCommandAsyncFullResponse(LOAN_CROSS_FORCE_LIQUIDATE_TRANSLATOR,
                (ApiLoanCrossForceLiquidate)cmd);
        } else if (cmd instanceof ApiAdjustLeverage) {
            return submitCommandAsyncFullResponse(ADJUST_LEVERAGE_TRANSLATOR, (ApiAdjustLeverage)cmd);
        } else if (cmd instanceof ApiAdjustPositionMode) {
            return submitCommandAsyncFullResponse(ADJUST_POSITION_MODE_TRANSLATOR, (ApiAdjustPositionMode)cmd);
        } else if (cmd instanceof ApiAdjustMargin) {
            return submitCommandAsyncFullResponse(ADJUST_MARGIN_TRANSLATOR, (ApiAdjustMargin)cmd);
        } else if (cmd instanceof ApiAdjustMarkPrice) {
            return submitCommandAsyncFullResponse(ADJUST_MARK_PRICE_TRANSLATOR, (ApiAdjustMarkPrice)cmd);
        } else if (cmd instanceof ApiSettleFundingFees) {
            return submitCommandAsyncFullResponse(SETTLE_FUNDING_FEES_TRANSLATOR, (ApiSettleFundingFees)cmd);
        } else if (cmd instanceof ApiSettlePNL) {
            return submitCommandAsyncFullResponse(SETTLE_PNL_TRANSLATOR, (ApiSettlePNL)cmd);
        } else if (cmd instanceof ApiResetFee) {
            return submitCommandAsyncFullResponse(RESET_FEE_TRANSLATOR, (ApiResetFee)cmd);
        } else if (cmd instanceof ApiReset) {
            return submitCommandAsyncFullResponse(RESET_TRANSLATOR, (ApiReset)cmd);
        } else if (cmd instanceof ApiNop) {
            return submitCommandAsyncFullResponse(NOP_TRANSLATOR, (ApiNop)cmd);
        } else if (cmd instanceof ApiSystemLiquidationNotify) {
            return submitCommandAsyncFullResponse(SYSTEM_LIQUIDATION_NOTIFY_TRANSLATOR,
                (ApiSystemLiquidationNotify)cmd);
        } else if (cmd instanceof ApiIFTakeOver) {
            return submitCommandAsyncFullResponse(IF_TAKEOVER_TRANSLATOR, (ApiIFTakeOver)cmd);
        } else if (cmd instanceof ApiAutoDeleveraging) {
            return submitCommandAsyncFullResponse(ADL_TRANSLATOR, (ApiAutoDeleveraging)cmd);
        } else if (cmd instanceof ApiInsuranceFundDeposit) {
            return submitCommandAsyncFullResponse(IF_DEPOSIT_TRANSLATOR, (ApiInsuranceFundDeposit)cmd);
        } else if (cmd instanceof ApiInsuranceFundWithdraw) {
            return submitCommandAsyncFullResponse(IF_WITHDRAW_TRANSLATOR, (ApiInsuranceFundWithdraw)cmd);
        } else if (cmd instanceof ApiLoanCreate) {
            return submitCommandAsyncFullResponse(LOAN_CREATE_TRANSLATOR, (ApiLoanCreate)cmd);
        } else if (cmd instanceof ApiLoanRepay) {
            return submitCommandAsyncFullResponse(LOAN_REPAY_TRANSLATOR, (ApiLoanRepay)cmd);
        } else if (cmd instanceof ApiLoanAddCollateral) {
            return submitCommandAsyncFullResponse(LOAN_ADD_COLLATERAL_TRANSLATOR, (ApiLoanAddCollateral)cmd);
        } else if (cmd instanceof ApiLoanReleaseCollateral) {
            return submitCommandAsyncFullResponse(LOAN_RELEASE_COLLATERAL_TRANSLATOR, (ApiLoanReleaseCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossAddCollateral) {
            return submitCommandAsyncFullResponse(LOAN_CROSS_ADD_COLLATERAL_TRANSLATOR, (ApiLoanCrossAddCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossWithdrawCollateral) {
            return submitCommandAsyncFullResponse(LOAN_CROSS_WITHDRAW_COLLATERAL_TRANSLATOR,
                (ApiLoanCrossWithdrawCollateral)cmd);
        } else if (cmd instanceof ApiLoanCrossBorrow) {
            return submitCommandAsyncFullResponse(LOAN_CROSS_BORROW_TRANSLATOR, (ApiLoanCrossBorrow)cmd);
        } else if (cmd instanceof ApiLoanCrossRepay) {
            return submitCommandAsyncFullResponse(LOAN_CROSS_REPAY_TRANSLATOR, (ApiLoanCrossRepay)cmd);
        } else if (cmd instanceof ApiPoolDeposit) {
            return submitCommandAsyncFullResponse(POOL_DEPOSIT_TRANSLATOR, (ApiPoolDeposit)cmd);
        } else if (cmd instanceof ApiPoolWithdraw) {
            return submitCommandAsyncFullResponse(POOL_WITHDRAW_TRANSLATOR, (ApiPoolWithdraw)cmd);
        } else if (cmd instanceof ApiPoolAbsorbBadDebt) {
            return submitCommandAsyncFullResponse(POOL_ABSORB_BAD_DEBT_TRANSLATOR, (ApiPoolAbsorbBadDebt)cmd);
        } else if (cmd instanceof ApiBinaryDataCommand) {
            return submitBinaryDataCommandAsync(((ApiBinaryDataCommand)cmd).data);
        } else {
            throw new IllegalArgumentException("Unsupported command type: " + cmd.getClass().getSimpleName());
        }
    }

    public void submitCommandsSync(List<? extends ApiCommand> cmd) {
        if (cmd.isEmpty()) {
            return;
        }
        cmd.subList(0, cmd.size() - 1).forEach(this::submitCommand);
        submitCommandAsync(cmd.get(cmd.size() - 1)).join();
    }

    public void submitCommandsSync(Stream<? extends ApiCommand> stream) {
        stream.forEach(this::submitCommand);
        submitCommandAsync(ApiNop.builder().build()).join();
    }

    private <T extends ApiCommand> CompletableFuture<CommandResultCode>
        submitCommandAsync(EventTranslatorOneArg<OrderCommand, T> translator, final T apiCommand) {
        return submitCommandAsync(translator, apiCommand, c -> c.resultCode);
    }

    private <T extends ApiCommand> CompletableFuture<OrderCommand>
        submitCommandAsyncFullResponse(EventTranslatorOneArg<OrderCommand, T> translator, final T apiCommand) {
        return submitCommandAsync(translator, apiCommand, Function.identity());
    }

    private <T extends ApiCommand, R> CompletableFuture<R> submitCommandAsync(
        final EventTranslatorOneArg<OrderCommand, T> translator, final T apiCommand,
        final Function<OrderCommand, R> responseTranslator) {
        final CompletableFuture<R> future = new CompletableFuture<>();

        ringBuffer.publishEvent((cmd, seq, apiCmd) -> {
            translator.translateTo(cmd, seq, apiCmd);
            promises.put(seq, orderCommand -> future.complete(responseTranslator.apply(orderCommand)));
        }, apiCommand);

        return future;
    }

    public CompletableFuture<CommandResultCode> submitPersistCommandAsync(final ApiPersistState apiCommand) {
        final CompletableFuture<CommandResultCode> future1 = new CompletableFuture<>();
        final CompletableFuture<CommandResultCode> future2 = new CompletableFuture<>();

        publishPersistCmd(apiCommand, (seq1, seq2) -> {
            promises.put(seq1, cmd -> future1.complete(cmd.resultCode));
            promises.put(seq2, cmd -> future2.complete(cmd.resultCode));
        });

        return future1.thenCombineAsync(future2, CommandResultCode::mergeToFirstFailed);
    }

    public CompletableFuture<CommandResultCode> submitRecoverCommandAsync(final ApiRecoverState apiRecoverState) {
        final CompletableFuture<CommandResultCode> future1 = new CompletableFuture<>();
        final CompletableFuture<CommandResultCode> future2 = new CompletableFuture<>();

        publishRecoverCmd(apiRecoverState, (seq1, seq2) -> {
            promises.put(seq1, cmd -> future1.complete(cmd.resultCode));
            promises.put(seq2, cmd -> future2.complete(cmd.resultCode));
        });

        return future1.thenCombineAsync(future2, CommandResultCode::mergeToFirstFailed);
    }

    public CompletableFuture<CommandResultCode> submitBinaryDataAsync(final BinaryDataCommand data) {
        final CompletableFuture<CommandResultCode> future = new CompletableFuture<>();
        publishBinaryData(OrderCommandType.BINARY_DATA_COMMAND, data, data.getBinaryCommandTypeCode(),
            (int)System.nanoTime(), 0L,
            seq -> promises.put(seq, orderCommand -> future.complete(orderCommand.resultCode)));
        return future;
    }

    public CompletableFuture<OrderCommand> submitBinaryDataCommandAsync(final BinaryDataCommand data) {
        final CompletableFuture<OrderCommand> future = new CompletableFuture<>();
        publishBinaryData(OrderCommandType.BINARY_DATA_COMMAND, data, data.getBinaryCommandTypeCode(),
            (int)System.nanoTime(), 0L, seq -> promises.put(seq, future::complete));
        return future;
    }

    public <R> CompletableFuture<R> submitBinaryCommandAsync(final BinaryDataCommand data, final int transferId,
        final Function<OrderCommand, R> translator) {
        final CompletableFuture<R> future = new CompletableFuture<>();

        publishBinaryData(ApiBinaryDataCommand.builder().data(data).transferId(transferId).build(),
            seq -> promises.put(seq, orderCommand -> future.complete(translator.apply(orderCommand))));

        return future;
    }

    public <R> CompletableFuture<R> submitQueryAsync(final ReportQuery<?> data, final int transferId,
        final Function<OrderCommand, R> translator) {
        final CompletableFuture<R> future = new CompletableFuture<>();

        publishQuery(ApiReportQuery.builder().query(data).transferId(transferId).build(),
            seq -> promises.put(seq, orderCommand -> future.complete(translator.apply(orderCommand))));

        return future;
    }

    public <Q extends ReportQuery<R>, R extends ReportResult> CompletableFuture<R> processReport(final Q query,
        final int transferId) {
        return submitQueryAsync(query, transferId, cmd -> query
            .createResult(OrderBookEventsHelper.deserializeEvents(cmd).values().parallelStream().map(Wire::bytes)));
    }

    public void publishBinaryData(final ApiBinaryDataCommand apiCmd, final LongConsumer endSeqConsumer) {
        publishBinaryData(OrderCommandType.BINARY_DATA_COMMAND, apiCmd.data, apiCmd.data.getBinaryCommandTypeCode(),
            apiCmd.transferId, apiCmd.timestamp, endSeqConsumer);
    }

    public void publishQuery(final ApiReportQuery apiCmd, final LongConsumer endSeqConsumer) {
        publishBinaryData(OrderCommandType.BINARY_DATA_QUERY, apiCmd.query, apiCmd.query.getReportTypeCode(),
            apiCmd.transferId, apiCmd.timestamp, endSeqConsumer);
    }

    private void publishBinaryData(final OrderCommandType cmdType, final WriteBytesMarshallable data,
        final int dataTypeCode, final int transferId, final long timestamp, final LongConsumer endSeqConsumer) {
        final long[] longsArrayData = SerializationUtils.bytesToLongArrayLz4(lz4Compressor,
            BinaryCommandsProcessor.serializeObject(data, dataTypeCode), LONGS_PER_MESSAGE);

        final int totalNumMessagesToClaim = longsArrayData.length / LONGS_PER_MESSAGE;

        // max fragment size is quarter of ring buffer
        final int batchSize = ringBuffer.getBufferSize() / 4;

        int offset = 0;
        boolean isLastFragment = false;
        int fragmentSize = batchSize;

        do {
            if (offset + batchSize >= totalNumMessagesToClaim) {
                fragmentSize = totalNumMessagesToClaim - offset;
                isLastFragment = true;
            }

            publishBinaryMessageFragment(cmdType, transferId, timestamp, endSeqConsumer, longsArrayData, fragmentSize,
                offset, isLastFragment);

            offset += batchSize;
        } while (!isLastFragment);
    }

    private void publishBinaryMessageFragment(OrderCommandType cmdType, int transferId, long timestamp,
        LongConsumer endSeqConsumer, long[] longsArrayData, int fragmentSize, int offset, boolean isLastFragment) {
        final long highSeq = ringBuffer.next(fragmentSize);
        final long lowSeq = highSeq - fragmentSize + 1;

        try {
            int ptr = offset * LONGS_PER_MESSAGE;
            for (long seq = lowSeq; seq <= highSeq; seq++) {
                OrderCommand cmd = ringBuffer.get(seq);
                cmd.command = cmdType;
                cmd.userCookie = transferId;
                cmd.symbol = (isLastFragment && seq == highSeq) ? -1 : 0;

                cmd.orderId = longsArrayData[ptr];
                cmd.price = longsArrayData[ptr + 1];
                cmd.reserveBidPrice = longsArrayData[ptr + 2];
                cmd.size = longsArrayData[ptr + 3];
                cmd.uid = longsArrayData[ptr + 4];

                cmd.timestamp = timestamp;
                cmd.resultCode = CommandResultCode.NEW;

                ptr += LONGS_PER_MESSAGE;
            }
        } catch (final Exception ex) {
            log.error("Binary commands processing exception: ", ex);
        } finally {
            if (isLastFragment) {
                // report last sequence before actually publishing data
                endSeqConsumer.accept(highSeq);
            }
            ringBuffer.publish(lowSeq, highSeq);
        }
    }

    private void publishRecoverCmd(final ApiRecoverState api, final LongLongConsumer seqConsumer) {
        long secondSeq = ringBuffer.next(2);
        long firstSeq = secondSeq - 1;
        try {
            long snapshotId = api.snapshotId;
            final OrderCommand cmdMatching = ringBuffer.get(firstSeq);
            cmdMatching.command = OrderCommandType.RECOVER_STATE_MATCHING;
            cmdMatching.orderId = snapshotId;
            cmdMatching.symbol = -1;
            cmdMatching.uid = 0;
            cmdMatching.price = 0;
            cmdMatching.timestamp = api.timestamp;
            cmdMatching.resultCode = CommandResultCode.NEW;

            final OrderCommand cmdRisk = ringBuffer.get(secondSeq);
            cmdRisk.command = OrderCommandType.RECOVER_STATE_RISK;
            cmdRisk.orderId = snapshotId;
            cmdRisk.symbol = -1;
            cmdRisk.uid = 0;
            cmdRisk.price = 0;
            cmdRisk.timestamp = api.timestamp;
            cmdRisk.resultCode = CommandResultCode.NEW;
        } finally {
            seqConsumer.accept(firstSeq, secondSeq);
            ringBuffer.publish(firstSeq, secondSeq);
        }
    }

    private void publishPersistCmd(final ApiPersistState api, final LongLongConsumer seqConsumer) {
        long secondSeq = ringBuffer.next(2);
        long firstSeq = secondSeq - 1;

        try {
            // will be ignored by risk handlers, but processed by matching engine
            final OrderCommand cmdMatching = ringBuffer.get(firstSeq);
            cmdMatching.command = OrderCommandType.PERSIST_STATE_MATCHING;
            cmdMatching.orderId = api.dumpId;
            cmdMatching.symbol = -1;
            cmdMatching.uid = 0;
            cmdMatching.price = 0;
            cmdMatching.timestamp = api.timestamp;
            cmdMatching.resultCode = CommandResultCode.NEW;

            // sequential command will make risk handler to create snapshot
            final OrderCommand cmdRisk = ringBuffer.get(secondSeq);
            cmdRisk.command = OrderCommandType.PERSIST_STATE_RISK;
            cmdRisk.orderId = api.dumpId;
            cmdRisk.symbol = -1;
            cmdRisk.uid = 0;
            cmdRisk.price = 0;
            cmdRisk.timestamp = api.timestamp;
            cmdRisk.resultCode = CommandResultCode.NEW;
        } finally {
            seqConsumer.accept(firstSeq, secondSeq);
            ringBuffer.publish(firstSeq, secondSeq);
        }
    }

   
    public void submitBatchAsync(ApiCommand[] commands, Consumer<OrderCommand>[] callbacks, int size) {
        if (size <= 0)
            return;

        final long hiSeq = ringBuffer.next(size);
        final long loSeq = hiSeq - size + 1;
        try {
            for (int i = 0; i < size; i++) {
                final long seq = loSeq + i;
                final OrderCommand cmd = ringBuffer.get(seq);
                resolveTranslator(commands[i]).translateTo(cmd, seq, commands[i]);
                promises.put(seq, callbacks[i]);
            }
        } finally {
            ringBuffer.publish(loSeq, hiSeq);
        }
    }

    public void binaryData(int serviceFlags, long eventsGroup, long timestampNs, byte lastFlag, long word0, long word1,
        long word2, long word3, long word4) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;

            cmd.command = OrderCommandType.BINARY_DATA_COMMAND;
            cmd.symbol = lastFlag;
            cmd.orderId = word0;
            cmd.price = word1;
            cmd.reserveBidPrice = word2;
            cmd.size = word3;
            cmd.uid = word4;
            cmd.timestamp = timestampNs;
            cmd.resultCode = CommandResultCode.NEW;
        }));
    }

    public void createUser(long userId, Consumer<OrderCommand> callback) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.command = OrderCommandType.ADD_USER;
            cmd.orderId = -1;
            cmd.symbol = -1;
            cmd.uid = userId;
            cmd.timestamp = System.currentTimeMillis();
            cmd.resultCode = CommandResultCode.NEW;

            promises.put(seq, callback);
        }));
    }

    public void suspendUser(long userId, Consumer<OrderCommand> callback) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.command = OrderCommandType.SUSPEND_USER;
            cmd.orderId = -1;
            cmd.symbol = -1;
            cmd.uid = userId;
            cmd.timestamp = System.currentTimeMillis();
            cmd.resultCode = CommandResultCode.NEW;

            promises.put(seq, callback);
        }));
    }

    public void resumeUser(long userId, Consumer<OrderCommand> callback) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.command = OrderCommandType.RESUME_USER;
            cmd.orderId = -1;
            cmd.symbol = -1;
            cmd.uid = userId;
            cmd.timestamp = System.currentTimeMillis();
            cmd.resultCode = CommandResultCode.NEW;

            promises.put(seq, callback);
        }));
    }

    public void createUser(int serviceFlags, long eventsGroup, long timestampNs, long userId) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;

            cmd.command = OrderCommandType.ADD_USER;
            cmd.orderId = -1;
            cmd.symbol = -1;
            cmd.uid = userId;
            cmd.timestamp = timestampNs;
            cmd.resultCode = CommandResultCode.NEW;
        }));
    }

    public void suspendUser(int serviceFlags, long eventsGroup, long timestampNs, long userId) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;

            cmd.command = OrderCommandType.SUSPEND_USER;
            cmd.orderId = -1;
            cmd.symbol = -1;
            cmd.uid = userId;
            cmd.timestamp = timestampNs;
            cmd.resultCode = CommandResultCode.NEW;
        }));
    }

    public void resumeUser(int serviceFlags, long eventsGroup, long timestampNs, long userId) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;

            cmd.command = OrderCommandType.RESUME_USER;
            cmd.orderId = -1;
            cmd.symbol = -1;
            cmd.uid = userId;
            cmd.timestamp = timestampNs;
            cmd.resultCode = CommandResultCode.NEW;
        }));
    }

    public void balanceAdjustment(long uid, long transactionId, int currency, long longAmount,
        BalanceAdjustmentType adjustmentType, Consumer<OrderCommand> callback) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.command = OrderCommandType.BALANCE_ADJUSTMENT;
            cmd.orderId = transactionId;
            cmd.symbol = currency;
            cmd.uid = uid;
            cmd.price = longAmount;
            cmd.orderType = OrderType.of(adjustmentType.getCode());
            cmd.size = 0;
            cmd.timestamp = System.currentTimeMillis();
            cmd.resultCode = CommandResultCode.NEW;

            promises.put(seq, callback);
        }));
    }

    public void balanceAdjustment(int serviceFlags, long eventsGroup, long timestampNs, long uid, long transactionId,
        int currency, long longAmount, BalanceAdjustmentType adjustmentType) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;
            cmd.command = OrderCommandType.BALANCE_ADJUSTMENT;
            cmd.orderId = transactionId;
            cmd.symbol = currency;
            cmd.uid = uid;
            cmd.price = longAmount;
            cmd.orderType = OrderType.of(adjustmentType.getCode());
            cmd.size = 0;
            cmd.timestamp = timestampNs;
            cmd.resultCode = CommandResultCode.NEW;
        }));
    }

    public void orderBookRequest(int symbolId, int depth, Consumer<OrderCommand> callback) {
        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.command = OrderCommandType.ORDER_BOOK_REQUEST;
            cmd.orderId = -1;
            cmd.symbol = symbolId;
            cmd.uid = -1;
            cmd.size = depth;
            cmd.timestamp = System.currentTimeMillis();
            cmd.resultCode = CommandResultCode.NEW;

            promises.put(seq, callback);
        }));
    }

    public CompletableFuture<L2MarketData> requestOrderBookAsync(int symbolId, int depth) {
        final CompletableFuture<L2MarketData> future = new CompletableFuture<>();

        ringBuffer.publishEvent(((cmd, seq) -> {
            cmd.command = OrderCommandType.ORDER_BOOK_REQUEST;
            cmd.orderId = -1;
            cmd.symbol = symbolId;
            cmd.uid = -1;
            cmd.size = depth;
            cmd.timestamp = System.currentTimeMillis();
            cmd.resultCode = CommandResultCode.NEW;

            promises.put(seq, cmd1 -> future.complete(cmd1.marketData));
        }));

        return future;
    }

    public long placeNewOrder(int userCookie, int leverage, MarginMode marginMode, int orderFlags, long price,
        long reservedBidPrice, long size, OrderAction action, OrderType orderType, int symbol, long uid,
        Consumer<OrderCommand> callback) {
        final long seq = ringBuffer.next();
        try {
            OrderCommand cmd = ringBuffer.get(seq);
            cmd.command = OrderCommandType.PLACE_ORDER;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.price = price;
            cmd.reserveBidPrice = reservedBidPrice;
            cmd.size = size;
            cmd.orderId = seq;
            cmd.timestamp = System.currentTimeMillis();
            cmd.action = action;
            cmd.orderType = orderType;
            cmd.symbol = symbol;
            cmd.uid = uid;
            cmd.userCookie = userCookie;
            cmd.leverage = leverage;
            cmd.marginMode = marginMode;
            cmd.orderFlags = orderFlags;
            promises.put(seq, callback);
        } finally {
            ringBuffer.publish(seq);
        }
        return seq;
    }

    public void placeNewOrder(int serviceFlags, long eventsGroup, long timestampNs, long orderId, int userCookie,
        int leverage, MarginMode marginMode, int orderFlags, long price, long reservedBidPrice, long size,
        OrderAction action, OrderType orderType, int symbol, long uid) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;

            cmd.command = OrderCommandType.PLACE_ORDER;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.price = price;
            cmd.reserveBidPrice = reservedBidPrice;
            cmd.size = size;
            cmd.orderId = orderId;
            cmd.timestamp = timestampNs;
            cmd.action = action;
            cmd.orderType = orderType;
            cmd.symbol = symbol;
            cmd.uid = uid;
            cmd.userCookie = userCookie;
            cmd.leverage = leverage;
            cmd.marginMode = marginMode;
            cmd.orderFlags = orderFlags;
        });
    }

    public void moveOrder(long price, long orderId, int symbol, long uid, Consumer<OrderCommand> callback) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.command = OrderCommandType.MOVE_ORDER;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.price = price;
            cmd.orderId = orderId;
            cmd.timestamp = System.currentTimeMillis();
            cmd.symbol = symbol;
            cmd.uid = uid;

            promises.put(seq, callback);
        });
    }

    public void moveOrder(int serviceFlags, long eventsGroup, long timestampNs, long price, long orderId, int symbol,
        long uid) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;

            cmd.command = OrderCommandType.MOVE_ORDER;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.price = price;
            cmd.orderId = orderId;
            cmd.timestamp = timestampNs;
            cmd.symbol = symbol;
            cmd.uid = uid;
        });
    }

    public void cancelOrder(long orderId, int symbol, long uid, Consumer<OrderCommand> callback) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.command = OrderCommandType.CANCEL_ORDER;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.orderId = orderId;
            cmd.timestamp = System.currentTimeMillis();
            cmd.symbol = symbol;
            cmd.uid = uid;

            promises.put(seq, callback);
        });
    }

    public void cancelOrder(int serviceFlags, long eventsGroup, long timestampNs, long orderId, int symbol, long uid) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;

            cmd.command = OrderCommandType.CANCEL_ORDER;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.orderId = orderId;
            cmd.timestamp = timestampNs;
            cmd.symbol = symbol;
            cmd.uid = uid;
        });
    }

    public void reduceOrder(long reduceSize, long orderId, int symbol, long uid, Consumer<OrderCommand> callback) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.command = OrderCommandType.REDUCE_ORDER;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.size = reduceSize;
            cmd.orderId = orderId;
            cmd.timestamp = System.currentTimeMillis();
            cmd.symbol = symbol;
            cmd.uid = uid;

            promises.put(seq, callback);
        });
    }

    public void reduceOrder(int serviceFlags, long eventsGroup, long timestampNs, long reduceSize, long orderId,
        int symbol, long uid) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.serviceFlags = serviceFlags;
            cmd.eventsGroup = eventsGroup;

            cmd.command = OrderCommandType.REDUCE_ORDER;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.size = reduceSize;
            cmd.orderId = orderId;
            cmd.timestamp = timestampNs;
            cmd.symbol = symbol;
            cmd.uid = uid;
        });
    }

    public void groupingControl(long timestampNs, long mode) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.command = OrderCommandType.GROUPING_CONTROL;
            cmd.resultCode = CommandResultCode.NEW;

            cmd.orderId = mode;
            cmd.timestamp = timestampNs;
        });
    }

    public void reset(long timestampNs) {
        ringBuffer.publishEvent((cmd, seq) -> {
            cmd.command = OrderCommandType.RESET;
            cmd.resultCode = CommandResultCode.NEW;
            cmd.timestamp = timestampNs;
        });
    }

    // ---------- Ring buffer translators (ApiCommand → OrderCommand) ----------

    private static final EventTranslatorOneArg<OrderCommand, ApiPlaceOrder> PLACE_ORDER_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.PLACE_ORDER;
            cmd.price = api.price;
            cmd.reserveBidPrice = api.reservePrice;
            cmd.size = api.size;
            cmd.orderId = api.orderId;
            cmd.timestamp = api.timestamp;
            cmd.action = api.action;
            cmd.orderType = api.orderType;
            cmd.symbol = api.symbol;
            cmd.leverage = api.leverage;
            cmd.marginMode = api.marginMode;
            cmd.uid = api.uid;
            cmd.userCookie = api.userCookie;
            cmd.orderFlags = 0;
            if (api.reduceOnly) {
                cmd.orderFlags |= OrderCommand.FLAG_REDUCE_ONLY;
            }
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiLiquidationOrder> LIQUIDATION_ORDER_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.FORCE_LIQUIDATION;
            cmd.price = api.price;
            cmd.size = api.size;
            cmd.orderId = api.orderId;
            cmd.timestamp = api.timestamp;
            cmd.action = api.action;
            cmd.orderType = api.orderType;
            cmd.symbol = api.symbol;
            cmd.uid = api.uid;
            cmd.resultCode = CommandResultCode.NEW;
        };

    // Loan Isolated 强平专属 translator：跟 LIQUIDATION_ORDER_TRANSLATOR 分开，避免 orderId hijack。
    // loanId 走 reserveBidPrice（跟其他 loan 命令一致，LOAN_CREATE / LOAN_REPAY 等都用这个字段）。
    private static final EventTranslatorOneArg<OrderCommand, ApiLoanForceLiquidate> LOAN_FORCE_LIQUIDATE_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_FORCE_LIQUIDATE;
            cmd.price = api.price;
            cmd.size = api.size;
            cmd.orderId = api.orderId;
            cmd.reserveBidPrice = api.loanId;
            cmd.timestamp = api.timestamp;
            cmd.action = api.action;
            cmd.orderType = api.orderType;
            cmd.symbol = api.symbol;
            cmd.uid = api.uid;
            cmd.resultCode = CommandResultCode.NEW;
        };

    // Loan Cross 强平专属 translator。targetLoanId 走 reserveBidPrice（跟 Isolated 同款字段约定）。
    private static final EventTranslatorOneArg<OrderCommand,
        ApiLoanCrossForceLiquidate> LOAN_CROSS_FORCE_LIQUIDATE_TRANSLATOR = (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE;
            cmd.price = api.price;
            cmd.size = api.size;
            cmd.orderId = api.orderId;
            cmd.reserveBidPrice = api.targetLoanId;
            cmd.timestamp = api.timestamp;
            cmd.action = api.action;
            cmd.orderType = api.orderType;
            cmd.symbol = api.symbol;
            cmd.uid = api.uid;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiAdjustLeverage> ADJUST_LEVERAGE_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LEVERAGE_ADJUSTMENT;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.symbol = api.symbol;
            cmd.leverage = api.leverage;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiAdjustPositionMode> ADJUST_POSITION_MODE_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.POSITION_MODE_ADJUSTMENT;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.action = OrderAction.of(api.positionMode.getCode());
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiAdjustMargin> ADJUST_MARGIN_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.MARGIN_ADJUSTMENT;
            cmd.timestamp = api.timestamp;
            cmd.orderId = api.transactionId;
            cmd.uid = api.uid;
            cmd.marginMode = api.marginMode;
            cmd.symbol = api.marginMode == MarginMode.ISOLATED ? api.symbol : api.currency;
            cmd.action = api.action;
            cmd.price = api.amount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiAdjustMarkPrice> ADJUST_MARK_PRICE_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.MARKPRICE_ADJUSTMENT;
            cmd.timestamp = api.timestamp;
            cmd.orderId = api.transactionId;
            cmd.symbol = api.symbol;
            cmd.price = api.markPrice;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiSettleFundingFees> SETTLE_FUNDING_FEES_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.SETTLE_FUNDINGFEES;
            cmd.timestamp = api.timestamp;
            cmd.orderId = api.transactionId;
            cmd.symbol = api.symbol;
            cmd.action = api.action;
            cmd.price = api.fundingRate;
            cmd.size = api.rateScaleK;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiSettlePNL> SETTLE_PNL_TRANSLATOR = (cmd, seq, api) -> {
        cmd.command = OrderCommandType.SETTLE_PNL;
        cmd.timestamp = api.timestamp;
        cmd.orderId = api.transactionId;
        cmd.symbol = api.symbol;
        cmd.price = api.settlePrice;
        cmd.resultCode = CommandResultCode.NEW;
    };

    private static final EventTranslatorOneArg<OrderCommand, ApiResetFee> RESET_FEE_TRANSLATOR = (cmd, seq, api) -> {
        cmd.command = OrderCommandType.RESET_FEE;
        cmd.timestamp = api.timestamp;
        cmd.resultCode = CommandResultCode.NEW;
    };

    private static final EventTranslatorOneArg<OrderCommand, ApiMoveOrder> MOVE_ORDER_TRANSLATOR = (cmd, seq, api) -> {
        cmd.command = OrderCommandType.MOVE_ORDER;
        cmd.price = api.newPrice;
        cmd.orderId = api.orderId;
        cmd.symbol = api.symbol;
        cmd.uid = api.uid;
        cmd.timestamp = api.timestamp;
        cmd.resultCode = CommandResultCode.NEW;
    };

    private static final EventTranslatorOneArg<OrderCommand, ApiCancelOrder> CANCEL_ORDER_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.CANCEL_ORDER;
            cmd.orderId = api.orderId;
            cmd.symbol = api.symbol;
            cmd.uid = api.uid;
            cmd.timestamp = api.timestamp;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiReduceOrder> REDUCE_ORDER_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.REDUCE_ORDER;
            cmd.orderId = api.orderId;
            cmd.symbol = api.symbol;
            cmd.uid = api.uid;
            cmd.size = api.reduceSize;
            cmd.timestamp = api.timestamp;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiClosePosition> CLOSE_POSITION_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.CLOSE_POSITION;
            cmd.price = api.price;
            cmd.reserveBidPrice = api.price;
            cmd.size = api.size;
            cmd.orderId = api.orderId;
            cmd.timestamp = api.timestamp;
            cmd.action = api.action;
            cmd.orderType = OrderType.GTC;
            cmd.symbol = api.symbol;
            cmd.uid = api.uid;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiOrderBookRequest> ORDER_BOOK_REQUEST_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.ORDER_BOOK_REQUEST;
            cmd.symbol = api.symbol;
            cmd.size = api.size;
            cmd.timestamp = api.timestamp;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiAddUser> ADD_USER_TRANSLATOR = (cmd, seq, api) -> {
        cmd.command = OrderCommandType.ADD_USER;
        cmd.uid = api.uid;
        cmd.timestamp = api.timestamp;
        cmd.resultCode = CommandResultCode.NEW;
    };

    private static final EventTranslatorOneArg<OrderCommand, ApiSuspendUser> SUSPEND_USER_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.SUSPEND_USER;
            cmd.uid = api.uid;
            cmd.timestamp = api.timestamp;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiResumeUser> RESUME_USER_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.RESUME_USER;
            cmd.uid = api.uid;
            cmd.timestamp = api.timestamp;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiAdjustUserBalance> ADJUST_USER_BALANCE_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.BALANCE_ADJUSTMENT;
            cmd.orderId = api.transactionId;
            cmd.symbol = api.currency;
            cmd.uid = api.uid;
            cmd.price = api.amount;
            cmd.orderType = OrderType.of(api.adjustmentType.getCode());
            cmd.timestamp = api.timestamp;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiReset> RESET_TRANSLATOR = (cmd, seq, api) -> {
        cmd.command = OrderCommandType.RESET;
        cmd.timestamp = api.timestamp;
        cmd.resultCode = CommandResultCode.NEW;
    };

    private static final EventTranslatorOneArg<OrderCommand, ApiNop> NOP_TRANSLATOR = (cmd, seq, api) -> {
        cmd.command = OrderCommandType.NOP;
        cmd.timestamp = api.timestamp;
        cmd.resultCode = CommandResultCode.NEW;
    };

    private static final EventTranslatorOneArg<OrderCommand,
        ApiSystemLiquidationNotify> SYSTEM_LIQUIDATION_NOTIFY_TRANSLATOR = (cmd, seq, api) -> {
            cmd.command = OrderCommandType.SYSTEM_LIQUIDATION_NOTIFY;
            cmd.timestamp = api.timestamp;
            cmd.takerFundEvents = api.fundEvent;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiIFTakeOver> IF_TAKEOVER_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.IF_TAKEOVER;
            cmd.timestamp = api.timestamp;
            cmd.orderId = api.orderId;
            cmd.uid = api.uid;
            cmd.symbol = api.symbol;
            cmd.action = api.action;
            cmd.size = api.size;
            cmd.price = api.price;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiAutoDeleveraging> ADL_TRANSLATOR = (cmd, seq, api) -> {
        cmd.command = OrderCommandType.AUTO_DELEVERAGING;
        cmd.timestamp = api.timestamp;
        cmd.orderId = api.orderId;
        cmd.uid = api.uid;
        cmd.symbol = api.symbol;
        cmd.action = api.action;
        cmd.size = api.size;
        cmd.price = api.price;
        cmd.resultCode = CommandResultCode.NEW;
    };

    private static final EventTranslatorOneArg<OrderCommand, ApiInsuranceFundDeposit> IF_DEPOSIT_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.IF_DEPOSIT;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.shardId; // uid 承载定向 shardId（IF_DEPOSIT/WITHDRAW 无真实 uid）
            cmd.orderId = api.transactionId;
            cmd.symbol = api.symbol;
            cmd.price = api.currencyAmount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiInsuranceFundWithdraw> IF_WITHDRAW_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.IF_WITHDRAW;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.shardId;
            cmd.orderId = api.transactionId;
            cmd.symbol = api.symbol;
            cmd.price = api.currencyAmount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    // ============================== loan 子域 translators（详见 loan.md §5 + LoanCommandHandlers 头部 field 映射约定）==============================

    private static final EventTranslatorOneArg<OrderCommand, ApiLoanCreate> LOAN_CREATE_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_CREATE;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.orderId = api.externalId;
            cmd.reserveBidPrice = api.loanId;
            cmd.symbol = api.symbol;
            cmd.size = api.collateralAmount;
            cmd.price = api.principal;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiLoanRepay> LOAN_REPAY_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_REPAY;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.orderId = api.externalId;
            cmd.reserveBidPrice = api.loanId;
            cmd.price = api.repayAmount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiLoanAddCollateral> LOAN_ADD_COLLATERAL_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_ADD_COLLATERAL;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.orderId = api.externalId;
            cmd.reserveBidPrice = api.loanId;
            cmd.size = api.amount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiLoanReleaseCollateral> LOAN_RELEASE_COLLATERAL_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_RELEASE_COLLATERAL;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.orderId = api.externalId;
            cmd.reserveBidPrice = api.loanId;
            cmd.size = api.amount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiLoanCrossAddCollateral> LOAN_CROSS_ADD_COLLATERAL_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_CROSS_ADD_COLLATERAL;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.orderId = api.externalId;
            cmd.symbol = api.currency;
            cmd.size = api.amount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiLoanCrossWithdrawCollateral> LOAN_CROSS_WITHDRAW_COLLATERAL_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_CROSS_WITHDRAW_COLLATERAL;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.orderId = api.externalId;
            cmd.symbol = api.currency;
            cmd.size = api.amount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiLoanCrossBorrow> LOAN_CROSS_BORROW_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_CROSS_BORROW;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.orderId = api.externalId;
            cmd.reserveBidPrice = api.loanId;
            cmd.symbol = api.loanCurrency;
            cmd.price = api.principal;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiLoanCrossRepay> LOAN_CROSS_REPAY_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.LOAN_CROSS_REPAY;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.uid;
            cmd.orderId = api.externalId;
            cmd.reserveBidPrice = api.loanId;
            cmd.price = api.repayAmount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    // POOL_*：uid 承载 shardId（跟 IF_DEPOSIT/WITHDRAW 同款 pattern，无真实 uid）
    private static final EventTranslatorOneArg<OrderCommand, ApiPoolDeposit> POOL_DEPOSIT_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.POOL_DEPOSIT;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.shardId;
            cmd.orderId = api.externalId;
            cmd.symbol = api.currency;
            cmd.size = api.amount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiPoolWithdraw> POOL_WITHDRAW_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.POOL_WITHDRAW;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.shardId;
            cmd.orderId = api.externalId;
            cmd.symbol = api.currency;
            cmd.size = api.amount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final EventTranslatorOneArg<OrderCommand, ApiPoolAbsorbBadDebt> POOL_ABSORB_BAD_DEBT_TRANSLATOR =
        (cmd, seq, api) -> {
            cmd.command = OrderCommandType.POOL_ABSORB_BAD_DEBT;
            cmd.timestamp = api.timestamp;
            cmd.uid = api.shardId;
            cmd.orderId = api.externalId;
            cmd.symbol = api.currency;
            cmd.size = api.amount;
            cmd.resultCode = CommandResultCode.NEW;
        };

    private static final Map<Class<? extends ApiCommand>,
        EventTranslatorOneArg<OrderCommand, ? extends ApiCommand>> TRANSLATORS;
    static {
        Map<Class<? extends ApiCommand>, EventTranslatorOneArg<OrderCommand, ? extends ApiCommand>> m = new HashMap<>();
        m.put(ApiPlaceOrder.class, PLACE_ORDER_TRANSLATOR);
        m.put(ApiMoveOrder.class, MOVE_ORDER_TRANSLATOR);
        m.put(ApiCancelOrder.class, CANCEL_ORDER_TRANSLATOR);
        m.put(ApiReduceOrder.class, REDUCE_ORDER_TRANSLATOR);
        m.put(ApiClosePosition.class, CLOSE_POSITION_TRANSLATOR);
        m.put(ApiOrderBookRequest.class, ORDER_BOOK_REQUEST_TRANSLATOR);
        m.put(ApiAddUser.class, ADD_USER_TRANSLATOR);
        m.put(ApiAdjustUserBalance.class, ADJUST_USER_BALANCE_TRANSLATOR);
        m.put(ApiResumeUser.class, RESUME_USER_TRANSLATOR);
        m.put(ApiSuspendUser.class, SUSPEND_USER_TRANSLATOR);
        m.put(ApiAdjustLeverage.class, ADJUST_LEVERAGE_TRANSLATOR);
        m.put(ApiAdjustPositionMode.class, ADJUST_POSITION_MODE_TRANSLATOR);
        m.put(ApiAdjustMargin.class, ADJUST_MARGIN_TRANSLATOR);
        m.put(ApiAdjustMarkPrice.class, ADJUST_MARK_PRICE_TRANSLATOR);
        m.put(ApiSettleFundingFees.class, SETTLE_FUNDING_FEES_TRANSLATOR);
        m.put(ApiSettlePNL.class, SETTLE_PNL_TRANSLATOR);
        m.put(ApiResetFee.class, RESET_FEE_TRANSLATOR);
        m.put(ApiLiquidationOrder.class, LIQUIDATION_ORDER_TRANSLATOR);
        m.put(ApiLoanForceLiquidate.class, LOAN_FORCE_LIQUIDATE_TRANSLATOR);
        m.put(ApiLoanCrossForceLiquidate.class, LOAN_CROSS_FORCE_LIQUIDATE_TRANSLATOR);
        m.put(ApiNop.class, NOP_TRANSLATOR);
        m.put(ApiReset.class, RESET_TRANSLATOR);
        m.put(ApiSystemLiquidationNotify.class, SYSTEM_LIQUIDATION_NOTIFY_TRANSLATOR);
        m.put(ApiIFTakeOver.class, IF_TAKEOVER_TRANSLATOR);
        m.put(ApiAutoDeleveraging.class, ADL_TRANSLATOR);
        m.put(ApiInsuranceFundDeposit.class, IF_DEPOSIT_TRANSLATOR);
        m.put(ApiInsuranceFundWithdraw.class, IF_WITHDRAW_TRANSLATOR);
        m.put(ApiLoanCreate.class, LOAN_CREATE_TRANSLATOR);
        m.put(ApiLoanRepay.class, LOAN_REPAY_TRANSLATOR);
        m.put(ApiLoanAddCollateral.class, LOAN_ADD_COLLATERAL_TRANSLATOR);
        m.put(ApiLoanReleaseCollateral.class, LOAN_RELEASE_COLLATERAL_TRANSLATOR);
        m.put(ApiLoanCrossAddCollateral.class, LOAN_CROSS_ADD_COLLATERAL_TRANSLATOR);
        m.put(ApiLoanCrossWithdrawCollateral.class, LOAN_CROSS_WITHDRAW_COLLATERAL_TRANSLATOR);
        m.put(ApiLoanCrossBorrow.class, LOAN_CROSS_BORROW_TRANSLATOR);
        m.put(ApiLoanCrossRepay.class, LOAN_CROSS_REPAY_TRANSLATOR);
        m.put(ApiPoolDeposit.class, POOL_DEPOSIT_TRANSLATOR);
        m.put(ApiPoolWithdraw.class, POOL_WITHDRAW_TRANSLATOR);
        m.put(ApiPoolAbsorbBadDebt.class, POOL_ABSORB_BAD_DEBT_TRANSLATOR);
        TRANSLATORS = Map.copyOf(m);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EventTranslatorOneArg<OrderCommand, ApiCommand> resolveTranslator(ApiCommand cmd) {
        final EventTranslatorOneArg t = TRANSLATORS.get(cmd.getClass());
        if (t == null) {
            throw new IllegalArgumentException("Unsupported command type for batch: " + cmd.getClass().getSimpleName());
        }
        return t;
    }
}
