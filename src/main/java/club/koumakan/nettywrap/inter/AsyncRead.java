package club.koumakan.nettywrap.inter;

import io.netty.buffer.ByteBuf;
import reactor.core.publisher.Mono;

public interface AsyncRead {

  Mono<ByteBuf> read();

  Mono<ByteBuf> read(int length);

}
