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


import io.joyrpc.event.AsyncResult;
import io.joyrpc.transport.buffer.ChannelBuffer;
import io.joyrpc.transport.message.Message;
import io.joyrpc.transport.session.Session;
import io.joyrpc.transport.session.SessionManager;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * @date: 2019/1/7
 */
public interface Channel {

    String BIZ_THREAD_POOL = "bizThreadPool";

    String IDLE_HEARTBEAT_TRIGGER = "idleHeartbeatTrigger";

    String CHANNEL_TRANSPORT = "CHANNEL_TRANSPORT";

    String SERVER_CHANNEL = "SERVER_CHANNEL";

    String CHANNEL_KEY = "CHANNEL_KEY";

    String PROTOCOL = "PROTOCOL";

    String PAYLOAD = "PAYLOAD";

    String EVENT_PUBLISHER = "EVENT_PUBLISHER";

    /**
     * 连接转字符串
     *
     * @return
     */
    static String toString(Channel channel) {
        return toString(channel.getLocalAddress()) + " -> " + toString(channel.getRemoteAddress());

    }

    /**
     * InetSocketAddress转 host:port 字符串
     *
     * @param address InetSocketAddress转
     * @return host:port 字符串
     */
    static String toString(final InetSocketAddress address) {
        if (address == null) {
            return "";
        } else {
            InetAddress inetAddress = address.getAddress();
            return inetAddress == null ? address.getHostName() :
                    inetAddress.getHostAddress() + ":" + address.getPort();
        }
    }

    /**
     * 发送一个object信息
     *
     * @param object
     */
    default void send(Object object) {
        send(object, null);
    }

    /**
     * 发送一个object信息
     *
     * @param object
     * @param consumer
     */
    void send(Object object, Consumer<SendResult> consumer);

    /**
     * 关闭channel
     *
     * @return
     */
    boolean close();

    /**
     * 异步关闭channel
     *
     * @param consumer
     */
    void close(Consumer<AsyncResult<Channel>> consumer);

    /**
     * 获取本地地址
     *
     * @return
     */
    InetSocketAddress getLocalAddress();

    /**
     * 获取远程地址
     *
     * @return
     */
    InetSocketAddress getRemoteAddress();

    /**
     * 是否可写
     *
     * @return
     */
    boolean isWritable();

    /**
     * 是否存活
     *
     * @return
     */
    boolean isActive();

    /**
     * 获取属性
     *
     * @param key
     * @param <T>
     * @return
     */
    <T> T getAttribute(String key);

    /**
     * 获取属性，如果为null返回默认值
     *
     * @param key
     * @param def
     * @param <T>
     * @return
     */
    default <T> T getAttribute(String key, T def) {
        T result = getAttribute(key);
        return result == null ? def : result;
    }

    /**
     * 获取属性，没有的时候调用Function创建
     *
     * @param key
     * @param function
     * @param <T>
     * @return
     */
    default <T> T getAttribute(final String key, final Function<String, T> function) {
        if (key == null) {
            return null;
        }
        T result = getAttribute(key);
        if (result == null) {
            result = function.apply(key);
            setAttribute(key, result);
        }
        return result;
    }

    /**
     * 设置属性
     *
     * @param key
     * @param value
     * @return
     */
    Channel setAttribute(String key, Object value);

    /**
     * 设置属性
     *
     * @param key
     * @param value
     * @param predicate
     * @return
     */
    default Channel setAttribute(final String key, final Object value, final BiPredicate<String, Object> predicate) {
        if (predicate.test(key, value)) {
            return setAttribute(key, value);
        }
        return this;
    }

    /**
     * 删除属性
     *
     * @param key
     * @return
     */
    Object removeAttribute(String key);

    /**
     * 获取Future管理器
     *
     * @return Future管理器
     */
    FutureManager<Long, Message> getFutureManager();

    /**
     * 申请一个ChannelBuffer
     *
     * @return ChannelBuffer
     */
    ChannelBuffer buffer();

    /**
     * 申请一个ChannelBuffer
     *
     * @param initialCapacity 初始长度
     * @return ChannelBuffer
     */
    ChannelBuffer buffer(int initialCapacity);

    /**
     * 申请一个ChannelBuffer
     *
     * @param initialCapacity 初始长度
     * @param maxCapacity     最大长度
     * @return ChannelBuffer
     */
    ChannelBuffer buffer(int initialCapacity, int maxCapacity);

    /**
     * 获取session管理器
     *
     * @return SessionManager
     */
    SessionManager getSessionManager();

    /**
     * 是否是服务端
     *
     * @return
     */
    boolean isServer();

    /**
     * 获取会话
     *
     * @param sessionId
     * @return
     */
    default Session getSession(final int sessionId) {
        return getSessionManager().get(sessionId);
    }

    /**
     * 设置会话
     *
     * @param sessionId
     * @param session
     * @return
     */
    default Session addSession(final int sessionId, final Session session) {
        return getSessionManager().put(sessionId, session);
    }

    /**
     * 添加会话
     *
     * @param sessionId
     * @param session
     * @return
     */
    default Session addIfAbsentSession(final int sessionId, final Session session) {
        return getSessionManager().putIfAbsent(sessionId, session);
    }

    /**
     * 删除会话
     *
     * @param sessionId
     * @return
     */
    default Session removeSession(final int sessionId) {
        return getSessionManager().remove(sessionId);
    }

    /**
     * 驱逐过期会话
     */
    default void evictSession() {
        getSessionManager().evict();
    }

    /**
     * 会话心跳
     *
     * @param sessionId
     */
    default boolean beatSession(final int sessionId) {
        return getSessionManager().beat(sessionId);
    }

    /**
     * 触发异常事件
     *
     * @param caught
     */
    void fireCaught(Throwable caught);


}
