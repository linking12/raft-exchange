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
package exchange.core2.tests.util;

import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;

/**
 * 测试用 OrderCommand 工厂。从 OrderCommand 主类剥离，避免把"静态构造载体"放在生产类上。
 * 返回的 cmd 用 numShards=0 构造，per-shard 数组长度 0 —— 不能进入 R1 / disruptor pipeline，
 * 仅供 OrderBook / matching processor 等下游组件局部使用。
 */
public final class OrderCommandFactory {

    private OrderCommandFactory() {
    }

    public static OrderCommand newOrder(OrderType orderType, long orderId, long uid, long price, long reserveBidPrice, long size, OrderAction action) {
        OrderCommand cmd = new OrderCommand(0);
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.orderId = orderId;
        cmd.uid = uid;
        cmd.price = price;
        cmd.reserveBidPrice = reserveBidPrice;
        cmd.size = size;
        cmd.action = action;
        cmd.orderType = orderType;
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        return cmd;
    }

    public static OrderCommand cancel(long orderId, long uid) {
        OrderCommand cmd = new OrderCommand(0);
        cmd.command = OrderCommandType.CANCEL_ORDER;
        cmd.orderId = orderId;
        cmd.uid = uid;
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        return cmd;
    }

    public static OrderCommand reduce(long orderId, long uid, long reduceSize) {
        OrderCommand cmd = new OrderCommand(0);
        cmd.command = OrderCommandType.REDUCE_ORDER;
        cmd.orderId = orderId;
        cmd.uid = uid;
        cmd.size = reduceSize;
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        return cmd;
    }

    public static OrderCommand update(long orderId, long uid, long price) {
        OrderCommand cmd = new OrderCommand(0);
        cmd.command = OrderCommandType.MOVE_ORDER;
        cmd.orderId = orderId;
        cmd.uid = uid;
        cmd.price = price;
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        return cmd;
    }
}
