package exchange.core2.core.common;

import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class FundingPaymentAndRecvNotional {
    public final LongLongHashMap payerAmounts = new LongLongHashMap();
    public final LongLongHashMap receiverNotionals = new LongLongHashMap();

    public static FundingPaymentAndRecvNotional[] createArray(int size) {
        FundingPaymentAndRecvNotional[] arr = new FundingPaymentAndRecvNotional[size];
        for (int i = 0; i < size; i++)
            arr[i] = new FundingPaymentAndRecvNotional();
        return arr;
    }

    public static void reset(FundingPaymentAndRecvNotional[] fundingPaymentAndRecvNotionals) {
        for (FundingPaymentAndRecvNotional fundingPaymentAndRecvNotional : fundingPaymentAndRecvNotionals) {
            fundingPaymentAndRecvNotional.payerAmounts.clear();
            fundingPaymentAndRecvNotional.receiverNotionals.clear();
        }
    }

}
