package dev.cauce.business.owner;

import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.usage.SpendAlertNotifier.SpendAlert;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * The fixed texts sent to an owner, in the business's language (its profile locale): Spanish, or
 * English for any other language until more are written. Platform wording, not sector wording:
 * they speak of cases and contacts, never of tables or dishes. Composed by code, never by a model
 * (ADR 0007, B10).
 */
public final class OwnerMessages {

    private OwnerMessages() {
    }

    /** Whether {@code locale} reads the Spanish texts. */
    public static boolean spanish(String locale) {
        return locale == null || Locale.forLanguageTag(locale).getLanguage().equals("es");
    }

    /**
     * The notice of a new handoff: who, the agent's summary, and — apart, labelled and quoted — the
     * person's own words when the agent quoted them, so the owner never mistakes one for the other.
     */
    public static String handoff(String locale, Handoff handoff) {
        boolean es = spanish(locale);
        String phone = handoff.contactPhone().map(p -> " · " + p).orElse("");
        String words = handoff.customerMessage()
                .map(quote -> es
                        ? "\n\nMensaje del cliente: «" + quote + "»"
                        : "\n\nCustomer's message: “" + quote + "”")
                .orElse("");
        return (es ? "Nuevo caso para ti [" : "New case for you [") + handoff.category() + "] · ref " + handoff.code()
                + "\n" + handoff.contactName() + phone
                + "\n\n" + (es ? "Resumen: " : "Summary: ") + handoff.summary()
                + words;
    }

    /** The reply to an owner who just linked this chat to {@code businessName}. */
    public static String linked(String locale, String businessName) {
        return spanish(locale)
                ? "Listo: este chat recibirá los avisos de " + businessName + "."
                : "Done: this chat will receive the notices of " + businessName + ".";
    }

    /** The reply to any other message on the owner bot from a chat that is not the linked owner. */
    public static String sendCode(String locale, String businessName) {
        return spanish(locale)
                ? "Este es el canal del dueño de " + businessName + ". Para vincular este chat, envía aquí el "
                        + "código de vinculación."
                : "This is the owner channel of " + businessName + ". To link this chat, send the link code "
                        + "here.";
    }

    /** The reply to a code that matches no link code of this bot, expired, or was used by someone else. */
    public static String codeNotValid(String locale) {
        return spanish(locale)
                ? "Ese código de vinculación no es válido, ha caducado o ya se usó. Pide uno nuevo."
                : "That link code is not valid, has expired or was already used. Ask for a new one.";
    }

    /**
     * The notice that the business reached 80 % of its monthly spend limit (ADR 0010, S6): what it
     * spent, of what, and what happens at the limit. The month is a calendar month in UTC.
     */
    public static String spendWarning(String locale, SpendAlert alert) {
        boolean es = spanish(locale);
        return es
                ? "Aviso de gasto: en " + alert.period() + " llevas " + usd(es, alert.spentUsd()) + " de tu límite de "
                        + usd(es, alert.monthlyLimitUsd()) + ". Al llegar al límite, tu asistente responderá a los "
                        + "clientes con su respuesta de respaldo hasta el mes que viene. Para ampliarlo, habla con "
                        + "quien te da el servicio."
                : "Spending notice: in " + alert.period() + " you have spent " + usd(es, alert.spentUsd())
                        + " of your " + usd(es, alert.monthlyLimitUsd()) + " limit. At the limit, your assistant "
                        + "will answer customers with its fallback reply until next month. To raise it, talk to "
                        + "whoever provides your service.";
    }

    /** The notice that the business reached its monthly spend limit: customers now get the fallback reply. */
    public static String spendLimitReached(String locale, SpendAlert alert) {
        boolean es = spanish(locale);
        return es
                ? "Límite de gasto alcanzado: en " + alert.period() + " se han gastado " + usd(es, alert.spentUsd())
                        + " de un límite de " + usd(es, alert.monthlyLimitUsd()) + ". Hasta el mes que viene, o "
                        + "hasta que se amplíe el límite, tu asistente responde a los clientes con su respuesta de "
                        + "respaldo. Para ampliarlo, habla con quien te da el servicio."
                : "Spending limit reached: in " + alert.period() + " " + usd(es, alert.spentUsd())
                        + " has been spent of a " + usd(es, alert.monthlyLimitUsd()) + " limit. Until next month, "
                        + "or until the limit is raised, your assistant answers customers with its fallback reply. "
                        + "To raise it, talk to whoever provides your service.";
    }

    private static String usd(boolean es, BigDecimal amount) {
        return String.format(es ? Locale.forLanguageTag("es") : Locale.ROOT, "%.2f US$", amount);
    }
}
