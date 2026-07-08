package exchange.core2.core.processors;

import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.CommandResultCode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

class UserProfileServiceTest {

    private static final int USDT = 840;
    private static final long UID = 100L;

    // 守卫：accounts 全 0 但 exchangeLocked 仍有冻结时，suspend 必须被拒。
    // 否则 userProfile 被 remove，遗弃的 lock 让全局守恒等式漂移；后续撮合时
    // getUserProfileOrAddSuspended 新建空对象，释放时让 exchangeLocked 变负。
    @Test
    void suspendBlockedByNonZeroExchangeLocked() {
        UserProfileService svc = new UserProfileService();
        svc.addEmptyUserProfile(UID);
        UserProfile profile = svc.getUserProfile(UID);
        profile.exchangeLocked.put(USDT, 500L); // 模拟"强平后 accounts 已 0，但现货挂单 lock 还在"

        assertThat(svc.suspendUserProfile(UID),
                is(CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS));
        assertThat("被拒后 profile 不能被 remove", svc.getUserProfile(UID), is(profile));
    }

    // exchangeLocked 清零后 suspend 应放行（其它前置条件都已满足）。
    @Test
    void suspendSucceedsAfterExchangeLockedCleared() {
        UserProfileService svc = new UserProfileService();
        svc.addEmptyUserProfile(UID);
        UserProfile profile = svc.getUserProfile(UID);
        profile.exchangeLocked.put(USDT, 500L);

        assertThat(svc.suspendUserProfile(UID),
                is(CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS));

        profile.exchangeLocked.put(USDT, 0L);
        assertThat(svc.suspendUserProfile(UID), is(CommandResultCode.SUCCESS));
        assertThat("suspend 成功后 profile 被 remove", svc.getUserProfile(UID), is((UserProfile) null));
    }

    // 非零 accounts 仍走原有 NON_EMPTY_ACCOUNTS 分支（新守卫不破坏原有逻辑）。
    @Test
    void suspendStillBlockedByNonZeroAccounts() {
        UserProfileService svc = new UserProfileService();
        svc.addEmptyUserProfile(UID);
        UserProfile profile = svc.getUserProfile(UID);
        profile.accounts.put(USDT, 1_000L);

        assertThat(svc.suspendUserProfile(UID),
                is(CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS));
    }

    // 全 0 时 suspend 直接成功 — sanity check 不被新守卫误伤。
    @Test
    void suspendSucceedsForFullyEmptyProfile() {
        UserProfileService svc = new UserProfileService();
        svc.addEmptyUserProfile(UID);
        assertThat(svc.suspendUserProfile(UID), is(CommandResultCode.SUCCESS));
    }

    // 已 suspend 用户不能再 suspend（不被新分支干扰）。
    @Test
    void suspendIdempotenceCheckNotBrokenByGuard() {
        UserProfileService svc = new UserProfileService();
        svc.getUserProfileOrAddSuspended(UID); // status=SUSPENDED
        assertThat(svc.suspendUserProfile(UID),
                is(CommandResultCode.USER_MGMT_USER_ALREADY_SUSPENDED));
    }

    // 不存在的 uid 返回 USER_NOT_FOUND。
    @Test
    void suspendUnknownUid() {
        UserProfileService svc = new UserProfileService();
        assertThat(svc.suspendUserProfile(UID),
                is(CommandResultCode.USER_MGMT_USER_NOT_FOUND));
    }

    // 同一外部事件 ID 重投：第二次直接拒为 ALREADY_APPLIED_SAME，accounts 不再变化。
    @Test
    void balanceAdjustmentIdempotentOnSameExternalId() {
        UserProfileService svc = new UserProfileService();
        svc.addEmptyUserProfile(UID);
        long extId = 9001L;

        assertThat(svc.balanceAdjustment(UID, USDT, 1000L, extId),
                is(CommandResultCode.SUCCESS));
        assertThat(svc.getUserProfile(UID).accounts.get(USDT), is(1000L));

        // 重投同 extId
        assertThat(svc.balanceAdjustment(UID, USDT, 1000L, extId),
                is(CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME));
        assertThat("重投不应改变余额", svc.getUserProfile(UID).accounts.get(USDT), is(1000L));
    }

    // externalEventId 不要求单调递增——外部乱序到达（如 chain reorg、MQ 重排）也应正常处理。
    @Test
    void balanceAdjustmentAcceptsOutOfOrderEvents() {
        UserProfileService svc = new UserProfileService();
        svc.addEmptyUserProfile(UID);

        assertThat(svc.balanceAdjustment(UID, USDT, 500L, 100L),
                is(CommandResultCode.SUCCESS));
        // ID 比上一条小，按"集合命中"语义仍应通过
        assertThat(svc.balanceAdjustment(UID, USDT, 300L, 50L),
                is(CommandResultCode.SUCCESS));
        assertThat(svc.getUserProfile(UID).accounts.get(USDT), is(800L));
    }

    // NSF 失败不能把事件 ID 入表——否则调用方修正参数后同 ID 重试会被误判为重复。
    // 验证方式：第一次 NSF 后用同 ID 重试一笔合法的充值，期望 SUCCESS 而非 ALREADY_APPLIED_SAME。
    @Test
    void balanceAdjustmentDoesNotClaimIdOnNsf() {
        UserProfileService svc = new UserProfileService();
        svc.addEmptyUserProfile(UID);
        long extId = 7L;

        assertThat(svc.balanceAdjustment(UID, USDT, -100L, extId),
                is(CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_NSF));
        assertThat("NSF 不应 claim ID——同 ID 重试合法充值应 SUCCESS",
                svc.balanceAdjustment(UID, USDT, 200L, extId),
                is(CommandResultCode.SUCCESS));
    }
}
