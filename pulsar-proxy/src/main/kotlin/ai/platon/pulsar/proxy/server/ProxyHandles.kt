package ai.platon.pulsar.proxy.server

import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.proxy.ProxyType
import ai.platon.pulsar.proxy.common.ProtoUtil
import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.ProxyHandler
import io.netty.handler.proxy.Socks4ProxyHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.resolver.NoopAddressResolverGroup
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.net.URL
import java.util.*

open class HttpProxyExceptionHandle {

    open fun beforeCatch(clientChannel: Channel, cause: Throwable) {
        throw Exception(cause)
    }

    open fun afterCatch(clientChannel: Channel, proxyChannel: Channel, cause: Throwable) {
        throw Exception(cause)
    }
}

/**
 * HTTP 代理，转发解码后的 HTTP 报文
 */
open class HttpProxyInitializer(
        private val clientChannel: Channel,
        private val requestProto: ProtoUtil.RequestProto,
        private val proxyHandler: ProxyHandler?
) : ChannelInitializer<Channel>() {

    override fun initChannel(ch: Channel) {
        if (proxyHandler != null) {
            ch.pipeline().addLast(proxyHandler)
        }
        ch.pipeline().addLast("httpCodec", HttpClientCodec())
        ch.pipeline().addLast("proxyClientHandle", HttpProxyClientHandle(clientChannel))
    }
}

open class HttpProxyClientHandle(private val clientChannel: Channel) : ChannelInboundHandlerAdapter() {

    private val serverHandle get() = clientChannel.pipeline().get(HttpProxyServerHandle::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        //客户端channel已关闭则不转发了
        if (!clientChannel.isOpen) {
            ReferenceCountUtil.release(msg)
            return
        }

        when (msg) {
            is HttpResponse -> serverHandle.interceptPipeline.afterResponse(clientChannel, ctx.channel(), msg)
            is HttpContent -> serverHandle.interceptPipeline.afterResponse(clientChannel, ctx.channel(), msg)
            else -> clientChannel.writeAndFlush(msg)
        }
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        ctx.channel().close()
        clientChannel.close()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.channel().close()
        clientChannel.close()
        serverHandle.exceptionHandle.afterCatch(clientChannel, ctx.channel(), cause)
    }
}

