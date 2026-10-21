package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityCreated;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.memory.identity.IdentityEntity;
import dev.cauce.memory.identity.IdentityMapper;
import dev.cauce.memory.identity.IdentityRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class IdentityServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    private IdentityRepository identityRepository;
    private ApplicationEventPublisher eventPublisher;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        identityRepository = Mockito.mock(IdentityRepository.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        service = new IdentityService(identityRepository, new IdentityMapper(), eventPublisher);
    }

    @Test
    void resolveOrCreate_whenChannelTypeUnsupported_throwsInvalidChannelType_beforeTouchingTheRepository() {
        assertThatThrownBy(() ->
                service.resolveOrCreate(TENANT, "discord", IdentityKind.PROVIDER_USER_ID, "42"))
                .isInstanceOf(InvalidChannelTypeException.class);

        verify(identityRepository, never()).insertIfAbsent(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void resolveOrCreate_whenValueBlank_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.resolveOrCreate(TENANT, "api", IdentityKind.CLIENT_REFERENCE, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveOrCreate_whenIdentityExists_returnsIt_withoutInserting() {
        IdentityEntity stored = stored("telegram", IdentityKind.PROVIDER_USER_ID, "987654321");
        when(identityRepository.findByTenantIdAndChannelTypeAndKindAndValue(
                TENANT, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321"))
                .thenReturn(Optional.of(stored));

        Identity resolved = service.resolveOrCreate(TENANT, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321");

        assertThat(resolved.id()).isEqualTo(stored.getId());
        verify(identityRepository, never()).insertIfAbsent(any(), any(), anyString(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void resolveOrCreate_stripsTheValue_beforeLookingItUp() {
        IdentityEntity stored = stored("api", IdentityKind.CLIENT_REFERENCE, "user-1");
        when(identityRepository.findByTenantIdAndChannelTypeAndKindAndValue(
                TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .thenReturn(Optional.of(stored));

        Identity resolved = service.resolveOrCreate(TENANT, " api ", IdentityKind.CLIENT_REFERENCE, "  user-1 ");

        assertThat(resolved.id()).isEqualTo(stored.getId());
    }

    @Test
    void resolveOrCreate_whenAbsent_insertsThenReturnsTheReRead_passingKindByName() {
        IdentityEntity winner = stored("api", IdentityKind.CLIENT_REFERENCE, "user-1");
        when(identityRepository.findByTenantIdAndChannelTypeAndKindAndValue(
                TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(identityRepository.insertIfAbsent(any(), eq(TENANT), eq("api"), eq("CLIENT_REFERENCE"), eq("user-1")))
                .thenReturn(1);

        Identity resolved = service.resolveOrCreate(TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-1");

        assertThat(resolved.id()).isEqualTo(winner.getId());
        verify(identityRepository).insertIfAbsent(any(), eq(TENANT), eq("api"), eq("CLIENT_REFERENCE"), eq("user-1"));
    }

    @Test
    void resolveOrCreate_whenThisCallInserts_publishesIdentityCreatedWithoutTheValue() {
        ArgumentCaptor<UUID> insertedId = ArgumentCaptor.forClass(UUID.class);
        when(identityRepository.findByTenantIdAndChannelTypeAndKindAndValue(
                TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .thenReturn(Optional.empty(), Optional.of(stored("api", IdentityKind.CLIENT_REFERENCE, "user-1")));
        when(identityRepository.insertIfAbsent(insertedId.capture(), any(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        service.resolveOrCreate(TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-1");

        ArgumentCaptor<IdentityCreated> event = ArgumentCaptor.forClass(IdentityCreated.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().type()).isEqualTo("conduct.identity.created");
        assertThat(event.getValue().identityId()).isEqualTo(insertedId.getValue());
        assertThat(event.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(event.getValue().channelType()).isEqualTo("api");
        assertThat(event.getValue().kind()).isEqualTo(IdentityKind.CLIENT_REFERENCE);
        assertThat(event.getValue().toString()).doesNotContain("user-1");
    }

    @Test
    void resolveOrCreate_whenInsertLosesTheRace_returnsTheWinner() {
        IdentityEntity winner = stored("api", IdentityKind.CLIENT_REFERENCE, "user-1");
        when(identityRepository.findByTenantIdAndChannelTypeAndKindAndValue(
                TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(identityRepository.insertIfAbsent(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(0);

        Identity resolved = service.resolveOrCreate(TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-1");

        assertThat(resolved.id()).isEqualTo(winner.getId());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void resolveOrCreate_whenReReadFindsNothing_throwsIllegalState() {
        when(identityRepository.findByTenantIdAndChannelTypeAndKindAndValue(
                any(), anyString(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(identityRepository.insertIfAbsent(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        assertThatThrownBy(() ->
                service.resolveOrCreate(TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static IdentityEntity stored(String channelType, IdentityKind kind, String value) {
        return new IdentityEntity(UUID.randomUUID(), TENANT, channelType, kind, value, Instant.now());
    }
}
