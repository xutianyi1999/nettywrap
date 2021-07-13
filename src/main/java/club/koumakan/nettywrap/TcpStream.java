package club.koumakan.nettywrap;

import club.koumakan.nettywrap.inter.AsyncRead;
import club.koumakan.nettywrap.inter.AsyncWrite;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.function.Consumer;

public class TcpStream implements AsyncRead, AsyncWrite {

  public final Channel channel;
  private final Queue<Tuple2<Integer, Consumer<ByteBuf>>> queue;
  private final Consumer<Channel> innerRead;

  public TcpStream(Channel channel, Queue<Tuple2<Integer, Consumer<ByteBuf>>> queue, Consumer<Channel> innerRead) {
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

    return Mono.create(sink -> {
      final Tuple2<Integer, Consumer<ByteBuf>> tuple = Tuples.of(length, data -> {
        if (data.isReadable()) {
          sink.success(data);
        } else {
          if (length == 0) sink.success(data);
          else sink.error(new IOException("Already EOF"));
        }
      });

      Runnable task = () -> {
        if (!channel.isActive()) {
          sink.error(new IOException("Already EOF"));
          return;
        }

        queue.add(tuple);

        if (queue.size() == 1) {
          innerRead.accept(channel);
        }
      };

      final EventLoop eventLoop = channel.eventLoop();

      if (eventLoop.inEventLoop()) {
        task.run();
      } else {
        eventLoop.execute(task);
      }
    });
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

    private final Queue<Tuple2<Integer, Consumer<ByteBuf>>> queue;
    private ByteBuf cumulator;

    public InboundHandler(Queue<Tuple2<Integer, Consumer<ByteBuf>>> queue) {
      this.queue = queue;
    }

    public void read(Channel channel) {
      if (!queue.isEmpty()) {
        Tuple2<Integer, Consumer<ByteBuf>> peek = queue.peek();
        final int requireLen = peek.getT1();
        final Consumer<ByteBuf> callback = peek.getT2();

        if (
          cumulator != null &&
            cumulator.isReadable() &&
            cumulator.readableBytes() >= requireLen
        ) {
          try {
            callback.accept(cumulator.readBytes(requireLen == 0 ? cumulator.readableBytes() : requireLen));
          } finally {
            if (!cumulator.isReadable()) {
              cumulator.release();
              cumulator = null;
            }

            queue.remove();
            read(channel);
          }
        } else {
          channel.read();
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

      while (!queue.isEmpty()) {
        final Consumer<ByteBuf> callback = queue.poll().getT2();
        callback.accept(Unpooled.EMPTY_BUFFER);
      }
    }
  }
}
