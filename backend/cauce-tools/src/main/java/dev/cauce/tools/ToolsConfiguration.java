package dev.cauce.tools;

import dev.cauce.core.business.booking.BookingProvider;
import dev.cauce.core.business.catalog.CatalogSource;
import dev.cauce.core.business.gap.KnowledgeGapSink;
import dev.cauce.core.business.handoff.HandoffSink;
import dev.cauce.core.business.profile.BusinessProfileSource;
import dev.cauce.tools.business.BookingTools;
import dev.cauce.tools.business.EscalateToOwnerTool;
import dev.cauce.tools.business.GetBusinessInfoTool;
import dev.cauce.tools.business.RecordUnansweredQuestionTool;
import dev.cauce.tools.business.SearchCatalogTool;
import dev.cauce.tools.clock.ClockTool;
import dev.cauce.tools.spi.Tool;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the built-in tools as Spring beans. Discovered by the application's
 * {@code dev.cauce} component scan; the {@link dev.cauce.tools.spi.ToolRegistry} then collects
 * them. Adding another built-in tool is a new {@code @Bean} method here.
 *
 * <p>The business tools (ADR 0007) reach their capability through a core port. The port is
 * looked up when the tool runs, so an application without the native adapters (a module test
 * context) still starts: the tool then answers that the capability is not available.
 */
@Configuration
public class ToolsConfiguration {

    /**
     * The built-in clock tool, driven by the application's clock — the database's (ADR 0008), so the
     * agent's "today" is the one bookings are validated against; local time when the business has a
     * profile.
     */
    @Bean
    public ClockTool clockTool(ObjectProvider<BusinessProfileSource> profiles, Clock clock) {
        return new ClockTool(clock, profiles::getIfAvailable);
    }

    @Bean
    public GetBusinessInfoTool getBusinessInfoTool(ObjectProvider<BusinessProfileSource> profiles) {
        return new GetBusinessInfoTool(profiles::getIfAvailable);
    }

    @Bean
    public SearchCatalogTool searchCatalogTool(ObjectProvider<CatalogSource> catalogs) {
        return new SearchCatalogTool(catalogs::getIfAvailable);
    }

    @Bean
    public RecordUnansweredQuestionTool recordUnansweredQuestionTool(ObjectProvider<KnowledgeGapSink> sinks) {
        return new RecordUnansweredQuestionTool(sinks::getIfAvailable);
    }

    @Bean
    public Tool checkAvailabilityTool(ObjectProvider<BookingProvider> bookings) {
        return BookingTools.checkAvailability(bookings::getIfAvailable);
    }

    @Bean
    public Tool proposeBookingTool(ObjectProvider<BookingProvider> bookings, Clock clock) {
        return BookingTools.proposeBooking(bookings::getIfAvailable, clock);
    }

    @Bean
    public Tool proposeBookingChangeTool(ObjectProvider<BookingProvider> bookings, Clock clock) {
        return BookingTools.proposeBookingChange(bookings::getIfAvailable, clock);
    }

    @Bean
    public Tool proposeCancellationTool(ObjectProvider<BookingProvider> bookings, Clock clock) {
        return BookingTools.proposeCancellation(bookings::getIfAvailable, clock);
    }

    @Bean
    public Tool confirmBookingTool(ObjectProvider<BookingProvider> bookings, Clock clock) {
        return BookingTools.confirmBooking(bookings::getIfAvailable, clock);
    }

    @Bean
    public Tool listMyBookingsTool(ObjectProvider<BookingProvider> bookings, Clock clock) {
        return BookingTools.listMyBookings(bookings::getIfAvailable, clock);
    }

    @Bean
    public EscalateToOwnerTool escalateToOwnerTool(ObjectProvider<HandoffSink> sinks) {
        return new EscalateToOwnerTool(sinks::getIfAvailable);
    }
}
