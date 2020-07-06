package io.joyrpc.transport.http2;

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

/**
 * @date: 2019/4/10
 */
public interface Http2Message {

    /**
     * 流式ID
     *
     * @return 流式ID
     */
    int getStreamId();

    /**
     * 消息ID
     *
     * @return 消息ID
     */
    long getMsgId();

    /**
     * 头部
     *
     * @return 头部
     */
    Http2Headers headers();

    /**
     * 获取结束头
     *
     * @return 结束头
     */
    Http2Headers getEndHeaders();

    /**
     * 设置结束头
     *
     * @param endHeaders 结束头
     */
    void setEndHeaders(Http2Headers endHeaders);

    /**
     * 内容
     *
     * @return 内容
     */
    byte[] content();

}
