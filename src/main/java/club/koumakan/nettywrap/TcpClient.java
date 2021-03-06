package club.koumakan.nettywrap;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.GenericFutureListener;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;

public class TcpClient {

  private final Bootstrap baseBootstrap;

  public TcpClient(Executor executor) {
    this.baseBootstrap = new Bootstrap()
      .option(ChannelOption.AUTO_READ, false)
      .group(executor.bossGroup)
      .channel(executor.channelClass);
  }

  public TcpClient(EventLoopGroup eventLoop, Class<? extends Channel> channelClass) {
    this.baseBootstrap = new Bootstrap()
      .option(ChannelOption.AUTO_READ, false)
      .group(eventLoop)
      .channel(channelClass);
  }

  public Mono<TcpStream> connect(InetSocketAddress dest) {
    final Queue<Tuple2<Integer, Consumer<ByteBuf>>> queue = new LinkedList<>();
    final TcpStream.InboundHandler inboundHandler = new TcpStream.InboundHandler(queue);
    Consumer<Channel> read = inboundHandler::read;

    return Mono.create(sink -> baseBootstrap.clone()
      .handler(inboundHandler)
      .connect(dest)
      .addListener((GenericFutureListener<ChannelFuture>) future -> {
        if (future.isSuccess()) sink.success(new TcpStream(future.channel(), queue, read));
        else sink.error(future.cause());
      })
    );
  }
}
