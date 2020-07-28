package io.joyrpc.transport.channel;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
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
 * #L%
 */

import io.joyrpc.exception.ChannelClosedException;
import io.joyrpc.transport.session.Session;
import io.joyrpc.util.SystemClock;
import io.joyrpc.util.Timer.TimeTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.joyrpc.constants.Constants.FUTURE_TIMEOUT_PREFIX;
import static io.joyrpc.util.Timer.timer;

/**
 * Future管理器，绑定到Channel上
 */
public class FutureManager<I, M> {
    /**
     * 通道
     */
    protected Channel channel;
    /**
     * ID生成器
     */
    protected Supplier<I> idGenerator;
    /**
     * 计数器
     */
    protected AtomicInteger counter = new AtomicInteger();
    /**
     * 消费者
     */
    protected Consumer<I> consumer;
    /**
     * Future管理，有些连接并发很少不需要初始化
     */
    protected Map<I, EnhanceCompletableFuture<I, M>> futures = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param channel
     * @param idGenerator
     */
    public FutureManager(final Channel channel, final Supplier<I> idGenerator) {
        this.channel = channel;
        this.idGenerator = idGenerator;
        this.consumer = id -> {
            //TODO 这个是timer的线程触发，需要确保future的链式处理很快。如果长时间占用线程，会影响其它的定时逻辑
            EnhanceCompletableFuture<I, M> future = futures.remove(id);
            if (future != null) {
                counter.decrementAndGet();
                //超时
                future.completeExceptionally(new TimeoutException("future is timeout."));
            }
        };
    }

    /**
     * 创建一个future
     *
     * @param messageId     消息ID
     * @param timeoutMillis 超时时间（毫秒）
     * @return 完成状态
     */
    public EnhanceCompletableFuture<I, M> create(final I messageId, final long timeoutMillis) {
        return create(messageId, timeoutMillis, null, null, null);
    }

    /**
     * 创建一个future
     *
     * @param messageId     消息ID
     * @param timeoutMillis 超时时间（毫秒）
     * @param afterRun      结束后执行
     * @return 完成状态
     */
    public EnhanceCompletableFuture<I, M> create(final I messageId, final long timeoutMillis,
                                                 final BiConsumer<M, Throwable> afterRun) {
        return create(messageId, timeoutMillis, null, null, afterRun);
    }

    /**
     * 创建一个future
     *
     * @param messageId     消息ID
     * @param timeoutMillis 超时时间
     * @param session       会话
     * @param requests      正在处理的请求数
     * @return 完成状态
     */
    public EnhanceCompletableFuture<I, M> create(final I messageId, final long timeoutMillis,
                                                 final Session session, final AtomicInteger requests) {
        return create(messageId, timeoutMillis, session, requests, null);
    }

    /**
     * 创建一个future
     *
     * @param messageId     消息ID
     * @param timeoutMillis 超时时间
     * @param session       会话
     * @param requests      正在处理的请求数
     * @param afterRun      结束后执行
     * @return 完成状态
     */
    public EnhanceCompletableFuture<I, M> create(final I messageId, final long timeoutMillis,
                                                 final Session session, final AtomicInteger requests,
                                                 final BiConsumer<M, Throwable> afterRun) {
        return futures.computeIfAbsent(messageId, o -> {
            //增加计数器
            counter.incrementAndGet();
            TimeTask task = new FutureTimeoutTask<>(messageId, SystemClock.now() + timeoutMillis, consumer);
            return new EnhanceCompletableFuture<>(o, session, timer().add(task), requests, afterRun);
        });
    }

    /**
     * 根据msgId获取future
     *
     * @param messageId
     * @return
     */
    public EnhanceCompletableFuture<I, M> get(final I messageId) {
        return futures.get(messageId);
    }

    /**
     * 正常结束
     *
     * @param messageId 消息ID
     * @param message   消息
     * @return 成功标识
     */
    public boolean complete(final I messageId, final M message) {
        EnhanceCompletableFuture<I, M> result = futures.remove(messageId);
        if (result != null) {
            //减少计数器
            counter.decrementAndGet();
            return result.complete(message);
        }
        return false;
    }

    /**
     * 异常结束
     *
     * @param messageId 消息ID
     * @param throwable 异常
     * @return 成功标识
     */
    public boolean completeExceptionally(final I messageId, final Throwable throwable) {
        EnhanceCompletableFuture<I, M> result = futures.remove(messageId);
        if (result != null) {
            //减少计数器
            counter.decrementAndGet();
            return result.completeExceptionally(throwable);
        }
        return false;
    }

    /**
     * 开启FutureManager（注册timeout事件）
     */
    public void open() {
    }

    /**
     * 清空
     */
    public void close() {
        Map<I, EnhanceCompletableFuture<I, M>> futures = this.futures;
        this.futures = new ConcurrentHashMap<>();
        this.counter = new AtomicInteger();
        Exception exception = new ChannelClosedException("channel is inactive, address is " + channel.getRemoteAddress());
        futures.forEach((id, future) -> future.completeExceptionally(exception));
        futures.clear();
    }

    /**
     * 生成消息ID
     *
     * @return 消息ID
     */
    public I generateId() {
        return idGenerator.get();
    }

    /**
     * 待应答的请求数
     *
     * @return 应答的请求数
     */
    public int size() {
        return counter.get();
    }

    /**
     * 请求数是否为空
     *
     * @return 请求数为空标识
     */
    public boolean isEmpty() {
        return counter.get() == 0;
    }

    /**
     * Future超时检查任务
     *
     * @param <I>
     */
    protected static class FutureTimeoutTask<I> implements TimeTask {

        /**
         * 消息ID
         */
        protected I messageId;
        /**
         * 时间
         */
        protected long time;
        /**
         * 执行回调的消费者
         */
        protected Consumer<I> consumer;

        /**
         * 构造函数
         *
         * @param messageId 消息ID
         * @param time      执行时间
         * @param consumer  消费者
         */
        public FutureTimeoutTask(I messageId, long time, Consumer<I> consumer) {
            this.messageId = messageId;
            this.time = time;
            this.consumer = consumer;
        }

        @Override
        public String getName() {
            return FUTURE_TIMEOUT_PREFIX + messageId.toString();
        }

        @Override
        public long getTime() {
            return time;
        }

        @Override
        public void run() {
            consumer.accept(messageId);
        }
    }

}
