package com.binance.raftexchange.client.grpc;

import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉死 ExchangeClient 在 leader 切换和 close 时的资源生命周期：
 * <ul>
 * <li>{@link ExchangeClient#switchToNewLeader}：换完 stub 后必须 shutdown 旧 channel—— 之前每次切主漏一个 channel + fd，没人管</li>
 * <li>{@link ExchangeClient#close}：完整释放 apiChannel + readOnlyClient + eventLoopGroup， 之前只 shutdown 了 apiChannel</li>
 * </ul>
 *
 * <p>
 * 用包级测试构造器注入 mock 资源，避开 bootstrapLeaderChannel 的真实网络握手； createChannel 通过子类 override 返回事先准备好的 mock channel。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ExchangeClientTest {

    @Test
    @DisplayName("switchToNewLeader：换完 stub 后 shutdown 旧 channel")
    void switchToNewLeaderShutsDownOldChannel() {
        EventLoopGroup eventLoop = mock(EventLoopGroup.class);
        ManagedChannel oldChannel = mock(ManagedChannel.class);
        ManagedChannel newChannel = mock(ManagedChannel.class);
        ExchangeReadOnlyClient readOnly = mock(ExchangeReadOnlyClient.class);
        ServerNode initialLeader = ServerNode.newBuilder().setHost("10.0.0.1").setPort(5001).build();
        ServerNode newLeader = ServerNode.newBuilder().setHost("10.0.0.2").setPort(5002).build();

        ExchangeClient client = new ExchangeClient(eventLoop, oldChannel, initialLeader, readOnly) {
            @Override
            protected ManagedChannel createChannel(String host, int port) {
                if ("10.0.0.2".equals(host) && port == 5002) {
                    return newChannel;
                }
                throw new AssertionError("unexpected createChannel(" + host + ":" + port + ")");
            }
        };

        client.switchToNewLeader(newLeader);

        // 旧 channel 必须被 shutdown；新 channel 不能被立刻 shutdown
        verify(oldChannel).shutdown();
        verify(newChannel, never()).shutdown();
    }

    @Test
    @DisplayName("close()：apiChannel / readOnlyClient / eventLoopGroup 三者都被释放")
    void closeReleasesAllResources() throws Exception {
        EventLoopGroup eventLoop = mock(EventLoopGroup.class);
        // shutdownGracefully 返回的 Future 不需要 mock 行为，verify 调用就够
        @SuppressWarnings({"unchecked", "rawtypes"})
        Future shutdownFuture = mock(Future.class);
        when(eventLoop.shutdownGracefully(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(shutdownFuture);

        ManagedChannel apiChannel = mock(ManagedChannel.class);
        when(apiChannel.awaitTermination(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        ExchangeReadOnlyClient readOnly = mock(ExchangeReadOnlyClient.class);
        ServerNode leader = ServerNode.newBuilder().setHost("10.0.0.1").setPort(5001).build();

        ExchangeClient client = new ExchangeClient(eventLoop, apiChannel, leader, readOnly);
        client.close();

        verify(readOnly).close();
        verify(apiChannel).shutdown();
        verify(apiChannel).awaitTermination(anyLong(), eq(TimeUnit.SECONDS));
        verify(eventLoop).shutdownGracefully(eq(0L), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("nodesFlushInterval：测试构造器走默认 1 分钟，public(host,port) 兜底也是 1 分钟")
    void defaultNodesFlushIntervalMatchesConstant() {
        EventLoopGroup eventLoop = mock(EventLoopGroup.class);
        ManagedChannel apiChannel = mock(ManagedChannel.class);
        ExchangeReadOnlyClient readOnly = mock(ExchangeReadOnlyClient.class);
        ServerNode leader = ServerNode.newBuilder().setHost("10.0.0.1").setPort(5001).build();

        ExchangeClient client = new ExchangeClient(eventLoop, apiChannel, leader, readOnly);
        org.junit.jupiter.api.Assertions.assertEquals(ExchangeClient.DEFAULT_NODES_FLUSH_INTERVAL,
            client.nodesFlushInterval());
    }

    @Test
    @DisplayName("nodesFlushInterval 必须 > 0：public 构造器拿到 0 / 负值直接抛")
    void publicCtorRejectsNonPositiveInterval() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ExchangeClient("127.0.0.1", 65535, java.time.Duration.ZERO));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ExchangeClient("127.0.0.1", 65535, java.time.Duration.ofMillis(-1)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ExchangeClient("127.0.0.1", 65535, null));
    }

    @Test
    @DisplayName("close()：awaitTermination 超时时 fallback 到 shutdownNow，防止线程卡死")
    void closeFallsBackToShutdownNowOnTimeout() throws Exception {
        EventLoopGroup eventLoop = mock(EventLoopGroup.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Future shutdownFuture = mock(Future.class);
        when(eventLoop.shutdownGracefully(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(shutdownFuture);

        ManagedChannel apiChannel = mock(ManagedChannel.class);
        // 模拟 awaitTermination 超时
        when(apiChannel.awaitTermination(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        ExchangeReadOnlyClient readOnly = mock(ExchangeReadOnlyClient.class);
        ServerNode leader = ServerNode.newBuilder().setHost("10.0.0.1").setPort(5001).build();

        ExchangeClient client = new ExchangeClient(eventLoop, apiChannel, leader, readOnly);
        client.close();

        verify(apiChannel).shutdown();
        verify(apiChannel).shutdownNow();
    }
}
