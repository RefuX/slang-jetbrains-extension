package slanglsp.multiplexer.utils;

import com.intellij.openapi.diagnostic.Logger;

public class ThreadUtils {
    private static final Logger LOG = Logger.getInstance(ThreadUtils.class);

    private ThreadUtils() {
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void startDaemonThread(String name, ThrowingRunnable task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted())
                    LOG.error(e);
            }
        }, name);

        thread.setDaemon(true);
        thread.start();
    }
}