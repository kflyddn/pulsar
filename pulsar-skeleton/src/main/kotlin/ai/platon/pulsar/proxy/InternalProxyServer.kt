package ai.platon.pulsar.proxy

import ai.platon.pulsar.common.DateTimeUtil
import ai.platon.pulsar.common.PulsarPaths
import ai.platon.pulsar.common.SimpleLogger
import ai.platon.pulsar.common.StringUtil
import ai.platon.pulsar.common.config.CapabilityTypes.*
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.PulsarConstants.INTERNAL_PROXY_SERVER_PORT
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.proxy.ProxyPool
import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline
import com.github.monkeywie.proxyee.intercept.common.FullRequestIntercept
import com.github.monkeywie.proxyee.intercept.common.FullResponseIntercept
import com.github.monkeywie.proxyee.proxy.ProxyConfig
import com.github.monkeywie.proxyee.proxy.ProxyType
import com.github.monkeywie.proxyee.server.HttpProxyServer
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig
import io.netty.channel.Channel
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class InternalProxyServer(
        private val proxyPool: ProxyPool,
        private val conf: ImmutableConfig
): AutoCloseable {
    private val log = LoggerFactory.getLogger(InternalProxyServer::class.java)
    private val now = DateTimeUtil.now("yyyyMMdd")
    private val path = PulsarPaths.get("proxy", "logs", "proxy-$now.log")
    private val proxyLog = SimpleLogger(path, SimpleLogger.INFO)

    private var runningWithoutProxy = false
    private var forwardServer: HttpProxyServer? = null
    private var forwardServerThread: Thread? = null
    private val loopThread = Thread(this::startLoop)
    private val closed = AtomicBoolean()
    private val connected = AtomicBoolean()
    private val lock: Lock = ReentrantLock()
    private val connectedCond: Condition = lock.newCondition()
    private var readyTimeout = Duration.ofSeconds(45)

    private val bossGroupThreads = conf.getInt(PROXY_INTERNAL_SERVER_BOSS_THREADS, 5)
    private val workerGroupThreads = conf.getInt(PROXY_INTERNAL_SERVER_WORKER_THREADS, 20)
    private val httpProxyServerConfig = HttpProxyServerConfig()

    val enabled = conf.getBoolean(PROXY_ENABLE_INTERNAL_SERVER, true)
    val disabled get() = !enabled
    var proxyEntry: ProxyEntry? = null
        private set
    val port = INTERNAL_PROXY_SERVER_PORT
    val totalConnects = AtomicInteger()
    val numRunningThreads = AtomicInteger()
    val isConnected get() = connected.get()
    val isClosed get() = closed.get()

    var lastActiveTime = Instant.now()
    private var idleTime = Duration.ZERO

    init {
        httpProxyServerConfig.bossGroupThreads = bossGroupThreads
        httpProxyServerConfig.workerGroupThreads = workerGroupThreads
        httpProxyServerConfig.isHandleSsl = false
    }

    fun start() {
        if (disabled) {
            log.warn("Internal proxy server is disabled")
            return
        }

        loopThread.isDaemon = true
        loopThread.start()
    }

    fun waitUntilRunning(): Boolean {
        if (disabled || isClosed) {
            return false
        }

        if (isConnected) {
            return true
        }

        log.info("Waiting for proxy server to connect ...")

        lastActiveTime = Instant.now()
        idleTime = Duration.ZERO

        lock.withLock {
            val b = connectedCond.await(readyTimeout.seconds, TimeUnit.SECONDS)
            if (!b) {
                // timeout
            }
        }

        return !isClosed && isConnected
    }

    private fun isIdle(): Boolean {
        var isIdle = false
        if (numRunningThreads.get() == 0) {
            idleTime = Duration.between(lastActiveTime, Instant.now())
            if (idleTime.toMinutes() > 20) {
                // do not waste the proxy resource, they are costly!
                isIdle = true
            }
        }
        return isIdle
    }

    private fun startLoop() {
        var tick = 0
        while (!isClosed) {
            // Wait for 5 seconds
            if (tick++ % 5 != 0) {
                continue
            }

            // TODO: use a connected connection for the testing
            val proxyStillAlive = proxyEntry?.testNetwork()?:false
            if (!proxyStillAlive) {
                val proxy = proxyEntry
                if (proxy != null) {
                    log.info("Proxy <{}> is unavailable, mark it retired", proxy)
                    proxyPool.retire(proxy)
                    proxyEntry = null
                }
            }

            val isIdle = isIdle()
            if (isIdle) {
                if (isConnected) {
                    log.info("The internal proxy server is idle, disconnect connected proxy.")
                    disconnect(notifyAll = true)
                }
            } else {
                if (!proxyStillAlive || !isConnected) {
                    // close old no working running forward proxy server and quit the thread
                    disconnect()
                    connect()
                }
            }

            try {
                TimeUnit.SECONDS.sleep(1)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        log.info("Quit internal proxy server")
    }

    @Synchronized
    private fun connect() {
        val proxy = proxyPool.poll()
        if (proxy != null || !runningWithoutProxy) {
            connectTo(proxy)
            this.proxyEntry = proxy
        }
    }

    private fun connectTo(proxy: ProxyEntry?) {
        if (isClosed) {
            return
        }

        if (isConnected) {
            log.warn("Internal proxy server is already running")
            return
        }

        val server = initForwardProxyServer(proxy)
        val thread = Thread { server.start(INTERNAL_PROXY_SERVER_PORT) }
        thread.isDaemon = true
        thread.start()

        forwardServer = server
        forwardServerThread = thread
        runningWithoutProxy = proxy == null
        totalConnects.incrementAndGet()

        connected.set(true)
        lock.withLock {
            connectedCond.signalAll()
        }

        log.info("Internal proxy server is started at {} with {}",
                INTERNAL_PROXY_SERVER_PORT,
                if (proxy != null) "external proxy <$proxy>" else "no proxy")
    }

    @Synchronized
    private fun disconnect(notifyAll: Boolean = false) {
        connected.set(false)
        // notify all to exit waiting
        if (notifyAll) {
            lock.withLock {
                connectedCond.signalAll()
            }
        }

        forwardServer?.use { it.close() }
        forwardServerThread?.interrupt()
        forwardServerThread?.join()
        forwardServer = null
        forwardServerThread = null
    }

    @Synchronized
    override fun close() {
        if (disabled || closed.getAndSet(true)) {
            return
        }

        log.info("Closing internal proxy server ...")

        disconnect(notifyAll = true)
        loopThread.join()

        proxyLog.use { it.close() }
    }

    private fun initForwardProxyServer(proxy: ProxyEntry?): HttpProxyServer {
        val server = HttpProxyServer()
        server.serverConfig(httpProxyServerConfig)

        if (proxy != null) {
            val proxyConfig = ProxyConfig(ProxyType.HTTP, proxy.host, proxy.port)
            server.proxyConfig(proxyConfig)
        }

        server.proxyInterceptInitializer(object : HttpProxyInterceptInitializer() {
            override fun init(pipeline: HttpProxyInterceptPipeline) {
                pipeline.addLast(object : FullRequestIntercept() {
                    override fun match(httpRequest: HttpRequest, pipeline: HttpProxyInterceptPipeline): Boolean {
                        // return HttpUtil.checkHeader(httpRequest.headers(), HttpHeaderNames.CONTENT_TYPE, "^(?i)application/json.*$")
                        return log.isDebugEnabled
                    }

                    override fun handelRequest(httpRequest: FullHttpRequest, pipeline: HttpProxyInterceptPipeline) {
                        log.debug("Ready to download {}", httpRequest.headers())
                    }
                })

                pipeline.addLast(object : FullResponseIntercept() {
                    override fun match(httpRequest: HttpRequest, httpResponse: HttpResponse, pipeline: HttpProxyInterceptPipeline): Boolean {
                        // return HttpUtil.checkUrl(pipeline.httpRequest, "^www.baidu.com$") && HttpUtil.isHtml(httpRequest, httpResponse)
                        return log.isDebugEnabled
                    }

                    override fun handelResponse(httpRequest: HttpRequest, httpResponse: FullHttpResponse, pipeline: HttpProxyInterceptPipeline) {
                        log.debug("Got resource {}, {}", httpResponse.status(), httpResponse.headers())
                    }
                })
            }
        })

        server.httpProxyExceptionHandle(object: HttpProxyExceptionHandle() {
            override fun beforeCatch(clientChannel: Channel, cause: Throwable) {
                // log.warn("Internal proxy error - {}", StringUtil.stringifyException(cause))
            }

            override fun afterCatch(clientChannel: Channel, proxyChannel: Channel, cause: Throwable) {
                val message = cause.message
                if (message == null) {
                    log.warn(StringUtil.stringifyException(cause))
                    return
                }

                if (message.endsWith("Connection reset by peer")) {
                    log.warn(StringUtil.stringifyException(cause))
                    return
                }

                if (message.endsWith("disconnected") || message.endsWith("timeout")) {
                    proxyLog.write(SimpleLogger.INFO, "proxy", message)
                } else {
                    log.warn(message)
                }
            }
        })

        return server
    }
}

fun main() {
    val conf = ImmutableConfig()
    val proxyPool = ProxyPool(conf)
    val server = InternalProxyServer(proxyPool, conf)
    server.start()
}