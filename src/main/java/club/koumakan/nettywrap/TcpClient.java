package club.koumakan.nettywrap;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
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

  public Mono<TcpStream> connect(InetSocketAddress dest) {
    final Lock<Queue<Tuple2<Integer, Consumer<ByteBuf>>>> queue = new Lock<>(new LinkedList<>());

    return Mono.create(sink -> baseBootstrap.clone()
      .handler(new TcpStream.InboundHandler(queue))
      .connect(dest)
      .addListener((GenericFutureListener<ChannelFuture>) future -> {
        if (future.isSuccess()) sink.success(new TcpStream(future.channel(), queue));
        else sink.error(future.cause());
      })
    );
  }
}
