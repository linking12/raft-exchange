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
package exchange.core2.core.processors;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ObjLongConsumer;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import exchange.core2.collections.objpool.ObjectsPool;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.ADLUserPosition;
import exchange.core2.core.common.BalanceAdjustmentType;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.api.binary.BatchAddAccountsCommand;
import exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.api.reports.ReportQuery;
import exchange.core2.core.common.api.reports.ReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.LoggingConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.ReportsQueriesConfiguration;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import exchange.core2.core.processors.liquidation.ADLUserPositionHelper;
import exchange.core2.core.processors.liquidation.IFService;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.SerializationUtils;
import exchange.core2.core.utils.UnsafeUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesMarshallable;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * Stateful risk engine
 */
@Slf4j
@Getter
public final class RiskEngine implements WriteBytesMarshallable {

    // 这些都是有状态的字段 state
    private SymbolSpecificationProvider symbolSpecificationProvider;
    private CurrencySpecificationProvider currencySpecificationProvider;
    private UserProfileService userProfileService;
    private IFService ifService;
    private BinaryCommandsProcessor binaryCommandsProcessor;
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
    private IntLongHashMap fees;
    private IntLongHashMap adjustments;
    private IntLongHashMap suspends;

    // 无状态的配置字段
    private final SharedPool sharedPool;
    private final ObjectsPool objectsPool;
    // sharding by symbolId
    private final int shardId;
    private final int numShards;
    private final long shardMask;
    private final String exchangeId; // TODO validate
    private final boolean cfgIgnoreRiskProcessing;
    private final boolean cfgMarginTradingEnabled;
    private final ISerializationProcessor serializationProcessor;
    private final boolean logDebug;
    private final ReportsQueriesConfiguration reportsQueriesConfiguration;
    private final ObjLongConsumer<OrderCommand> resultsConsumer;
    private final LiquidationEngine liquidationEngine;
    private final FundEventsHelper eventsHelper;
    private final ADLUserPositionHelper adlUserPositionHelper;

    public RiskEngine(final int shardId,
                      final int numShards,
                      final ISerializationProcessor serializationProcessor,
                      final SharedPool sharedPool,
                      final ExchangeConfiguration exchangeConfiguration,
                      final ObjLongConsumer<OrderCommand> resultsConsumer) {
        if (Long.bitCount(numShards) != 1) {
            throw new IllegalArgumentException("Invalid number of shards " + numShards + " - must be power of 2");
        }
        this.exchangeId = exchangeConfiguration.getInitStateCfg().getExchangeId();
        this.shardId = shardId;
        this.numShards = numShards;
        this.shardMask = numShards - 1;
        this.serializationProcessor = serializationProcessor;
        this.sharedPool = sharedPool;
        // initialize object pools
        final HashMap<Integer, Integer> objectsPoolConfig = new HashMap<>();
        objectsPoolConfig.put(ObjectsPool.SYMBOL_POSITION_RECORD, 1024 * 256);
        this.objectsPool = new ObjectsPool(objectsPoolConfig);
        this.logDebug = exchangeConfiguration.getLoggingCfg().getLoggingLevels().contains(LoggingConfiguration.LoggingLevel.LOGGING_RISK_DEBUG);
        final OrdersProcessingConfiguration ordersProcCfg = exchangeConfiguration.getOrdersProcessingCfg();
        this.cfgIgnoreRiskProcessing = ordersProcCfg.getRiskProcessingMode() == OrdersProcessingConfiguration.RiskProcessingMode.NO_RISK_PROCESSING;
        this.cfgMarginTradingEnabled = ordersProcCfg.getMarginTradingMode() == OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_ENABLED;
        this.reportsQueriesConfiguration = exchangeConfiguration.getReportsQueriesCfg();
        this.resultsConsumer = resultsConsumer;
        this.liquidationEngine = new LiquidationEngine(sharedPool::getFundEventChain, shardId, numShards);
        this.eventsHelper = new FundEventsHelper(sharedPool::getFundEventChain, shardId, numShards);
        this.adlUserPositionHelper = new ADLUserPositionHelper(sharedPool::getADLCandidateChain);
        this.initState();
    }
    
    private void initState() {
        this.symbolSpecificationProvider = new SymbolSpecificationProvider();
        this.currencySpecificationProvider = new CurrencySpecificationProvider();
        this.userProfileService = new UserProfileService();
        this.ifService = new IFService();
        this.binaryCommandsProcessor = new BinaryCommandsProcessor(
            this::handleBinaryMessage,
            this::handleReportQuery,
            sharedPool, 
            reportsQueriesConfiguration, 
            shardId);
        this.lastPriceCache = new IntObjectHashMap<LastPriceCacheRecord>();
        this.fees = new IntLongHashMap();
        this.adjustments = new IntLongHashMap();
        this.suspends = new IntLongHashMap();
        this.eventsHelper.setSymbolSpecificationProvider(this.symbolSpecificationProvider);
        this.eventsHelper.setCurrencySpecificationProvider(this.currencySpecificationProvider);
        this.eventsHelper.setUserProfileService(this.userProfileService);
        this.eventsHelper.setLastPriceCache(this.lastPriceCache);
        if (this.resultsConsumer instanceof SimpleEventsProcessor) {
            SimpleEventsProcessor simpleEventsProcessor = (SimpleEventsProcessor) this.resultsConsumer;
            simpleEventsProcessor.setNumShards(numShards);
            simpleEventsProcessor.setSymbolSpecificationProvider(this.symbolSpecificationProvider);
            simpleEventsProcessor.saveUserProfileService(shardId, this.userProfileService);
        }
        this.liquidationEngine.updateProvider(symbolSpecificationProvider, currencySpecificationProvider, userProfileService, lastPriceCache);
    }

    
    private void recoverStateBySnapshot(long snapshotId) {
        final State state = serializationProcessor.loadData(snapshotId,
            ISerializationProcessor.SerializedModuleType.RISK_ENGINE, shardId, bytesIn -> {
                if (shardId != bytesIn.readInt()) {
                    throw new IllegalStateException("wrong shardId");
                }
                if (shardMask != bytesIn.readLong()) {
                    throw new IllegalStateException("wrong shardMask");
                }
                final SymbolSpecificationProvider symbolSpecificationProvider =
                    new SymbolSpecificationProvider(bytesIn);
                final CurrencySpecificationProvider currencySpecificationProvider =
                    new CurrencySpecificationProvider(bytesIn);
                final UserProfileService userProfileService = new UserProfileService(bytesIn);
                final IFService ifService = new IFService(bytesIn);
                final BinaryCommandsProcessor binaryCommandsProcessor =
                    new BinaryCommandsProcessor(
                        this::handleBinaryMessage, 
                        this::handleReportQuery, 
                        sharedPool,
                        reportsQueriesConfiguration, 
                        bytesIn, 
                        shardId);
                final IntObjectHashMap<LastPriceCacheRecord> lastPriceCache =
                    SerializationUtils.readIntHashMap(bytesIn, LastPriceCacheRecord::new);
                final IntLongHashMap fees = SerializationUtils.readIntLongHashMap(bytesIn);
                final IntLongHashMap adjustments = SerializationUtils.readIntLongHashMap(bytesIn);
                final IntLongHashMap suspends = SerializationUtils.readIntLongHashMap(bytesIn);
                return new State(symbolSpecificationProvider, currencySpecificationProvider, userProfileService,
                    ifService, binaryCommandsProcessor, lastPriceCache, fees, adjustments, suspends);
            });
        if (state.lastPriceCache == null || state.fees == null) {
            throw new IllegalStateException("Invalid recovered state: missing critical fields");
        }
        synchronized (this) {
            this.symbolSpecificationProvider = state.symbolSpecificationProvider;
            this.currencySpecificationProvider = state.currencySpecificationProvider;
            this.userProfileService = state.userProfileService;
            this.ifService = state.ifService;
            this.binaryCommandsProcessor = state.binaryCommandsProcessor;
            this.lastPriceCache = state.lastPriceCache;
            this.eventsHelper.setSymbolSpecificationProvider(this.symbolSpecificationProvider);
            this.eventsHelper.setCurrencySpecificationProvider(this.currencySpecificationProvider);
            this.eventsHelper.setUserProfileService(this.userProfileService);
            this.eventsHelper.setLastPriceCache(this.lastPriceCache);
            if (this.resultsConsumer instanceof SimpleEventsProcessor) {
                SimpleEventsProcessor simpleEventsProcessor = (SimpleEventsProcessor) this.resultsConsumer;
                simpleEventsProcessor.setSymbolSpecificationProvider(this.symbolSpecificationProvider);
                simpleEventsProcessor.saveUserProfileService(shardId, this.userProfileService);
            }
            this.liquidationEngine.updateProvider(symbolSpecificationProvider, currencySpecificationProvider, userProfileService, lastPriceCache);
            this.fees = state.fees;
            this.adjustments = state.adjustments;
            this.suspends = state.suspends;
        }
    }
    
    @ToString
    public static class LastPriceCacheRecord implements BytesMarshallable, StateHash {
        public long askPrice = Long.MAX_VALUE;
        public long bidPrice = 0L;
        public long markPrice = 0L;

        public LastPriceCacheRecord() {
        }

        public LastPriceCacheRecord(long askPrice, long bidPrice, long markPrice) {
            this.askPrice = askPrice;
            this.bidPrice = bidPrice;
            this.markPrice = markPrice;
        }

