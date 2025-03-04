/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerBootstrapTest {

    private static void testParentHandler(boolean channelInitializer) throws Exception {
        final LocalAddress addr = new LocalAddress(UUID.randomUUID().toString());
        final CountDownLatch readLatch = new CountDownLatch(1);
        final CountDownLatch initLatch = new CountDownLatch(1);

        final ChannelHandler handler = new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                initLatch.countDown();
                super.handlerAdded(ctx);
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                readLatch.countDown();
                super.channelRead(ctx, msg);
            }
        };

        EventLoopGroup group = new DefaultEventLoopGroup(1);
        Channel sch = null;
        Channel cch = null;
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb.channel(LocalServerChannel.class)
                    .group(group)
                    .childHandler(new ChannelInboundHandlerAdapter());
            if (channelInitializer) {
                sb.handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(handler);
                    }
                });
            } else {
                sb.handler(handler);
            }

            Bootstrap cb = new Bootstrap();
            cb.group(group)
                    .channel(LocalChannel.class)
                    .handler(new ChannelInboundHandlerAdapter());

            sch = sb.bind(addr).syncUninterruptibly().channel();

            cch = cb.connect(addr).syncUninterruptibly().channel();

            initLatch.await();
            readLatch.await();
        } finally {
            if (sch != null) {
                sch.close().syncUninterruptibly();
            }
            if (cch != null) {
                cch.close().syncUninterruptibly();
            }
            group.shutdownGracefully();
        }
    }

    private static void testParentHandler1(boolean channelInitializer) throws Exception {


        NioEventLoopGroup servergroup = new NioEventLoopGroup(2);
        NioEventLoopGroup iogroup = new NioEventLoopGroup();
        final NioEventLoopGroup workergroup = new NioEventLoopGroup(2);
        Channel sch = null;
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb
                    .channel(NioServerSocketChannel.class)
                    .group(servergroup, iogroup)
                    .option(ChannelOption.SO_BACKLOG,1024)
                    .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS,5000) // 启动2s延时任务检查
                    .handler(new SimpleChannelInboundHandler<SocketChannel>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, SocketChannel msg) throws Exception {
                            System.out.println("log new friend in...");
                        }
                    })
//                    .handler()
                    // 每accept一个socketchannel时,如何初始化
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(workergroup, new SimpleChannelInboundHandler<Object>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {

                                }
                            });

                        }
                    });
            sb.bind(8008).sync();


        } finally {

        }
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testHandlerRegister() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        LocalEventLoopGroup group = new LocalEventLoopGroup(1);
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb.channel(LocalServerChannel.class)
                    .group(group)
                    .childHandler(new ChannelInboundHandlerAdapter())
                    .handler(new ChannelHandlerAdapter() {
                        @Override
                        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                            try {
                                assertTrue(ctx.executor().inEventLoop());
                            } catch (Throwable cause) {
                                error.set(cause);
                            } finally {
                                latch.countDown();
                            }
                        }
                    });
            sb.register().syncUninterruptibly();
            latch.await();
            assertNull(error.get());
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    @Timeout(value = 3000, unit = TimeUnit.MILLISECONDS)
    public void testParentHandler() throws Exception {
        testParentHandler(false);
    }

    @Test
    public void testParentHandlerViaChannelInitializer() throws Exception {
//        testParentHandler(true);
        testParentHandler1(true);
    }

    @Test
    public void optionsAndAttributesMustBeAvailableOnChildChannelInit() throws InterruptedException {
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        LocalAddress addr = new LocalAddress(UUID.randomUUID().toString());
        final AttributeKey<String> key = AttributeKey.valueOf(UUID.randomUUID().toString());
        final AtomicBoolean requestServed = new AtomicBoolean();
        ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(LocalServerChannel.class)
                .childAttr(key, "value")
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(LocalChannel ch) throws Exception {
                        Integer option = ch.config().getOption(ChannelOption.CONNECT_TIMEOUT_MILLIS);
                        assertEquals(4242, (int) option);
                        assertEquals("value", ch.attr(key).get());
                        requestServed.set(true);
                    }
                });
        Channel serverChannel = sb.bind(addr).syncUninterruptibly().channel();

        Bootstrap cb = new Bootstrap();
        cb.group(group)
                .channel(LocalChannel.class)
                .handler(new ChannelInboundHandlerAdapter());
        Channel clientChannel = cb.connect(addr).syncUninterruptibly().channel();
        serverChannel.close().syncUninterruptibly();
        clientChannel.close().syncUninterruptibly();
        group.shutdownGracefully();
        assertTrue(requestServed.get());
    }
}
