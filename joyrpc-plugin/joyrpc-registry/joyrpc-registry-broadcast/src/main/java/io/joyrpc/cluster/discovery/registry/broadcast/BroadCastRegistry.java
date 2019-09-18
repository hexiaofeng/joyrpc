package io.joyrpc.cluster.discovery.registry.broadcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryExpiredListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import io.joyrpc.cluster.Shard;
import io.joyrpc.cluster.discovery.backup.Backup;
import io.joyrpc.cluster.discovery.config.ConfigHandler;
import io.joyrpc.cluster.discovery.naming.ClusterHandler;
import io.joyrpc.cluster.discovery.registry.AbstractRegistry;
import io.joyrpc.cluster.discovery.registry.URLKey;
import io.joyrpc.cluster.event.ClusterEvent;
import io.joyrpc.cluster.event.ClusterEvent.*;
import io.joyrpc.cluster.event.ConfigEvent;
import io.joyrpc.context.GlobalContext;
import io.joyrpc.extension.URL;
import io.joyrpc.extension.URLOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static io.joyrpc.constants.Constants.*;
import static io.joyrpc.event.UpdateEvent.UpdateType.FULL;
import static io.joyrpc.event.UpdateEvent.UpdateType.UPDATE;

/**
 * hazelcast注册中心实现
 */
public class BroadCastRegistry extends AbstractRegistry {

    private static final Logger logger = LoggerFactory.getLogger(BroadCastRegistry.class);

    /**
     * 节点失效时间参数
     */
    public static final URLOption<Long> NODE_EXPIRED_TIME = new URLOption<>("nodeExpiredTime", 30000L);
    /**
     * hazelcast实例配置
     */
    protected Config cfg;
    /**
     * hazelcast实例
     */
    protected HazelcastInstance instance;
    /**
     * 根路径
     */
    protected String root;
    /**
     * 节点时效时间
     */
    protected long nodeExpiredTime;
    /**
     * 存储provider的Map的路径函数
     */
    protected Function<URL, String> providersRootKeyFunc;
    /**
     * 存在provider或者consumer的Map的路径函数
     */
    protected Function<URL, String> serviceRootKeyFunc;
    /**
     * provider或consumer在存储map中的key值的函数
     */
    protected Function<URL, String> serviceNodeKeyFunc;
    /**
     * 存储接口配置的Map的路径函数
     */
    protected Function<URL, String> configRootKeyFunc;
    /**
     * 集群订阅管理
     */
    protected SubscriberManager clusterSubscriberManager;
    /**
     * 配置订阅管理
     */
    protected SubscriberManager configSubscriberManager;
    /**
     * consuner与provider与注册中心心跳task
     */
    protected HeartbeatTask heartbeatTask;

    public BroadCastRegistry(String name, URL url, Backup backup) {
        super(name, url, backup);
        //TODO 根据url创建cfg
        this.cfg = new Config();
        this.nodeExpiredTime = url.getLong(NODE_EXPIRED_TIME);
        this.root = url.getString("namespace", GlobalContext.getString(PROTOCOL_KEY));
        if (root.charAt(0) != '/') {
            root = "/" + root;
        }
        if (root.charAt(root.length() - 1) == '/') {
            root = root.substring(0, root.length() - 1);
        }
        this.providersRootKeyFunc = u -> root + "/service/" + u.getPath() + "/" + u.getString(ALIAS_OPTION) + "/" + SIDE_PROVIDER;
        this.serviceRootKeyFunc = u -> root + "/service/" + u.getPath() + "/" + u.getString(ALIAS_OPTION) + "/" + u.getString(ROLE_OPTION);
        this.serviceNodeKeyFunc = u -> u.getProtocol() + "://" + u.getHost() + ":" + u.getPort();
        this.configRootKeyFunc = u -> root + "/config/" + u.getPath() + "/" + u.getString(ROLE_OPTION) + "/" + GlobalContext.getString(KEY_APPNAME);
        this.clusterSubscriberManager = new SubscriberManager(providersRootKeyFunc);
        this.configSubscriberManager = new SubscriberManager(configRootKeyFunc);
    }

