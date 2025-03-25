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
import java.util.Objects;
import java.util.Optional;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import exchange.core2.collections.objpool.ObjectsPool;
import exchange.core2.core.common.BalanceAdjustmentType;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.api.binary.BatchAddAccountsCommand;
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
    private UserProfileService userProfileService;
    private BinaryCommandsProcessor binaryCommandsProcessor;
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
    private IntLongHashMap fees;
    private IntLongHashMap adjustments;
    private IntLongHashMap suspends;
    
    
    // 无状态的配置字段
    private final SharedPool sharedPool;
    private final ObjectsPool objectsPool;
    private final FundEventsHelper eventsHelper;
    // sharding by symbolId
    private final int shardId;
    private final long shardMask;
    private final String exchangeId; // TODO validate
    private final boolean cfgIgnoreRiskProcessing;
    private final boolean cfgMarginTradingEnabled;
    private final ISerializationProcessor serializationProcessor;
    private final boolean logDebug;
    private ReportsQueriesConfiguration reportsQueriesConfiguration;
   

    public RiskEngine(final int shardId,
                      final long numShards,
                      final ISerializationProcessor serializationProcessor,
                      final SharedPool sharedPool,
                      final ExchangeConfiguration exchangeConfiguration) {
        if (Long.bitCount(numShards) != 1) {
            throw new IllegalArgumentException("Invalid number of shards " + numShards + " - must be power of 2");
        }
        this.exchangeId = exchangeConfiguration.getInitStateCfg().getExchangeId();
        this.shardId = shardId;
        this.shardMask = numShards - 1;
        this.serializationProcessor = serializationProcessor;
        this.sharedPool = sharedPool;
        this.eventsHelper = new FundEventsHelper(sharedPool::getFundEventPool);
        // initialize object pools
        final HashMap<Integer, Integer> objectsPoolConfig = new HashMap<>();
        objectsPoolConfig.put(ObjectsPool.SYMBOL_POSITION_RECORD, 1024 * 256);
        this.objectsPool = new ObjectsPool(objectsPoolConfig);
        this.logDebug = exchangeConfiguration.getLoggingCfg().getLoggingLevels().contains(LoggingConfiguration.LoggingLevel.LOGGING_RISK_DEBUG);
        final OrdersProcessingConfiguration ordersProcCfg = exchangeConfiguration.getOrdersProcessingCfg();
        this.cfgIgnoreRiskProcessing = ordersProcCfg.getRiskProcessingMode() == OrdersProcessingConfiguration.RiskProcessingMode.NO_RISK_PROCESSING;
        this.cfgMarginTradingEnabled = ordersProcCfg.getMarginTradingMode() == OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_ENABLED;
        this.reportsQueriesConfiguration = exchangeConfiguration.getReportsQueriesCfg();
        this.initState();
    }
    
    private void initState() {
        this.symbolSpecificationProvider = new SymbolSpecificationProvider();
        this.userProfileService = new UserProfileService();
        this.binaryCommandsProcessor = new BinaryCommandsProcessor(
            this::handleBinaryMessage,
            this::handleReportQuery,
            sharedPool, 
            reportsQueriesConfiguration, 
            shardId);
        this.lastPriceCache = new IntObjectHashMap<>();
        this.fees = new IntLongHashMap();
        this.adjustments = new IntLongHashMap();
        this.suspends = new IntLongHashMap();
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
                final UserProfileService userProfileService = new UserProfileService(bytesIn);
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
                return new State(symbolSpecificationProvider, userProfileService, binaryCommandsProcessor,
                    lastPriceCache, fees, adjustments, suspends);
            });
        if (state.lastPriceCache == null || state.fees == null) {
            throw new IllegalStateException("Invalid recovered state: missing critical fields");
        }
        synchronized (this) {
            this.symbolSpecificationProvider = state.symbolSpecificationProvider;
            this.userProfileService = state.userProfileService;
            this.binaryCommandsProcessor = state.binaryCommandsProcessor;
            this.lastPriceCache = state.lastPriceCache;
            this.fees = state.fees;
            this.adjustments = state.adjustments;
            this.suspends = state.suspends;
        }
    }
    
    @ToString
    public static class LastPriceCacheRecord implements BytesMarshallable, StateHash {
        public long askPrice = Long.MAX_VALUE;
        public long bidPrice = 0L;
        public long markPrice = 0;

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
            average.markPrice = average.askPrice;
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
                    if (amountDiff < 0 && cfgMarginTradingEnabled ) { 
                        long withdrawalAmount = -amountDiff;
                        long minRequiredBalance = calculateMinRequiredBalance(userProfile, cmd.symbol);
                        if (currentBalance - withdrawalAmount < minRequiredBalance) {
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
                        if (amountDiff > 0) {
                            eventsHelper.sendDepositEvent(cmd, uid, currency, currentBalance);
                        } else {
                            eventsHelper.sendWithdrawEvent(cmd, uid, currency, currentBalance);
                        }
                    }
                }
                return false;

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
            case SYSTEM_CHECK_POSITION: {
                checkAndLiquidateAllPositions(cmd);
                return false;
            }
            case SYSTEM_SETTLE_PNL: {
                settlePnl(cmd);
                return false;
            }
        }
        return false;
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

    private long calculateMinRequiredBalance(UserProfile userProfile, int currency) {
        long minRequired = 0;
        for (SymbolPositionRecord position : userProfile.positions) {
            if (position.currency == currency && position.direction != PositionDirection.EMPTY) {
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                minRequired += position.calculateMaintenanceMargin(spec);
            }
        }
        return minRequired;
    }
    
    private void handleBinaryMessage(BinaryDataCommand message) {

        if (message instanceof BatchAddSymbolsCommand) {

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
            return CommandResultCode.RISK_NSF;
        }

        return resultCode;
    }


    private CommandResultCode placeOrder(final OrderCommand cmd,
                                         final UserProfile userProfile,
                                         final CoreSymbolSpecification spec) {


        if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR) {

            return placeExchangeOrder(cmd, userProfile, spec);

        } else if (spec.type == SymbolType.FUTURES_CONTRACT) {

            if (!cfgMarginTradingEnabled) {
                return CommandResultCode.RISK_MARGIN_TRADING_DISABLED;
            }

            SymbolPositionRecord position = userProfile.positions.get(spec.symbolId); // TODO getIfAbsentPut?
            if (position == null) {
                position = objectsPool.get(ObjectsPool.SYMBOL_POSITION_RECORD, SymbolPositionRecord::new);
                position.initialize(userProfile.uid, spec.symbolId, spec.quoteCurrency);
                userProfile.positions.put(spec.symbolId, position);
            }

            final boolean canPlaceOrder = canPlaceMarginOrder(cmd, userProfile, spec, position);
            if (canPlaceOrder) {
                position.pendingHold(cmd.action, cmd.size);
                return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
            } else {
                // try to cleanup position if refusing to place
                if (position.isEmpty()) {
                    removePositionRecord(position, userProfile);
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
            for (final SymbolPositionRecord position : userProfile.positions) {
                if (position.currency == currency) {
                    final int recSymbol = position.symbol;
                    final CoreSymbolSpecification spec2 = symbolSpecificationProvider.getSymbolSpecification(recSymbol);
                    // add P&L subtract margin
                    freeFuturesMargin +=
                            (position.estimateProfit(spec2, lastPriceCache.get(recSymbol)) - position.calculateRequiredMarginForFutures(spec2));
                }
            }
        }

        final long size = cmd.size;
        final long orderHoldAmount;
        if (cmd.action == OrderAction.BID) {

            if (cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET) {

                if (cmd.reserveBidPrice != cmd.price) {
                    //log.warn("reserveBidPrice={} less than price={}", cmd.reserveBidPrice, cmd.price);
                    return CommandResultCode.RISK_INVALID_RESERVE_BID_PRICE;
                }

                orderHoldAmount = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(size, cmd.price, spec);
                if (logDebug) log.debug("hold amount budget buy {} = {} * {} + {} * {}", cmd.price, size, spec.quoteScaleK, size, spec.takerFee);

            } else {

                if (cmd.reserveBidPrice < cmd.price) {
                    //log.warn("reserveBidPrice={} less than price={}", cmd.reserveBidPrice, cmd.price);
                    return CommandResultCode.RISK_INVALID_RESERVE_BID_PRICE;
                }
                orderHoldAmount = CoreArithmeticUtils.calculateAmountBidTakerFee(size, cmd.reserveBidPrice, spec);
                if (logDebug) log.debug("hold amount buy {} = {} * ( {} * {} + {} )", orderHoldAmount, size, cmd.reserveBidPrice, spec.quoteScaleK, spec.takerFee);
            }

        } else {

            if (cmd.price * spec.quoteScaleK < spec.takerFee) {
                // log.debug("cmd.price {} * spec.quoteScaleK {} < {} spec.takerFee", cmd.price, spec.quoteScaleK, spec.takerFee);
                // todo also check for move command
                return CommandResultCode.RISK_ASK_PRICE_LOWER_THAN_FEE;
            }

            orderHoldAmount = CoreArithmeticUtils.calculateAmountAsk(size, spec);
            if (logDebug) log.debug("hold sell {} = {} * {} ", orderHoldAmount, size, spec.baseScaleK);
        }

        if (logDebug) {
            log.debug("R1 uid={} : orderHoldAmount={} vs serProfile.accounts.get({})={} + freeFuturesMargin={}",
                    userProfile.uid, orderHoldAmount, currency, userProfile.accounts.get(currency), freeFuturesMargin);
        }

        // speculative change balance
        long newBalance = userProfile.accounts.addToValue(currency, -orderHoldAmount);

        final boolean canPlace = newBalance + freeFuturesMargin >= 0;

        if (!canPlace) {
            // revert balance change
            long userBalance = userProfile.accounts.addToValue(currency, orderHoldAmount);
            // log.warn("orderAmount={} > userProfile.accounts.get({})={}", orderAmount, currency, userProfile.accounts.get(currency));
            /**
             * @modify 恢复资金
             */
            this.eventsHelper.sendUnLockEvent(cmd, userProfile.uid, currency, userBalance);
            return CommandResultCode.RISK_NSF;
        } else {
            /**
             * @modify 冻结资金
             */
            this.eventsHelper.sendLockEvent(cmd, userProfile.uid, currency, newBalance, orderHoldAmount);
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
                                        final SymbolPositionRecord position) {

        final long newRequiredMarginForSymbol = position.calculateRequiredMarginForOrder(spec, cmd.action, cmd.size);
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
                    freeMargin += positionRecord.estimateProfit(spec2, lastPriceCache.get(recSymbol));
                    //freeMargin 使用维持保证金，提升检查严格性
                    freeMargin -= positionRecord.calculateMaintenanceMargin(spec2);
                }
            } else {
                freeMargin = position.estimateProfit(spec, lastPriceCache.get(spec.symbolId));
            }
        }