        public LastPriceCacheRecord(BytesIn bytes) {
            this.askPrice = bytes.readLong();
            this.bidPrice = bytes.readLong();
            this.markPrice = bytes.readLong();
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeLong(askPrice);
            bytes.writeLong(bidPrice);
            bytes.writeLong(markPrice);
        }

        public LastPriceCacheRecord averagingRecord() {
            LastPriceCacheRecord average = new LastPriceCacheRecord();
            average.askPrice = (this.askPrice + this.bidPrice) >> 1;
            average.bidPrice = average.askPrice;
            average.markPrice = this.markPrice;
            return average;
        }

        public static LastPriceCacheRecord dummy = new LastPriceCacheRecord(42, 42,42);

        @Override
        public int stateHash() {
            return Objects.hash(askPrice, bidPrice, markPrice);
        }
    }


    /**
     * Pre-process command handler
     * 1. MOVE/CANCEL commands ignored, for specific uid marked as valid for matching engine
     * 2. PLACE ORDER checked with risk ending for specific uid
     * 3. ADD USER, BALANCE_ADJUSTMENT processed for specific uid, not valid for matching engine
     * 4. BINARY_DATA commands processed for ANY uid and marked as valid for matching engine TODO which handler marks?
     * 5. RESET commands processed for any uid
     *
     * @param cmd - command
     * @param seq - command sequence
     * @return true if caller should publish sequence even if batch was not processed yet
     */
    public boolean preProcessCommand(final long seq, final OrderCommand cmd) {
        switch (cmd.command) {
            case MOVE_ORDER:
            case CANCEL_ORDER:
            case REDUCE_ORDER:
            case ORDER_BOOK_REQUEST:
                return false;

            case CLOSE_POSITION:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = closePositionRiskCheck(cmd);
                }
                return false;

            case PLACE_ORDER:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = placeOrderRiskCheck(cmd);
                }
                return false;

