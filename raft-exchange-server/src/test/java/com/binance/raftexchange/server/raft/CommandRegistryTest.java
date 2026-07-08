package com.binance.raftexchange.server.raft;

import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiNop;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryTest {

    private static CommandRegistry registry() {
        return new CommandRegistry();
    }

    @Test
    void toExchangeCommand_addUser_mapsToCorePojo() {
        ApiCommand cmd =
            ApiCommand.newBuilder().setTimestamp(123L).setAddUser(ApiAddUser.newBuilder().setUid(42L)).build();

        exchange.core2.core.common.api.ApiCommand result = registry().toExchangeCommand(cmd);

        assertInstanceOf(exchange.core2.core.common.api.ApiAddUser.class, result);
        assertEquals(42L, ((exchange.core2.core.common.api.ApiAddUser)result).uid);
        assertEquals(123L, result.timestamp);
    }

    @Test
    void toExchangeCommand_placeOrder_mapsToCorePojo() {
        ApiCommand cmd = ApiCommand.newBuilder()
            .setPlaceOrder(ApiPlaceOrder.newBuilder().setOrderId(100L).setUid(7L).setSymbol(1).setSize(5)).build();

        exchange.core2.core.common.api.ApiCommand result = registry().toExchangeCommand(cmd);

        assertInstanceOf(exchange.core2.core.common.api.ApiPlaceOrder.class, result);
        exchange.core2.core.common.api.ApiPlaceOrder po = (exchange.core2.core.common.api.ApiPlaceOrder)result;
        assertEquals(100L, po.orderId);
        assertEquals(7L, po.uid);
        assertEquals(5L, po.size);
    }

    @Test
    void toExchangeCommand_adjustBalance_mapsToCorePojo() {
        ApiCommand cmd = ApiCommand.newBuilder()
            .setAdjustBalance(
                ApiAdjustUserBalance.newBuilder().setUid(7L).setCurrency(2).setAmount(1000L).setTransactionId(99L))
            .build();

        exchange.core2.core.common.api.ApiCommand result = registry().toExchangeCommand(cmd);

        assertInstanceOf(exchange.core2.core.common.api.ApiAdjustUserBalance.class, result);
    }

    @Test
    void toExchangeCommand_binaryData_returnsNull() {
        // BINARY_DATA 不可批量；走 ExchangeStateMachine.processBinaryDataCommand 而非 CommandRegistry
        ApiCommand cmd = ApiCommand.newBuilder().build(); // 默认 COMMAND_NOT_SET — 等价：unknown case
        assertNull(registry().toExchangeCommand(cmd), "未注册 case 应返回 null（走单独路径）");
    }

    @Test
    void toExchangeCommand_nop_stillMapsForBatchPath() {
        // NOP 在 batch 路径上要 mapping 出 ApiNop；NOP 单条 SUCCESS short-circuit 在 state machine 里做，不在 registry 里
        ApiCommand cmd = ApiCommand.newBuilder().setNop(ApiNop.newBuilder()).build();
        exchange.core2.core.common.api.ApiCommand result = registry().toExchangeCommand(cmd);
        assertNotNull(result);
        assertInstanceOf(exchange.core2.core.common.api.ApiNop.class, result);
    }

    @Test
    void canBatch_registeredCase_true_unknownCase_false() {
        // ADD_USER 在表里 → true；空 builder（COMMAND_NOT_SET）不在表里 → false
        ApiCommand addUser = ApiCommand.newBuilder().setAddUser(ApiAddUser.newBuilder().setUid(1L)).build();
        assertTrue(registry().canBatch(addUser));
        assertFalse(registry().canBatch(ApiCommand.newBuilder().build()));
    }
}