open class HttpProxyServerHandle(
        val serverConfig: HttpProxyServerConfig,
        private val interceptInitializer: HttpProxyInterceptInitializer,
        private val proxyEntry: ProxyEntry?,
        val exceptionHandle: HttpProxyExceptionHandle
) : ChannelInboundHandlerAdapter() {

    private var channelFuture: ChannelFuture? = null
    private lateinit var host: String
    private var port: Int = 0
    private var status = 0
    private var requestList = mutableListOf<Any>()
    private var isConnected: Boolean = false

    lateinit var interceptPipeline: HttpProxyInterceptPipeline
        private set

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            // 第一次建立连接取host和端口号和处理代理握手
            if (status == 0) {
                val requestProto = ProtoUtil.getRequestProto(msg)
                if (requestProto == null) {
                    // bad request
                    ctx.channel().close()
                    return
                }

                status = 1
                this.host = requestProto.host
                this.port = requestProto.port
                if ("CONNECT".equals(msg.method().name(), ignoreCase = true)) {//建立代理握手
                    status = 2
                    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpProxyServer.SUCCESS)
                    ctx.writeAndFlush(response)
                    ctx.channel().pipeline().remove("httpCodec")
                    //fix issue #42
                    ReferenceCountUtil.release(msg)
                    return
                }
            }

            interceptPipeline = buildPipeline()
            interceptPipeline.requestProto = ProtoUtil.RequestProto(host, port)
            //fix issue #27
            if (msg.uri().indexOf("/") != 0) {
                val url = URL(msg.uri())
                msg.uri = url.file
            }
            interceptPipeline.beforeRequest(ctx.channel(), msg)
        } else if (msg is HttpContent) {
            if (status != 2) {
                interceptPipeline.beforeRequest(ctx.channel(), msg)
            } else {
                ReferenceCountUtil.release(msg)
                status = 1
            }
        } else { // ssl和websocket的握手处理
            handleProxyData(ctx.channel(), msg, false)
        }
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        channelFuture?.channel()?.close()
        ctx.channel().close()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        channelFuture?.channel()?.close()
        ctx.channel().close()
        exceptionHandle.beforeCatch(ctx.channel(), cause)
    }

    private fun handleProxyData(channel: Channel, msg: Any, isHttp: Boolean) {
        if (channelFuture == null) {
            // connection异常 还有HttpContent进来，不转发
            if (isHttp && msg !is HttpRequest) {
                return
            }

            // TODO: choose a external proxy from proxy pool here
            val proxyHandler = if (proxyEntry == null) null else ProxyHandleFactory.build(proxyEntry)
            /*
             添加SSL client hello的Server Name Indication extension(SNI扩展)
             有些服务器对于client hello不带SNI扩展时会直接返回Received fatal alert: handshake_failure(握手错误)
             例如：https://cdn.mdn.mozilla.net/static/img/favicon32.7f3da72dcea1.png
            */
            val requestProto = ProtoUtil.RequestProto(host, port)
            val channelInitializer = if (isHttp)
                HttpProxyInitializer(channel, requestProto, proxyHandler)
            else
                TunnelProxyInitializer(channel, proxyHandler)

            val bootstrap = Bootstrap()
            bootstrap.group(serverConfig.proxyLoopGroup) // 注册线程池
                    .channel(NioSocketChannel::class.java) // 使用NioSocketChannel来作为连接用的channel类
                    .handler(channelInitializer)

            if (proxyEntry != null) {
                // 代理服务器解析DNS和连接
                bootstrap.resolver(NoopAddressResolverGroup.INSTANCE)
            }

            requestList = LinkedList()

            channelFuture = bootstrap.connect(host, port)

            // System.out.println("Connected to " + host + ":" + port);

            channelFuture?.addListener({ future: ChannelFuture ->
                if (future.isSuccess) {
                    future.channel().writeAndFlush(msg)
                    synchronized(requestList) {
                        requestList.forEach { obj -> future.channel().writeAndFlush(obj) }
                        requestList.clear()
                        isConnected = true
                    }
                } else {
                    requestList.forEach { obj -> ReferenceCountUtil.release(obj) }
                    requestList.clear()
                    future.channel().close()
                    channel.close()
                }
            } as ChannelFutureListener)
        } else {
            synchronized(requestList) {
                if (isConnected) {
                    channelFuture!!.channel().writeAndFlush(msg)
                } else {
                    requestList.add(msg)
                }
            }
        }
    }

    private fun buildPipeline(): HttpProxyInterceptPipeline {
        val proxyIntercept = object: HttpProxyIntercept() {
            override fun beforeRequest(clientChannel: Channel, httpRequest: HttpRequest,
                                       pipeline: HttpProxyInterceptPipeline) {
                handleProxyData(clientChannel, httpRequest, true)
            }

            override fun beforeRequest(clientChannel: Channel, httpContent: HttpContent,
                                       pipeline: HttpProxyInterceptPipeline) {
                handleProxyData(clientChannel, httpContent, true)
            }

            override fun afterResponse(clientChannel: Channel, proxyChannel: Channel,
                                       httpResponse: HttpResponse, pipeline: HttpProxyInterceptPipeline) {
                clientChannel.writeAndFlush(httpResponse)
                if (HttpHeaderValues.WEBSOCKET.toString() == httpResponse.headers().get(HttpHeaderNames.UPGRADE)) {
                    // websocket转发原始报文
                    proxyChannel.pipeline().remove("httpCodec")
                    clientChannel.pipeline().remove("httpCodec")
                }
            }

            override fun afterResponse(clientChannel: Channel, proxyChannel: Channel,
                                       httpContent: HttpContent, pipeline: HttpProxyInterceptPipeline) {
                clientChannel.writeAndFlush(httpContent)
            }
        }

        val interceptPipeline = HttpProxyInterceptPipeline(proxyIntercept)
        interceptInitializer.init(interceptPipeline)
        return interceptPipeline
    }
}

/**
 * http代理隧道，转发原始报文
 */
open class TunnelProxyInitializer(
        private val clientChannel: Channel,
        private val proxyHandler: ProxyHandler?
) : ChannelInitializer<Channel>() {

    override fun initChannel(ch: Channel) {
        if (proxyHandler != null) {
            ch.pipeline().addLast(proxyHandler)
        }

        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
            override fun channelRead(ctx0: ChannelHandlerContext, msg0: Any) {
                clientChannel.writeAndFlush(msg0)
            }

            override fun channelUnregistered(ctx0: ChannelHandlerContext) {
                ctx0.channel().close()
                clientChannel.close()
            }

            override fun exceptionCaught(ctx0: ChannelHandlerContext, cause: Throwable) {
                ctx0.channel().close()
                clientChannel.close()
                val exceptionHandle = (clientChannel.pipeline()
                        .get("serverHandle") as HttpProxyServerHandle).exceptionHandle
                exceptionHandle.afterCatch(clientChannel, ctx0.channel(), cause)
            }
        })
    }
}

object ProxyHandleFactory {
    fun build(config: ProxyEntry): ProxyHandler {
        val proxyHandler: ProxyHandler
        val isAuth = config.user != null && config.pwd != null
        val inetSocketAddress = InetSocketAddress(config.host, config.port)
        when (config.proxyType) {
            ProxyType.HTTP -> if (isAuth) {
                proxyHandler = HttpProxyHandler(inetSocketAddress, config.user, config.pwd)
            } else {
                proxyHandler = HttpProxyHandler(inetSocketAddress)
            }
            ProxyType.SOCKS4 -> proxyHandler = Socks4ProxyHandler(inetSocketAddress)
            ProxyType.SOCKS5 -> if (isAuth) {
                proxyHandler = Socks5ProxyHandler(inetSocketAddress, config.user, config.pwd)
            } else {
                proxyHandler = Socks5ProxyHandler(inetSocketAddress)
            }
        }
        return proxyHandler
    }
}
