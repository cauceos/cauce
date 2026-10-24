package dev.cauce.evals.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpokenTextTest {

    @Test
    void tokens_dropCaseAccentsAndPunctuation() {
        assertThat(SpokenText.tokens("¡Hola! ¿Tenéis mesa, Ana López?"))
                .containsExactly("hola", "teneis", "mesa", "ana", "lopez");
    }

    @Test
    void tokens_aPhoneSpeltDigitByDigit_andInFigures_agree() {
        assertThat(SpokenText.tokens("seis uno dos, tres cuatro cinco"))
                .isEqualTo(SpokenText.tokens("612-345"))
                .containsExactly("6", "1", "2", "3", "4", "5");
    }

    @Test
    void tokens_spelledNumbers_areTheirDigits() {
        assertThat(SpokenText.tokens("para cuatro")).isEqualTo(SpokenText.tokens("para 4"));
        assertThat(SpokenText.tokens("veintiuno")).containsExactly("2", "1");
        assertThat(SpokenText.tokens("treinta y cinco")).containsExactly("3", "5");
        assertThat(SpokenText.tokens("trescientos cuarenta y cinco")).containsExactly("3", "4", "5");
        assertThat(SpokenText.tokens("dos mil veintiséis")).containsExactly("2", "0", "2", "6");
        assertThat(SpokenText.tokens("doce personas")).containsExactly("1", "2", "personas");
    }

    @Test
    void tokens_aYThatJoinsNoNumber_staysAWord() {
        assertThat(SpokenText.tokens("pan y vino")).containsExactly("pan", "y", "vino");
        assertThat(SpokenText.tokens("cuatro y media")).containsExactly("4", "y", "media");
    }
}