    @Override
    protected CompletableFuture<Void> connect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            instance = Hazelcast.newHazelcastInstance(cfg);
            heartbeatTask = new HeartbeatTask();
            Thread heartbeatThread = new Thread(heartbeatTask);
            heartbeatThread.setName("BroadCastRegistry-" + registryId + "-heartbeat-task");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
            future.complete(null);
        } catch (Exception e) {
            logger.error(String.format("Error occurs while connect, caused by %s", e.getMessage()), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    protected CompletableFuture<Void> disconnect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            if (heartbeatTask != null) {
                heartbeatTask.close();
                heartbeatTask = null;
            }
            instance.shutdown();
            future.complete(null);
        } catch (Exception e) {
            logger.error(String.format("Error occurs while disconnect, caused by %s", e.getMessage()), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    protected CompletableFuture<Void> doRegister(URLKey url) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            IMap<String, URL> serviceNodes = instance.getMap(serviceRootKeyFunc.apply(url.getUrl()));
            serviceNodes.put(serviceNodeKeyFunc.apply(url.getUrl()), url.getUrl(), nodeExpiredTime, TimeUnit.MILLISECONDS);
            future.complete(null);
        } catch (Exception e) {
            logger.error(String.format("Error occurs while do register of %s, caused by %s", url.getKey(), e.getMessage()), e);
            future.completeExceptionally(e);

        }
        return future;
    }

    @Override
    protected CompletableFuture<Void> doDeregister(URLKey url) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            IMap<String, URL> serviceNodes = instance.getMap(serviceRootKeyFunc.apply(url.getUrl()));
            serviceNodes.remove(serviceNodeKeyFunc.apply(url.getUrl()));
            future.complete(null);
        } catch (Exception e) {
            logger.error(String.format("Error occurs while do deregister of %s, caused by %s", url.getKey(), e.getMessage()), e);
            future.completeExceptionally(e);

        }
        return future;
    }

    @Override
    protected CompletableFuture<Void> doSubscribe(URLKey url, ClusterHandler handler) {
        return clusterSubscriberManager.subscribe(url, new ClusterListener(url.getUrl(), handler));
    }

    @Override
    protected CompletableFuture<Void> doUnsubscribe(URLKey url, ClusterHandler handler) {
        return clusterSubscriberManager.unSubscribe(url);
    }

    @Override
    protected CompletableFuture<Void> doSubscribe(URLKey url, ConfigHandler handler) {
        return configSubscriberManager.subscribe(url, new ConfigListener(url.getUrl(), handler));
    }

    @Override
    protected CompletableFuture<Void> doUnsubscribe(URLKey url, ConfigHandler handler) {
        return configSubscriberManager.unSubscribe(url);
    }

    protected class SubscriberManager {

        protected Map<String, String> listenerIds = new ConcurrentHashMap<>();

        protected Function<URL, String> function;

        public SubscriberManager(Function<URL, String> function) {
            this.function = function;
        }

        public CompletableFuture<Void> subscribe(URLKey urlKey, SubscribeListener listener) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            try {
                listenerIds.computeIfAbsent(urlKey.getKey(), o -> {
                    IMap map = instance.getMap(function.apply(urlKey.getUrl()));
                    listener.onFullEvent(map);
                    return map.addEntryListener(listener, true);
                });
                future.complete(null);
            } catch (Exception e) {
                logger.error(String.format("Error occurs while subscribe of %s, caused by %s", urlKey.getKey(), e.getMessage()), e);
                future.completeExceptionally(e);
            }
            return future;
        }

        public CompletableFuture<Void> unSubscribe(URLKey urlKey) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            try {
                String listenerId = listenerIds.remove(urlKey.getKey());
                IMap map = instance.getMap(function.apply(urlKey.getUrl()));
                map.removeEntryListener(listenerId);
                future.complete(null);
            } catch (Exception e) {
                logger.error(String.format("Error occurs while unsubscribe of %s, caused by %s", urlKey.getKey(), e.getMessage()), e);
                future.completeExceptionally(e);
            }
            return future;
        }

    }

    protected interface SubscribeListener extends EntryAddedListener, EntryUpdatedListener, EntryRemovedListener, EntryExpiredListener {

        void onFullEvent(IMap map);

    }

    protected class ClusterListener implements SubscribeListener {

        /**
         * consumer的url
         */
        protected URL serviceUrl;
        /**
         * 集群事件handler
         */
        protected ClusterHandler handler;
        /**
         * 事件版本
         */
        protected AtomicLong version = new AtomicLong();

        public ClusterListener(URL serviceUrl, ClusterHandler handler) {
            this.serviceUrl = serviceUrl;
            this.handler = handler;
        }

        @Override
        public void entryAdded(EntryEvent entryEvent) {
            onUpdateEvent((URL) entryEvent.getValue(), ShardEventType.ADD);
        }

        @Override
        public void entryExpired(EntryEvent entryEvent) {
            onUpdateEvent((URL) entryEvent.getOldValue(), ShardEventType.DELETE);
        }

        @Override
        public void entryRemoved(EntryEvent entryEvent) {
            onUpdateEvent((URL) entryEvent.getOldValue(), ShardEventType.DELETE);
        }

        @Override
        public void entryUpdated(EntryEvent entryEvent) {
            onUpdateEvent((URL) entryEvent.getValue(), ShardEventType.UPDATE);
        }

        public void onUpdateEvent(URL providerUrl, ShardEventType eventType) {
            List<ShardEvent> shardEvents = new ArrayList<>();
            shardEvents.add(new ShardEvent(new Shard.DefaultShard(providerUrl), eventType));
            handler.handle(new ClusterEvent(BroadCastRegistry.this, null, UPDATE, version.incrementAndGet(), shardEvents));
        }


        @Override
        public void onFullEvent(IMap map) {
            List<ShardEvent> shardEvents = new ArrayList<>();
            Collection<URL> providers = map.values();
            providers.forEach(providerUrl -> {
                shardEvents.add(new ShardEvent(new Shard.DefaultShard(providerUrl), ShardEventType.ADD));
            });
            handler.handle(new ClusterEvent(BroadCastRegistry.this, null, FULL, version.incrementAndGet(), shardEvents));
        }
    }

    protected class ConfigListener implements SubscribeListener {

        /**
         * consumer或provider的url
         */
        protected URL serviceUrl;
        /**
         * 配置事件handler
         */
        protected ConfigHandler handler;
        /**
         * 事件版本
         */
        protected AtomicLong version = new AtomicLong();

        public ConfigListener(URL serviceUrl, ConfigHandler handler) {
            this.serviceUrl = serviceUrl;
            this.handler = handler;
        }

        @Override
        public void entryAdded(EntryEvent entryEvent) {
            IMap<String, String> map = instance.getMap(configRootKeyFunc.apply(serviceUrl));
            onFullEvent(map);
        }

        @Override
        public void entryExpired(EntryEvent entryEvent) {
        }

        @Override
        public void entryRemoved(EntryEvent entryEvent) {
            IMap<String, String> map = instance.getMap(configRootKeyFunc.apply(serviceUrl));
            onFullEvent(map);
        }

        @Override
        public void entryUpdated(EntryEvent entryEvent) {
            IMap<String, String> map = instance.getMap(configRootKeyFunc.apply(serviceUrl));
            onFullEvent(map);
        }

        @Override
        public void onFullEvent(IMap map) {
            Map<String, String> datum;
            if (map != null) {
                datum = new HashMap<>(map);
            } else {
                datum = new HashMap<>();
            }
            handler.handle(new ConfigEvent(BroadCastRegistry.this, null, FULL, version.incrementAndGet(), datum));
        }
    }

    protected class HeartbeatTask implements Runnable {

        /**
         * 启动标识
         */
        protected AtomicBoolean started = new AtomicBoolean(true);

        @Override
        public void run() {
            long sleep = nodeExpiredTime / 3;
            while (started.get()) {
                try {
                    Thread.sleep(sleep);
                    registers.forEach((key, meta) -> {
                        URL url = meta.getUrl();
                        String serviceRootKey = serviceRootKeyFunc.apply(url);
                        String nodeKey = serviceNodeKeyFunc.apply(url);
                        IMap<String, URL> map = instance.getMap(serviceRootKey);
                        map.get(nodeKey);
                    });
                } catch (InterruptedException e) {
                }
            }
        }

        /**
         * 关闭
         */
        public void close() {
            started.set(false);
        }
    }

}
