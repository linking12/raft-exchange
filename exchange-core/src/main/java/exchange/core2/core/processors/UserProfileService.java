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

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.utils.HashingUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * Stateful (!) User profile service
 * <p>
 * TODO make multi instance
 */
@Slf4j
public class UserProfileService implements WriteBytesMarshallable, StateHash {

    /*
     * State: uid to UserProfile
     */
    @Getter
    private final LongObjectHashMap<UserProfile> userProfiles;

    public UserProfileService() {
        this.userProfiles = new LongObjectHashMap<>(1024);
    }

    public UserProfileService(BytesIn bytes) {
        this.userProfiles = SerializationUtils.readLongHashMap(bytes, UserProfile::new);
    }

    /**
     * Find user profile
     *
     * @param uid uid
     * @return user profile
     */
    public UserProfile getUserProfile(long uid) {
        return userProfiles.get(uid);
    }

    public UserProfile getUserProfileOrAddSuspended(long uid) {
        return userProfiles.getIfAbsentPut(uid, () -> new UserProfile(uid, UserStatus.SUSPENDED));
    }


    /**
     * 调整指定用户余额。transactionId 命中 {@link UserProfile#processedTransactionIds} 即拒，
     * 不要求单调递增。NSF 时不 claim ID，避免污染后续修正重试。
     *
     * <p>顺序：先 NSF 校验，再 tryClaim——校验失败路径上 ID 未入表，调用方修正参数后可同 ID 重试。
     */
    public CommandResultCode balanceAdjustment(final long uid, final int currency, final long amount,
        final long transactionId, final long nowMs) {

        final UserProfile userProfile = getUserProfile(uid);
        if (userProfile == null) {
            log.warn("User profile {} not found", uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }

        if (amount < 0 && (userProfile.accounts.get(currency) + amount < 0)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_NSF;
        }

        if (!userProfile.processedTransactionIds.tryClaim(transactionId, nowMs)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        userProfile.accounts.addToValue(currency, amount);
        return CommandResultCode.SUCCESS;
    }

    /**
     * Create a new user profile with known unique uid
     *
     * @param uid uid
     * @return true if user was added
     */
    public boolean addEmptyUserProfile(long uid) {
        if (userProfiles.get(uid) == null) {
            userProfiles.put(uid, new UserProfile(uid, UserStatus.ACTIVE));
            return true;
        } else {
            log.debug("Can not add user, already exists: {}", uid);
            return false;
        }
    }

    /**
     * Suspend removes inactive clients profile from the core in order to increase performance.
     * Account balances should be first adjusted to zero with BalanceAdjustmentType=SUSPEND.
     * No open margin positions allowed in the suspended profile.
     * However in some cases profile can come back with positions and non-zero balances,
     * if pending orders or pending commands was not processed yet.
     * Therefore resume operation must be able to merge profile.
     *
     * @param uid client id
     * @return result code
     */
    public CommandResultCode suspendUserProfile(long uid) {
        final UserProfile userProfile = userProfiles.get(uid);
        if (userProfile == null) {
            return CommandResultCode.USER_MGMT_USER_NOT_FOUND;

        } else if (userProfile.userStatus == UserStatus.SUSPENDED) {
            return CommandResultCode.USER_MGMT_USER_ALREADY_SUSPENDED;

        } else if (userProfile.positions.anySatisfy(pos -> !pos.isEmpty())) {
            return CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_HAS_POSITIONS;

        } else if (userProfile.accounts.anySatisfy(acc -> acc != 0)) {
            return CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS;

        } else if (userProfile.exchangeLocked.anySatisfy(v -> v != 0)) {
            // 现货挂单冻结 (exchangeLocked) 也必须清零才能 suspend。
            // 否则 remove userProfile 时这部分 lock 被遗弃，后续撮合释放会让新建的
            // suspended-profile 出现负 exchangeLocked，破坏全局守恒等式。
            return CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS;

        } else {
            log.debug("Suspended user profile: {}", userProfile);
            userProfiles.remove(uid);
            // TODO pool UserProfile objects
            return CommandResultCode.SUCCESS;
        }
    }

    public CommandResultCode resumeUserProfile(long uid) {
        final UserProfile userProfile = userProfiles.get(uid);
        if (userProfile == null) {
            // create new empty user profile
            // account balance adjustments should be applied later
            userProfiles.put(uid, new UserProfile(uid, UserStatus.ACTIVE));
            return CommandResultCode.SUCCESS;
        } else if (userProfile.userStatus != UserStatus.SUSPENDED) {
            // attempt to resume non-suspended account (or resume twice)
            return CommandResultCode.USER_MGMT_USER_NOT_SUSPENDED;
        } else {
            // resume existing suspended profile (can contain non empty positions or accounts)
            userProfile.userStatus = UserStatus.ACTIVE;
            log.debug("Resumed user profile: {}", userProfile);
            return CommandResultCode.SUCCESS;
        }
    }

    /**
     * Reset module - for testing only
     */
    public void reset() {
        userProfiles.clear();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // write symbolSpecs
        SerializationUtils.marshallLongHashMap(userProfiles, bytes);
    }

    @Override
    public int stateHash() {
        return HashingUtils.stateHash(userProfiles);
    }

}