            case ADD_USER:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = userProfileService.addEmptyUserProfile(cmd.uid)
                            ? CommandResultCode.SUCCESS
                            : CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS;
                }
                return false;

            case MARGIN_ADJUSTMENT:
                if (uidForThisHandler(cmd.uid)) {
                    if (cmd.price <= 0) {
                        cmd.resultCode = CommandResultCode.RISK_INVALID_AMOUNT;
                        return false;
                    }
                    final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
                    if (userProfile == null) {
                        cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
                        return false;
                    }
                    if (cmd.marginMode == MarginMode.CROSS) {
                        cmd.resultCode = adjustBalance(cmd.uid, cmd.symbol, cmd.price, cmd.orderId, BalanceAdjustmentType.ADJUSTMENT);
                        if (cmd.resultCode == CommandResultCode.SUCCESS) {
                            long locked = calculateLockedMargin(userProfile, cmd.symbol);
                            long free = userProfile.accounts.get(cmd.symbol) - locked;
                            // 所有同currency的全仓仓位都通知
                            userProfile.positions.select(pos -> pos.marginMode == MarginMode.CROSS && pos.currency == cmd.symbol)
                                .forEach(pos -> eventsHelper.sendMarginAdjustmentEvent(cmd, pos, free, locked));
                        }
                    } else {
                        SymbolPositionRecord pos = userProfile.positions.get(userProfile.createPositionsKey(cmd.symbol, cmd.action, cmd.command));
                        if (pos == null) {
                            cmd.resultCode = CommandResultCode.RISK_MARGIN_POSITION_NOT_EXISTS;
                            return false;
                        }
                        if (pos.marginMode != cmd.marginMode) {
                            cmd.resultCode = CommandResultCode.RISK_MARGIN_MODE_MISMATCH;
                            return false;
                        }
                        // 复用提款的校验
                        final long currentBalance = userProfile.accounts.get(pos.currency);
                        long freeFuturesMargin = calcFreeFuturesMargin(userProfile, pos.currency);
                        if (currentBalance + freeFuturesMargin - cmd.price < 0) {
                            cmd.resultCode = CommandResultCode.RISK_NSF;
                            return false;
                        }
                        // 划转
                        long balance = userProfile.accounts.addToValue(pos.currency, -cmd.price);
                        CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(pos.currency);
                        CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(pos.symbol);
                        pos.extraMargin += CoreArithmeticUtils.currencyToSymbolScale(cmd.price, spec, currencySpec);
                        cmd.resultCode = CommandResultCode.SUCCESS;
                        // send event
                        long locked = calculateLockedMargin(userProfile, pos.currency);
                        eventsHelper.sendMarginAdjustmentEvent(cmd, pos, balance - locked, locked);
                    }
                }
                return false;

            case BALANCE_ADJUSTMENT:
                if (uidForThisHandler(cmd.uid)) {
                    final long uid = cmd.uid;
                    final int currency = cmd.symbol;
                    final long amountDiff = cmd.price;
                    final UserProfile userProfile = userProfileService.getUserProfile(uid);
                    if (userProfile == null) {
                        cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
                        return false;
                    }
                    final long currentBalance = userProfile.accounts.get(cmd.symbol);
                    // 如果是提款操作，并且杠杆交易，需要校验下最小保证金
                    if (amountDiff < 0 && cfgMarginTradingEnabled) {
                        long withdrawalAmount = -amountDiff;
                        long freeFuturesMargin = calcFreeFuturesMargin(userProfile, currency);
                        if (currentBalance + freeFuturesMargin - withdrawalAmount < 0) {
                            cmd.resultCode = CommandResultCode.RISK_NSF;
                            return false;
                        }
                    }
                    cmd.resultCode = adjustBalance(
                            cmd.uid, cmd.symbol, cmd.price, cmd.orderId, BalanceAdjustmentType.of(cmd.orderType.getCode()));
                    /**
                     * @modify 存款/提现
                     */
                    if (cmd.resultCode == CommandResultCode.SUCCESS) {
                        long locked = calculateLockedMargin(userProfile, currency);
                        long balance = userProfile.accounts.get(cmd.symbol); // 获取调整后的余额
                        if (amountDiff > 0) {
                            eventsHelper.sendDepositEvent(cmd, currency, balance - locked, locked);
                        } else {
                            eventsHelper.sendWithdrawEvent(cmd, currency, balance - locked, locked);
                        }
                    }
                }
                return false;

            case MARKPRICE_ADJUSTMENT: {
                final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
                if (spec == null) {
                    cmd.resultCode = CommandResultCode.INVALID_SYMBOL;
                    return false;
                }
                LastPriceCacheRecord priceRecord = lastPriceCache.getIfAbsentPut(cmd.symbol, LastPriceCacheRecord::new);
                priceRecord.markPrice = cmd.price;
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;
            }

            case SUSPEND_USER:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = userProfileService.suspendUserProfile(cmd.uid);
                }
                return false;
            case RESUME_USER:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = userProfileService.resumeUserProfile(cmd.uid);
                }
                return false;

            case FORCE_LIQUIDATION:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = normalizeCmdPositionSize(cmd);
                }
                return false;
            case LEVERAGE_ADJUSTMENT:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = adjustLeverage(cmd);
                }
                return false;

            case POSITION_MODE_ADJUSTMENT:
                if (uidForThisHandler(cmd.uid)) {
                    final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
                    if (userProfile == null) {
                        cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
                        return false;
                    }
                    PositionMode positionMode = PositionMode.of(cmd.action.getCode());
                    if (userProfile.positionMode == positionMode) {
                        cmd.resultCode = CommandResultCode.SUCCESS;
                        return false;
                    }
                    if (!userProfile.positions.isEmpty()) {
                        cmd.resultCode = CommandResultCode.RISK_MARGIN_POSITION_EXISTS;
                        return false;
                    }
                    userProfile.positionMode = positionMode;
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;

            case SETTLE_FUNDINGFEES: {
                final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
                if (spec == null || spec.type != SymbolType.FUTURES_CONTRACT_PERPETUAL) {
                    cmd.resultCode = CommandResultCode.INVALID_SYMBOL;
                    return false;
                }
                LastPriceCacheRecord priceRecord = lastPriceCache.get(cmd.symbol);
                if (priceRecord == null) {
                    cmd.resultCode = CommandResultCode.RISK_MARKPRICE_NOT_AVAILABLE;
                    return false;
                }
                settleFundingFees(cmd);
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;
            }
            case SETTLE_PNL: {
                final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
                if (spec == null || spec.type != SymbolType.FUTURES_CONTRACT_DELIVERY) {
                    cmd.resultCode = CommandResultCode.INVALID_SYMBOL;
                    return false;
                }
                settlePnl(cmd);
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;
            }
            case RESET_FEE: {
                fees.forEachKeyValue((currency, amount) -> {
                    fees.addToValue(currency, -amount);
                    adjustments.addToValue(currency, +amount);
                    eventsHelper.sendResetFeeEvent(cmd, currency, amount);
                });
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;
            }
            case BINARY_DATA_COMMAND:
            case BINARY_DATA_QUERY:
                binaryCommandsProcessor.acceptBinaryFrame(cmd); // ignore return result, because it should be set by MatchingEngineRouter
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return false;

            case RESET:
                reset();
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;

            case PERSIST_STATE_MATCHING:
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return true;// true = publish sequence before finishing processing whole batch

            case PERSIST_STATE_RISK:
                final boolean isSuccess = serializationProcessor.storeData(
                        cmd.orderId,
                        seq,
                        cmd.timestamp,
                        ISerializationProcessor.SerializedModuleType.RISK_ENGINE,
                        shardId,
                        this);
                UnsafeUtils.setResultVolatile(cmd, isSuccess, CommandResultCode.SUCCESS, CommandResultCode.STATE_PERSIST_RISK_ENGINE_FAILED);
                return false;
            case RECOVER_STATE_RISK:
                recoverStateBySnapshot(cmd.orderId);
                UnsafeUtils.setResultVolatile(cmd, true, CommandResultCode.SUCCESS, CommandResultCode.STATE_RECOVER_RISK_ENGINE_FAILED);
                return false;
            case RECOVER_STATE_MATCHING: {
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return true;
            }
            case SYSTEM_LIQUIDATION_NOTIFY: {
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;
            }
            case AUTO_DELEVERAGING: {
                collectADLProfitablePositions(cmd);
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = normalizeCmdPositionSize(cmd);
                }
                return false;
            }
        }
        return false;
    }

    // 对于强平或者adl命令，下单时候的仓位可能和真实仓位不一样了，需要重新校准一下size
    private CommandResultCode normalizeCmdPositionSize(final OrderCommand cmd) {
        final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
        if (userProfile == null) {
            return CommandResultCode.AUTH_INVALID_USER;
        }
        int positionRecordKey = userProfile.createPositionsKey(cmd.symbol, cmd.action, cmd.command);
        SymbolPositionRecord position = userProfile.positions.get(positionRecordKey);
        if (position == null) {
            return CommandResultCode.SUCCESS;
        }
        // 再校准一次size，这样ME那边用的时候size一定是准的
        cmd.size = Math.min(position.openVolume, cmd.size);
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    private void collectADLProfitablePositions(final OrderCommand cmd) {
        final int symbol = cmd.symbol;
        final long bankruptcyPrice = cmd.price;
        long remaining = cmd.size;
        if (remaining <= 0) {
            return;
        }
        /* ====== 1. 快照过滤 + 排序（R1 决定 score） ====== */
        MutableList<SymbolPositionRecord> profitablePositions = userProfileService.getProfitablePositionsBySymbol(symbol)
                .select(pos -> {
                    if (pos.openVolume <= 0) return false;
                    if (pos.openVolume <= pos.pendingADLSize) return false;
                    if (pos.direction.isSameAsAction(cmd.action)) return false;
                    long unrealizedPnl = pos.direction.getMultiplier() * (bankruptcyPrice * pos.openVolume - pos.openPriceSum);
                    return unrealizedPnl > 0;
                })
                .sortThisByLong(pos -> ADLUserPositionHelper.riskScore(pos, bankruptcyPrice))
                .reverseThis(); // score 从大到小
        if (profitablePositions.isEmpty()) {
            return;
        }
        /* ====== 2. 正序挂到 cmd（尾插，保持排序结果） ====== */
        ADLUserPosition head = null;
        ADLUserPosition tail = null;
        for (SymbolPositionRecord pos : profitablePositions) {
            if (remaining <= 0) break;

            long available = pos.openVolume - pos.pendingADLSize;
            long canTake = Math.min(available, remaining);
            // 冻结待处理的仓位数量（防止重复触发）
            pos.pendingADLSize += canTake;

            ADLUserPosition adlPos = adlUserPositionHelper.newADLUserPosition();
            adlPos.uid = pos.uid;
            adlPos.symbol = symbol;
            adlPos.direction = pos.direction;
            adlPos.volume = canTake;
            adlPos.score = ADLUserPositionHelper.riskScore(pos, bankruptcyPrice);

            // 尾插，保证正序
            if (head == null) {
                head = adlPos;
            } else {
                tail.next = adlPos;
            }
            tail = adlPos;

            remaining -= canTake;
        }
        /* ====== 3. 写入固定槽位（只写自己的shard） ====== */
        if (head != null) {
            if (cmd.adlUserPositionsByShard == null) {
                cmd.adlUserPositionsByShard = new ADLUserPosition[numShards];
            }
            cmd.adlUserPositionsByShard[shardId] = head;
        }
    }

    /**
     * 用于提现/划转判断，在 {@link #calculateLockedMargin} 基础上还考虑了pnl
     * 结果会缩放到currency精度
     */
    private long calcFreeFuturesMargin(final UserProfile userProfile, final int currency) {
        return calcFreeFuturesMargin(userProfile, currency, -1);
    }

    /**
     * 用于现货下单判断判断，逐仓的未实现盈亏能参与当前symbol的分摊
     * 结果会缩放到currency精度
     */
    private long calcFreeFuturesMargin(final UserProfile userProfile, final int currency, final int curPosSymbol) {
        long totalRealizedPnl = 0L;
        long totalUnrealizedPnl = 0L;
        long totalIsolateRequire = 0L;
        long totalCrossRequireByInitMargin = 0L;
        long totalCrossRequireByMaintainceMargin = 0L;
        CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        for (final SymbolPositionRecord position : userProfile.positions) {
            if (position.currency == currency) {
                final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                final LastPriceCacheRecord lastPrice = lastPriceCache.get(position.symbol);
                if (position.marginMode == MarginMode.CROSS) {
                    totalUnrealizedPnl += CoreArithmeticUtils.sizePriceToCurrencyScale(position.estimateUnrealizedProfit(lastPrice), spec, currencySpec);
                    long requiredMarginForFutures = position.calculateRequiredMarginForFutures(spec);
                    totalCrossRequireByInitMargin += CoreArithmeticUtils.sizePriceToCurrencyScale(requiredMarginForFutures, spec, currencySpec);
                    long crossMaintainDelta = requiredMarginForFutures - position.openInitMarginSum + position.calculateMaintenanceMargin(spec, lastPrice);
                    totalCrossRequireByMaintainceMargin += CoreArithmeticUtils.sizePriceToCurrencyScale(crossMaintainDelta, spec, currencySpec);
                } else {
                    if (curPosSymbol == position.symbol) {
                        // 逐仓的未实现盈亏只能参与当前symbol的分摊
                        totalUnrealizedPnl += position.estimateUnrealizedProfit(lastPrice);
                    }
                    totalIsolateRequire += CoreArithmeticUtils.sizePriceToCurrencyScale(position.calculateRequiredMarginForFutures(spec), spec, currencySpec);
                }
                totalRealizedPnl += CoreArithmeticUtils.sizePriceToCurrencyScale(position.profit, spec, currencySpec);
            }
        }
        return Math.min(
            // 如果算上了未实现盈亏，则全仓按初始初始保证金扣除
            totalRealizedPnl + totalUnrealizedPnl - totalCrossRequireByInitMargin - totalIsolateRequire,
            // 如果完全不算未实现盈亏，则全仓按维持保证金扣除
            totalRealizedPnl - totalCrossRequireByMaintainceMargin - totalIsolateRequire
        );
    }

    private CommandResultCode adjustBalance(long uid, int currency, long amountDiff, long fundingTransactionId, BalanceAdjustmentType adjustmentType) {
        final CommandResultCode res = userProfileService.balanceAdjustment(uid, currency, amountDiff, fundingTransactionId);
        if (res == CommandResultCode.SUCCESS) {
            switch (adjustmentType) {
                case ADJUSTMENT: // adjust total adjustments amount
                    adjustments.addToValue(currency, -amountDiff);
                    break;

                case SUSPEND: // adjust total suspends amount
                    suspends.addToValue(currency, -amountDiff);
                    break;
            }
        }
        return res;
    }
    
    private void handleBinaryMessage(BinaryDataCommand message) {

        if (message instanceof BatchAddCurrenciesCommand) {

            final IntObjectHashMap<CoreCurrencySpecification> currencies = ((BatchAddCurrenciesCommand) message).getCurrencies();
            currencies.forEach(spec -> {
                currencySpecificationProvider.addCurrency(spec);
            });

        } else if (message instanceof BatchAddSymbolsCommand) {

            final IntObjectHashMap<CoreSymbolSpecification> symbols = ((BatchAddSymbolsCommand) message).getSymbols();
            symbols.forEach(spec -> {
                if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR || cfgMarginTradingEnabled) {
                    symbolSpecificationProvider.addSymbol(spec);
                } else {
                    log.warn("Margin symbols are not allowed: {}", spec);
                }
            });

        } else if (message instanceof BatchAddAccountsCommand) {

            ((BatchAddAccountsCommand) message).getUsers().forEachKeyValue((uid, accounts) -> {
                if (userProfileService.addEmptyUserProfile(uid)) {
                    accounts.forEachKeyValue((cur, bal) ->
                            adjustBalance(uid, cur, bal, 1_000_000_000 + cur, BalanceAdjustmentType.ADJUSTMENT));
                } else {
                    log.debug("User already exist: {}", uid);
                }
            });
        }
    }

    private <R extends ReportResult> Optional<R> handleReportQuery(ReportQuery<R> reportQuery) {
        return reportQuery.process(this);
    }

    public boolean uidForThisHandler(final long uid) {
        return (shardMask == 0) || ((uid & shardMask) == shardId);
    }

    private CommandResultCode adjustLeverage(final OrderCommand cmd) {
        final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            log.warn("User profile {} not found", cmd.uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }

        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            log.warn("Symbol {} not found", cmd.symbol);
            return CommandResultCode.INVALID_SYMBOL;
        }

        List<SymbolPositionRecord> positions = FastList.newList();
        userProfile.processPositionRecord(cmd.symbol, positions::add);
        // 没有仓位不修改
        if (positions.isEmpty()) {
            return CommandResultCode.SUCCESS;
        }
        // 检查用户杠杆是否超过限制
        LastPriceCacheRecord priceRecord = lastPriceCache.get(cmd.symbol);
        long oldRequired = 0L;
        long newRequired = 0L;
        for (SymbolPositionRecord position : positions) {
            long notional = position.estimateNotionalForOrder(null, 0, priceRecord.markPrice);
            if (!spec.isValidLeverage(notional, cmd.leverage)) {
                return CommandResultCode.RISK_INVALID_LEVERAGE;
            }
            oldRequired += position.calculateRequiredMarginForFutures(spec);
            newRequired += position.calculateRequiredMarginForFutures(spec, cmd.leverage);
        }
        // 检查保证金变化是否在可承受范围内
        if (newRequired > oldRequired) {
            long balance = userProfile.accounts.get(spec.quoteCurrency);
            long locked = calculateLockedMargin(userProfile, spec.quoteCurrency);
            // 修改杠杆后新增的保证金占用 > 可以余额，不让修改
            CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);
            long diff = CoreArithmeticUtils.sizePriceToCurrencyScale(newRequired - oldRequired, spec, currencySpec);
            if (diff > (balance - locked)) {
                return CommandResultCode.RISK_NSF;
            }
        }
        // 修改杠杆
        for (SymbolPositionRecord position : positions) {
            position.updateLeverage(cmd.leverage);
        }
        return CommandResultCode.SUCCESS;
    }

    private CommandResultCode closePositionRiskCheck(final OrderCommand cmd) {
        final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            log.warn("User profile {} not found", cmd.uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }
        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            log.warn("Symbol {} not found", cmd.symbol);
            return CommandResultCode.INVALID_SYMBOL;
        }
        if (cfgIgnoreRiskProcessing) {
            // skip processing
            return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        }

        if (!SymbolType.isFuturesContract(spec.type)) {
            return CommandResultCode.UNSUPPORTED_SYMBOL_TYPE;
        }
        if (!cfgMarginTradingEnabled) {
            return CommandResultCode.RISK_MARGIN_TRADING_DISABLED;
        }

        int positionRecordKey = userProfile.createPositionsKey(spec.symbolId, cmd.action, cmd.command);
        SymbolPositionRecord position = userProfile.positions.get(positionRecordKey);
        if (position == null) {
            return CommandResultCode.SUCCESS;
        }
        long pendingClose = (cmd.action == OrderAction.ASK) ? position.pendingSellSize : position.pendingBuySize;
        long closable = Math.max(0, position.openVolume - pendingClose);
        long closeSize = Math.min(cmd.size, closable);
        if (closeSize <= 0) {
            return CommandResultCode.SUCCESS;
        }
        cmd.size = closeSize;
        cmd.leverage = position.getLeverage();
        cmd.marginMode = position.marginMode;

        position.pendingHold(cmd.action, cmd.size, cmd.price);
        long totalBalance = userProfile.accounts.get(position.currency);
        long lockedMargin = calculateLockedMargin(userProfile, position.currency);
        long free = totalBalance - lockedMargin;
        eventsHelper.sendLockPendingEvent(cmd, position, free, lockedMargin);

        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    private CommandResultCode placeOrderRiskCheck(final OrderCommand cmd) {

        final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            log.warn("User profile {} not found", cmd.uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }

        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            log.warn("Symbol {} not found", cmd.symbol);
            return CommandResultCode.INVALID_SYMBOL;
        }

        if (cfgIgnoreRiskProcessing) {
            // skip processing
            return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        }

        // check if account has enough funds
        final CommandResultCode resultCode = placeOrder(cmd, userProfile, spec);

        if (resultCode != CommandResultCode.VALID_FOR_MATCHING_ENGINE) {
            log.warn("{} risk result={} uid={}: Can not place {}", cmd.orderId, resultCode, userProfile.uid, cmd);
            log.warn("{} accounts:{}", cmd.orderId, userProfile.accounts);
            return resultCode;
        }

        return resultCode;
    }

    private CommandResultCode placeOrder(final OrderCommand cmd,
                                         final UserProfile userProfile,
                                         final CoreSymbolSpecification spec) {


        if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR) {

            return placeExchangeOrder(cmd, userProfile, spec);

        } else if (SymbolType.isFuturesContract(spec.type)) {

            if (!cfgMarginTradingEnabled) {
                return CommandResultCode.RISK_MARGIN_TRADING_DISABLED;
            }
            // 没有markPrice拒绝下单
            LastPriceCacheRecord priceRecord = lastPriceCache.get(cmd.symbol);
            if (priceRecord == null || priceRecord.markPrice == 0) {
                return CommandResultCode.RISK_MARKPRICE_NOT_AVAILABLE;
            }
            // 检查用户已有的仓位模式和现有的仓位模式是否匹配，在同一币种下只能开一种模式
            if (userProfile.countPositionRecord(spec.symbolId,
                    pos -> pos.marginMode != cmd.marginMode) > 0) {
                return CommandResultCode.RISK_MARGIN_MODE_MISMATCH;
            }
            // 检查用户已有的仓位杠杆和现有的杠杆是否匹配，在同一币种下只能开同一杠杆
            if (userProfile.countPositionRecord(spec.symbolId,
                    pos -> !pos.isSameLeverage(cmd.leverage)) > 0) {
                return CommandResultCode.RISK_LEVERAGE_MISMATCH;
            }

            int positionRecordKey = userProfile.createPositionsKey(spec.symbolId, cmd.action, cmd.command);
            SymbolPositionRecord position = userProfile.positions.get(positionRecordKey);
            if (position == null) {
                position = objectsPool.get(ObjectsPool.SYMBOL_POSITION_RECORD, SymbolPositionRecord::new);
                position.initialize(userProfile.uid, spec.symbolId, spec.quoteCurrency, cmd.action, cmd.leverage, cmd.marginMode);
                userProfile.positions.put(positionRecordKey, position);
            }
            // 检查用户杠杆是否超过symbol的杠杆限制
            long notional = position.estimateNotionalForOrder(cmd.action, cmd.size, priceRecord.markPrice);
            if (!spec.isValidLeverage(notional, cmd.leverage)) {
                return CommandResultCode.RISK_INVALID_LEVERAGE;
            }

            // calculateLockedMargin 仅统计仓位实际冻结的开仓保证金；
            // 而canPlaceMarginOrder还会考虑浮动盈亏(pnl)，只有在总余额减去浮亏后仍能覆盖新增保证金与手续费，才允许挂单。
            CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(position.currency);
            final boolean canPlaceOrder = canPlaceMarginOrder(cmd, userProfile, spec, position, currencySpec);
            if (canPlaceOrder) {
                position.pendingHold(cmd.action, cmd.size, cmd.price);
                long totalBalance = userProfile.accounts.get(position.currency);
                long lockedMargin = calculateLockedMargin(userProfile, position.currency);
                long free = totalBalance - lockedMargin;
                eventsHelper.sendLockPendingEvent(cmd, position, free, lockedMargin);
                return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
            } else {
                // try to cleanup position if refusing to place
                if (position.isEmpty()) {
                    removePositionRecord(spec, position, userProfile, currencySpec);
                }
                return CommandResultCode.RISK_NSF;
            }

        } else {
            return CommandResultCode.UNSUPPORTED_SYMBOL_TYPE;
        }
    }

    private CommandResultCode placeExchangeOrder(final OrderCommand cmd,
                                                 final UserProfile userProfile,
                                                 final CoreSymbolSpecification spec) {

        final int currency = (cmd.action == OrderAction.BID) ? spec.quoteCurrency : spec.baseCurrency;

        // futures positions check for this currency
        long freeFuturesMargin = 0L;
        if (cfgMarginTradingEnabled) {
            freeFuturesMargin = calcFreeFuturesMargin(userProfile, currency, spec.symbolId);
        }

        final long size = cmd.size;
        long orderHoldAmount;
        CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        if (cmd.action == OrderAction.BID) {

            if (cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET) {

                if (cmd.reserveBidPrice != cmd.price) {
                    //log.warn("reserveBidPrice={} less than price={}", cmd.reserveBidPrice, cmd.price);
                    return CommandResultCode.RISK_INVALID_RESERVE_BID_PRICE;
                }

                orderHoldAmount = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(size, cmd.price, spec);
                if (logDebug) CoreArithmeticUtils.logAmountBidTakerFeeForBudget(orderHoldAmount, size, cmd.price, spec);

            } else {

                if (cmd.reserveBidPrice < cmd.price) {
                    //log.warn("reserveBidPrice={} less than price={}", cmd.reserveBidPrice, cmd.price);
                    return CommandResultCode.RISK_INVALID_RESERVE_BID_PRICE;
                }
                orderHoldAmount = CoreArithmeticUtils.calculateAmountBidTakerFee(size, cmd.reserveBidPrice, spec);
                if (logDebug) CoreArithmeticUtils.logAmountBidTakerFee(orderHoldAmount, size, cmd.reserveBidPrice, spec);
            }
            orderHoldAmount = CoreArithmeticUtils.sizePriceToCurrencyScale(orderHoldAmount, spec, currencySpec);
        } else {

            if (CoreArithmeticUtils.isAskPriceTooLow(cmd.price, spec)) {
                // log.debug("cmd.price {} * spec.quoteScaleK {} < {} spec.takerFee", cmd.price, spec.quoteScaleK, spec.takerFee);
                // todo also check for move command
                return CommandResultCode.RISK_ASK_PRICE_LOWER_THAN_FEE;
            }

            orderHoldAmount = CoreArithmeticUtils.calculateAmountAsk(size);
            if (logDebug) log.debug("hold sell {}", orderHoldAmount);
            orderHoldAmount = CoreArithmeticUtils.symbolToCurrencyScale(orderHoldAmount, spec, currencySpec);
        }

        if (logDebug) {
            log.debug("R1 uid={} : orderHoldAmount={} vs userProfile.accounts.get({})={} + freeFuturesMargin={}",
                    userProfile.uid, orderHoldAmount, currency, userProfile.accounts.get(currency), freeFuturesMargin);
        }

        // speculative change balance
        long balance = userProfile.accounts.addToValue(currency, -orderHoldAmount);

        final boolean canPlace = balance + freeFuturesMargin >= 0;

        if (!canPlace) {
            // revert balance change
            userProfile.accounts.addToValue(currency, orderHoldAmount);
            // log.warn("orderAmount={} > userProfile.accounts.get({})={}", orderAmount, currency, userProfile.accounts.get(currency));
            return CommandResultCode.RISK_NSF;
        } else {
            /**
             * @modify 冻结资金
             */
            long locked = calculateLockedMargin(userProfile, currency);
            this.eventsHelper.sendLockEvent(cmd, spec.symbolId, currency, balance - locked, locked);
            return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        }
    }


    /**
     * Checks:
     * 1. Users account balance
     * 2. Margin
     * 3. Current limit orders
     * <p>
     * NOTE: Current implementation does not care about accounts and positions quoted in different currencies
     *
     * @param cmd         - order command
     * @param userProfile - user profile
     * @param spec        - symbol specification
     * @param position    - users position
     * @return true if placing is allowed
     */
    private boolean canPlaceMarginOrder(final OrderCommand cmd,
                                        final UserProfile userProfile,
                                        final CoreSymbolSpecification spec,
                                        final SymbolPositionRecord position,
                                        final CoreCurrencySpecification currencySpec) {
        final long extraNotional = (cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET) ? cmd.price : cmd.size * cmd.price;
        final long newRequiredMarginForSymbol = position.calculateRequiredMarginForOrder(spec, cmd.action, extraNotional);
        if (newRequiredMarginForSymbol == -1) {
            // always allow placing a new order if it would not increase exposure
            return true;
        }

        // extra margin is required

        final int symbol = cmd.symbol;
        // calculate free margin for all positions same currency
        long freeMargin = 0L;
        for (final SymbolPositionRecord positionRecord : userProfile.positions) {
            final int recSymbol = positionRecord.symbol;
            if (recSymbol != symbol) {
                if (positionRecord.currency == spec.quoteCurrency) {
                    final CoreSymbolSpecification spec2 = symbolSpecificationProvider.getSymbolSpecification(recSymbol);
                    // add P&L subtract margin
                    freeMargin += positionRecord.estimatePnl(lastPriceCache.get(recSymbol));
                    freeMargin -= positionRecord.calculateRequiredMarginForFutures(spec2);
                }
            } else {
                freeMargin += position.estimatePnl(lastPriceCache.get(spec.symbolId));
            }
        }

        // 下单时候要确保有足够手续费，R2阶段真实扣除手续费
        final long estimatedFee = position.calculatePendingFeeForOrder(spec, cmd.action, cmd.size, cmd.price);

//        log.debug("newMargin={} <= account({})={} + free {}",
//                newRequiredMarginForSymbol, position.currency, userProfile.accounts.get(position.currency), freeMargin);

        // check if current balance and margin can cover new required margin for symbol position
        long balance = userProfile.accounts.get(position.currency);
        long newRequired = newRequiredMarginForSymbol + estimatedFee - freeMargin;
        newRequired = CoreArithmeticUtils.sizePriceToCurrencyScale(newRequired, spec, currencySpec);
        return newRequired <= balance;
    }

    public boolean handlerRiskRelease(final long seq, final OrderCommand cmd) {

        final int symbol = cmd.symbol;

        final L2MarketData marketData = cmd.marketData;
        MatcherTradeEvent mte = cmd.matcherEvent;

        // skip events processing if no events (or if contains BINARY EVENT)
        if (mte == null || mte.eventType == MatcherEventType.BINARY_EVENT) {
            return false;
        }

        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
        if (spec == null) {
            throw new IllegalStateException("Symbol not found: " + symbol);
        }

        final boolean takerSell = cmd.action == OrderAction.ASK;

        if (mte != null && mte.eventType != MatcherEventType.BINARY_EVENT) {
            // at least one event to process, resolving primary/taker user profile
            // TODO processing order is reversed
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR) {

                final UserProfile takerUp = uidForThisHandler(cmd.uid)
                        ? userProfileService.getUserProfileOrAddSuspended(cmd.uid)
                        : null;

                // REJECT always comes first; REDUCE is always single event
                if (mte.eventType == MatcherEventType.REDUCE || mte.eventType == MatcherEventType.REJECT) {
                    if (takerUp != null) {
                        handleMatcherRejectReduceEventExchange(cmd, mte, spec, takerSell, takerUp);
                    }
                    mte = mte.nextEvent;
                }

                if (mte != null) {
                    if (takerSell) {
                        handleMatcherEventsExchangeSell(cmd, mte, spec, takerUp);
                    } else {
                        handleMatcherEventsExchangeBuy(mte, spec, takerUp, cmd);
                    }
                }
            } else {

                final UserProfile takerUp = uidForThisHandler(cmd.uid) ? userProfileService.getUserProfileOrAddSuspended(cmd.uid) : null;

                // for margin-mode symbols also resolve position record
                final SymbolPositionRecord takerSpr = (takerUp != null) ? takerUp.getPositionRecordOrThrowEx(takerUp.createPositionsKey(symbol, cmd.action, cmd.command)) : null;

                final CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);

                // ===== 1. 处理 matcherEvent 链 =====
                do {
                    if (cmd.command == OrderCommandType.AUTO_DELEVERAGING) {
                        handleADLRelease(cmd, mte, spec, currencySpec);
                    } else {
                        handleMatcherEventMargin(cmd, mte, spec, cmd.action, takerUp, takerSpr, currencySpec);
                    }
                    mte = mte.nextEvent;
                } while (mte != null);

                // ===== 2. cmd 结束后的收尾 =====
                if (cmd.command == OrderCommandType.AUTO_DELEVERAGING) {
                    finalizeADL(cmd, takerUp, takerSpr, spec, currencySpec);
                } else if (cmd.command == OrderCommandType.FORCE_LIQUIDATION) {
                    collectLiquidationFee(cmd, takerUp, takerSpr, spec, currencySpec);
                }

                // ===== 3.推进 liquidation 状态机 =====
                if (takerSpr != null) {
                    liquidationEngine.nextLiquidationState(cmd, takerSpr);
                }
            }
        }

        // update LastPriceRecord
        if (cfgMarginTradingEnabled) {
            final LastPriceCacheRecord record = lastPriceCache.getIfAbsentPut(symbol, LastPriceCacheRecord::new);
            // 优先使用市场数据，要求买一卖一都有深度
            if (marketData != null && marketData.askSize > 0 && marketData.bidSize > 0) {
                record.askPrice = marketData.askPrices[0];
                record.bidPrice = marketData.bidPrices[0];
            } else {
                // fallback：寻找第一笔有效成交事件
                MatcherTradeEvent firstTrade = cmd.matcherEvent;
                while (firstTrade != null && firstTrade.eventType != MatcherEventType.TRADE) {
                    firstTrade = firstTrade.nextEvent;
                }
                if (firstTrade != null) {
                    record.askPrice = firstTrade.price;
                    record.bidPrice = firstTrade.price;
                }
            }
        }
        return false;
    }
    
    /**
     * 计算用户在指定货币中的冻结保证金总额（包含pending部分，隐式锁定）。
     * 结果会缩放到currency精度。
     */
    private long calculateLockedMargin(UserProfile userProfile, int currency) {
        long locked = 0;
        CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        for (SymbolPositionRecord position : userProfile.positions) {
            if (position.currency == currency) {
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                long required = position.calculateRequiredMarginForFutures(spec);
                locked += CoreArithmeticUtils.sizePriceToCurrencyScale(required, spec, currencySpec);
            }
        }
        return locked;
    }

    /**
     * 永续合约计算fundingFee，多头给空头或者空头给多头
     * 无需跨分片协调：因为多空仓位总是对等，每个分片按自己视角加减同一金额，整体上是对称操作，系统资金不失衡。
     * 无精度尾差：资金费率都是整数，不存在浮点精度误差；long的向下取整，对称场景下（多空相等）是彼此抵消的。
     */
    private void settleFundingFees(OrderCommand cmd) {
        final int symbol = cmd.symbol;
        final long markPrice = lastPriceCache.get(cmd.symbol).markPrice;
        userProfileService.getUserProfiles().forEachValue(userProfile ->
            userProfile.processPositionRecord(symbol, position -> {
                if (position.openVolume == 0) {
                    return; // 跳过空仓位
                }
                long fundingFee = position.openVolume * markPrice * cmd.price / cmd.size;
                if (position.direction == PositionDirection.LONG) {
                    position.profit -= fundingFee;
                } else {
                    position.profit += fundingFee;
                }
                long balance = userProfile.accounts.get(position.currency);
                long locked = calculateLockedMargin(userProfile, position.currency);
                eventsHelper.sendFundingFeeEvent(cmd, position, balance - locked, locked);
            })
        );
    }

    /**
     * 实现合约交割
     * 将未实现盈亏转为已实现盈亏并更新账户余额。
     */
    private void settlePnl(OrderCommand cmd) {
        final int symbol = cmd.symbol;
        userProfileService.getUserProfiles().forEachValue(userProfile ->
            userProfile.processPositionRecord(symbol, position -> {
                if (position.openVolume == 0) {
                    return; // 跳过空仓位
                }
                // 1.关闭仓位
                OrderAction action = position.direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID;
                position.closeCurrentPositionFutures(action, position.openVolume, cmd.price);
                // 2.清算盈亏到账户余额
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
                CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(position.currency);
                refundExtraMargin(cmd, userProfile.uid, spec, position, userProfile, currencySpec);
                removePositionRecord(spec, position, userProfile, currencySpec);
                // 3.发送结算事件
                long balance = userProfile.accounts.get(position.currency);
                long locked = calculateLockedMargin(userProfile, position.currency);
                eventsHelper.sendPnlSettlementEvent(cmd, position, balance - locked, locked);
            })
        );
    }

    private void collectLiquidationFee(final OrderCommand cmd,
                                       final UserProfile takerUp,
                                       final SymbolPositionRecord takerSpr,
                                       final CoreSymbolSpecification spec,
                                       final CoreCurrencySpecification currencySpec) {
        if (takerSpr != null && cmd.matcherEvent.eventType != MatcherEventType.REJECT) {
            long takerSizeForThisHandler = 0L;
            long takerSizePriceForThisHandler = 0L;
            MatcherTradeEvent ev = cmd.matcherEvent;
            while (ev != null) {
                if (ev.eventType == MatcherEventType.TRADE) {
                    takerSizeForThisHandler += ev.size;
                    takerSizePriceForThisHandler += ev.size * ev.price;
                }
                ev = ev.nextEvent;
            }
            if (takerSizeForThisHandler == 0) {
                return;
            }
            long notional = CoreArithmeticUtils.calculateLiquidationFee(takerSizeForThisHandler, takerSizePriceForThisHandler, spec);
            long fee = CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, currencySpec);
            takerUp.accounts.addToValue(takerSpr.currency, -fee);
            ifService.addFee(takerSpr.symbol, takerSpr.currency, notional);
            // 强平费事件
            long locked = calculateLockedMargin(takerUp, spec.quoteCurrency);
            long free = takerUp.accounts.get(spec.quoteCurrency) - locked;
            eventsHelper.sendLiquidationFeeEvent(cmd, cmd.orderId, takerSpr, free, locked);
        }
    }

    private void handleADLRelease(final OrderCommand cmd,
                                  final MatcherTradeEvent ev,
                                  final CoreSymbolSpecification spec,
                                  final CoreCurrencySpecification currencySpec) {
        if (ev.eventType != MatcherEventType.ADL_EVENT) {
            return;
        }
        // 1. 取 ADL 目标用户
        final long uid = ev.matchedOrderUid;
        if (!uidForThisHandler(uid)) {
            return; // 不是本 shard 的用户，直接跳过
        }
        final UserProfile up = userProfileService.getUserProfile(uid);

        // 2. 找到被 ADL 的仓位（与cmd方向相反）
        OrderAction adlPosSide = cmd.action.opposite();
        final SymbolPositionRecord pos = up.positions.get(up.createPositionsKey(cmd.symbol, adlPosSide, cmd.command));

        // 3. 更新仓位信息
        pos.closeCurrentPositionFutures(adlPosSide.opposite(), ev.size, cmd.price);

        // ADL 平仓事件
        long locked = calculateLockedMargin(up, spec.quoteCurrency);
        long free = up.accounts.get(spec.quoteCurrency) - locked;
        eventsHelper.sendADLClosePositionEvent(cmd, cmd.orderId, pos, free, locked);
        if (pos.isEmpty()) {
            refundExtraMargin(cmd, cmd.orderId, spec, pos, up, currencySpec);
            removePositionRecord(spec, pos, up, currencySpec);
        }
    }

    private void finalizeADL(final OrderCommand cmd,
                             final UserProfile takerUp,
                             final SymbolPositionRecord takerSpr,
                             final CoreSymbolSpecification spec,
                             final CoreCurrencySpecification currencySpec) {
        // 1. 关闭原始仓位（只做一次）
        if (takerSpr != null && cmd.matcherEvent.eventType != MatcherEventType.REJECT) {
            takerSpr.closeCurrentPositionFutures(cmd.action.opposite(), cmd.size, cmd.price);
            // ADL 平仓事件
            long locked = calculateLockedMargin(takerUp, spec.quoteCurrency);
            long free = takerUp.accounts.get(spec.quoteCurrency) - locked;
            eventsHelper.sendADLClosePositionEvent(cmd, cmd.orderId, takerSpr, free, locked);
            if (takerSpr.isEmpty()) {
                refundExtraMargin(cmd, cmd.orderId, spec, takerSpr, takerUp, currencySpec);
                removePositionRecord(spec, takerSpr, takerUp, currencySpec);
            }
        }
        // 2. 释放所有 pendingADLSize
        ADLUserPosition head = cmd.adlUserPositionsByShard[shardId];
        while (head != null) {
            UserProfile up = userProfileService.getUserProfile(head.uid);
            SymbolPositionRecord pos = up.positions.get(up.createPositionsKey(head.symbol, cmd.action.opposite(), cmd.command));
            if (pos != null && pos.pendingADLSize > 0) {
                pos.pendingADLSize -= head.volume;
            }
            head = head.next;
        }
    }

    private void handleMatcherEventMargin(final OrderCommand cmd,
                                          final MatcherTradeEvent ev,
                                          final CoreSymbolSpecification spec,
                                          final OrderAction takerAction,
                                          final UserProfile takerUp,
                                          final SymbolPositionRecord takerSpr,
                                          final CoreCurrencySpecification currencySpec) {
        if (takerUp != null) {
            if (ev.eventType == MatcherEventType.TRADE) {
                // update taker's position
                long preVolume = takerSpr.openVolume;

                // un-hold pending
                long pendingReleasedSize = takerSpr.pendingRelease(takerAction, ev.size);
                if (pendingReleasedSize > 0) {
                    long totalBalance = takerUp.accounts.get(takerSpr.currency);
                    long lockedMargin = calculateLockedMargin(takerUp, takerSpr.currency);
                    long free = totalBalance - lockedMargin;
                    eventsHelper.sendUnlockPendingEvent(cmd, cmd.orderId, takerSpr, free, lockedMargin);
                }

                // 先平反方向仓位，返回剩余未成交数量
                final long sizeToOpen = takerSpr.closeCurrentPositionFutures(takerAction, ev.size, ev.price);
                long closedSize = Math.max(0, preVolume - takerSpr.openVolume);

                // 平仓事件
                if (closedSize > 0) {
                    long locked = calculateLockedMargin(takerUp, spec.quoteCurrency);
                    long free = takerUp.accounts.get(spec.quoteCurrency) - locked;
                    boolean isLiquidation = LiquidationEngine.isLiquidationOrderId(cmd.orderId, takerSpr.symbol, takerSpr.uid);
                    eventsHelper.sendClosePositionEvent(cmd, cmd.orderId, isLiquidation, takerSpr, free, locked);
                }

                // 开仓事件
                if (sizeToOpen > 0) {
                    // 再开新方向仓位
                    takerSpr.openPositionMargin(takerAction, sizeToOpen, ev.price, spec, lastPriceCache.get(spec.symbolId));

                    // 计算开仓手续费
                    long fee = CoreArithmeticUtils.calculateTakerFee(sizeToOpen, ev.price, spec);
                    fee = CoreArithmeticUtils.sizePriceToCurrencyScale(fee, spec, currencySpec);
                    long balance = takerUp.accounts.addToValue(spec.quoteCurrency, -fee);
                    fees.addToValue(spec.quoteCurrency, fee);
                    long locked = calculateLockedMargin(takerUp, spec.quoteCurrency);
                    long free = balance - locked;
                    eventsHelper.sendOpenPositionEvent(cmd, cmd.orderId, takerSpr, free, locked);
                }
            } else if (ev.eventType == MatcherEventType.REJECT || ev.eventType == MatcherEventType.REDUCE) {
                // for cancel/rejection only one party is involved
                /**
                 * 这里不需要动用户金额，因为cancel order总是把未成单的取消掉，因此总会走到后面的removePositionRecord
                 */
                takerSpr.pendingRelease(takerAction, ev.size);

                long totalBalance = takerUp.accounts.get(takerSpr.currency);
                long lockedMargin = calculateLockedMargin(takerUp, takerSpr.currency);
                long free = totalBalance - lockedMargin;
                eventsHelper.sendUnlockPendingEvent(cmd, cmd.orderId, takerSpr, free, lockedMargin);
            }
            if (takerSpr.isEmpty()) {
                refundExtraMargin(cmd, cmd.orderId, spec, takerSpr, takerUp, currencySpec);
                removePositionRecord(spec, takerSpr, takerUp, currencySpec);
            }
        }

        if (ev.eventType == MatcherEventType.TRADE && uidForThisHandler(ev.matchedOrderUid)) {
            // update maker's position
            UserProfile maker = userProfileService.getUserProfileOrAddSuspended(ev.matchedOrderUid);
            SymbolPositionRecord makerSpr = maker.getPositionRecordOrThrowEx(maker.createPositionsKey(spec.symbolId, takerAction.opposite(), ev.matchedOrderCommandType));
            long preVolume = makerSpr.openVolume;

            long pendingReleasedSize = makerSpr.pendingRelease(takerAction.opposite(), ev.size);
            if (pendingReleasedSize > 0) {
                long totalBalance = maker.accounts.get(makerSpr.currency);
                long lockedMargin = calculateLockedMargin(maker, makerSpr.currency);
                long free = totalBalance - lockedMargin;
                eventsHelper.sendUnlockPendingEvent(cmd, ev.matchedOrderId, makerSpr, free, lockedMargin);
            }

            // 先平仓
            final long sizeToOpen = makerSpr.closeCurrentPositionFutures(takerAction.opposite(), ev.size, ev.price);
            long sizeClosed = Math.max(0, preVolume - makerSpr.openVolume);

            // 计算平仓信息
            if (sizeClosed > 0) {
                long locked = calculateLockedMargin(maker, spec.quoteCurrency);
                long free = maker.accounts.get(spec.quoteCurrency) - locked;
                // Maker是被动成交者，不属于liquidation
                eventsHelper.sendClosePositionEvent(cmd, ev.matchedOrderId, false, makerSpr, free, locked);
            }

            if (sizeToOpen > 0) {
                makerSpr.openPositionMargin(takerAction.opposite(), sizeToOpen, ev.price, spec, lastPriceCache.get(spec.symbolId));

                long fee = CoreArithmeticUtils.calculateMakerFee(sizeToOpen, ev.price, spec);
                fee = CoreArithmeticUtils.sizePriceToCurrencyScale(fee, spec, currencySpec);
                long balance = maker.accounts.addToValue(spec.quoteCurrency, -fee);
                fees.addToValue(spec.quoteCurrency, fee);
                long locked = calculateLockedMargin(maker, spec.quoteCurrency);
                long free = balance - locked;
                eventsHelper.sendOpenPositionEvent(cmd, ev.matchedOrderId, makerSpr, free, locked);
            }
            if (makerSpr.isEmpty()) {
                refundExtraMargin(cmd, ev.matchedOrderId, spec, makerSpr, maker, currencySpec);
                removePositionRecord(spec, makerSpr, maker, currencySpec);
            }
        }

    }

    private void handleMatcherRejectReduceEventExchange(final OrderCommand cmd,
                                                        final MatcherTradeEvent ev,
                                                        final CoreSymbolSpecification spec,
                                                        final boolean takerSell,
                                                        final UserProfile taker) {

        //log.debug("REDUCE/REJECT {} {}", cmd, ev);

        // for cancel/rejection only one party is involved
        
        /**
         * 买单（BID）：
                下单时：冻结 quoteCurrency（如 USD），因为用户需要支付它来购买 baseCurrency（如 BTC）。
                拒绝/减少时：解冻 quoteCurrency，因为交易未完成，资金需要返还。
                
           卖单（ASK）：
                下单时：冻结 baseCurrency（如 BTC），因为用户需要提供它来出售换取 quoteCurrency（如 USD）。
                拒绝/减少时：解冻 baseCurrency，因为交易未完成，资产需要返还。
         * 
         */
        long balance;
        if (takerSell) {
            CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(spec.baseCurrency);
            long refund = CoreArithmeticUtils.calculateAmountAsk(ev.size);
            refund = CoreArithmeticUtils.symbolToCurrencyScale(refund, spec, currencySpec);
            balance = taker.accounts.addToValue(spec.baseCurrency, refund);
        } else {
            long refund;
            if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.orderType == OrderType.FOK_BUDGET) {
                refund = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(ev.size, ev.price, spec);
            } else if (cmd.orderType == OrderType.IOC_BUDGET && ev.nextEvent == null) {
                // IOC_BUDGET 且没有后续事件，表示订单被完全拒绝，全额返还
                refund = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(cmd.size, cmd.price, spec);
            } else {
                // 其他情况（包括 REDUCE 或非 IOC_BUDGET），释放指定大小的冻结资金
                refund = CoreArithmeticUtils.calculateAmountBidTakerFee(ev.size, ev.bidderHoldPrice, spec);
            }
            CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);
            refund = CoreArithmeticUtils.sizePriceToCurrencyScale(refund, spec, currencySpec);
            balance = taker.accounts.addToValue(spec.quoteCurrency, refund);
        }
        /**
         * @modify 恢复资金 买单解冻 quoteCurrency,卖单解冻 baseCurrency
         */
        long locked = calculateLockedMargin(taker, takerSell ? spec.baseCurrency : spec.quoteCurrency);
        this.eventsHelper.sendUnLockEvent(cmd, spec.symbolId, takerSell ? spec.baseCurrency : spec.quoteCurrency, balance - locked, locked);

    }


    private void handleMatcherEventsExchangeSell(final OrderCommand cmd,
                                                 MatcherTradeEvent ev,
                                                 final CoreSymbolSpecification spec,
                                                 final UserProfile taker) {

        //log.debug("TRADE EXCH SELL {}", ev);

        long takerSizeForThisHandler = 0L;
        long makerSizeForThisHandler = 0L;

        long takerSizePriceForThisHandler = 0L;
        long makerSizePriceForThisHandler = 0L;

        final int quoteCurrency = spec.quoteCurrency;
        final CoreCurrencySpecification baseCurrencySpec = currencySpecificationProvider.getCurrencySpecification(spec.baseCurrency);
        final CoreCurrencySpecification quoteCurrencySpec = currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);

        /**
         * 卖单（ASK）：
             Taker：支付 baseCurrency（BTC，已冻结），接收 quoteCurrency（USD）。
             Maker：支付 quoteCurrency（USD），接收 baseCurrency（BTC）。
         */
        
        while (ev != null) {
            assert ev.eventType == MatcherEventType.TRADE;

            // aggregate transfers for selling taker
            if (taker != null) {
                takerSizePriceForThisHandler += ev.size * ev.price;
                takerSizeForThisHandler += ev.size;
            }

            // process transfers for buying maker
            if (uidForThisHandler(ev.matchedOrderUid)) {
                final long size = ev.size;
                final UserProfile maker = userProfileService.getUserProfileOrAddSuspended(ev.matchedOrderUid);

                // buying, use bidderHoldPrice to calculate released amount based on price difference
                long amountDiffToReleaseInQuoteCurrency = CoreArithmeticUtils.calculateAmountBidReleaseCorrMaker(size, ev.bidderHoldPrice, ev.price, spec);
                amountDiffToReleaseInQuoteCurrency = CoreArithmeticUtils.sizePriceToCurrencyScale(amountDiffToReleaseInQuoteCurrency, spec, quoteCurrencySpec);
                // 支付 quoteCurrency
                long quoteCurrencyBalance = maker.accounts.addToValue(quoteCurrency, amountDiffToReleaseInQuoteCurrency);
                long gainedAmountInBaseCurrency = CoreArithmeticUtils.calculateAmountAsk(size);
                gainedAmountInBaseCurrency = CoreArithmeticUtils.symbolToCurrencyScale(gainedAmountInBaseCurrency, spec, baseCurrencySpec);
                // 接收 baseCurrency
                long baseCurrencyBalance = maker.accounts.addToValue(spec.baseCurrency, gainedAmountInBaseCurrency);
                /**
                 * @modify 资金转移
                 */
                long lockedMarginQuote = calculateLockedMargin(maker, quoteCurrency);
                long lockedMarginBase = calculateLockedMargin(maker, spec.baseCurrency);
                this.eventsHelper.sendTransferEvent(cmd, ev.matchedOrderId, maker.uid, quoteCurrency, spec.symbolId, quoteCurrencyBalance - lockedMarginQuote, lockedMarginQuote);
                this.eventsHelper.sendTransferEvent(cmd, ev.matchedOrderId, maker.uid, spec.baseCurrency, spec.symbolId, baseCurrencyBalance - lockedMarginBase, lockedMarginBase);

                makerSizePriceForThisHandler += ev.size * ev.price;
                makerSizeForThisHandler += size;
            }

            ev = ev.nextEvent;
        }

        if (taker != null) {
            // 支付 baseCurrency（已在冻结阶段处理）
            // 接收 quoteCurrency
            long fee = CoreArithmeticUtils.calculateTakerFee(takerSizeForThisHandler, takerSizePriceForThisHandler / takerSizeForThisHandler, spec);
            long toBeAdded = CoreArithmeticUtils.sizePriceToCurrencyScale(takerSizePriceForThisHandler - fee, spec, quoteCurrencySpec);
            long quoteCurrencyBalance = taker.accounts.addToValue(quoteCurrency, toBeAdded);
            /**
             * @modify 资金转移
             */
            long lockedMarginQuote = calculateLockedMargin(taker, quoteCurrency);
            this.eventsHelper.sendTransferEvent(cmd, cmd.orderId, taker.uid, quoteCurrency, spec.symbolId, quoteCurrencyBalance - lockedMarginQuote, lockedMarginQuote);
        }

        if (takerSizeForThisHandler != 0 || makerSizeForThisHandler != 0) {
            long avgTakerPrice = takerSizeForThisHandler > 0 ? takerSizePriceForThisHandler / takerSizeForThisHandler : 0;
            long takerFee = CoreArithmeticUtils.calculateTakerFee(takerSizeForThisHandler, avgTakerPrice, spec);

            long avgMakerPrice = makerSizeForThisHandler > 0 ? makerSizePriceForThisHandler / makerSizeForThisHandler : 0;
            long makerFee = CoreArithmeticUtils.calculateMakerFee(makerSizeForThisHandler, avgMakerPrice, spec);

            long toBeAdded = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee + makerFee, spec, quoteCurrencySpec);
            fees.addToValue(quoteCurrency, toBeAdded);
            /**
             * @TODO 把手续费加到Trade上面去？
             */
        }
    }

    private void handleMatcherEventsExchangeBuy(MatcherTradeEvent ev,
                                                final CoreSymbolSpecification spec,
                                                final UserProfile taker,
                                                final OrderCommand cmd) {
        //log.debug("TRADE EXCH BUY {}", ev);

        long takerSizeForThisHandler = 0L;
        long makerSizeForThisHandler = 0L;

        long takerSizePriceSum = 0L;
        long takerSizePriceHeldSum = 0L;
        long makerSizePriceSum = 0L;
        final int quoteCurrency = spec.quoteCurrency;
        final CoreCurrencySpecification baseCurrencySpec = currencySpecificationProvider.getCurrencySpecification(spec.baseCurrency);
        final CoreCurrencySpecification quoteCurrencySpec = currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);

        /**
        买单（BID）：
           Taker：支付 quoteCurrency（USD），接收 baseCurrency（BTC）。
           Maker：支付 baseCurrency（BTC，已冻结），接收 quoteCurrency（USD）。
         */
        while (ev != null) {
            assert ev.eventType == MatcherEventType.TRADE;

            // perform transfers for taker
            if (taker != null) {

                takerSizePriceSum += ev.size * ev.price;
                takerSizePriceHeldSum += ev.size * ev.bidderHoldPrice;

                takerSizeForThisHandler += ev.size;
            }

            // process transfers for maker
            if (uidForThisHandler(ev.matchedOrderUid)) {
                final long size = ev.size;
                final UserProfile maker = userProfileService.getUserProfileOrAddSuspended(ev.matchedOrderUid);
                final long gainedAmountInQuoteCurrency = CoreArithmeticUtils.calculateAmountBid(size, ev.price);
                // 支付 baseCurrency（已在冻结阶段处理）
                // 接收 quoteCurrency
                long fee = CoreArithmeticUtils.calculateMakerFee(size, ev.price, spec);
                long toBeAdded = CoreArithmeticUtils.sizePriceToCurrencyScale(gainedAmountInQuoteCurrency - fee, spec, quoteCurrencySpec);
                long balance = maker.accounts.addToValue(quoteCurrency, toBeAdded);
                /**
                 * @modify 资金转移
                 */
                long lockedMarginQuote = calculateLockedMargin(maker, quoteCurrency);
                this.eventsHelper.sendTransferEvent(cmd, ev.matchedOrderId, maker.uid, quoteCurrency, spec.symbolId, balance - lockedMarginQuote, lockedMarginQuote);

                makerSizePriceSum += ev.size * ev.price;
                makerSizeForThisHandler += size;
            }

            ev = ev.nextEvent;
        }

       
        if (taker != null) {
            long leftover = 0;
            if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.orderType == OrderType.FOK_BUDGET) {
                // for FOK budget held sum calculated differently
                takerSizePriceHeldSum = cmd.price; // FOK_BUDGET冻结的是总预算，即cmd.price，不是实际成交价
            } else if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.orderType == OrderType.IOC_BUDGET) {
                // IOC_BUDGET 部分成交
                // 1. 原始冻结总额
                long heldTotal = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(cmd.size, cmd.price, spec);
                // 2. 实际成交金额
                long actualMatchedAmount = takerSizePriceSum;
                // 3. 实际手续费，基于实际均价
                long actualFee = CoreArithmeticUtils.calculateTakerFee(takerSizeForThisHandler, takerSizePriceSum / takerSizeForThisHandler, spec);
                // 4. 差值 = 预扣 - 实际支出
                leftover = heldTotal - (actualMatchedAmount + actualFee);
                // 置为相等，这样 totalAdjustment 就只和 leftover 有关
                takerSizePriceHeldSum = takerSizePriceSum;
            } else {
                // 其他单子，都能部分成交，bidPrice和price有可能不一样，都要重新算
                long feeHeld = CoreArithmeticUtils.calculateTakerFee(takerSizeForThisHandler, takerSizePriceHeldSum / takerSizeForThisHandler, spec);
                long feeUsed = CoreArithmeticUtils.calculateTakerFee(takerSizeForThisHandler, takerSizePriceSum / takerSizeForThisHandler, spec);
                leftover = feeHeld - feeUsed;
            }
            long totalAdjustment = CoreArithmeticUtils.sizePriceToCurrencyScale(takerSizePriceHeldSum - takerSizePriceSum + leftover, spec, quoteCurrencySpec);
            long lockedMarginQuote = calculateLockedMargin(taker, quoteCurrency);
            // 支付 quoteCurrency
            long quoteCurrencyBalance = taker.accounts.addToValue(quoteCurrency, totalAdjustment);
            if (leftover > 0) {
                this.eventsHelper.sendUnLockEvent(cmd, spec.symbolId, quoteCurrency, quoteCurrencyBalance - lockedMarginQuote, lockedMarginQuote);
            }
            // 接收 baseCurrency
            long toBeAdded = CoreArithmeticUtils.symbolToCurrencyScale(takerSizeForThisHandler, spec, baseCurrencySpec);
            long baseCurrencyBalance = taker.accounts.addToValue(spec.baseCurrency, toBeAdded);
            /**
             * @modify 资金转移
             */
            long lockedMarginBase = calculateLockedMargin(taker, spec.baseCurrency);
            this.eventsHelper.sendTransferEvent(cmd, cmd.orderId, taker.uid, quoteCurrency, spec.symbolId, quoteCurrencyBalance - lockedMarginQuote, lockedMarginQuote);
            this.eventsHelper.sendTransferEvent(cmd, cmd.orderId, taker.uid, spec.baseCurrency, spec.symbolId, baseCurrencyBalance - lockedMarginBase, lockedMarginBase);
        }

        if (takerSizeForThisHandler != 0 || makerSizeForThisHandler != 0) {
            long avgTakerPrice = takerSizeForThisHandler > 0 ? takerSizePriceSum / takerSizeForThisHandler : 0;
            long avgMakerPrice = makerSizeForThisHandler > 0 ? makerSizePriceSum / makerSizeForThisHandler : 0;

            long takerFee = CoreArithmeticUtils.calculateTakerFee(takerSizeForThisHandler, avgTakerPrice, spec);
            long makerFee = CoreArithmeticUtils.calculateMakerFee(makerSizeForThisHandler, avgMakerPrice, spec);

            long toBeAdded = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee + makerFee, spec, quoteCurrencySpec);
            fees.addToValue(quoteCurrency, toBeAdded);
            /**
             * @TODO 把手续费加到Trade上面去？
             */
            
        }
    }

    private void refundExtraMargin(OrderCommand cmd, long orderId, CoreSymbolSpecification spec, SymbolPositionRecord record,
                                   UserProfile userProfile, CoreCurrencySpecification currencySpec) {
        if (record.extraMargin > 0) {
            long refund = CoreArithmeticUtils.symbolToCurrencyScale(record.extraMargin, spec, currencySpec);
            long balance = userProfile.accounts.addToValue(record.currency, refund);
            long locked = calculateLockedMargin(userProfile, record.currency);
            long free = balance - locked;
            eventsHelper.sendMarginRefundEvent(cmd, orderId, record, free, locked);
        }
    }

    private void removePositionRecord(CoreSymbolSpecification spec, SymbolPositionRecord record, UserProfile userProfile,
                                      CoreCurrencySpecification currencySpec) {
        if (record.profit != 0) {
            long profit = CoreArithmeticUtils.sizePriceToCurrencyScale(record.profit, spec, currencySpec);
            userProfile.accounts.addToValue(record.currency, profit);
        }
        userProfile.positions.removeKey(userProfile.createPositionsKey(record));
        userProfileService.getProfitablePositionsBySymbol(record.symbol).removeIf(pos -> pos == record);
        objectsPool.put(ObjectsPool.SYMBOL_POSITION_RECORD, record);
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {

        bytes.writeInt(shardId).writeLong(shardMask);

        symbolSpecificationProvider.writeMarshallable(bytes);
        currencySpecificationProvider.writeMarshallable(bytes);
        userProfileService.writeMarshallable(bytes);
        ifService.writeMarshallable(bytes);
        binaryCommandsProcessor.writeMarshallable(bytes);
        SerializationUtils.marshallIntHashMap(lastPriceCache, bytes);
        SerializationUtils.marshallIntLongHashMap(fees, bytes);
        SerializationUtils.marshallIntLongHashMap(adjustments, bytes);
        SerializationUtils.marshallIntLongHashMap(suspends, bytes);
    }

    public void reset() {
        userProfileService.reset();
        ifService.reset();
        symbolSpecificationProvider.reset();
        currencySpecificationProvider.reset();
        binaryCommandsProcessor.reset();
        lastPriceCache.clear();
        fees.clear();
        adjustments.clear();
        suspends.clear();
    }

    @AllArgsConstructor
    @Getter
    private static class State {
        private final SymbolSpecificationProvider symbolSpecificationProvider;
        private final CurrencySpecificationProvider currencySpecificationProvider;
        private final UserProfileService userProfileService;
        private final IFService ifService;
        private final BinaryCommandsProcessor binaryCommandsProcessor;
        private final IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
        private final IntLongHashMap fees;
        private final IntLongHashMap adjustments;
        private final IntLongHashMap suspends;
    }
}
