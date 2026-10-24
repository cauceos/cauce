package dev.cauce.evals.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a run comes from, printed before it starts: the commit, the uncommitted changes, and how
 * far it is behind the branch it should be measuring. A worktree left on an old commit measures an
 * old prompt and old scenarios, and the history records it as if it were current, so a full battery
 * is refused from such a tree ({@link #problem}); a subset only warns.
 *
 * @param commit the full hash of {@code HEAD}, or "desconocido" without git
 * @param changed the paths with uncommitted changes, relative to the repository root, as git
 *        writes them ({@code /}-separated); untracked files included
 * @param upstream the branch compared with, e.g. {@code origin/main}
 * @param behind how many commits of {@code upstream} {@code HEAD} lacks; null if it could not be told
 */
public record Provenance(String commit, List<String> changed, String upstream, Integer behind) {

    public Provenance {
        changed = List.copyOf(changed);
    }

    /** Whether the tree has uncommitted changes, anywhere. */
    public boolean dirty() {
        return !changed.isEmpty();
    }

    /** The line printed when the run starts. */
    public String line() {
        String state = behind == null ? "sin saber si está al día con " + upstream
                : behind == 0 ? "al día con " + upstream
                : commits(behind) + " por detrás de " + upstream;
        return "Commit: " + abbreviate(commit) + (dirty() ? " (con cambios sin commitear)" : "") + " · " + state;
    }

    /** The warning for a run that is not measuring the current branch, if it is not. */
    public Optional<String> warning() {
        if (behind == null || behind == 0) {
            return Optional.empty();
        }
        return Optional.of("AVISO: este árbol está " + commits(behind) + " por detrás de " + upstream
                + ". Mide el prompt y los escenarios de " + abbreviate(commit)
                + ", no los vigentes: actualízalo (git checkout --detach " + upstream + ") antes de una línea base.");
    }

    /**
     * Why this tree does not measure the current agent of the pack at {@code packPath} (e.g.
     * {@code examples/hosteleria/}): behind {@code upstream}, or with uncommitted changes to the
     * pack — its prompts, tools, fallback, fixture or scenarios. Its history ({@code evals/historial/})
     * is not the agent and does not count.
     */
    public Optional<String> problem(String packPath) {
        String pack = packPath.endsWith("/") ? packPath : packPath + "/";
        List<String> reasons = new ArrayList<>();
        if (behind != null && behind > 0) {
            reasons.add("va " + commits(behind) + " por detrás de " + upstream);
        }
        List<String> agentChanges = changed.stream()
                .filter(path -> path.startsWith(pack) && !path.startsWith(pack + "evals/historial/"))
                .toList();
        if (!agentChanges.isEmpty()) {
            reasons.add("tiene cambios sin commitear en el paquete: " + String.join(", ", agentChanges));
        }
        return reasons.isEmpty() ? Optional.empty()
                : Optional.of("El árbol (" + abbreviate(commit) + ") " + String.join(" y ", reasons) + ".");
    }

    private static String commits(int count) {
        return count + (count == 1 ? " commit" : " commits");
    }

    private static String abbreviate(String commit) {
        return commit.matches("[0-9a-f]{8,}") ? commit.substring(0, 7) : commit;
    }
}
