package dev.cauce.evals.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PackFixtureTest {

    private static final Path EXAMPLES = Path.of("..", "..", "examples");

    @Test
    void theBar_hasAMenuAndAnAgenda_andCauceWebs_aCatalogAndNoAgenda() {
        PackFixture bar = PackFixture.of(EXAMPLES.resolve("hosteleria"), "bar-ejemplo");
        PackFixture shop = PackFixture.of(EXAMPLES.resolve("caucewebs"), "caucewebs");

        assertThat(bar.catalog().getFileName()).hasToString("carta.json");
        assertThat(bar.agenda()).isPresent();
        assertThat(shop.catalog().getFileName()).hasToString("catalogo.json");
        assertThat(shop.agenda()).isEmpty();
        assertThat(PackFixture.allOf(EXAMPLES.resolve("caucewebs"))).containsExactly(shop);
    }
}
