package club.koumakan.nettywrap;

import java.util.function.Function;

public class Lock<T> {

  private final T t;

  public <R> R lock(Function<T, R> f) {
    synchronized (this) {
      return f.apply(t);
    }
  }

  public Lock(T t) {
    this.t = t;
  }
}
