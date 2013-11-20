package net.plan99.payfile.utils;

import java.util.concurrent.Callable;

/**
 * <p>Simple wrapper class that should really be in Java 8 library. Makes checked exceptions less painful. Statically
 * import it then use like this:</p>
 *
 * <code><pre>
 *    runUnchecked(() -> foo.canGoWrong());   // Any checked exceptions are wrapped in RuntimeException and rethrown.
 *    runUnchecked(foo::canGoWrong);          // Even easier.
 *    bar = evalUnchecked(() -> foo.calculate());
 * </pre></code>
 */
public class Exceptions {
    public interface ExceptionWrapper<E> {
        E wrap(Exception e);
    }

    // Why does this not exist in the SDK?
    public interface CodeThatCanThrow {
        public void run() throws Exception;
    }

    public static <T> T evalUnchecked(Callable<T> callable) throws RuntimeException {
        return evalUnchecked(callable, RuntimeException::new);
    }

    public static <T, E extends Throwable> T evalUnchecked(Callable<T> callable, ExceptionWrapper<E> wrapper) throws E {
        try {
            return callable.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw wrapper.wrap(e);
        }
    }

    public static void runUnchecked(CodeThatCanThrow runnable) throws RuntimeException {
        runUnchecked(runnable, RuntimeException::new);
    }

    public static <E extends Throwable> void runUnchecked(CodeThatCanThrow runnable, ExceptionWrapper<E> wrapper) throws E {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw wrapper.wrap(e);
        }
    }
}