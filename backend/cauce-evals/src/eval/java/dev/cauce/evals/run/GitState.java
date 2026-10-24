package dev.cauce.evals.run;

import dev.cauce.evals.report.Provenance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * The tree a run comes from, read with git: its commit, whether it has uncommitted changes, and how
 * far behind {@value #UPSTREAM} it is, after fetching it (a worktree shares the main clone's refs,
 * which can be stale). Never fails a run: without git, or offline, what cannot be told is unknown.
 */
final class GitState {

    static final String UPSTREAM = "origin/main";

    private static final long FETCH_TIMEOUT_SECONDS = 20;

    private GitState() {
    }

    /** Reads it, and prints where the run comes from and, if it is behind, the warning. */
    static Provenance announce(Path repoRoot) {
        Provenance provenance = of(repoRoot);
        System.out.println(provenance.line());
        provenance.warning().ifPresent(warning -> System.out.printf("%n%s%n%n", warning));
        return provenance;
    }

    static Provenance of(Path repoRoot) {
        String commit;
        boolean dirty;
        try {
            commit = command(repoRoot, "git", "rev-parse", "HEAD").strip();
            dirty = !command(repoRoot, "git", "status", "--porcelain").isBlank();
        } catch (Exception e) {
            return new Provenance("desconocido", false, UPSTREAM, null);
        }
        return new Provenance(commit, dirty, UPSTREAM, behind(repoRoot));
    }

    private static Integer behind(Path repoRoot) {
        try {
            Process fetch = new ProcessBuilder("git", "fetch", "--quiet", "origin", "main")
                    .directory(repoRoot.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!fetch.waitFor(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fetch.destroyForcibly(); // offline: compare with the last fetched refs
            }
            return Integer.parseInt(command(repoRoot, "git", "rev-list", "--count", "HEAD.." + UPSTREAM).strip());
        } catch (Exception e) {
            return null;
        }
    }

    private static String command(Path repoRoot, String... args) throws Exception {
        Process process = new ProcessBuilder(args).directory(repoRoot.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", args) + " failed: " + output.strip());
        }
        return output;
    }
}
