package club.koumakan.nettywrap;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.GenericFutureListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;

public class TcpServer {

  public InetSocketAddress localAddress;
  private final ServerBootstrap serverBootstrap;
  private Channel server;

  public TcpServer(Executor executor) {
    serverBootstrap = new ServerBootstrap()
      .childOption(ChannelOption.AUTO_READ, false)
      .group(executor.bossGroup, executor.workGroup)
      .channel(executor.serverChannelClass);
  }

  public Flux<TcpStream> bind(InetSocketAddress address) {
    localAddress = address;

    return Flux.create(sink -> {
      serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel ch) {
          final Lock<Queue<Tuple2<Integer, Consumer<ByteBuf>>>> queue = new Lock<>(new LinkedList<>());

          ch.pipeline()
            .addLast(new ChannelInboundHandlerAdapter() {
              @Override
              public void channelActive(ChannelHandlerContext ctx) {
                sink.next(new TcpStream(ctx.channel(), queue));
                ctx.fireChannelActive();
              }
            }).addLast(new TcpStream.InboundHandler(queue));
        }
      });

      serverBootstrap.bind(address).addListener((GenericFutureListener<ChannelFuture>) future -> {
        if (future.isSuccess()) {
          server = future.channel();
          server.closeFuture().addListener(f -> sink.complete());
        } else sink.error(future.cause());
      });
    });
  }

  public void close() {
    if (server != null) {
      server.close();
    }
  }

  public Mono<Void> closeable() {
    return Mono.create(sink -> server.closeFuture().addListener(f -> sink.success()));
  }
}
