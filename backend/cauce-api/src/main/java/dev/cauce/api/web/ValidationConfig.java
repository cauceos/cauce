package dev.cauce.api.web;

import jakarta.validation.MessageInterpolator;
import java.util.Locale;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Pins Bean Validation messages to English so the API error contract is deterministic and does
 * not drift with the server's JVM/OS locale. The default interpolator resolves messages against
 * {@code Locale.getDefault()}, which would make {@code message} text vary per host; here every
 * interpolation is forced to {@link Locale#ENGLISH}, aligning the wire contract with the rest of
 * the codebase (README, code, commits — all English).
 */
@Configuration(proxyBeanMethods = false)
public class ValidationConfig {

    @Bean
    public LocalValidatorFactoryBean defaultValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new FixedLocaleMessageInterpolator(Locale.ENGLISH));
        return validator;
    }

    /** Delegates to the standard interpolator but always with a fixed locale. */
    private static final class FixedLocaleMessageInterpolator implements MessageInterpolator {

        private final MessageInterpolator delegate = new ResourceBundleMessageInterpolator();
        private final Locale locale;

        FixedLocaleMessageInterpolator(Locale locale) {
            this.locale = locale;
        }

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return delegate.interpolate(messageTemplate, context, locale);
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale requestedLocale) {
            return delegate.interpolate(messageTemplate, context, locale);
        }
    }
}
