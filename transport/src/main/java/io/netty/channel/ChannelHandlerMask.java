/*
 * Copyright 2019 The Netty Project
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
package io.netty.channel;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.PlatformDependent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.WeakHashMap;

final class ChannelHandlerMask {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelHandlerMask.class);
    // 定义了很多常量，每个常量表示支持一种回调的事件类型（比如handlerAdded、handlerRemoved等）
    // Using to mask which methods must be called for a ChannelHandler.
    static final int MASK_EXCEPTION_CAUGHT = 1; // 1    0001 ，发生异常回调
    static final int MASK_CHANNEL_REGISTERED = 1 << 1; // 2    0010(左移1位) ，channel注册到eventLoop中
    static final int MASK_CHANNEL_UNREGISTERED = 1 << 2; // 4    0100(左移2位) ，channel解除注册
    static final int MASK_CHANNEL_ACTIVE = 1 << 3; // 8    1000(左移3位)
    static final int MASK_CHANNEL_INACTIVE = 1 << 4; // 16
    static final int MASK_CHANNEL_READ = 1 << 5; // 32
    static final int MASK_CHANNEL_READ_COMPLETE = 1 << 6; // 64
    static final int MASK_USER_EVENT_TRIGGERED = 1 << 7; // 128
    static final int MASK_CHANNEL_WRITABILITY_CHANGED = 1 << 8; // 256
    static final int MASK_BIND = 1 << 9; // 512
    static final int MASK_CONNECT = 1 << 10; // 1024
    static final int MASK_DISCONNECT = 1 << 11; // 2048
    static final int MASK_CLOSE = 1 << 12; // 4096
    static final int MASK_DEREGISTER = 1 << 13; // 8192
    static final int MASK_READ = 1 << 14; // 16384
    static final int MASK_WRITE = 1 << 15; // 32768   1000 0000 0000 0000  (左移15位)
    static final int MASK_FLUSH = 1 << 16; //65536   1 0000 0000 0000 0000 (左移16位)

    static final int MASK_ONLY_INBOUND =  MASK_CHANNEL_REGISTERED |
            MASK_CHANNEL_UNREGISTERED | MASK_CHANNEL_ACTIVE | MASK_CHANNEL_INACTIVE | MASK_CHANNEL_READ |
            MASK_CHANNEL_READ_COMPLETE | MASK_USER_EVENT_TRIGGERED | MASK_CHANNEL_WRITABILITY_CHANGED; // 入站inBound事件
    private static final int MASK_ALL_INBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_INBOUND; // 支持的所有入站inBound回调的mask
    static final int MASK_ONLY_OUTBOUND =  MASK_BIND | MASK_CONNECT | MASK_DISCONNECT |
            MASK_CLOSE | MASK_DEREGISTER | MASK_READ | MASK_WRITE | MASK_FLUSH; // 出站outBound事件
    private static final int MASK_ALL_OUTBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_OUTBOUND; // 支持的所有出站outBound回调的mask
    // 维护着每个handler的Class对象对应的executeMask值，根据一个handler获取其mask值，从而知道其支持的事件类型，并间接的知道这个handler属于inbound handler还是属于outbound handler
    private static final FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>> MASKS =
            new FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>>() {
                @Override
                protected Map<Class<? extends ChannelHandler>, Integer> initialValue() {
                    return new WeakHashMap<Class<? extends ChannelHandler>, Integer>(32);
                }
            };

    /**
     * Return the {@code executionMask}.
     */
    static int mask(Class<? extends ChannelHandler> clazz) {
        // Try to obtain the mask from the cache first. If this fails calculate it and put it in the cache for fast
        // lookup in the future.
        Map<Class<? extends ChannelHandler>, Integer> cache = MASKS.get();
        Integer mask = cache.get(clazz);
        if (mask == null) {
            mask = mask0(clazz);
            cache.put(clazz, mask);
        }
        return mask;
    }

    /**
     * Calculate the {@code executionMask}. 计算支持的回调事件
     */
    private static int mask0(Class<? extends ChannelHandler> handlerType) {
        int mask = MASK_EXCEPTION_CAUGHT;
        try {
            if (ChannelInboundHandler.class.isAssignableFrom(handlerType)) {
                mask |= MASK_ALL_INBOUND; // 所有的in事件
                // 如果该 Handler 的 xx 方法标注了 @Skip 注解，则将他剔除
                if (isSkippable(handlerType, "channelRegistered", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_REGISTERED; // 排除REGISTERED ，非:把1变成0  0变成1，先把自己的值取反（把1变成0 0变成1），然后和mask取并（11得1），这样原本的位置由1变成了0
                } // mask &= ~MASK_CHANNEL_REGISTERED，反向推，怎么把mask中对应MASK_CHANNEL_REGISTERED中1的位置变成0，就先取反，把1的位置变0，然后取并，这样mask中原来是1的位置就被处理成0了，达成目的
                if (isSkippable(handlerType, "channelUnregistered", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_UNREGISTERED; // 排除UNREGISTERED
                }
                if (isSkippable(handlerType, "channelActive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_ACTIVE;
                }
                if (isSkippable(handlerType, "channelInactive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_INACTIVE;
                }
                if (isSkippable(handlerType, "channelRead", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_CHANNEL_READ;
                }
                if (isSkippable(handlerType, "channelReadComplete", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_READ_COMPLETE;
                }
                if (isSkippable(handlerType, "channelWritabilityChanged", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_WRITABILITY_CHANGED;
                }
                if (isSkippable(handlerType, "userEventTriggered", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_USER_EVENT_TRIGGERED;
                }
            }

            if (ChannelOutboundHandler.class.isAssignableFrom(handlerType)) {
                mask |= MASK_ALL_OUTBOUND; // 所有的out事件

                if (isSkippable(handlerType, "bind", ChannelHandlerContext.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_BIND; // 排除bind
                }
                if (isSkippable(handlerType, "connect", ChannelHandlerContext.class, SocketAddress.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_CONNECT;
                }
                if (isSkippable(handlerType, "disconnect", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DISCONNECT;
                }
                if (isSkippable(handlerType, "close", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_CLOSE;
                }
                if (isSkippable(handlerType, "deregister", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DEREGISTER;
                }
                if (isSkippable(handlerType, "read", ChannelHandlerContext.class)) {
                    mask &= ~MASK_READ;
                }
                if (isSkippable(handlerType, "write", ChannelHandlerContext.class,
                        Object.class, ChannelPromise.class)) {
                    mask &= ~MASK_WRITE;
                }
                if (isSkippable(handlerType, "flush", ChannelHandlerContext.class)) {
                    mask &= ~MASK_FLUSH;
                }
            }

            if (isSkippable(handlerType, "exceptionCaught", ChannelHandlerContext.class, Throwable.class)) {
                mask &= ~MASK_EXCEPTION_CAUGHT;
            }
        } catch (Exception e) {
            // Should never reach here.
            PlatformDependent.throwException(e);
        }

        return mask;
    }

    @SuppressWarnings("rawtypes")
    private static boolean isSkippable(
            final Class<?> handlerType, final String methodName, final Class<?>... paramTypes) throws Exception {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                Method m;
                try {
                    m = handlerType.getMethod(methodName, paramTypes);
                } catch (NoSuchMethodException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                            "Class {} missing method {}, assume we can not skip execution", handlerType, methodName, e);
                    }
                    return false;
                }
                return m.isAnnotationPresent(Skip.class);
            }
        });
    }

    private ChannelHandlerMask() { }

    /**
     * Indicates that the annotated event handler method in {@link ChannelHandler} will not be invoked by
     * {@link ChannelPipeline} and so <strong>MUST</strong> only be used when the {@link ChannelHandler}
     * method does nothing except forward to the next {@link ChannelHandler} in the pipeline.
     * <p>
     * Note that this annotation is not {@linkplain Inherited inherited}. If a user overrides a method annotated with
     * {@link Skip}, it will not be skipped anymore. Similarly, the user can override a method not annotated with
     * {@link Skip} and simply pass the event through to the next handler, which reverses the behavior of the
     * supertype.
     * </p> 假如要使handler的某个回调方法不支持回调的话，只要在这个方法的声明上添加@Skip的注解，但是Skip的注解声明的公开性是默认包的，因此应用程序无法直接访问到
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Skip {
        // no value
    }
}
