package com.binance.raftexchange.client.grpc;

import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 钉死 onNodeListReceived() 的核心业务逻辑：
 * <ul>
 * <li>leader 未变 → 不触发切换</li>
 * <li>leader 变更 → 旧 channel 被 shutdown</li>
 * <li>节点列表无 LEADER → 不切换，不抛异常</li>
 * </ul>
 *
 * <p>
 * 直接调 onNodeListReceived()，完全同步——不依赖 nodeStub mock、executor 注入或异步等待。 RaftNameResolverProvider.refresh 用 mockStatic
 * 屏蔽，避免 gRPC 注册表初始化依赖。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ExchangeClientFlushNodesTest {

    private static final ServerNode CURRENT_LEADER = ServerNode.newBuilder().setHost("10.0.0.1").setPort(5001).build();

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onNodeListReceived: leader 未变 → 不触发切换")
    void leaderUnchanged_noSwitch() {
        ManagedChannel apiChannel = mock(ManagedChannel.class);
        ExchangeClient client = buildClient(apiChannel);

        NodeList sameLeader = NodeList.newBuilder()
            .addNodes(ServerNode.newBuilder().setHost("10.0.0.1").setPort(5001).setType(NodeType.LEADER)).build();

        try (MockedStatic<RaftNameResolverProvider> ignored = mockStatic(RaftNameResolverProvider.class)) {
            client.onNodeListReceived(sameLeader, CURRENT_LEADER);
        }

        verify(apiChannel, never()).shutdown();
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onNodeListReceived: leader 变更 → 旧 channel 被 shutdown，新 channel 不动")
    void leaderChanged_triggersSwitch() {
        ManagedChannel apiChannel = mock(ManagedChannel.class);
        ManagedChannel newChannel = mock(ManagedChannel.class);

        ExchangeClient client = new ExchangeClient(mock(EventLoopGroup.class), apiChannel, CURRENT_LEADER,
            mock(ExchangeReadOnlyClient.class)) {
            @Override
            protected ManagedChannel createChannel(String host, int port) {
                return newChannel;
            }
        };

        NodeList newLeaderList = NodeList.newBuilder()
            .addNodes(ServerNode.newBuilder().setHost("10.0.0.2").setPort(5002).setType(NodeType.LEADER)).build();

        try (MockedStatic<RaftNameResolverProvider> ignored = mockStatic(RaftNameResolverProvider.class)) {
            client.onNodeListReceived(newLeaderList, CURRENT_LEADER);
        }

        verify(apiChannel).shutdown();
        verify(newChannel, never()).shutdown();
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onNodeListReceived: 节点列表无 LEADER → 不切换，不抛异常")
    void noLeaderInList_noSwitch() {
        ManagedChannel apiChannel = mock(ManagedChannel.class);
        ExchangeClient client = buildClient(apiChannel);

        try (MockedStatic<RaftNameResolverProvider> ignored = mockStatic(RaftNameResolverProvider.class)) {
            client.onNodeListReceived(NodeList.getDefaultInstance(), CURRENT_LEADER);
        }

        verify(apiChannel, never()).shutdown();
    }

    // -----------------------------------------------------------------------

    private static ExchangeClient buildClient(ManagedChannel apiChannel) {
        return new ExchangeClient(mock(EventLoopGroup.class), apiChannel, CURRENT_LEADER,
            mock(ExchangeReadOnlyClient.class));
    }
}
