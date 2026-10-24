package dev.cauce.evals.run;

import dev.cauce.evals.report.Provenance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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

    /** Reads it and prints where the run comes from; what to do about it is the runner's. */
    static Provenance announce(Path repoRoot) {
        Provenance provenance = of(repoRoot);
        System.out.println(provenance.line());
        return provenance;
    }

    static Provenance of(Path repoRoot) {
        String commit;
        List<String> changed;
        try {
            commit = command(repoRoot, "git", "rev-parse", "HEAD").strip();
            // -uall: an untracked file by its path, never only its directory.
            changed = command(repoRoot, "git", "status", "--porcelain", "-uall").lines()
                    .filter(line -> line.length() > 3)
                    .map(GitState::path)
                    .toList();
        } catch (Exception e) {
            return new Provenance("desconocido", List.of(), UPSTREAM, null);
        }
        return new Provenance(commit, changed, UPSTREAM, behind(repoRoot));
    }

    /** The path of a {@code --porcelain} line ("XY path", or "XY old -> new" for a rename). */
    private static String path(String line) {
        String path = line.substring(3);
        int renamed = path.indexOf(" -> ");
        path = renamed >= 0 ? path.substring(renamed + 4) : path;
        return path.startsWith("\"") && path.endsWith("\"") ? path.substring(1, path.length() - 1) : path;
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
