package club.koumakan.nettywrap;

import club.koumakan.nettywrap.inter.AsyncRead;
import club.koumakan.nettywrap.inter.AsyncWrite;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.commons.lang3.ArrayUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

public class Test {
  final Executor executor = Executor.build();

  @org.junit.jupiter.api.Test
  void start() {
    final TcpServer tcpServer = executor.createTcpServer();

    final Flux<TcpStream> incoming = tcpServer.bind(new InetSocketAddress("0.0.0.0", 12345));
    System.out.println("Listen on: " + tcpServer.localAddress);

    incoming.subscribe(this::serve, Throwable::printStackTrace);
    tcpServer.closeable().block();
  }

  void serve(TcpStream tcpStream) {
    negotiate(tcpStream)
      .then(accept(tcpStream, tcpStream.channel.eventLoop()))
      .flatMap(destStream -> tunnel(tcpStream, destStream))
      .subscribe(f -> {
      }, Throwable::printStackTrace);
  }

  public static final byte SOCKS5_VERSION = 0x05;

  public static final byte IPV4 = 0x01;
  public static final byte DOMAIN_NAME = 0x03;
  public static final byte IPV6 = 0x04;

  static class NegotiateRequest {
    public byte version;
    public byte[] methods;

    public NegotiateRequest(byte version, byte[] methods) {
      this.version = version;
      this.methods = methods;
    }
  }

  <RW extends AsyncRead & AsyncWrite> Mono<Void> negotiate(RW stream) {
    return stream.read(2).flatMap(buff -> {
      final byte version = buff.readByte();
      final byte nmethods = buff.readByte();
      buff.release();

      return stream.read(nmethods).map(buf -> {
        byte[] methods = new byte[buf.readableBytes()];
        buf.readBytes(methods);
        buf.release();
        return new NegotiateRequest(version, methods);
      });
    }).flatMap(request -> {
      byte noAuth = 0x00;
      int noAcceptableMethods = 0xff;

      if (request.version != SOCKS5_VERSION) {
        return Mono.error(new IOException("Invalid protocol version"));
      }

      final ByteBuf resp = Unpooled.buffer(2);
      resp.writeByte(SOCKS5_VERSION);

      if (ArrayUtils.contains(request.methods, noAuth)) {
        resp.writeByte(noAuth);
        return stream.write(resp);
      } else {
        resp.writeByte(noAcceptableMethods);
        return stream.write(resp).then(Mono.error(new IOException("No acceptable methods")));
      }
    });
  }

