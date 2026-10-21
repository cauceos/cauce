package dev.cauce.core.business.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.business.catalog.Catalog.SearchResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogTest {

    private static final CatalogItem BRAVAS = new CatalogItem("bravas", "Tapas", "Patatas bravas", null,
            new BigDecimal("5.50"), Map.of("allergens", List.of("huevo"), "vegetariano", true));
    private static final CatalogItem CROQUETAS = new CatalogItem("croquetas", "Tapas", "Croquetas de jamón",
            "Seis unidades", new BigDecimal("8.00"), Map.of("allergens", List.of("gluten", "leche", "huevo")));
    private static final CatalogItem TARTA = new CatalogItem("tarta-queso", "Postres", "Tarta de queso", null,
            new BigDecimal("5.00"), Map.of());
    private static final CatalogItem AGUA = new CatalogItem("agua", "Bebidas", "Agua mineral", null, null,
            Map.of("allergens", List.of()));

    private final Catalog catalog = Catalog.create(UUID.randomUUID(), List.of("allergens"),
            List.of(BRAVAS, CROQUETAS, TARTA, AGUA));

    @Test
    void search_matchesWordsIgnoringCaseAccentsAndPlural() {
        assertThat(catalog.search("CROQUETA jamon", null, null, null).matches()).containsExactly(CROQUETAS);
        assertThat(catalog.search("¿Tenéis patatas bravas?", null, null, null).matches()).containsExactly(BRAVAS);
        assertThat(catalog.search("paella", null, null, null).matches()).isEmpty();
    }

    @Test
    void search_withABlankText_matchesEveryItem_andFiltersBySection() {
        assertThat(catalog.search(" ", null, null, null).matches()).hasSize(4);
        assertThat(catalog.search(null, "postres", null, null).matches()).containsExactly(TARTA);
    }

    @Test
    void search_without_keepsOnlyItemsThatStateTheAttribute_andSetsAsideTheUndetermined() {
        SearchResult result = catalog.search(null, null, "allergens", "Gluten");

        // An empty list is a declaration ("contains none"); an absent attribute is not.
        assertThat(result.matches()).containsExactly(BRAVAS, AGUA);
        assertThat(result.undetermined()).containsExactly(TARTA);
    }

    @Test
    void statedAttribute_reportsADeclaredButAbsentAttributeAsNotDeclared() {
        assertThat(catalog.statedAttribute(TARTA, "allergens")).contains(Catalog.NOT_DECLARED);
        assertThat(catalog.statedAttribute(AGUA, "allergens")).contains(List.of());
        assertThat(catalog.statedAttribute(TARTA, "vegetariano")).isEmpty();
        assertThat(catalog.sections()).containsExactly("Tapas", "Postres", "Bebidas");
    }

    @Test
    void create_rejectsARepeatedRef_andAnInvalidAttribute() {
        assertThatThrownBy(() -> Catalog.create(UUID.randomUUID(), List.of(), List.of(BRAVAS, BRAVAS)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> new CatalogItem("x", "s", "n", null, null, Map.of("a", Map.of("b", "c"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogItem("x", "s", "n", null, new BigDecimal("-1"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacedBy_keepsIdentity() {
        Catalog replaced = catalog.replacedBy(List.of(), List.of(AGUA));

        assertThat(replaced).isEqualTo(catalog);
        assertThat(replaced.items()).containsExactly(AGUA);
        assertThat(replaced.declaredAttributes()).isEmpty();
    }
}
