package club.koumakan.nettywrap;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class Executor {

  public final EventLoopGroup bossGroup;
  public final EventLoopGroup workGroup;
  public final Class<? extends Channel> channelClass;
  public final Class<? extends ServerChannel> serverChannelClass;

  public Executor(EventLoopGroup bossGroup, EventLoopGroup workGroup, Class<? extends Channel> channelClass, Class<? extends ServerChannel> serverChannelClass) {
    this.bossGroup = bossGroup;
    this.workGroup = workGroup;
    this.channelClass = channelClass;
    this.serverChannelClass = serverChannelClass;
  }

  public TcpClient createTcpClient() {
    return new TcpClient(this);
  }

  public TcpServer createTcpServer() {
    return new TcpServer(this);
  }

  public static Executor build() {
    return new Executor(
      new NioEventLoopGroup(1),
      new NioEventLoopGroup(),
      NioSocketChannel.class,
      NioServerSocketChannel.class
    );
  }
}
