package dev.cauce.business.booking;

import dev.cauce.core.business.booking.Booking;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for bookings; RLS-filtered like every other. */
public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {

    Optional<BookingEntity> findByTenantIdAndCode(UUID tenantId, String code);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);

    /** The bookings of one shift on one date in the given statuses: what capacity is counted from. */
    List<BookingEntity> findByTenantIdAndServiceDateAndShiftIdAndStatusIn(UUID tenantId, LocalDate serviceDate,
                                                                          String shiftId,
                                                                          Collection<Booking.Status> statuses);

    List<BookingEntity> findByTenantIdAndIdentityIdAndServiceDateGreaterThanEqualAndStatusIn(
            UUID tenantId, UUID identityId, LocalDate from, Collection<Booking.Status> statuses);

    List<BookingEntity> findByTenantIdAndServiceDateAndStatus(UUID tenantId, LocalDate serviceDate,
                                                              Booking.Status status);

    List<BookingEntity> findByTenantIdOrderByIdAsc(UUID tenantId, Limit limit);

    List<BookingEntity> findByTenantIdAndIdGreaterThanOrderByIdAsc(UUID tenantId, UUID afterId, Limit limit);

    List<BookingEntity> findByTenantIdAndServiceDateOrderByIdAsc(UUID tenantId, LocalDate serviceDate, Limit limit);

    List<BookingEntity> findByTenantIdAndServiceDateAndIdGreaterThanOrderByIdAsc(UUID tenantId, LocalDate serviceDate,
                                                                                UUID afterId, Limit limit);
}
