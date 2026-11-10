package dev.cauce.core.business.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContactTest {

    @Test
    void aWholePhone_isKeptAsWritten_andAnIncompleteOne_isRefusedSayingToAsk() {
        assertThat(Contact.phone(" 600 123 456 ")).isEqualTo(new Contact(Contact.Type.PHONE, "600 123 456"));
        assertThat(Contact.phone("+34 600-123-456").value()).isEqualTo("+34 600-123-456");
        assertThat(Contact.phone("(+44) 20 7946 0958").type()).isEqualTo(Contact.Type.PHONE);

        assertThatThrownBy(() -> Contact.phone("612 345 67"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("8 digits")
                .hasMessageContaining("ask").hasMessageContaining("never complete");
        assertThatThrownBy(() -> Contact.phone("600 123 456 789 0123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("16 digits");
        assertThatThrownBy(() -> Contact.phone("seis cero cero"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a phone");
    }

    @Test
    void aWholeEmail_isKept_andAnIncompleteOne_isRefusedSayingToAsk() {
        assertThat(Contact.email("ana@barlaesquina.es")).isEqualTo(new Contact(Contact.Type.EMAIL, "ana@barlaesquina.es"));
        assertThat(Contact.email("ana.lopez+bar@mail.example.com").type()).isEqualTo(Contact.Type.EMAIL);

        for (String bad : new String[] {"ana@", "ana@barlaesquina", "ana barlaesquina.es", "@barlaesquina.es", "ana@@x.es"}) {
            assertThatThrownBy(() -> Contact.email(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never complete");
        }
    }

    @Test
    void aStoredContact_readsBackWithoutTheFormatChecks() {
        assertThat(new Contact(Contact.Type.PHONE, "600 12").value()).isEqualTo("600 12");
        assertThatThrownBy(() -> new Contact(Contact.Type.EMAIL, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
