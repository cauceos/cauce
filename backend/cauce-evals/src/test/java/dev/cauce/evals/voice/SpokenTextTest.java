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
        assertThat(SpokenText.tokens("dos y cinco niños")).containsExactly("2", "y", "5", "ninos");
    }

    @Test
    void tokens_aClockTime_saidOrInFigures_isOneTokenOnTheTwelveHourDial() {
        assertThat(SpokenText.tokens("a las dos y media")).containsExactly("a", "las", "2h30");
        assertThat(SpokenText.tokens("a las 2:30")).isEqualTo(SpokenText.tokens("a las dos y media"));
        assertThat(SpokenText.tokens("a las 14:30")).isEqualTo(SpokenText.tokens("a las dos y media"));
        assertThat(SpokenText.tokens("a las 14.30 h")).containsExactly("a", "las", "2h30", "h");
        assertThat(SpokenText.tokens("las nueve menos cuarto")).containsExactly("las", "8h45");
        assertThat(SpokenText.tokens("la una menos cuarto")).containsExactly("la", "12h45");
        assertThat(SpokenText.tokens("las nueve y cuarto")).containsExactly("las", "9h15");
        assertThat(SpokenText.tokens("las nueve y diez")).isEqualTo(SpokenText.tokens("las 21:10"));
        assertThat(SpokenText.tokens("las nueve menos diez")).containsExactly("las", "8h50");
        assertThat(SpokenText.tokens("la una en punto")).isEqualTo(SpokenText.tokens("la 1:00"));
        assertThat(SpokenText.tokens("cuatro y media")).containsExactly("4h30");
    }

    @Test
    void tokens_aFigureThatIsNoTime_staysItsDigits() {
        assertThat(SpokenText.tokens("612.345.678")).containsExactly("6", "1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(SpokenText.tokens("cuesta 2,50")).containsExactly("cuesta", "2", "5", "0");
        assertThat(SpokenText.tokens("las 25:30")).containsExactly("las", "2", "5", "3", "0");
    }
}
