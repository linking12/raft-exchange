package exchange.core2.tests.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiInsuranceFundDeposit;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.processors.liquidation.LiquidationService.IFPositionRecord;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test class for a fixed matching-engine bug (first found in the Rust port of this
 * engine, then reproduced here in Java): a user (x) holds an open futures position AND an
 * unrelated resting order on the SAME symbol. Force-liquidation used to close the position via a
 * synthetic IOC order whose {@code handleMatcherEventMargin} taker-side bookkeeping called
 * {@link SymbolPositionRecord#pendingRelease} unconditionally — draining the position record's
 * SHARED per-side {@code pendingSellSize}/{@code pendingBuySize} aggregate (min-clamped against
 * whatever the field currently held, with no notion of "which order" contributed it) even though
 * the synthetic liquidation order never held any pending of its own (it skips R1's
 * {@code pendingHold}). That aggregate also backed x's unrelated resting order, so once
 * {@code pendingSellSize} hit 0 (together with {@code openVolume} hitting 0 from the liquidation
 * close), {@link SymbolPositionRecord#isEmpty()} wrongly returned true, {@code
 * removePositionRecord} deleted x's position record — but x's resting order stayed live in the
 * book. When that resting order later matched as MAKER, {@code RiskEngine.handleMatcherEventMargin}
 * called {@code makerUp.getPositionRecordOrThrowEx(...)}, which threw {@link IllegalStateException}
 * ("not found position for key ...").
 *
 * <p>Root cause (confirmed by reading source):
 * <ul>
 *   <li>{@code UserProfile.createPositionsKey(symbol, action, command)} returns just {@code
 *   symbol} in {@link PositionMode#ONEWAY} (the default) — regardless of {@code action} — so a
 *   BID that opens a position and a later unrelated ASK on the same symbol collide on the SAME
 *   {@link SymbolPositionRecord}, sharing its {@code pendingSellSize} field
 *   (exchange-core/src/main/java/exchange/core2/core/common/UserProfile.java:163-172).</li>
 *   <li>{@code SymbolPositionRecord.pendingRelease} is {@code Math.min(pendingSellSize, size)} —
 *   it releases whatever is available, with no link back to which order originally reserved it
 *   (exchange-core/.../SymbolPositionRecord.java:210-223).</li>
 *   <li>{@code UserProfile.getPositionRecordOrThrowEx} throws exactly {@code "not found position
 *   for key " + key} (UserProfile.java:314-320), called unconditionally from the maker block of
 *   {@code handleMatcherEventMargin} (RiskEngine.java ~1460+) with no null-check — left unchanged
 *   by the fix (maker-side strictness is intentional and out of scope here).</li>
 * </ul>
 *
 * <p><b>The fix</b> (production change, {@code RiskEngine.handleMatcherEventMargin}'s taker
 * block): both {@code pendingRelease} call sites (the TRADE branch and the REJECT/REDUCE branch)
 * are now guarded with {@code if (!isLiquidation)} — {@code isLiquidation} was already computed
 * at the top of the taker block via {@code LiquidationService.isLiquidationOrderId(cmd.orderId,
 * ...)}. A synthetic FORCE/liquidation order never holds pending (R1's
 * {@code normalizeCmdPositionSize} skips {@code pendingHold} for FORCE_LIQUIDATION), so it has
 * nothing of its own to release; skipping the release (and its {@code sendUnlockPendingEvent})
 * for liquidation orders leaves any unrelated resting order's pending untouched. Ordinary orders
 * (including IOC) still call {@code pendingHold} at PLACE_ORDER time, so the release remains
 * symmetric and correct for them. The maker leg (a real resting order legitimately filling) is
 * untouched — it correctly still releases pending, because {@code isLiquidation} is false there.
 * ADL's and IF-takeover's own position-close methods ({@code ADLCommandProcessor.applyEvent},
 * {@code IFCommandProcessor.finalizeForCommand}) needed no change — they close positions via a
 * direct {@code closeCurrentPositionFutures} call that never touched pending in the first place;
 * IF-takeover's crash (Test D) was actually caused by the precursor FORCE order's own REJECT
 * event hitting the (now-fixed) taker-side REJECT/REDUCE branch, not by IF's own code.
 *
 * <p>These tests now assert the FIXED behavior: after the liquidation cascade, the affected
 * user's position record is retained (not wrongly emptied), the unrelated resting order's
 * pending stays intact, the order remains correctly linked to its position, a later fill against
 * it completes normally with no {@code IllegalStateException} logged and no engine
 * self-shutdown, and the container's own global-balance reconciliation at close() passes (no
 * {@code skipGlobalReconcileOnClose()} needed anymore, since the fix stops the accounting
 * corruption at the source). Test C (ADL counterparty close) was already correct pre-fix and is
 * kept as a regression guard, unchanged.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class ITStaleRestingOrderLiquidationRepro {

    private static final int QUOTE_ID = 840;

    @Mock
    IEventsHandler4Test handler;

    /** Poll captured logback events for an ERROR record whose throwable message contains {@code needle}. */
    private static Optional<ILoggingEvent> waitForLoggedException(ListAppender<ILoggingEvent> appender, String needle, long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            for (ILoggingEvent evt : appender.list) {
                final IThrowableProxy tp = evt.getThrowableProxy();
                if (tp != null && tp.getMessage() != null && tp.getMessage().contains(needle)) {
                    return Optional.of(evt);
                }
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.currentTimeMillis() < deadline);
        return Optional.empty();
    }

    /** Poll for the ABSENCE of a matching logged exception over the given window (best-effort negative check). */
    private static boolean noLoggedExceptionWithin(ListAppender<ILoggingEvent> appender, String needle, long windowMs) {
        return waitForLoggedException(appender, needle, windowMs).isEmpty();
    }

    /**
     * Runs the full scenario for a given {@link MarginMode} and returns whatever we observed, so
     * each {@code @Test} can assert/report on it explicitly. Post-fix, this exercises the FORCE
     * liquidation TRADE-branch path (Tests A/B).
     */
    private ScenarioResult runScenario(MarginMode marginMode) throws Exception {
        final long deposit = 3000L;         // x: small, so a 95% crash triggers full liquidation
        final long largeDeposit = 1_000_000L;
        final int positionSize = 10;
        final long entryPrice = 10_000L;
        final long liquidationPrice = 500L; // 95% drop, mirrors ITLiquidationIntegration#testBasicLiquidationIsolatedMode
        final long bpFillPrice = 9_920L;    // m2's resting bid: liquidation exit liquidity
        final long staleAskPrice = 11_000L; // x's unrelated resting ask: above entry, never crosses
        final int staleAskSize = 3;

        final long x = 501001L;
        final long m1 = 502001L; // opens x's position (resting ask x's bid fills against)
        final long m2 = 502002L; // exit liquidity for the liquidation IOC
        final long m3 = 502003L; // later hits x's still-live resting ask as taker

        final SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(handler, true);

        // Attach a logback ListAppender to root BEFORE any exchange activity, so we can assert
        // NO DisruptorExceptionHandler / ExchangeCore ERROR record ever appears.
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        final ScenarioResult result = new ScenarioResult();

        final ExchangeTestContainer container =
            ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor);
        try {
            // No skipGlobalReconcileOnClose(): the fix stops the accounting corruption at the
            // source, so container.close()'s own "global balance reconciles to zero" self-check
            // now doubles as our global-conservation assertion — if it fails, close() throws and
            // this test fails.

            final List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            container.createUserWithSpecificMoney(x, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m1, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m2, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m3, largeDeposit, QUOTE_ID);

            long orderId = 900001L;

            log.info("[{}] step1: m1 rests ASK {} @ {}", marginMode, positionSize, entryPrice);
            container.createAskWithOrderId(orderId++, m1, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[{}] step2: x BID {} @ {} fully fills -> x is LONG {}", marginMode, positionSize, entryPrice, positionSize);
            container.createBidWithOrderId(orderId++, x, positionSize, entryPrice, symbol.symbolId, marginMode);

            container.validateUserState(x, profile -> {
                assertThat("x should have exactly 1 position after opening", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("x opened the expected size", pos.openVolume, is((long) positionSize));
                assertThat("x direction is LONG", pos.direction, is(PositionDirection.LONG));
                assertThat("no pending sell yet", pos.pendingSellSize, is(0L));
                log.info("[{}] x position after open: openVolume={} pendingSell={} pendingBuy={}",
                    marginMode, pos.openVolume, pos.pendingSellSize, pos.pendingBuySize);
            });

            log.info("[{}] step3: x rests an UNRELATED ASK {} @ {} (does not cross)", marginMode, staleAskSize, staleAskPrice);
            final long xStaleAskOrderId = orderId++;
            container.createAskWithOrderId(xStaleAskOrderId, x, staleAskSize, staleAskPrice, symbol.symbolId, marginMode);

            container.validateUserState(x, profile -> {
                assertThat("x still has exactly 1 position record (ONEWAY shares the key)",
                    profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("open long size unchanged (resting order does not fill)", pos.openVolume, is((long) positionSize));
                assertThat("resting ask registered as pendingSellSize on the SAME position record",
                    pos.pendingSellSize, is((long) staleAskSize));
                log.info("[{}] x position after resting stale ask: openVolume={} pendingSell={}",
                    marginMode, pos.openVolume, pos.pendingSellSize);
            });

            log.info("[{}] step4: m2 rests BID {} @ {} as liquidation exit liquidity", marginMode, positionSize + 15, bpFillPrice);
            container.createBidWithOrderId(orderId++, m2, positionSize + 15, bpFillPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[{}] step5: mark price crashes {} -> {}, triggering forced liquidation of x", marginMode, entryPrice, liquidationPrice);
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            // Post-fix: x's position record is RETAINED (pendingSellSize keeps isEmpty() false),
            // so we can no longer wait on "no positions" — wait on openVolume hitting 0 instead.
            container.triggerLiquidationUntil(15_000, () -> {
                try {
                    final List<SingleUserReportResult.Position> positions =
                        container.getUserProfile(x).getPositions().get(symbol.symbolId);
                    return positions != null && !positions.isEmpty() && positions.get(0).openVolume == 0;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // ---- FIX check #1: position record retained, pending stays intact (margin not
            //      wrongly refunded), resting order stays correctly linked. ----
            container.validateUserState(x, profile -> {
                assertThat("x's position record must be RETAINED (fixed: no isEmpty() false positive)",
                    profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                result.openVolumeAfterLiquidation = pos.openVolume;
                result.pendingSellSizeAfterLiquidation = pos.pendingSellSize;
                assertThat("x's long is fully closed by the liquidation", pos.openVolume, is(0L));
                assertThat("x's pendingSellSize (backing the unrelated resting ask) must be UNCHANGED — "
                        + "this is the direct evidence the reserved margin for that order was not wrongly released",
                    pos.pendingSellSize, is((long) staleAskSize));
                assertThat("pendingSellAvgPrice unchanged too", pos.pendingSellAvgPrice, is(staleAskPrice));
                log.info("[{}] after liquidation cascade (FIXED): x position retained, openVolume={} pendingSellSize={} pendingSellAvgPrice={}",
                    marginMode, pos.openVolume, pos.pendingSellSize, pos.pendingSellAvgPrice);
            });
            result.positionRetainedAfterLiquidation = true;

            final var orderBookAfterLiquidation = container.requestCurrentOrderBook(symbol.symbolId);
            boolean staleAskStillInBook = false;
            for (int i = 0; i < orderBookAfterLiquidation.askSize; i++) {
                if (orderBookAfterLiquidation.askPrices[i] == staleAskPrice && orderBookAfterLiquidation.askVolumes[i] >= staleAskSize) {
                    staleAskStillInBook = true;
                    break;
                }
            }
            result.staleOrderStillInBook = staleAskStillInBook;
            assertTrue(staleAskStillInBook, "x's unrelated resting ask must still be live in the book after liquidation");

            // ---- FIX check #2: m3 crosses x's (correctly still-linked) resting ask -> x is
            //      MAKER, opens a new legitimate SHORT of size staleAskSize; NO crash. ----
            log.info("[{}] step6: m3 BID {} @ {} crosses x's resting ask", marginMode, staleAskSize, staleAskPrice);
            final ApiPlaceOrder finalOrder = container.genOrder(
                orderId++, m3, staleAskSize, staleAskPrice, symbol.symbolId,
                OrderAction.BID, OrderType.GTC, MarginMode.CROSS);
            result.finalOrderResultCode = container.getApi().submitCommandAsync(finalOrder).get(10, TimeUnit.SECONDS);
            log.info("[{}] m3 crossing order outcome: resultCode={}", marginMode, result.finalOrderResultCode);
            assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);

            // No IllegalStateException should ever be logged now.
            result.noCrashLogged = noLoggedExceptionWithin(appender, "not found position for key", 3_000);
            assertTrue(result.noCrashLogged,
                "expected NO IllegalStateException ('not found position for key ...') to be logged post-fix");

            // Engine must still be alive (no async self-shutdown): a harmless follow-up command
            // must still succeed promptly.
            result.engineAliveAfterFill =
                container.getApi().submitCommandAsync(exchange.core2.core.common.api.ApiNop.builder().build())
                    .get(5, TimeUnit.SECONDS) == CommandResultCode.SUCCESS;
            assertTrue(result.engineAliveAfterFill, "engine must still be responsive after m3's fill (no self-shutdown)");

            // x legitimately opened a new SHORT of staleAskSize as MAKER; pending released
            // through the (untouched) maker-side pendingRelease.
            container.validateUserState(x, profile -> {
                assertThat("x still has exactly 1 position record", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                result.afterFillDirection = pos.direction;
                result.afterFillOpenVolume = pos.openVolume;
                result.afterFillPendingSellSize = pos.pendingSellSize;
                assertThat("x legitimately opened a new SHORT via the real maker fill", pos.direction, is(PositionDirection.SHORT));
                assertThat("new short size == the resting ask's size", pos.openVolume, is((long) staleAskSize));
                assertThat("pendingSellSize correctly released by the maker leg (untouched by the fix)", pos.pendingSellSize, is(0L));
                log.info("[{}] after m3's fill: x direction={} openVolume={} pendingSellSize={}",
                    marginMode, pos.direction, pos.openVolume, pos.pendingSellSize);
            });

            return result;
        } finally {
            rootLogger.detachAppender(appender);
            container.close(); // let a reconciliation failure surface as a normal test failure
        }
    }

    private static final class ScenarioResult {
        Boolean positionRetainedAfterLiquidation;
        Long openVolumeAfterLiquidation;
        Long pendingSellSizeAfterLiquidation;
        Boolean staleOrderStillInBook;
        CommandResultCode finalOrderResultCode;
        Boolean noCrashLogged;
        Boolean engineAliveAfterFill;
        PositionDirection afterFillDirection;
        Long afterFillOpenVolume;
        Long afterFillPendingSellSize;
    }

    /**
     * Test A — ISOLATED / one-way margin mode. Asserts the FIXED behavior.
     */
    @Test
    void staleRestingOrderAfterLiquidation_isolatedOneWay() throws Exception {
        final ScenarioResult result = runScenario(MarginMode.ISOLATED);

        assertTrue(Boolean.TRUE.equals(result.positionRetainedAfterLiquidation),
            "expected x's position record to be RETAINED by the liquidation (fix: isEmpty() correctly stays false)");
        assertEquals(0L, (long) result.openVolumeAfterLiquidation);
        assertEquals(3L, (long) result.pendingSellSizeAfterLiquidation,
            "the reserved margin for x's resting ask must not have been wrongly released during liquidation");
        assertTrue(Boolean.TRUE.equals(result.staleOrderStillInBook));
        assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);
        assertTrue(Boolean.TRUE.equals(result.noCrashLogged), "no IllegalStateException should be logged");
        assertTrue(Boolean.TRUE.equals(result.engineAliveAfterFill), "engine must not self-shutdown");
        assertEquals(PositionDirection.SHORT, result.afterFillDirection);
        assertEquals(3L, (long) result.afterFillOpenVolume);
        assertEquals(0L, (long) result.afterFillPendingSellSize);

        log.info("ISOLATED (fixed): x retained openVolume=0 pendingSell=3 through liquidation, then legitimately "
            + "opened SHORT 3 on m3's fill with no crash and no engine shutdown.");
    }

    /**
     * Test B — CROSS margin mode. Asserts the FIXED behavior.
     */
    @Test
    void staleRestingOrderAfterLiquidation_cross() throws Exception {
        final ScenarioResult result = runScenario(MarginMode.CROSS);

        assertTrue(Boolean.TRUE.equals(result.positionRetainedAfterLiquidation),
            "expected x's position record to be RETAINED by the liquidation (fix: isEmpty() correctly stays false)");
        assertEquals(0L, (long) result.openVolumeAfterLiquidation);
        assertEquals(3L, (long) result.pendingSellSizeAfterLiquidation,
            "the reserved margin for x's resting ask must not have been wrongly released during liquidation");
        assertTrue(Boolean.TRUE.equals(result.staleOrderStillInBook));
        assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);
        assertTrue(Boolean.TRUE.equals(result.noCrashLogged), "no IllegalStateException should be logged");
        assertTrue(Boolean.TRUE.equals(result.engineAliveAfterFill), "engine must not self-shutdown");
        assertEquals(PositionDirection.SHORT, result.afterFillDirection);
        assertEquals(3L, (long) result.afterFillOpenVolume);
        assertEquals(0L, (long) result.afterFillPendingSellSize);

        log.info("CROSS (fixed): x retained openVolume=0 pendingSell=3 through liquidation, then legitimately "
            + "opened SHORT 3 on m3's fill with no crash and no engine shutdown.");
    }

    // ========================================================================================
    // Test C — ADL counterparty close path
    // ========================================================================================

    private static final class AdlScenarioResult {
        Integer winnerPositionCount;
        Long winnerOpenVolume;
        Long winnerPendingBuySize;
        Boolean winnerRestingBidStillInBook;
        ILoggingEvent crashLogEvent;
    }

    /**
     * Test C: y ("winner") is a profitable counterparty selected for AUTO_DELEVERAGING when
     * x's ("loser") force-liquidation finds zero book depth and IF has zero funds (both reject
     * outright, mirroring {@code ITExchangeCoreADL#testADL}). y ALSO holds an unrelated resting
     * order on the same symbol (ONEWAY, same shape as Test A). Unlike FORCE's taker-side path,
     * {@code ADLCommandProcessor.applyEvent} closes the ADL'd counterparty's position via a
     * DIRECT call to {@code SymbolPositionRecord.closeCurrentPositionFutures} (ADLCommandProcessor.java:196)
     * — it never calls {@code pendingRelease}. So y's shared {@code pendingBuySize} (from the
     * unrelated resting order) should stay untouched, which means {@code isEmpty()} should
     * correctly stay false even once y's ADL'd position volume hits zero, and y's position
     * record should NOT be removed / the resting order should NOT be orphaned.
     */
    private AdlScenarioResult runAdlCounterpartyScenario() throws Exception {
        final long loserDeposit = 5_000L;
        final long winnerDeposit = 50_000L;
        final long makerDeposit = 1_000_000L;
        final int loserSize = 5;
        final int winnerSize = 10;         // winner opens LARGER than loser needs closed: ADL only PARTIALLY
                                            // consumes winner (5 of 10) in one clean shot, mirroring the already
                                            // proven-stable ITExchangeCoreADL#testADL sizing (avoids a separate,
                                            // already-documented ADL cascade-sequencing edge case — see note below).
        final long entryPrice = 1_000L;
        final long liquidationPrice = 600L; // mirrors ITExchangeCoreADL#testADL
        // IMPORTANT: must be BELOW liquidationPrice. ExchangeTestContainer#updateCurrentPriceTo
        // itself places a synthetic ASK at exactly `liquidationPrice` (to push the last-traded
        // price) — a resting BID priced ABOVE liquidationPrice would be legitimately crossed and
        // filled by THAT synthetic order before the liquidation cascade even starts, which is a
        // test-harness artifact, not the bug under test (this is exactly what happened when this
        // test was first drafted with restingBidPrice=900: the "unrelated" order got genuinely
        // filled/closed by the harness's own price-push mechanism, not orphaned by ADL).
        final long restingBidPrice = 500L;
        final int restingBidSize = 2;

        final long loser = 601001L;
        final long winner = 601002L;
        final long maker = 601003L;
        final long lateTaker = 601004L; // crosses winner's resting bid AFTER the ADL, to prove winner's record still works

        final SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(handler, true);

        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        final AdlScenarioResult result = new AdlScenarioResult();

        final ExchangeTestContainer container =
            ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor);
        try {
            container.skipGlobalReconcileOnClose();
            container.getExchangeCore().getLiquidationEngines().forEach(exchange.core2.core.processors.liquidation.LiquidationEngine::stop);

            // Deliberately mirrors ITExchangeCoreADL's own symbol spec (NOT
            // container.initFutureSymbols()'s default symbol0): that spec's maxLeverage caps out
            // at 5x-10x for this notional range, which computes a bankruptcy price ABOVE entry
            // for a LONG loser and made ADL permanently reject (unrelatedPnl filter never
            // satisfied) — a sizing artifact of that spec, not of the bug under test. Using the
            // SAME leverage schedule as the already-proven-working ITExchangeCoreADL#testADL
            // avoids that artifact.
            final CoreSymbolSpecification symbol = CoreSymbolSpecification.builder()
                .symbolId(20001)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(11).quoteCurrency(QUOTE_ID)
                .baseScaleK(1).quoteScaleK(1)
                .takerFee(2).feeScaleK(1000)
                .initMargin(1).initMarginScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100_000L, 10L))
                .maintenanceMarginScaleK(1000)
                .maxLeverage(TreeSortedMap.newMapWith(1000L, 75L, 100_000L, 40L))
                .build();
            container.addSymbol(symbol);
            container.addCurrency(symbol.baseCurrency, 0);
            container.addCurrency(symbol.quoteCurrency, 0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            container.createUserWithSpecificMoney(loser, loserDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(winner, winnerDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(maker, makerDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(lateTaker, makerDeposit, QUOTE_ID);

            long orderId = 950001L;

            log.info("[ADL] step1: loser opens LONG {} @ {} (fully consumes maker's ask, no book depth left)", loserSize, entryPrice);
            container.createBidWithOrderId(orderId++, loser, loserSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, maker, loserSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[ADL] step2: winner opens SHORT {} @ {} (fully consumes maker's bid, no book depth left)", winnerSize, entryPrice);
            container.createAskWithOrderId(orderId++, winner, winnerSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);
            container.createBidWithOrderId(orderId++, maker, winnerSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[ADL] step3: winner rests an UNRELATED BID {} @ {} (does not cross)", restingBidSize, restingBidPrice);
            container.createBidWithOrderId(orderId++, winner, restingBidSize, restingBidPrice, symbol.symbolId, MarginMode.ISOLATED);

            container.validateUserState(winner, profile -> {
                assertThat("winner has exactly 1 position record", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("winner still short the full size", pos.openVolume, is((long) winnerSize));
                assertThat("resting bid registered as pendingBuySize on the SAME position record", pos.pendingBuySize, is((long) restingBidSize));
            });

            log.info("[ADL] step4: mark price crashes {} -> {} (no exit liquidity, no IF funds -> FORCE reject, IF reject, ADL against winner)",
                entryPrice, liquidationPrice);
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            container.triggerLiquidationUntil(15_000, () -> container.noPositions(loser));

            container.validateUserState(winner, profile -> {
                result.winnerPositionCount = profile.getPositions().size();
                if (result.winnerPositionCount > 0) {
                    final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                    result.winnerOpenVolume = pos.openVolume;
                    result.winnerPendingBuySize = pos.pendingBuySize;
                    log.info("[ADL] after cascade: winner openVolume={} pendingBuySize={} pendingSellSize={}",
                        pos.openVolume, pos.pendingBuySize, pos.pendingSellSize);
                } else {
                    log.info("[ADL] after cascade: winner has NO position records left (would mean the guard did NOT hold)");
                }
            });

            final var orderBookAfterCascade = container.requestCurrentOrderBook(symbol.symbolId);
            boolean restingBidStillInBook = false;
            for (int i = 0; i < orderBookAfterCascade.bidSize; i++) {
                if (orderBookAfterCascade.bidPrices[i] == restingBidPrice && orderBookAfterCascade.bidVolumes[i] >= restingBidSize) {
                    restingBidStillInBook = true;
                    break;
                }
            }
            result.winnerRestingBidStillInBook = restingBidStillInBook;
            log.info("[ADL] winner's resting bid (price={}, size={}) still in book: {}", restingBidPrice, restingBidSize, restingBidStillInBook);

            if (restingBidStillInBook) {
                log.info("[ADL] step5: lateTaker ASK {} @ {} crosses winner's resting bid -> winner is MAKER", restingBidSize, restingBidPrice);
                final ApiPlaceOrder finalOrder = container.genOrder(
                    orderId++, lateTaker, restingBidSize, restingBidPrice, symbol.symbolId,
                    OrderAction.ASK, OrderType.GTC, MarginMode.CROSS);
                final CommandResultCode lateResult = container.getApi().submitCommandAsync(finalOrder).get(10, TimeUnit.SECONDS);
                log.info("[ADL] lateTaker crossing order outcome: resultCode={}", lateResult);

                // Give any async exception a window to show up, then check — we expect NONE.
                result.crashLogEvent = waitForLoggedException(appender, "not found position for key", 3_000).orElse(null);
            }

            return result;
        } finally {
            rootLogger.detachAppender(appender);
            try {
                container.close();
            } catch (Throwable t) {
                log.warn("[ADL] container.close() threw during teardown: {}", t.toString());
            }
        }
    }

    @Test
    void staleRestingOrderOnAdlCounterparty_notOrphaned() throws Exception {
        final AdlScenarioResult result = runAdlCounterpartyScenario();

        assertNotNull(result.winnerPositionCount, "scenario did not reach the post-cascade check");
        assertEquals(1, result.winnerPositionCount,
            "expected winner's position record to SURVIVE the ADL close (ADL's counterparty-close path never touches pendingBuySize, "
                + "so isEmpty() should correctly stay false while the unrelated resting order's pending is still live)");
        assertNotNull(result.winnerPendingBuySize);
        assertEquals(restingBidSizeConst(), (long) result.winnerPendingBuySize,
            "winner's pendingBuySize should be untouched by ADL's direct close (ADLCommandProcessor.java:196 never calls pendingRelease)");
        assertNotNull(result.winnerOpenVolume);
        assertEquals(5L, (long) result.winnerOpenVolume,
            "winner should be partially closed by ADL (opened 10, loser needed 5 closed -> 5 remaining)");
        assertTrue(Boolean.TRUE.equals(result.winnerRestingBidStillInBook),
            "winner's resting bid should still be live and still correctly linked to winner's surviving position record");
        assertNull(result.crashLogEvent,
            "did NOT expect any IllegalStateException when a later taker crosses winner's resting bid — "
                + "winner's position record should still be found, because ADL's counterparty-close path never desynced pendingBuySize");

        log.info("ADL repro: winner survived with openVolume={} pendingBuySize={} (position NOT removed, NOT orphaned, NO crash) — "
                + "confirms ADLCommandProcessor.applyEvent's direct closeCurrentPositionFutures() call (line 196) does not hit this bug",
            result.winnerOpenVolume, result.winnerPendingBuySize);
    }

    private static long restingBidSizeConst() {
        return 2L; // kept in sync with runAdlCounterpartyScenario()'s restingBidSize
    }

    // ========================================================================================
    // Test D — IF-takeover close path
    // ========================================================================================

    private static final class IfScenarioResult {
        Boolean positionRetainedAfterLiquidation;
        Long openVolumeAfterLiquidation;
        Long pendingSellSizeAfterLiquidation;
        Boolean staleOrderStillInBook;
        CommandResultCode finalOrderResultCode;
        Boolean noCrashLogged;
        Boolean engineAliveAfterFill;
        PositionDirection afterFillDirection;
        Long afterFillOpenVolume;
        Long afterFillPendingSellSize;
        long ifTakenOverVolume;
    }

    /** Aggregates the IF's own open volume for a symbol+direction across all risk-engine shards. */
    private static long sumIfOpenVolume(ExchangeTestContainer container, int symbolId, PositionDirection direction) {
        long total = 0;
        for (RiskEngine engine : container.getExchangeCore().getRiskEngines()) {
            final LiquidationService svc = engine.getLiquidationService();
            final IFPositionRecord pos = svc.getPositions().get(symbolId * direction.getMultiplier());
            if (pos != null) {
                total += pos.openVolume;
            }
        }
        return total;
    }

    /**
     * Test D: same shape as Test A (x holds a LONG position + an unrelated resting ask on the
     * same symbol, ONEWAY), but this time there is NO exit liquidity in the book at all, and the
     * Insurance Fund is funded (via the admin {@code ApiInsuranceFundDeposit} command, mirroring
     * {@code ITExchangeCoreADL#testIFTakeover}) so the cascade goes straight FORCE(reject, 0 fill)
     * → IF-takeover(accept), never reaching ADL.
     *
     * <p>{@code IFCommandProcessor.finalizeForCommand} closes x's OWN position via a DIRECT
     * {@code closeCurrentPositionFutures} call (IFCommandProcessor.java:106) — like ADL, it never
     * calls {@code pendingRelease}. Pre-fix, x's account was still corrupted because the
     * precursor FORCE_LIQUIDATION command's own IOC sell, having matched nothing (0 book depth),
     * produced a REJECT event that was processed through the ORDINARY {@code
     * handleMatcherEventMargin} taker block's REJECT/REDUCE branch — which unconditionally
     * called {@code pendingRelease} even though nothing traded. Post-fix, that REJECT/REDUCE
     * branch is guarded by {@code !isLiquidation} too, so x's pendingSellSize survives FORCE's
     * REJECT, and IF's later direct close (openVolume -> 0) now correctly leaves {@code
     * isEmpty()} false.
     */
    private IfScenarioResult runIfTakeoverScenario() throws Exception {
        final long deposit = 3_000L;
        final long largeDeposit = 1_000_000L;
        final int positionSize = 10;
        final long entryPrice = 10_000L;
        final long liquidationPrice = 500L;
        final long staleAskPrice = 11_000L;
        final int staleAskSize = 3;

        final long x = 602001L;
        final long m1 = 602002L; // opens x's position, fully consumed (no book depth left for FORCE)
        final long m3 = 602003L; // later hits x's orphaned resting ask

        final SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(handler, true);

        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        final IfScenarioResult result = new IfScenarioResult();

        final ExchangeTestContainer container =
            ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor);
        try {
            // No skipGlobalReconcileOnClose(): post-fix this scenario no longer corrupts
            // accounting, so container.close()'s own reconciliation check is our global-
            // conservation assertion.

            final List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            container.createUserWithSpecificMoney(x, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m1, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m3, largeDeposit, QUOTE_ID);

            // Fund IF generously on every shard so IF-takeover can fully absorb x's position
            // regardless of the exact bankruptcy price / shard routing.
            final int numShards = container.getExchangeCore().getLiquidationEngines().size();
            for (int s = 0; s < numShards; s++) {
                container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                        .shardId(s)
                        .transactionId(700_000L + s)
                        .symbol(symbol.symbolId)
                        .currencyAmount(positionSize * entryPrice * 10L)
                        .build(),
                    CommandResultCode.SUCCESS);
            }

            long orderId = 960001L;

            log.info("[IF] step1: m1 rests ASK {} @ {}", positionSize, entryPrice);
            container.createAskWithOrderId(orderId++, m1, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[IF] step2: x BID {} @ {} fully fills (no leftover book depth for FORCE)", positionSize, entryPrice);
            container.createBidWithOrderId(orderId++, x, positionSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);

            log.info("[IF] step3: x rests an UNRELATED ASK {} @ {} (does not cross)", staleAskSize, staleAskPrice);
            container.createAskWithOrderId(orderId++, x, staleAskSize, staleAskPrice, symbol.symbolId, MarginMode.ISOLATED);

            container.validateUserState(x, profile -> {
                assertThat("x has exactly 1 position record", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("x still long the full size", pos.openVolume, is((long) positionSize));
                assertThat("resting ask registered as pendingSellSize on the SAME position record", pos.pendingSellSize, is((long) staleAskSize));
            });

            log.info("[IF] step4: mark price crashes {} -> {} (no exit liquidity -> FORCE fully rejects -> IF takes over)",
                entryPrice, liquidationPrice);
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            // Post-fix: x's record is RETAINED, so wait on openVolume hitting 0 instead of
            // "no positions".
            container.triggerLiquidationUntil(15_000, () -> {
                try {
                    final List<SingleUserReportResult.Position> positions =
                        container.getUserProfile(x).getPositions().get(symbol.symbolId);
                    return positions != null && !positions.isEmpty() && positions.get(0).openVolume == 0;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            result.ifTakenOverVolume = sumIfOpenVolume(container, symbol.symbolId, PositionDirection.LONG);
            log.info("[IF] IF total taken-over LONG volume={}", result.ifTakenOverVolume);

            container.validateUserState(x, profile -> {
                assertThat("x's position record must be RETAINED (fixed: no isEmpty() false positive)",
                    profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                result.openVolumeAfterLiquidation = pos.openVolume;
                result.pendingSellSizeAfterLiquidation = pos.pendingSellSize;
                assertThat("x's long is fully closed by IF-takeover", pos.openVolume, is(0L));
                assertThat("x's pendingSellSize (backing the unrelated resting ask) must be UNCHANGED — "
                        + "not wrongly released by FORCE's REJECT event nor by IF's own close",
                    pos.pendingSellSize, is((long) staleAskSize));
                log.info("[IF] after cascade (FIXED): x position retained, openVolume={} pendingSellSize={}",
                    pos.openVolume, pos.pendingSellSize);
            });
            result.positionRetainedAfterLiquidation = true;

            final var orderBookAfterCascade = container.requestCurrentOrderBook(symbol.symbolId);
            boolean staleAskStillInBook = false;
            for (int i = 0; i < orderBookAfterCascade.askSize; i++) {
                if (orderBookAfterCascade.askPrices[i] == staleAskPrice && orderBookAfterCascade.askVolumes[i] >= staleAskSize) {
                    staleAskStillInBook = true;
                    break;
                }
            }
            result.staleOrderStillInBook = staleAskStillInBook;
            assertTrue(staleAskStillInBook, "x's unrelated resting ask must still be live in the book after IF-takeover");

            log.info("[IF] step5: m3 BID {} @ {} crosses x's resting ask", staleAskSize, staleAskPrice);
            final ApiPlaceOrder finalOrder = container.genOrder(
                orderId++, m3, staleAskSize, staleAskPrice, symbol.symbolId,
                OrderAction.BID, OrderType.GTC, MarginMode.CROSS);
            result.finalOrderResultCode = container.getApi().submitCommandAsync(finalOrder).get(10, TimeUnit.SECONDS);
            log.info("[IF] m3 crossing order outcome: resultCode={}", result.finalOrderResultCode);
            assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);

            result.noCrashLogged = noLoggedExceptionWithin(appender, "not found position for key", 3_000);
            assertTrue(result.noCrashLogged, "expected NO IllegalStateException to be logged post-fix");

            result.engineAliveAfterFill =
                container.getApi().submitCommandAsync(exchange.core2.core.common.api.ApiNop.builder().build())
                    .get(5, TimeUnit.SECONDS) == CommandResultCode.SUCCESS;
            assertTrue(result.engineAliveAfterFill, "engine must still be responsive after m3's fill (no self-shutdown)");

            container.validateUserState(x, profile -> {
                assertThat("x still has exactly 1 position record", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                result.afterFillDirection = pos.direction;
                result.afterFillOpenVolume = pos.openVolume;
                result.afterFillPendingSellSize = pos.pendingSellSize;
                assertThat("x legitimately opened a new SHORT via the real maker fill", pos.direction, is(PositionDirection.SHORT));
                assertThat("new short size == the resting ask's size", pos.openVolume, is((long) staleAskSize));
                assertThat("pendingSellSize correctly released by the maker leg (untouched by the fix)", pos.pendingSellSize, is(0L));
                log.info("[IF] after m3's fill: x direction={} openVolume={} pendingSellSize={}",
                    pos.direction, pos.openVolume, pos.pendingSellSize);
            });

            return result;
        } finally {
            rootLogger.detachAppender(appender);
            container.close(); // let a reconciliation failure surface as a normal test failure
        }
    }

    @Test
    void staleRestingOrderIfTakeover_isolated() throws Exception {
        final IfScenarioResult result = runIfTakeoverScenario();

        assertTrue(result.ifTakenOverVolume > 0,
            "expected the Insurance Fund to actually take over x's position (ifTakenOverVolume=" + result.ifTakenOverVolume
                + ") — otherwise this test did not exercise the IF-takeover path");
        assertTrue(Boolean.TRUE.equals(result.positionRetainedAfterLiquidation),
            "expected x's position record to be RETAINED (fix: isEmpty() correctly stays false through FORCE's REJECT + IF's close)");
        assertEquals(0L, (long) result.openVolumeAfterLiquidation);
        assertEquals(3L, (long) result.pendingSellSizeAfterLiquidation,
            "the reserved margin for x's resting ask must not have been wrongly released");
        assertTrue(Boolean.TRUE.equals(result.staleOrderStillInBook));
        assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);
        assertTrue(Boolean.TRUE.equals(result.noCrashLogged), "no IllegalStateException should be logged");
        assertTrue(Boolean.TRUE.equals(result.engineAliveAfterFill), "engine must not self-shutdown");
        assertEquals(PositionDirection.SHORT, result.afterFillDirection);
        assertEquals(3L, (long) result.afterFillOpenVolume);
        assertEquals(0L, (long) result.afterFillPendingSellSize);

        log.info("IF-takeover (fixed): x retained openVolume=0 pendingSell=3 through FORCE-reject+IF-takeover, then "
            + "legitimately opened SHORT 3 on m3's fill with no crash and no engine shutdown. ifTakenOverVolume={}",
            result.ifTakenOverVolume);
    }

    // ========================================================================================
    // Test E — same-direction resting order (NEGATIVE: never triggers, pre-fix or post-fix)
    // ========================================================================================

    private static final class SameSideScenarioResult {
        Boolean positionRetainedAfterLiquidation;
        Long openVolumeAfterLiquidation;
        Long pendingBuySizeAfterLiquidation;
        Boolean staleOrderStillInBook;
        CommandResultCode finalOrderResultCode;
        Boolean noCrashLogged;
        Boolean engineAliveAfterFill;
        PositionDirection afterFillDirection;
        Long afterFillOpenVolume;
        Long afterFillPendingBuySize;
    }

    /**
     * Test E: x is LONG and rests an UNRELATED order on the SAME side as opening (a BID — the
     * add-to-position side), then gets force-liquidated. The liquidation closes a LONG via a
     * synthetic ASK, whose taker-side {@code pendingRelease(ASK, ...)} only ever touches {@code
     * pendingSellSize} ({@code SymbolPositionRecord.pendingRelease}, RiskEngine.java ~210-226) —
     * never {@code pendingBuySize}. x's resting BID lives on {@code pendingBuySize}, a different
     * field on the same shared record, so it is never touched by the liquidation's release call.
     * {@code isEmpty()} therefore correctly stays false (pendingBuySize nonzero) REGARDLESS of
     * the fix — this scenario never hit the bug in the first place, even pre-fix. It documents
     * the trigger boundary: the bug needs the resting order on the side OPPOSITE the position
     * (the side the liquidation's close releases), not the same side.
     */
    private SameSideScenarioResult runSameSideScenario() throws Exception {
        final long deposit = 3000L;
        final long largeDeposit = 1_000_000L;
        final int positionSize = 10;
        final long entryPrice = 10_000L;
        final long liquidationPrice = 500L;
        final long bpFillPrice = 9_920L;
        final long staleBidPrice = 100L;    // well below liquidationPrice: never crosses anything
        final int staleBidSize = 3;

        final long x = 503001L;
        final long m1 = 504001L;
        final long m2 = 504002L;
        final long m3 = 504003L;

        final SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(handler, true);

        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        final SameSideScenarioResult result = new SameSideScenarioResult();

        final ExchangeTestContainer container =
            ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor);
        try {
            final List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            container.createUserWithSpecificMoney(x, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m1, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m2, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m3, largeDeposit, QUOTE_ID);

            long orderId = 970001L;

            log.info("[E] step1: m1 rests ASK {} @ {}", positionSize, entryPrice);
            container.createAskWithOrderId(orderId++, m1, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[E] step2: x BID {} @ {} fully fills -> x is LONG {}", positionSize, entryPrice, positionSize);
            container.createBidWithOrderId(orderId++, x, positionSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);

            log.info("[E] step3: x rests an UNRELATED same-side BID {} @ {} (add-to-position side)", staleBidSize, staleBidPrice);
            container.createBidWithOrderId(orderId++, x, staleBidSize, staleBidPrice, symbol.symbolId, MarginMode.ISOLATED);

            container.validateUserState(x, profile -> {
                assertThat("x has exactly 1 position record", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("open long size unchanged", pos.openVolume, is((long) positionSize));
                assertThat("resting bid registered as pendingBuySize on the SAME record", pos.pendingBuySize, is((long) staleBidSize));
                assertThat("no pendingSellSize", pos.pendingSellSize, is(0L));
            });

            // Exactly positionSize (no leftover): a leftover m2 bid would sit at a far better
            // price than x's low same-side resting bid and would wrongly absorb m3's crossing
            // order first (bids are matched best-price-first) — that's a test-harness sizing
            // pitfall, not the bug under test.
            log.info("[E] step4: m2 rests BID {} @ {} as liquidation exit liquidity (sized to fully consume, no leftover)", positionSize, bpFillPrice);
            container.createBidWithOrderId(orderId++, m2, positionSize, bpFillPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[E] step5: mark price crashes {} -> {}, triggering forced liquidation of x", entryPrice, liquidationPrice);
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            container.triggerLiquidationUntil(15_000, () -> {
                try {
                    final List<SingleUserReportResult.Position> positions =
                        container.getUserProfile(x).getPositions().get(symbol.symbolId);
                    return positions != null && !positions.isEmpty() && positions.get(0).openVolume == 0;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            container.validateUserState(x, profile -> {
                assertThat("x's position record must be RETAINED (never at risk here, pre-fix or post-fix)",
                    profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                result.openVolumeAfterLiquidation = pos.openVolume;
                result.pendingBuySizeAfterLiquidation = pos.pendingBuySize;
                assertThat("x's long is fully closed by the liquidation", pos.openVolume, is(0L));
                assertThat("x's pendingBuySize (backing the same-side resting bid) must be UNCHANGED — "
                        + "the liquidation's ASK-side release never touches this field",
                    pos.pendingBuySize, is((long) staleBidSize));
                log.info("[E] after liquidation cascade: x position retained, openVolume={} pendingBuySize={}",
                    pos.openVolume, pos.pendingBuySize);
            });
            result.positionRetainedAfterLiquidation = true;

            final var orderBookAfterLiquidation = container.requestCurrentOrderBook(symbol.symbolId);
            boolean staleBidStillInBook = false;
            for (int i = 0; i < orderBookAfterLiquidation.bidSize; i++) {
                if (orderBookAfterLiquidation.bidPrices[i] == staleBidPrice && orderBookAfterLiquidation.bidVolumes[i] >= staleBidSize) {
                    staleBidStillInBook = true;
                    break;
                }
            }
            result.staleOrderStillInBook = staleBidStillInBook;
            assertTrue(staleBidStillInBook, "x's same-side resting bid must still be live in the book after liquidation");

            // ExchangeTestContainer#updateCurrentPriceTo (step5) itself left a resting BID of its
            // own (UPDATE_PRICE_USER1, size 10 @ liquidationPrice=500) — priced BETTER than x's
            // low same-side bid (100), so a plain ASK at 100 would match THAT bid first (best-
            // price priority) and never reach x's order. Sweep through it in the same order by
            // sizing m3's ASK to cover both levels; x's own fill is still verified below by size.
            final int pushBidSize = 10; // matches ExchangeTestContainer#updateCurrentPriceTo's hardcoded size
            final int sweepSize = pushBidSize + staleBidSize;
            log.info("[E] step6: m3 ASK {} @ {} sweeps through the harness's own price-push bid (10 @ {}) down to x's resting bid",
                sweepSize, staleBidPrice, liquidationPrice);
            final ApiPlaceOrder finalOrder = container.genOrder(
                orderId++, m3, sweepSize, staleBidPrice, symbol.symbolId,
                OrderAction.ASK, OrderType.GTC, MarginMode.CROSS);
            result.finalOrderResultCode = container.getApi().submitCommandAsync(finalOrder).get(10, TimeUnit.SECONDS);
            log.info("[E] m3 crossing order outcome: resultCode={}", result.finalOrderResultCode);
            assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);

            result.noCrashLogged = noLoggedExceptionWithin(appender, "not found position for key", 3_000);
            assertTrue(result.noCrashLogged, "expected NO IllegalStateException to be logged");

            result.engineAliveAfterFill =
                container.getApi().submitCommandAsync(exchange.core2.core.common.api.ApiNop.builder().build())
                    .get(5, TimeUnit.SECONDS) == CommandResultCode.SUCCESS;
            assertTrue(result.engineAliveAfterFill, "engine must still be responsive after m3's fill");

            container.validateUserState(x, profile -> {
                assertThat("x still has exactly 1 position record", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                result.afterFillDirection = pos.direction;
                result.afterFillOpenVolume = pos.openVolume;
                result.afterFillPendingBuySize = pos.pendingBuySize;
                assertThat("x legitimately opened a new LONG via the real maker fill", pos.direction, is(PositionDirection.LONG));
                assertThat("new long size == the resting bid's size", pos.openVolume, is((long) staleBidSize));
                assertThat("pendingBuySize correctly released by the maker leg", pos.pendingBuySize, is(0L));
                log.info("[E] after m3's fill: x direction={} openVolume={} pendingBuySize={}",
                    pos.direction, pos.openVolume, pos.pendingBuySize);
            });

            return result;
        } finally {
            rootLogger.detachAppender(appender);
            container.close();
        }
    }

    @Test
    void sameSideRestingOrderAfterLiquidation_negative() throws Exception {
        final SameSideScenarioResult result = runSameSideScenario();

        assertTrue(Boolean.TRUE.equals(result.positionRetainedAfterLiquidation));
        assertEquals(0L, (long) result.openVolumeAfterLiquidation);
        assertEquals(3L, (long) result.pendingBuySizeAfterLiquidation,
            "the same-side resting bid's pending must never be touched by a LONG liquidation's ASK-side release");
        assertTrue(Boolean.TRUE.equals(result.staleOrderStillInBook));
        assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);
        assertTrue(Boolean.TRUE.equals(result.noCrashLogged));
        assertTrue(Boolean.TRUE.equals(result.engineAliveAfterFill));
        assertEquals(PositionDirection.LONG, result.afterFillDirection);
        assertEquals(3L, (long) result.afterFillOpenVolume);
        assertEquals(0L, (long) result.afterFillPendingBuySize);

        log.info("Test E (negative, same-side): x retained openVolume=0 pendingBuy=3 through liquidation "
            + "(never at risk — liquidation releases pendingSellSize, resting bid holds pendingBuySize), "
            + "then legitimately opened LONG 3 on m3's fill with no crash.");
    }

    // ========================================================================================
    // Test F — short-side mirror (POSITIVE: fix covers the BID/pendingBuySize direction too)
    // ========================================================================================

    /**
     * Test F: mirror of Test A with x SHORT instead of LONG, and an unrelated resting BID (the
     * reduce/opposite side of a SHORT, sharing {@code pendingBuySize}) instead of a resting ASK.
     * A SHORT's force-liquidation closes via a synthetic BID ({@code LiquidationEngine
     * .buildForceCmd}: {@code action = LONG ? ASK : BID}), whose taker-side {@code
     * pendingRelease(BID, ...)} pre-fix unconditionally drained {@code pendingBuySize} — the
     * SAME field backing x's resting bid — reproducing the identical crash via the buy-side
     * instead of the sell-side. Proves the {@code !isLiquidation} guard is direction-symmetric.
     */
    private ScenarioResult runShortSideScenario() throws Exception {
        final long deposit = 3000L;
        final long largeDeposit = 1_000_000L;
        final int positionSize = 10;
        final long entryPrice = 10_000L;
        final long liquidationPrice = 50_000L; // 5x up-move mirrors A/B/E's 95% down-move magnitude
        final long bpFillPrice = 10_080L;      // m2's resting ask: liquidation buy-back exit liquidity
        final long staleBidPrice = 100L;       // x's unrelated resting bid: well below entry, never crosses
        final int staleBidSize = 3;

        final long x = 505001L;
        final long m1 = 506001L; // opens x's SHORT (resting bid x's ask fills against)
        final long m2 = 506002L; // exit liquidity for the liquidation buy-back IOC
        final long m3 = 506003L; // later hits x's resting bid as taker

        final SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(handler, true);

        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        final ScenarioResult result = new ScenarioResult();

        final ExchangeTestContainer container =
            ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor);
        try {
            final List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            container.createUserWithSpecificMoney(x, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m1, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m2, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(m3, largeDeposit, QUOTE_ID);

            long orderId = 980001L;

            log.info("[F] step1: m1 rests BID {} @ {}", positionSize, entryPrice);
            container.createBidWithOrderId(orderId++, m1, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[F] step2: x ASK {} @ {} fully fills -> x is SHORT {}", positionSize, entryPrice, positionSize);
            container.createAskWithOrderId(orderId++, x, positionSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);

            container.validateUserState(x, profile -> {
                assertThat("x should have exactly 1 position after opening", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("x opened the expected size", pos.openVolume, is((long) positionSize));
                assertThat("x direction is SHORT", pos.direction, is(PositionDirection.SHORT));
                assertThat("no pending buy yet", pos.pendingBuySize, is(0L));
            });

            log.info("[F] step3: x rests an UNRELATED BID {} @ {} (reduce/opposite side, does not cross)", staleBidSize, staleBidPrice);
            container.createBidWithOrderId(orderId++, x, staleBidSize, staleBidPrice, symbol.symbolId, MarginMode.ISOLATED);

            container.validateUserState(x, profile -> {
                assertThat("x still has exactly 1 position record (ONEWAY shares the key)",
                    profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("open short size unchanged", pos.openVolume, is((long) positionSize));
                assertThat("resting bid registered as pendingBuySize on the SAME position record",
                    pos.pendingBuySize, is((long) staleBidSize));
            });

            log.info("[F] step4: m2 rests ASK {} @ {} as liquidation buy-back exit liquidity", positionSize + 15, bpFillPrice);
            container.createAskWithOrderId(orderId++, m2, positionSize + 15, bpFillPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("[F] step5: mark price rises {} -> {}, triggering forced liquidation of x's SHORT", entryPrice, liquidationPrice);
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            container.triggerLiquidationUntil(15_000, () -> {
                try {
                    final List<SingleUserReportResult.Position> positions =
                        container.getUserProfile(x).getPositions().get(symbol.symbolId);
                    return positions != null && !positions.isEmpty() && positions.get(0).openVolume == 0;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            container.validateUserState(x, profile -> {
                assertThat("x's position record must be RETAINED (fixed: no isEmpty() false positive)",
                    profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                result.openVolumeAfterLiquidation = pos.openVolume;
                result.pendingSellSizeAfterLiquidation = pos.pendingBuySize; // reused field: holds pendingBuySize here
                assertThat("x's short is fully closed by the liquidation", pos.openVolume, is(0L));
                assertThat("x's pendingBuySize (backing the unrelated resting bid) must be UNCHANGED",
                    pos.pendingBuySize, is((long) staleBidSize));
                log.info("[F] after liquidation cascade (FIXED): x position retained, openVolume={} pendingBuySize={}",
                    pos.openVolume, pos.pendingBuySize);
            });
            result.positionRetainedAfterLiquidation = true;

            final var orderBookAfterLiquidation = container.requestCurrentOrderBook(symbol.symbolId);
            boolean staleBidStillInBook = false;
            for (int i = 0; i < orderBookAfterLiquidation.bidSize; i++) {
                if (orderBookAfterLiquidation.bidPrices[i] == staleBidPrice && orderBookAfterLiquidation.bidVolumes[i] >= staleBidSize) {
                    staleBidStillInBook = true;
                    break;
                }
            }
            result.staleOrderStillInBook = staleBidStillInBook;
            assertTrue(staleBidStillInBook, "x's unrelated resting bid must still be live in the book after liquidation");

            log.info("[F] step6: m3 ASK {} @ {} crosses x's resting bid", staleBidSize, staleBidPrice);
            final ApiPlaceOrder finalOrder = container.genOrder(
                orderId++, m3, staleBidSize, staleBidPrice, symbol.symbolId,
                OrderAction.ASK, OrderType.GTC, MarginMode.CROSS);
            result.finalOrderResultCode = container.getApi().submitCommandAsync(finalOrder).get(10, TimeUnit.SECONDS);
            log.info("[F] m3 crossing order outcome: resultCode={}", result.finalOrderResultCode);
            assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);

            result.noCrashLogged = noLoggedExceptionWithin(appender, "not found position for key", 3_000);
            assertTrue(result.noCrashLogged, "expected NO IllegalStateException to be logged post-fix");

            result.engineAliveAfterFill =
                container.getApi().submitCommandAsync(exchange.core2.core.common.api.ApiNop.builder().build())
                    .get(5, TimeUnit.SECONDS) == CommandResultCode.SUCCESS;
            assertTrue(result.engineAliveAfterFill, "engine must still be responsive after m3's fill (no self-shutdown)");

            container.validateUserState(x, profile -> {
                assertThat("x still has exactly 1 position record", profile.getPositions().size(), is(1));
                final SingleUserReportResult.Position pos = profile.getPositions().get(symbol.symbolId).get(0);
                result.afterFillDirection = pos.direction;
                result.afterFillOpenVolume = pos.openVolume;
                result.afterFillPendingSellSize = pos.pendingBuySize; // reused field: holds pendingBuySize here
                assertThat("x legitimately opened a new LONG via the real maker fill", pos.direction, is(PositionDirection.LONG));
                assertThat("new long size == the resting bid's size", pos.openVolume, is((long) staleBidSize));
                assertThat("pendingBuySize correctly released by the maker leg (untouched by the fix)", pos.pendingBuySize, is(0L));
                log.info("[F] after m3's fill: x direction={} openVolume={} pendingBuySize={}",
                    pos.direction, pos.openVolume, pos.pendingBuySize);
            });

            return result;
        } finally {
            rootLogger.detachAppender(appender);
            container.close(); // let a reconciliation failure surface as a normal test failure
        }
    }

    @Test
    void shortSideRestingOrderAfterLiquidation_fixed() throws Exception {
        final ScenarioResult result = runShortSideScenario();

        assertTrue(Boolean.TRUE.equals(result.positionRetainedAfterLiquidation),
            "expected x's SHORT position record to be RETAINED by the liquidation (fix covers the BID/pendingBuySize direction too)");
        assertEquals(0L, (long) result.openVolumeAfterLiquidation);
        assertEquals(3L, (long) result.pendingSellSizeAfterLiquidation,
            "the reserved margin for x's resting bid must not have been wrongly released during liquidation");
        assertTrue(Boolean.TRUE.equals(result.staleOrderStillInBook));
        assertEquals(CommandResultCode.SUCCESS, result.finalOrderResultCode);
        assertTrue(Boolean.TRUE.equals(result.noCrashLogged), "no IllegalStateException should be logged");
        assertTrue(Boolean.TRUE.equals(result.engineAliveAfterFill), "engine must not self-shutdown");
        assertEquals(PositionDirection.LONG, result.afterFillDirection);
        assertEquals(3L, (long) result.afterFillOpenVolume);
        assertEquals(0L, (long) result.afterFillPendingSellSize);

        log.info("Test F (short-side mirror, fixed): x retained openVolume=0 pendingBuy=3 through SHORT liquidation "
            + "(buy-back IOC's BID-side release), then legitimately opened LONG 3 on m3's fill with no crash and no engine shutdown.");
    }
}
