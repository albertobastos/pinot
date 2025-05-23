/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.transport;

import com.google.common.annotations.VisibleForTesting;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.pinot.common.config.NettyConfig;
import org.apache.pinot.common.config.TlsConfig;
import org.apache.pinot.common.metrics.ServerGauge;
import org.apache.pinot.common.metrics.ServerMetrics;
import org.apache.pinot.core.util.OsCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The {@code QueryServer} is the Netty server that runs on Pinot Server to handle the instance requests sent from Pinot
 * Brokers.
 */
public class QueryServer {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryServer.class);
  private final int _port;
  private final TlsConfig _tlsConfig;

  private final EventLoopGroup _bossGroup;
  private final EventLoopGroup _workerGroup;
  private final Class<? extends ServerSocketChannel> _channelClass;
  private final ChannelHandler _instanceRequestHandler;
  private ServerSocketChannel _channel;
  private final ConcurrentHashMap<SocketChannel, Boolean> _allChannels = new ConcurrentHashMap<>();


  /**
   * Create an unsecured server instance
   *
   * @param port bind port
   * @param nettyConfig configurations for netty library
   */
  public QueryServer(int port, NettyConfig nettyConfig, ChannelHandler instanceRequestHandler) {
    this(port, nettyConfig, null, instanceRequestHandler);
  }

  /**
   * Create a server instance with TLS config
   *
   * @param port bind port
   * @param nettyConfig configurations for netty library
   * @param tlsConfig TLS/SSL config
   */
  public QueryServer(int port, NettyConfig nettyConfig, TlsConfig tlsConfig, ChannelHandler instanceRequestHandler) {
    _port = port;
    _tlsConfig = tlsConfig;
    _instanceRequestHandler = instanceRequestHandler;

    boolean enableNativeTransports = nettyConfig != null && nettyConfig.isNativeTransportsEnabled();
    OsCheck.OSType operatingSystemType = OsCheck.getOperatingSystemType();
    if (enableNativeTransports && operatingSystemType == OsCheck.OSType.Linux && Epoll.isAvailable()) {
      _bossGroup = new EpollEventLoopGroup();
      _workerGroup = new EpollEventLoopGroup();
      _channelClass = EpollServerSocketChannel.class;
      LOGGER.info("Using Epoll event loop");
    } else if (enableNativeTransports && operatingSystemType == OsCheck.OSType.MacOS && KQueue.isAvailable()) {
      _bossGroup = new KQueueEventLoopGroup();
      _workerGroup = new KQueueEventLoopGroup();
      _channelClass = KQueueServerSocketChannel.class;
      LOGGER.info("Using KQueue event loop");
    } else {
      _bossGroup = new NioEventLoopGroup();
      _workerGroup = new NioEventLoopGroup();
      _channelClass = NioServerSocketChannel.class;
      StringBuilder log = new StringBuilder("Using NIO event loop");
      if (operatingSystemType == OsCheck.OSType.Linux && enableNativeTransports) {
        log.append(", as Epoll is not available: ").append(Epoll.unavailabilityCause());
      } else if (operatingSystemType == OsCheck.OSType.MacOS && enableNativeTransports) {
        log.append(", as KQueue is not available: ").append(KQueue.unavailabilityCause());
      }
      LOGGER.info(log.toString());
    }
  }

  public void start() {
    try {
      ServerBootstrap serverBootstrap = new ServerBootstrap();

      PooledByteBufAllocator bufAllocator = PooledByteBufAllocator.DEFAULT;
      PooledByteBufAllocatorMetric metric = bufAllocator.metric();
      ServerMetrics metrics = ServerMetrics.get();
      PooledByteBufAllocator bufAllocatorWithLimits =
          PooledByteBufAllocatorWithLimits.getBufferAllocatorWithLimits(metric);
      metric = bufAllocatorWithLimits.metric();
      metrics.setOrUpdateGlobalGauge(ServerGauge.NETTY_POOLED_USED_DIRECT_MEMORY, metric::usedDirectMemory);
      metrics.setOrUpdateGlobalGauge(ServerGauge.NETTY_POOLED_USED_HEAP_MEMORY, metric::usedHeapMemory);
      metrics.setOrUpdateGlobalGauge(ServerGauge.NETTY_POOLED_ARENAS_DIRECT, metric::numDirectArenas);
      metrics.setOrUpdateGlobalGauge(ServerGauge.NETTY_POOLED_ARENAS_HEAP, metric::numHeapArenas);
      metrics.setOrUpdateGlobalGauge(ServerGauge.NETTY_POOLED_CACHE_SIZE_SMALL, metric::smallCacheSize);
      metrics.setOrUpdateGlobalGauge(ServerGauge.NETTY_POOLED_CACHE_SIZE_NORMAL, metric::normalCacheSize);
      metrics.setOrUpdateGlobalGauge(ServerGauge.NETTY_POOLED_THREADLOCALCACHE, metric::numThreadLocalCaches);
      metrics.setOrUpdateGlobalGauge(ServerGauge.NETTY_POOLED_CHUNK_SIZE, metric::chunkSize);
      _channel = (ServerSocketChannel) serverBootstrap.group(_bossGroup, _workerGroup).channel(_channelClass)
          .option(ChannelOption.SO_BACKLOG, 128)
          .childOption(ChannelOption.SO_KEEPALIVE, true)
          .option(ChannelOption.ALLOCATOR, bufAllocatorWithLimits)
          .childOption(ChannelOption.ALLOCATOR, bufAllocatorWithLimits)
          .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
              _allChannels.put(ch, true);

              ch.pipeline()
                  .addLast(ChannelHandlerFactory.getDirectOOMHandler(null, null, null, _allChannels, _channel));
              if (_tlsConfig != null) {
                // Add SSL handler first to encrypt and decrypt everything.
                ch.pipeline()
                    .addLast(ChannelHandlerFactory.SSL, ChannelHandlerFactory.getServerTlsHandler(_tlsConfig, ch));
              }

              ch.pipeline().addLast(ChannelHandlerFactory.getLengthFieldBasedFrameDecoder());
              ch.pipeline().addLast(ChannelHandlerFactory.getLengthFieldPrepender());
              ch.pipeline().addLast(_instanceRequestHandler);
            }
          }).bind(_port).sync().channel();
    } catch (Exception e) {
      // Shut down immediately
      _workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
      _bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
      throw new RuntimeException(e);
    }
  }

  public void shutDown() {
    try {
      _channel.close().sync();
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      _workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
      _bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }
  }

  @VisibleForTesting
  ServerSocketChannel getChannel() {
    return _channel;
  }
}
