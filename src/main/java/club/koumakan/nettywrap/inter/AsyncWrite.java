package club.koumakan.nettywrap.inter;

import io.netty.buffer.ByteBuf;
import reactor.core.publisher.Mono;

public interface AsyncWrite {

  Mono<Void> write(ByteBuf buf);
}
