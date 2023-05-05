/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.IntSupplier;

/**
 * Select strategy interface.
 *
 * Provides the ability to control the behavior of the select loop. For example a blocking select
 * operation can be delayed or skipped entirely if there are events to process immediately.
 * 提供通过 calculateStrategy 返回值 控制 EventLoop 下面行为的能力。
 * 例如，如果有立刻要被处理的事件（task queue不为空），那么更应该用非阻塞的selectNow()，而不是阻塞的select()
 */
public interface SelectStrategy {

    /**
     * Indicates a blocking select should follow.
     * 表示EventLoop 下一步应该是【阻塞】的select操作
     */
    int SELECT = -1;
    /**
     * Indicates the IO loop should be retried, no blocking select to follow directly.
     * 表示 EventLoop 下面应该重试IO循环，下一步是【非阻塞】的selectNow操作
     */
    int CONTINUE = -2;
    /**
     * Indicates the IO loop to poll for new events without blocking.
     * 表示IO循环应该用【非阻塞】的方式去拉取新的事件
     */
    int BUSY_WAIT = -3;

    /**
     * The {@link SelectStrategy} can be used to steer the outcome of a potential select
     * call.
     * {@link SelectStrategy}可以被用来指示一个潜在的select调用结果
     * @param selectSupplier The supplier with the result of a select result.
     * @param hasTasks true if tasks are waiting to be processed.如果有任务等待去被执行
     * @return {@link #SELECT} if the next step should be blocking select {@link #CONTINUE} if
     *         the next step should be to not select but rather jump back to the IO loop and try
     *         again. Any value >= 0 is treated as an indicator that work needs to be done.
     *         如果返回{@link #SELECT}，说明下一步操作应该是【阻塞的select】；如果返回{@link #CONTINUE}，说明
     *         下一步操作应该不应该是select，而应该是继续【跳回IO循环并且重试】；并且，如果value >= 0，
     *         说明有任务（selectedKey）需要被处理
     */
    int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception;
}
