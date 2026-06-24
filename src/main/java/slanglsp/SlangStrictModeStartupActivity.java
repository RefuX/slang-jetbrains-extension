package slanglsp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/**
 * Brings the static "slanglsp.SlangLanguageServer" definition's enabled state in line
 * with the persisted strict-per-module-isolation setting as early as possible in a
 * project's lifecycle.
 * <p>
 * This has to happen before any {@code .slang} file could cause lsp4ij to lazily start
 * that definition's wrapper — see {@link SlangLanguageServerFactory#syncWithStrictModeSetting}
 * for why doing it reactively (only inside {@code createConnectionProvider}) is too
 * late once strict mode is already the persisted setting when a project opens.
 * <p>
 * {@code ProjectActivity} is declared in Kotlin as {@code suspend fun execute(project:
 * Project)}, which on the JVM compiles to {@code Object execute(Project,
 * Continuation<? super Unit>)}. This implementation never actually suspends — the work
 * is plain synchronous Java — so it just does the work and returns {@code Unit.INSTANCE}
 * directly, ignoring the continuation. This is the standard way to implement a
 * non-suspending {@code ProjectActivity} from Java without depending on Kotlin
 * coroutine machinery beyond the stdlib types in the method signature itself.
 */
final class SlangStrictModeStartupActivity implements ProjectActivity {
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        SlangLanguageServerFactory.syncWithStrictModeSetting(project);
        return Unit.INSTANCE;
    }
}
