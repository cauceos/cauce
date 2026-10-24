package dev.cauce.evals.report;

import java.util.Optional;

/**
 * Where a run comes from, printed before it starts: the commit, whether the tree has uncommitted
 * changes, and how far it is behind the branch it should be measuring. A worktree left on an old
 * commit measures an old prompt and old scenarios, and the history records it as if it were current.
 *
 * @param commit the full hash of {@code HEAD}, or "desconocido" without git
 * @param dirty whether the tree has uncommitted changes
 * @param upstream the branch compared with, e.g. {@code origin/main}
 * @param behind how many commits of {@code upstream} {@code HEAD} lacks; null if it could not be told
 */
public record Provenance(String commit, boolean dirty, String upstream, Integer behind) {

    /** The line printed when the run starts. */
    public String line() {
        String state = behind == null ? "sin saber si está al día con " + upstream
                : behind == 0 ? "al día con " + upstream
                : behind + (behind == 1 ? " commit" : " commits") + " por detrás de " + upstream;
        return "Commit: " + abbreviate(commit) + (dirty ? " (con cambios sin commitear)" : "") + " · " + state;
    }

    /** The warning for a run that is not measuring the current branch, if it is not. */
    public Optional<String> warning() {
        if (behind == null || behind == 0) {
            return Optional.empty();
        }
        return Optional.of("AVISO: este árbol está " + behind + (behind == 1 ? " commit" : " commits")
                + " por detrás de " + upstream + ". Mide el prompt y los escenarios de " + abbreviate(commit)
                + ", no los vigentes: actualízalo (git checkout --detach " + upstream + ") antes de una línea base.");
    }

    private static String abbreviate(String commit) {
        return commit.matches("[0-9a-f]{8,}") ? commit.substring(0, 7) : commit;
    }
}
