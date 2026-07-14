package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_CREATE —— 单笔 Isolated loan 建仓（详见 loan.md §5.2）。字段映射到 OrderCommand：externalId → orderId、 loanId →
 * reserveBidPrice、symbol → symbol、collateralAmount → size、principal → price；金额均在 currency scale 下。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanCreate extends ApiCommand {

    public final long externalId;
    public final long uid;
    public final long loanId;
    public final int symbol;
    public final long collateralAmount;
    public final long principal;
    public final byte rateMode; // 0=LOCKED(Fixed，默认) / 1=FLOATING(Flexible)；承载进 cmd.userCookie，见 loan.md §13.2

    @Override
    public String toString() {
        return "[LOAN_CREATE ext" + externalId + " u" + uid + " id" + loanId + " s" + symbol + " col="
            + collateralAmount + " prin=" + principal + " rateMode=" + rateMode + "]";
    }
}
