package slanglsp.utils;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.Optional;

public final class NotificationUtil {
    private static final Logger LOG = Logger.getInstance(NotificationUtil.class);

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static <T> Optional<T> runOrNotify(
            Project project,
            String errorMessage,
            ThrowingSupplier<T> operation
    ) {
        try {
            return Optional.ofNullable(operation.get());
        } catch (Exception e) {
            notifyUser(project, errorMessage, NotificationType.ERROR);
            LOG.warn(errorMessage, e);
            return Optional.empty();
        }
    }

    public static boolean runOrNotify(
            Project project,
            String errorMessage,
            ThrowingRunnable operation
    ) {
        try {
            operation.run();
            return true;
        } catch (Exception e) {
            notifyUser(project, errorMessage, NotificationType.ERROR);
            LOG.warn(errorMessage, e);
            return false;
        }
    }

    public static void notifyUser(
            Project project,
            String message,
            NotificationType type
    ) {
        switch (type) {
            case ERROR -> LOG.error(message);
            case WARNING -> LOG.warn(message);
            case INFORMATION -> LOG.info(message);
            default -> LOG.debug(message);
        }

        NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                "Slang LSP",
                message,
                type
        ).notify(project);
    }
}