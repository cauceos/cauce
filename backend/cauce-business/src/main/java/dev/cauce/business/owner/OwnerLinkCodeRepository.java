package dev.cauce.business.owner;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for owner link codes; RLS-filtered like every other. */
public interface OwnerLinkCodeRepository extends JpaRepository<OwnerLinkCodeEntity, UUID> {

    Optional<OwnerLinkCodeEntity> findByCodeHash(String codeHash);
}
