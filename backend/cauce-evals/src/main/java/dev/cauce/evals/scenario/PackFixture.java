package dev.cauce.evals.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A sector pack's reference business, a directory of the pack a scenario names with {@code datos}
 * (ADR 0009; ADR 0014, WC7): its sheet ({@code ficha.json}), its catalog ({@code catalogo.json}, or
 * {@code carta.json} where the catalog is a menu) and, for a business that takes bookings, its agenda
 * ({@code agenda.json}). The harness assumes no sector: what a fixture has decides what is loaded.
 */
public record PackFixture(Path dir) {

    public static final String SHEET = "ficha.json";
    static final List<String> CATALOGS = List.of("catalogo.json", "carta.json");
    static final String AGENDA = "agenda.json";

    /** The fixture {@code name} of the pack at {@code packDir}. */
    public static PackFixture of(Path packDir, String name) {
        return new PackFixture(packDir.resolve(name));
    }

    public Path sheet() {
        return dir.resolve(SHEET);
    }

    /** The catalog file, whichever of its names the fixture uses. */
    public Path catalog() {
        return CATALOGS.stream().map(dir::resolve).filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IllegalStateException("Fixture " + dir + " has no catalog (" + CATALOGS + ")"));
    }

    /** The agenda, for a business that takes bookings. */
    public Optional<Path> agenda() {
        return Optional.of(dir.resolve(AGENDA)).filter(Files::isRegularFile);
    }

    /** Every fixture of the pack: its directories with a sheet, in name order. */
    public static List<PackFixture> allOf(Path packDir) {
        try (Stream<Path> dirs = Files.list(packDir)) {
            return dirs.filter(d -> Files.isRegularFile(d.resolve(SHEET))).sorted().map(PackFixture::new).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
