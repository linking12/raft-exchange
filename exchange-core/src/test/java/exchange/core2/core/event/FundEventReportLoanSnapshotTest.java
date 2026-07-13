package exchange.core2.core.event;

import exchange.core2.core.IFundEventsHandler.FundEventReport;
import exchange.core2.core.common.FundEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FundEvent 的 loan 字段 → 对外 DTO {@link FundEventReport.LoanSnapshot} 的搬运（fromFundEvent.fill）保字段无丢无串。
 */
class FundEventReportLoanSnapshotTest {

    @Test
    void fromFundEvent_carriesLoanSnapshot() {
        FundEvent ev = new FundEvent();
        ev.eventType = FundEvent.FundEventType.LOAN_REPAY;
        ev.uid = 42L;
        ev.orderId = 999L;   // loanId
        ev.currency = 2;     // loanCurrency
        ev.loanMode = 1;
        ev.loanCollateralCurrency = 1;
        ev.loanOutstandingPrincipal = 30_000L;
        ev.loanAccumulatedInterest = 500L;
        ev.loanCollateralAmount = 3L;
        ev.loanRateBps = 500;
        ev.loanLtvBps = 6000L;
        ev.loanPrincipalDelta = -10_000L;
        ev.loanCollateralDelta = 0L;
        ev.loanInterestPaid = 500L;
        ev.loanBadDebt = 0L;
        ev.loanThresholdBps = 0L;

        FundEventReport report = FundEventReport.fromFundEvent(ev, 7L);
        assertEquals(42L, report.getAccountId());
        assertEquals(FundEvent.FundEventType.LOAN_REPAY, report.getEventType());

        FundEventReport.LoanSnapshot loan = report.getLoan();
        assertEquals((byte) 1, loan.getMode());
        assertEquals(1, loan.getCollateralCurrency());
        assertEquals(30_000L, loan.getOutstandingPrincipal());
        assertEquals(500L, loan.getAccumulatedInterest());
        assertEquals(3L, loan.getCollateralAmount());
        assertEquals(500, loan.getRateBps());
        assertEquals(6000L, loan.getLtvBps());
        assertEquals(-10_000L, loan.getPrincipalDelta());
        assertEquals(0L, loan.getCollateralDelta());
        assertEquals(500L, loan.getInterestPaid());
        assertEquals(0L, loan.getBadDebt());
        assertEquals(0L, loan.getThresholdBps());
    }

    @Test
    void fromFundEvent_nonLoanEvent_loanSnapshotAllZero() {
        FundEvent ev = new FundEvent();
        ev.eventType = FundEvent.FundEventType.DEPOSIT;
        ev.uid = 1L;
        ev.currency = 2;

        FundEventReport.LoanSnapshot loan = FundEventReport.fromFundEvent(ev, 1L).getLoan();
        assertEquals((byte) 0, loan.getMode());
        assertEquals(0L, loan.getOutstandingPrincipal());
        assertEquals(0L, loan.getLtvBps());
        assertEquals(0L, loan.getBadDebt());
    }
}
