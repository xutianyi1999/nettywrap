package club.koumakan.nettywrap;

import club.koumakan.nettywrap.inter.AsyncRead;
import club.koumakan.nettywrap.inter.AsyncWrite;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.function.Consumer;

public class TcpStream implements AsyncRead, AsyncWrite {

  private final Channel channel;
  private final Lock<Queue<Tuple2<Integer, Consumer<ByteBuf>>>> queue;
  private final Consumer<Channel> innerRead;

  public TcpStream(Channel channel, Lock<Queue<Tuple2<Integer, Consumer<ByteBuf>>>> queue, Consumer<Channel> innerRead) {
    this.channel = channel;
    this.queue = queue;
    this.innerRead = innerRead;
  }

  @Override
  public Mono<ByteBuf> read() {
    return read(0);
  }

  @Override
  public Mono<ByteBuf> read(int length) {
    if (length < 0) {
      return Mono.error(new IOException("Length must be greater than 0"));
    }

    if (!channel.isActive()) {
      return Mono.error(new IOException("Channel is closed"));
    }

    return Mono.create(sink -> queue.lock(queue -> {
      queue.add(Tuples.of(length, data -> {
        if (data.isReadable()) {
          sink.success(data);
        } else {
          if (length == 0) sink.success(data);
          else sink.error(new IOException("Already EOF"));
        }
      }));

      if (queue.size() == 1) {
        innerRead.accept(channel);
      }
      return null;
    }));
  }

  public Mono<Void> closeable() {
    return Mono.create(sink -> channel.closeFuture().addListener(future -> sink.success()));
  }

  @Override
  public Mono<Void> write(ByteBuf buf) {
    return Mono.create(sink -> channel.writeAndFlush(buf).addListener(future -> {
      if (future.isSuccess()) sink.success();
      else sink.error(future.cause());
    }));
  }

  public void close() {
    channel.close();
  }

  public SocketAddress localAddress() {
    return channel.localAddress();
  }

  public SocketAddress remoteAddress() {
    return channel.remoteAddress();
  }

  public static class InboundHandler extends ChannelInboundHandlerAdapter {

    private final Lock<Queue<Tuple2<Integer, Consumer<ByteBuf>>>> queue;
    private ByteBuf cumulator;

    public InboundHandler(Lock<Queue<Tuple2<Integer, Consumer<ByteBuf>>>> queue) {
      this.queue = queue;
    }

    public void read(Channel channel) {
      try {
        queue.lock(queue -> {
          final Consumer<Void> yf = YFact.yConsumer(f -> nil -> {
            if (!queue.isEmpty()) {
              Tuple2<Integer, Consumer<ByteBuf>> peek = queue.peek();
              final int requireLen = peek.getT1();
              final Consumer<ByteBuf> callback = peek.getT2();

              if (
                cumulator != null &&
                  cumulator.isReadable() &&
                  cumulator.readableBytes() >= requireLen
              ) {
                callback.accept(cumulator.readBytes(requireLen == 0 ? cumulator.readableBytes() : requireLen));
                queue.remove();
                f.accept(null);
              } else {
                channel.read();
              }
            }
          });

          yf.accept(null);
          return null;
        });
      } finally {
        if (cumulator != null && !cumulator.isReadable()) {
          cumulator.release();
          cumulator = null;
        }
      }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      ByteBuf data = (ByteBuf) msg;

      try {
        if (cumulator == null) {
          cumulator = ctx.alloc().buffer();
        }

        cumulator.writeBytes(data);
        read(ctx.channel());
      } finally {
        data.release();
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      if (cumulator != null) {
        cumulator.release();
      }

      queue.lock(queue -> {
        while (!queue.isEmpty()) {
          final Consumer<ByteBuf> callback = queue.poll().getT2();
          callback.accept(Unpooled.EMPTY_BUFFER);
        }
        return null;
      });
    }
  }
}
