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
        ev.loanDebtPrincipal = 30_000L;
        ev.loanDebtInterest = 500L;
        ev.loanCollateralPledged = 3L;
        ev.loanLtvBps = 6000L;
        ev.loanInterestPaidTotal = 500L;
        ev.loanThresholdBps = 0L;
        ev.loanCollateralCurrencyScaleK = 100_000_000L;
        ev.loanCollateralFree = 7L;
        ev.loanCollateralLocked = 3L;

        FundEventReport report = FundEventReport.fromFundEvent(ev, 7L);
        assertEquals(42L, report.getAccountId());
        assertEquals(FundEvent.FundEventType.LOAN_REPAY, report.getEventType());

        FundEventReport.LoanSnapshot loan = report.getLoan();
        assertEquals((byte) 1, loan.getMode());
        assertEquals(1, loan.getCollateralCurrency());
        assertEquals(30_000L, loan.getDebtPrincipal());
        assertEquals(500L, loan.getDebtInterest());
        assertEquals(3L, loan.getCollateralPledged());
        assertEquals(6000L, loan.getLtvBps());
        assertEquals(500L, loan.getInterestPaidTotal());
        assertEquals(0L, loan.getThresholdBps());
        assertEquals(100_000_000L, loan.getCollateralCurrencyScaleK());
        assertEquals(7L, loan.getCollateralFree());
        assertEquals(3L, loan.getCollateralLocked());
    }

    @Test
    void fromFundEvent_nonLoanEvent_loanSnapshotAllZero() {
        FundEvent ev = new FundEvent();
        ev.eventType = FundEvent.FundEventType.DEPOSIT;
        ev.uid = 1L;
        ev.currency = 2;

        FundEventReport.LoanSnapshot loan = FundEventReport.fromFundEvent(ev, 1L).getLoan();
        assertEquals((byte) 0, loan.getMode());
        assertEquals(0L, loan.getDebtPrincipal());
        assertEquals(0L, loan.getLtvBps());
        assertEquals(0L, loan.getCollateralPledged());
        assertEquals(0L, loan.getCollateralFree());
        assertEquals(0L, loan.getCollateralLocked());
    }
}