//        log.debug("newMargin={} <= account({})={} + free {}",
//                newRequiredMarginForSymbol, position.currency, userProfile.accounts.get(position.currency), freeMargin);

        // check if current balance and margin can cover new required margin for symbol position
        return newRequiredMarginForSymbol <= userProfile.accounts.get(position.currency) + freeMargin;
    }

    public boolean handlerRiskRelease(final long seq, final OrderCommand cmd) {

        final int symbol = cmd.symbol;

        final L2MarketData marketData = cmd.marketData;
        MatcherTradeEvent mte = cmd.matcherEvent;

        // skip events processing if no events (or if contains BINARY EVENT)
        if (marketData == null && (mte == null || mte.eventType == MatcherEventType.BINARY_EVENT)) {
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
                        handleMatcherEventsExchangeSell(mte, spec, takerUp);
                    } else {
                        handleMatcherEventsExchangeBuy(mte, spec, takerUp, cmd);
                    }
                }
            } else {

                final UserProfile takerUp = uidForThisHandler(cmd.uid) ? userProfileService.getUserProfileOrAddSuspended(cmd.uid) : null;

                // for margin-mode symbols also resolve position record
                final SymbolPositionRecord takerSpr = (takerUp != null) ? takerUp.getPositionRecordOrThrowEx(symbol) : null;
                do {
                    handleMatcherEventMargin(mte, spec, cmd.action, takerUp, takerSpr);
                    mte = mte.nextEvent;
                } while (mte != null);
            }
        }

        // Process marked data
        if (marketData != null && cfgMarginTradingEnabled) {
            final RiskEngine.LastPriceCacheRecord record = lastPriceCache.getIfAbsentPut(symbol, RiskEngine.LastPriceCacheRecord::new);
            record.askPrice = (marketData.askSize != 0) ? marketData.askPrices[0] : Long.MAX_VALUE;
            record.bidPrice = (marketData.bidSize != 0) ? marketData.bidPrices[0] : 0;
            // 计算标记价格，简单取买卖价中值，提供平滑性（可扩展为更复杂算法）
            record.markPrice = (record.askPrice != Long.MAX_VALUE && record.bidPrice != 0) ? (record.askPrice + record.bidPrice) >> 1 : record.markPrice;
        }

        return false;
    }
    
    /**
     * 检查并强平所有用户的期货仓位，基于维持保证金规则，确保账户权益满足最低要求。
     * 
     * 业务逻辑：
     * 1. 遍历所有期货符号，获取对应的规格和最新价格数据，用于计算未实现盈亏和强平价格。
     * 2. 对每个用户，检查其持仓的账户权益（Equity = 可用余额 + 未实现盈亏）。
     * 3. 若权益低于维持保证金（Maintenance Margin），触发部分强平，计算并清算足够仓位以恢复权益至维持保证金水平。
     * 4. 若权益低于预警阈值（1.2 * 维持保证金）但高于维持保证金，发送 Margin Call 提醒用户追加资金，避免进一步恶化。
     * 5. 更新账户可用余额、持仓状态，并生成相应的事件（如强平或 Margin Call），记录交易细节。
     * 
     * 注意事项：
     * - accounts 表示扣除初始保证金后的可用余额，冻结的保证金通过余额减少隐式体现，不单独记录。
     * - 未实现盈亏基于 markPrice 计算，提供平滑的价格参考，但依赖价格数据的实时性。
     * - 强平价格优先使用市场价格（bid/ask），若无流动性则回退到开仓均价，可能影响盈亏准确性。
     * - 该方法为周期性检查的一部分，通常由外部调度（如定时器）触发，频率影响风险控制的及时性。
     */
    private void checkAndLiquidateAllPositions(OrderCommand cmd) {
        // 遍历所有符号，仅处理期货合约类型，确保不干扰现货交易
        symbolSpecificationProvider.getAllSymbols().forEach(symbol -> {
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
            if (spec.type != SymbolType.FUTURES_CONTRACT)
                return; // 跳过非期货符号
            // 获取最新价格记录，用于计算未实现盈亏和确定强平价格
            LastPriceCacheRecord priceRecord = lastPriceCache.get(symbol);
            if (priceRecord == null) {
                log.warn("No price record for symbol={}", symbol); // 缺少价格数据，跳过处理
                return;
            }
            // 遍历所有用户，仅处理当前分片（shard）且有持仓的用户，提高并行效率
            userProfileService.getAllUserProfiles().filter(up -> uidForThisHandler(up.uid)) // 确保只处理本分片用户
                .filter(up -> !up.positions.isEmpty()) // 跳过无持仓用户
                .forEach(userProfile -> {
                    SymbolPositionRecord position = userProfile.positions.get(symbol);
                    // 仅处理有持仓（方向非 EMPTY）的用户
                    if (position != null && position.direction != PositionDirection.EMPTY) {
                        // 当前账户可用余额，已扣除开仓时的初始保证金，反映用户可自由支配的资金
                        long balance = userProfile.accounts.get(spec.quoteCurrency);
                        // 未实现盈亏，基于标记价格（markPrice），反映持仓的市场价值变化
                        long profit = position.liquidateEstimateProfit(spec, priceRecord);
                        // 账户总权益 = 可用余额 + 未实现盈亏，表示账户的整体抗风险能力
                        long equity = balance + profit;
                        // 维持保证金，基于持仓量和规格定义的最低资金要求，若低于此值需强平
                        long maintenanceMargin = position.calculateMaintenanceMargin(spec);
                        // 预警阈值，设为维持保证金的 1.2 倍，用于提前提醒用户追加资金
                        long warningThreshold = (long)(maintenanceMargin * 1.2);
                        // 当前持仓所需的初始保证金，实时计算，已通过 accounts 减少隐式冻结
                        long locked = position.calculateRequiredMarginForFutures(spec);
                        // 权益低于维持保证金，触发强平以保护系统免受进一步亏损
                        if (equity < maintenanceMargin) {
                            // 计算资金缺口：需要多少权益恢复到维持保证金水平
                            long deficit = maintenanceMargin - equity;
                            // 强平价格：多头用买价（bidPrice），空头用卖价（askPrice），反映市场可执行价格
                            long price = position.direction == PositionDirection.LONG ? priceRecord.bidPrice : priceRecord.askPrice;
                            // 若市场无流动性（价格为 0 或 MAX_VALUE），回退到平均开仓价作为兜底
                            if (price == 0 || price == Long.MAX_VALUE) {
                                price = position.openVolume > 0 ? position.openPriceSum / position.openVolume : 0;
                                log.debug("Fallback to average open price={} for symbol={}", price, symbol);
                            }
                            // 计算需要清算的仓位数量：缺口 / 当前价格（向上取整），限制不超过当前持仓量
                            long sizeToLiquidate = Math.min(position.openVolume, (long)Math.ceil(deficit / (double)price));
                            if (sizeToLiquidate > 0) {
                                // 确定强平方向：多头卖出（ASK）清算，空头买入（BID）清算
                                OrderAction action = position.direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID;
                                long prevLocked = position.calculateRequiredMarginForFutures(spec);
                                // 执行强平，更新持仓并返回本次盈亏（基于强平价格）
                                long liquidationPnl = position.liquidate(action, sizeToLiquidate, price);
                                // 计算强平手续费，基于清算量和规格中的 takerFee
                                long fee = CoreArithmeticUtils.calculateTakerFee(sizeToLiquidate, spec);
                                // 更新手续费统计，用于平台收入记录
                                fees.addToValue(spec.quoteCurrency, fee);
                                // 更新账户可用余额：加上强平盈亏，扣除手续费（释放的保证金已隐式处理）
                                long free = userProfile.accounts.addToValue(spec.quoteCurrency, liquidationPnl - fee);
                                // 更新当前持仓所需的初始保证金，反映强平后的状态
                                locked = position.calculateRequiredMarginForFutures(spec);
                                // 释放保证金
                                long releasedMargin = prevLocked - locked;
                                // 剩余持仓量，可能为 0（全平）或部分剩余
                                long remainingPosition = position.openVolume;
                                // 若仓位清空，从用户持仓记录中移除，释放内存
                                if (position.isEmpty()) {
                                    userProfile.positions.remove(symbol);
                                }
                                // 生成强平事件，记录用户、仓位和交易细节，便于审计和通知
                                eventsHelper.sendLiquidationEvent(cmd, position, free, locked, remainingPosition, sizeToLiquidate, price, fee, liquidationPnl);
                                log.debug("Liquidated: uid={} symbol={} size={} price={} pnl={}", userProfile.uid, symbol, sizeToLiquidate, price,
                                    liquidationPnl);
                            }
                        }
                        // 权益低于预警阈值但高于维持保证金，发送 Margin Call 提醒用户追加资金
                        else if (equity < warningThreshold) {
                            // 发送提醒事件，包含当前余额和冻结保证金，便于用户了解资金状态
                            eventsHelper.sendMarginAdjustmentEvent(cmd, position, balance, locked);
                            log.debug("Margin call: uid={} symbol={} equity={} threshold={}", userProfile.uid, symbol, equity, warningThreshold);
                        }
                    }
                });
        });
    }
    
    /**
     * 计算用户在指定货币中的冻结保证金总额（实时计算）。
     */
    private long calculateLockedMargin(UserProfile userProfile, int currency) {
        long locked = 0;
        for (SymbolPositionRecord position : userProfile.positions) {
            if (position.currency == currency && position.direction != PositionDirection.EMPTY) {
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                locked += position.calculateRequiredMarginForFutures(spec);
            }
        }
        return locked;
    }
    /**
     * 周期性结算期货仓位的未实现盈亏。
     * 将未实现盈亏转为已实现盈亏并更新账户余额。
     */
    private void settlePnl(OrderCommand cmd) {
        symbolSpecificationProvider.getAllSymbols().forEach(symbol -> {
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
            if (spec == null || spec.type != SymbolType.FUTURES_CONTRACT) {
                return;
            }
            LastPriceCacheRecord priceRecord = lastPriceCache.get(symbol);
            if (priceRecord == null) {
                log.warn("No price record for symbol={}", symbol);
                return;
            }
            userProfileService.getAllUserProfiles()
                .filter(up -> uidForThisHandler(up.uid))
                .filter(up -> !up.positions.isEmpty())
                .forEach(userProfile -> {
                    SymbolPositionRecord position = userProfile.positions.get(symbol);
                    if (position == null || position.direction == PositionDirection.EMPTY) {
                        return;
                    }
                    long balance = userProfile.accounts.get(spec.quoteCurrency);
                    long unrealizedPnl = position.liquidateEstimateProfit(spec, priceRecord);
                    if (unrealizedPnl != 0) {
                        long free = userProfile.accounts.addToValue(spec.quoteCurrency, unrealizedPnl);
                        position.profit += unrealizedPnl;
                        position.openPriceSum = position.openVolume * priceRecord.markPrice; // 重置
                        long locked = position.calculateRequiredMarginForFutures(spec);
                        eventsHelper.sendPnlSettlementEvent(cmd, position, free, locked, unrealizedPnl);
                    }
                });
        });
    }
    
    private void handleMatcherEventMargin(final MatcherTradeEvent ev,
                                          final CoreSymbolSpecification spec,
                                          final OrderAction takerAction,
                                          final UserProfile takerUp,
                                          final SymbolPositionRecord takerSpr) {
        if (takerUp != null) {
            if (ev.eventType == MatcherEventType.TRADE) {
                // update taker's position
                long prevMargin = takerSpr.calculateRequiredMarginForFutures(spec);
                final long sizeOpen = takerSpr.updatePositionForMarginTrade(takerAction, ev.size, ev.price);
                final long fee = CoreArithmeticUtils.calculateTakerFee(Math.abs(sizeOpen), spec);
                long locked = takerSpr.calculateRequiredMarginForFutures(spec);
                long marginDiff = locked - prevMargin; // 保证金变化量
                if (sizeOpen > 0) {
                    // 开仓：扣除手续费和新增保证金
                    long free = takerUp.accounts.addToValue(spec.quoteCurrency, -fee - marginDiff);
                    fees.addToValue(spec.quoteCurrency, fee);
                    eventsHelper.sendLockEvent(ev, takerUp.uid, spec.quoteCurrency, free, marginDiff); // 新增
                    eventsHelper.sendOpenPositionEvent(ev, takerSpr, free, locked, sizeOpen, ev.price, fee);
                    log.debug("Opened position: uid={} symbol={} size={} price={} marginDiff={}", 
                             takerUp.uid, spec.symbolId, sizeOpen, ev.price, marginDiff);
                } else if (sizeOpen < 0) {
                    // 平仓：返还释放的保证金，加上平仓盈亏，扣除手续费
                    long sizeClosed = -sizeOpen;
                    long closePnl = (takerAction == OrderAction.BID ? (ev.price - takerSpr.openPriceSum / takerSpr.openVolume)
                        : (takerSpr.openPriceSum / takerSpr.openVolume - ev.price)) * sizeClosed * spec.quoteScaleK;
                    long free = takerUp.accounts.addToValue(spec.quoteCurrency, closePnl - fee - marginDiff); // marginDiff
                                                                                                              // 为负值
                    fees.addToValue(spec.quoteCurrency, fee);
                    eventsHelper.sendUnLockEvent(ev, takerUp.uid, spec.quoteCurrency, free, -marginDiff);
                    eventsHelper.sendClosePositionEvent(ev, takerSpr, free, locked, sizeClosed, ev.price, fee, closePnl);
                    log.debug("Closed position: uid={} symbol={} size={} price={} pnl={} marginDiff={}", takerUp.uid, spec.symbolId, sizeClosed, ev.price,
                        closePnl, marginDiff);
                }
                
                
            } else if (ev.eventType == MatcherEventType.REJECT || ev.eventType == MatcherEventType.REDUCE) {
                // for cancel/rejection only one party is involved
                long prevMargin = takerSpr.calculateRequiredMarginForFutures(spec);
                takerSpr.pendingRelease(takerAction, ev.size);
                long locked = takerSpr.calculateRequiredMarginForFutures(spec);
                long releasedMargin = prevMargin - locked;
                if (releasedMargin > 0) {
                    long free = takerUp.accounts.addToValue(spec.quoteCurrency, releasedMargin);
                    eventsHelper.sendUnLockEvent(ev, takerUp.uid, spec.quoteCurrency, free, releasedMargin);
                }
            }
            if (takerSpr.isEmpty()) {
                removePositionRecord(takerSpr, takerUp);
            }
        }

        if (ev.eventType == MatcherEventType.TRADE && uidForThisHandler(ev.matchedOrderUid)) {
            UserProfile maker = userProfileService.getUserProfileOrAddSuspended(ev.matchedOrderUid);
            SymbolPositionRecord makerSpr = maker.getPositionRecordOrThrowEx(spec.symbolId);
            long prevMargin = makerSpr.calculateRequiredMarginForFutures(spec);
            long sizeOpen = makerSpr.updatePositionForMarginTrade(takerAction.opposite(), ev.size, ev.price);
            long fee = CoreArithmeticUtils.calculateTakerFee(ev.size, spec);
            long locked = makerSpr.calculateRequiredMarginForFutures(spec);
            long marginDiff = locked - prevMargin;
            if (sizeOpen > 0) {
                long free = maker.accounts.addToValue(spec.quoteCurrency, -fee - marginDiff);
                fees.addToValue(spec.quoteCurrency, fee);
                eventsHelper.sendLockEvent(ev, maker.uid, spec.quoteCurrency, free, marginDiff);
                eventsHelper.sendOpenPositionEvent(ev, makerSpr, free, locked, sizeOpen, ev.price, fee);
            } else if (sizeOpen < 0) {
                long sizeClosed = -sizeOpen;
                long closePnl = (takerAction.opposite() == OrderAction.BID ? 
                                (ev.price - makerSpr.openPriceSum / makerSpr.openVolume) : 
                                (makerSpr.openPriceSum / makerSpr.openVolume - ev.price)) * sizeClosed * spec.quoteScaleK;
                long free = maker.accounts.addToValue(spec.quoteCurrency, closePnl - fee - marginDiff);
                fees.addToValue(spec.quoteCurrency, fee);
                eventsHelper.sendUnLockEvent(ev, maker.uid, spec.quoteCurrency, free, -marginDiff);
                eventsHelper.sendClosePositionEvent(ev, makerSpr, free, locked, sizeClosed, ev.price, fee, closePnl);
            }
            if (makerSpr.isEmpty()) {
                removePositionRecord(makerSpr, maker);
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
        if (takerSell) {
            long userBalance = taker.accounts.addToValue(spec.baseCurrency, CoreArithmeticUtils.calculateAmountAsk(ev.size, spec));
            /**
             * @modify 恢复资金,卖单解冻 baseCurrency
             */
            this.eventsHelper.sendUnLockEvent(cmd, taker.uid, spec.baseCurrency, userBalance);

        } else {
            if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.orderType == OrderType.FOK_BUDGET) {
                long userBalance = taker.accounts.addToValue(spec.quoteCurrency, CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(ev.size, ev.price, spec));
                /**
                 * @modify 恢复资金 买单解冻 quoteCurrency
                 */
                this.eventsHelper.sendUnLockEvent(cmd, taker.uid, spec.quoteCurrency, userBalance);
            } else {
                long userBalance = taker.accounts.addToValue(spec.quoteCurrency, CoreArithmeticUtils.calculateAmountBidTakerFee(ev.size, ev.bidderHoldPrice, spec));
                /**
                 * @modify 恢复资金 买单解冻 quoteCurrency
                 */
                this.eventsHelper.sendUnLockEvent(cmd, taker.uid, spec.quoteCurrency, userBalance);
            }
            // TODO for OrderType.IOC_BUDGET - for REJECT should release leftover deposit after all trades calculated
        }

    }


    private void handleMatcherEventsExchangeSell(MatcherTradeEvent ev,
                                                 final CoreSymbolSpecification spec,
                                                 final UserProfile taker) {

        //log.debug("TRADE EXCH SELL {}", ev);

        long takerSizeForThisHandler = 0L;
        long makerSizeForThisHandler = 0L;

        long takerSizePriceForThisHandler = 0L;

        MatcherTradeEvent tradeEventHead = ev;
        final int quoteCurrency = spec.quoteCurrency;

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
                final long priceDiff = ev.bidderHoldPrice - ev.price;
                final long amountDiffToReleaseInQuoteCurrency = CoreArithmeticUtils.calculateAmountBidReleaseCorrMaker(size, priceDiff, spec);
                // 支付 quoteCurrency
                long quoteCurrencyBalance = maker.accounts.addToValue(quoteCurrency, amountDiffToReleaseInQuoteCurrency);
                final long gainedAmountInBaseCurrency = CoreArithmeticUtils.calculateAmountAsk(size, spec);
                // 接收 baseCurrency
                long baseCurrencyBalance = maker.accounts.addToValue(spec.baseCurrency, gainedAmountInBaseCurrency);
                /**
                 * @modify 资金转移
                 */
                this.eventsHelper.sendTransferEvent(ev, maker.uid, quoteCurrency, quoteCurrencyBalance);
                this.eventsHelper.sendTransferEvent(ev, maker.uid, spec.baseCurrency, baseCurrencyBalance);
                
                
                makerSizeForThisHandler += size;
            }

            ev = ev.nextEvent;
        }

        if (taker != null) {
           // 支付 baseCurrency（已在冻结阶段处理）
           // 接收 quoteCurrency
           long quoteCurrencyBalance = taker.accounts.addToValue(quoteCurrency, takerSizePriceForThisHandler * spec.quoteScaleK - spec.takerFee * takerSizeForThisHandler);
           /**
            * @modify 资金转移
            */
           this.eventsHelper.sendTransferEvent(tradeEventHead, taker.uid, quoteCurrency, quoteCurrencyBalance);
           tradeEventHead = null;
        }

        if (takerSizeForThisHandler != 0 || makerSizeForThisHandler != 0) {
            long totalTradefee = spec.takerFee * takerSizeForThisHandler + spec.makerFee * makerSizeForThisHandler;
            fees.addToValue(quoteCurrency, totalTradefee);
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
        MatcherTradeEvent tradeEventHead = ev;
        final int quoteCurrency = spec.quoteCurrency;

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
                final long gainedAmountInQuoteCurrency = CoreArithmeticUtils.calculateAmountBid(size, ev.price, spec);
                // 支付 baseCurrency（已在冻结阶段处理）
                // 接收 quoteCurrency
                long userBalance = maker.accounts.addToValue(quoteCurrency, gainedAmountInQuoteCurrency - spec.makerFee * size);
                /**
                 * @modify 资金转移
                 */
                this.eventsHelper.sendTransferEvent(ev, maker.uid, quoteCurrency, userBalance);
                makerSizeForThisHandler += size;
            }

            ev = ev.nextEvent;
        }

       
        if (taker != null) {

            if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.orderType == OrderType.FOK_BUDGET) {
                // for FOK budget held sum calculated differently
                takerSizePriceHeldSum = cmd.price;
            }
            // TODO IOC_BUDGET - order can be partially rejected - need held taker fee correction
            
            // 支付 quoteCurrency
            long quoteCurrencyBalance = taker.accounts.addToValue(quoteCurrency, (takerSizePriceHeldSum - takerSizePriceSum) * spec.quoteScaleK);
            // 接收 baseCurrency
            long baseCurrencyBalance = taker.accounts.addToValue(spec.baseCurrency, takerSizeForThisHandler * spec.baseScaleK);
            /**
             * @modify 资金转移
             */
            this.eventsHelper.sendTransferEvent(tradeEventHead, taker.uid, quoteCurrency, quoteCurrencyBalance);
            this.eventsHelper.sendTransferEvent(tradeEventHead, taker.uid, spec.baseCurrency, baseCurrencyBalance);
            tradeEventHead = null;
        }

        if (takerSizeForThisHandler != 0 || makerSizeForThisHandler != 0) {
            long totalTradefee = spec.takerFee * takerSizeForThisHandler + spec.makerFee * makerSizeForThisHandler;
            fees.addToValue(quoteCurrency, totalTradefee);
            /**
             * @TODO 把手续费加到Trade上面去？
             */
            
        }
    }

    private void removePositionRecord(SymbolPositionRecord record, UserProfile userProfile) {
        userProfile.accounts.addToValue(record.currency, record.profit);
        userProfile.positions.removeKey(record.symbol);
        objectsPool.put(ObjectsPool.SYMBOL_POSITION_RECORD, record);
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {

        bytes.writeInt(shardId).writeLong(shardMask);

        symbolSpecificationProvider.writeMarshallable(bytes);
        userProfileService.writeMarshallable(bytes);
        binaryCommandsProcessor.writeMarshallable(bytes);
        SerializationUtils.marshallIntHashMap(lastPriceCache, bytes);
        SerializationUtils.marshallIntLongHashMap(fees, bytes);
        SerializationUtils.marshallIntLongHashMap(adjustments, bytes);
        SerializationUtils.marshallIntLongHashMap(suspends, bytes);
    }

    public void reset() {
        userProfileService.reset();
        symbolSpecificationProvider.reset();
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
        private final UserProfileService userProfileService;
        private final BinaryCommandsProcessor binaryCommandsProcessor;
        private final IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
        private final IntLongHashMap fees;
        private final IntLongHashMap adjustments;
        private final IntLongHashMap suspends;
    }
}
