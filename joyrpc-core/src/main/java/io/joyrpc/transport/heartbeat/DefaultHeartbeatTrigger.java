package io.joyrpc.transport.heartbeat;

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

import io.joyrpc.event.Publisher;
import io.joyrpc.extension.URL;
import io.joyrpc.transport.channel.Channel;
import io.joyrpc.transport.channel.FutureManager;
import io.joyrpc.transport.event.HeartbeatEvent;
import io.joyrpc.transport.event.InactiveEvent;
import io.joyrpc.transport.event.TransportEvent;
import io.joyrpc.transport.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 默认心跳触发器
 */
public class DefaultHeartbeatTrigger implements HeartbeatTrigger {

    private static final Logger logger = LoggerFactory.getLogger(DefaultHeartbeatTrigger.class);
    /**
     * 通道
     */
    protected final Channel channel;
    /**
     * URL
     */
    protected final URL url;
    /**
     * 心跳策略
     */
    protected final HeartbeatStrategy strategy;
    /**
     * 事件发布器
     */
    protected final Publisher<TransportEvent> publisher;
    /**
     * 心跳应答
     */
    protected final BiConsumer<Message, Throwable> afterRun;

    /**
     * 构造函数
     *
     * @param channel
     * @param url
     * @param strategy
     * @param publisher
     */
    public DefaultHeartbeatTrigger(Channel channel, URL url, HeartbeatStrategy strategy, Publisher<TransportEvent> publisher) {
        this.channel = channel;
        this.url = url;
        this.strategy = strategy;
        this.publisher = publisher;
        this.afterRun = (msg, err) -> {
            if (err != null) {
                publisher.offer(new HeartbeatEvent(channel, url, err));
            } else {
                publisher.offer(new HeartbeatEvent(msg, channel, url));
            }
        };
    }

    @Override
    public HeartbeatStrategy strategy() {
        return strategy;
    }

    @Override
    public void run() {
        Message hbMsg;
        Supplier<Message> heartbeat = strategy.getHeartbeat();
        if (heartbeat != null && (hbMsg = heartbeat.get()) != null) {
            if (channel.isActive()) {
                FutureManager<Long, Message> futureManager = channel.getFutureManager();
                //设置id
                hbMsg.setMsgId(futureManager.generateId());
                //创建future
                futureManager.create(hbMsg.getMsgId(), strategy.getTimeout(), afterRun);
                //发送消息
                channel.send(hbMsg, r -> {
                    //心跳有应答消息
                    if (!r.isSuccess()) {
                        futureManager.completeExceptionally(hbMsg.getMsgId(), r.getThrowable());
                        logger.error(String.format("Error occurs while sending heartbeat to %s, caused by:",
                                Channel.toString(channel.getRemoteAddress())), r.getThrowable());
                    }
                });
            } else {
                publisher.offer(new InactiveEvent(channel));
            }
        }
    }

}