  <RW extends AsyncRead & AsyncWrite> Mono<TcpStream> accept(RW stream, EventLoopGroup ctx) {
    return stream.read(4).flatMap(buff -> {
      final byte version = buff.readByte();
      final byte cmd = buff.readByte();
      buff.readByte();
      final byte addrType = buff.readByte();

      buff.release();

      Mono<Optional<AcceptRequest>> mono;

      if (addrType == IPV4) {
        mono = stream.read(4 + 2).map(buf -> {
          byte[] bindAddr = new byte[4];
          buf.readBytes(bindAddr);
          final int bindPort = buf.readUnsignedShort();

          buf.release();
          return Optional.of(new AcceptRequest(version, cmd, addrType, bindAddr, bindPort));
        });
      } else if (addrType == IPV6) {
        mono = stream.read(16 + 2).map(buf -> {
          final byte[] bindAddr = new byte[16];
          buf.readBytes(bindAddr);
          final int bindPort = buf.readUnsignedShort();

          buf.release();
          return Optional.of(new AcceptRequest(version, cmd, addrType, bindAddr, bindPort));
        });
      } else if (addrType == DOMAIN_NAME) {
        mono = stream.read(1).flatMap(lenBuf -> {
          final int len = lenBuf.readUnsignedByte();
          lenBuf.release();

          return stream.read(len + 2).map(buf -> {
            byte[] domainName = new byte[len];
            buf.readBytes(domainName);
            final int bindPort = buf.readUnsignedShort();

            buf.release();
            return Optional.of(new AcceptRequest(version, cmd, addrType, domainName, bindPort));
          });
        });
      } else {
        mono = Mono.just(Optional.empty());
      }
      return mono;
    }).flatMap(req -> {
      if (req.isPresent()) {
        final AcceptRequest acceptRequest = req.get();

        if (acceptRequest.version != SOCKS5_VERSION) {
          return Mono.error(new IOException("Invalid protocol version"));
        }

        byte tcp = 0x01;

        if (acceptRequest.cmd == tcp) {
          Mono<TcpStream> connection;

          final TcpClient client = new TcpClient(ctx, NioSocketChannel.class);

          if (acceptRequest.addrType == IPV4 || acceptRequest.addrType == IPV6) {
            final InetSocketAddress destAddr;

            try {
              destAddr = new InetSocketAddress(InetAddress.getByAddress(acceptRequest.bindAddr), acceptRequest.bindPort);
            } catch (Exception e) {
              return Mono.error(e);
            }
            connection = client.connect(destAddr);
          } else {
            connection = client.connect(new InetSocketAddress(new String(acceptRequest.bindAddr, StandardCharsets.UTF_8), acceptRequest.bindPort));
          }

          return connection.flatMap(destStream -> {
            final ByteBuf resp = Unpooled.buffer(64);
            byte success = 0x00;

            resp.writeByte(SOCKS5_VERSION)
              .writeByte(success)
              .writeByte(0x00);

            InetSocketAddress localSocketAddress = (InetSocketAddress) destStream.localAddress();
            final InetAddress localAddress = localSocketAddress.getAddress();

            if (localAddress instanceof Inet4Address) {
              resp.writeByte(IPV4);
            } else {
              resp.writeByte(IPV6);
            }

            resp.writeBytes(localAddress.getAddress())
              .writeShort(localSocketAddress.getPort());

            return stream.write(resp).thenReturn(destStream);
          });
        } else {
          byte cmdNotSupported = 0x07;
          stream.write(buildErrResp(cmdNotSupported));
          return Mono.error(new IOException("Cmd not supported"));
        }
      } else {
        byte addressTypeNotSupported = 0x08;
        stream.write(buildErrResp(addressTypeNotSupported));
        return Mono.error(new IOException("Address type not supported"));
      }
    });
  }

  static class AcceptRequest {
    byte version;
    byte cmd;
    byte addrType;
    byte[] bindAddr;
    int bindPort;

    public AcceptRequest(byte version, byte cmd, byte addrType, byte[] bindAddr, int bindPort) {
      this.version = version;
      this.cmd = cmd;
      this.addrType = addrType;
      this.bindAddr = bindAddr;
      this.bindPort = bindPort;
    }
  }

  Mono<Void> tunnel(TcpStream sourceStream, TcpStream destStream) {
    return Mono.create(sink -> {
      final Consumer<Tuple2<TcpStream, TcpStream>> yf = YFact.yConsumer(f -> tuple -> {
          final TcpStream source = tuple.getT1();
          final TcpStream dest = tuple.getT2();

          source.read().subscribe(data -> {
            if (data.isReadable()) {
              dest.write(data)
                .hasElement()
                .subscribe(_nil -> f.accept(tuple), err -> {
                  source.close();
                  sink.error(err);
                });
            } else {
              dest.close();
              sink.success();
            }
          }, sink::error);
        }
      );

      final Tuple2<TcpStream, TcpStream> t1 = Tuples.of(sourceStream, destStream);
      final Tuple2<TcpStream, TcpStream> t2 = Tuples.of(destStream, sourceStream);

      yf.accept(t1);
      yf.accept(t2);
    });
  }

  static ByteBuf buildErrResp(byte rep) {
    final ByteBuf resp = Unpooled.buffer(16);
    resp
      .writeByte(SOCKS5_VERSION)
      .writeByte(rep)
      .writeByte(0x00)
      .writeByte(IPV4)
      .writeBytes(new byte[]{0, 0, 0, 0})
      .writeShort(0);
    return resp;
  }
}
