package de.caritas.cob.userservice.api.service.identity;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GuestUsernameAvailabilityTest {
  private static final String NAME = "Quiet Otter";
  private static final String ENCODED = new UsernameTranscoder().encodeUsername(NAME);
  @Mock private UserRepository users;
  @Mock private ConsultantRepository consultants;
  @Mock private IdentityUsernameAvailability identity;
  @Mock private MatrixUserClient matrix;
  @InjectMocks private GuestUsernameAvailability availability;

  @AfterEach
  void cleanup() {
    TenantContext.clear();
  }

  @Test
  void diagnosticContainsOnlyDependencyTypeWithoutSecretMessageOrCause() {
    when(identity.isUsernameAvailable(NAME))
        .thenThrow(new IllegalStateException("secret-credential-in-upstream-body"));
    try (var logs = LogbackCaptor.forClass(GuestUsernameAvailability.class)) {
      assertThatThrownBy(() -> availability.isAvailable(NAME))
          .isInstanceOf(ServiceUnavailableException.class)
          .hasNoCause()
          .hasMessageNotContaining("secret-credential");
      assertThat(logs.events()).hasSize(1);
      assertThat(logs.events().getFirst().getFormattedMessage())
          .contains("java.lang.IllegalStateException")
          .doesNotContain("secret-credential");
      assertThat(logs.events().getFirst().getThrowableProxy()).isNull();
    }
  }

  @Test
  void checksAllSystemsInGlobalNamespaceAndRestoresCaller() {
    var caller = new TenantData(83L, "caller");
    TenantContext.setCurrentTenantData(caller);
    when(users.findAllByUsernameInAndDeleteDateIsNullOrderByCreateDateAsc(List.of(ENCODED, NAME)))
        .thenAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenant())
                  .isEqualTo(TenantContext.TECHNICAL_TENANT_ID);
              return List.of();
            });
    when(consultants.findByUsernameAndDeleteDateIsNull(NAME))
        .thenAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenant())
                  .isEqualTo(TenantContext.TECHNICAL_TENANT_ID);
              return Optional.empty();
            });
    when(identity.isUsernameAvailable(NAME))
        .thenAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenantData()).isSameAs(caller);
              return true;
            });
    assertThat(availability.isAvailable(NAME)).isTrue();
    verify(matrix).userExistsStrict(ENCODED);
    verify(matrix).userExistsStrict(NAME);
    assertThat(TenantContext.getCurrentTenantData()).isSameAs(caller);
    assertThat(caller.getTenantId()).isEqualTo(83L);
    assertThat(caller.getSubdomain()).isEqualTo("caller");
  }

  @Test
  void rejectsUserCollisionWithoutOtherLookups() {
    when(users.findAllByUsernameInAndDeleteDateIsNullOrderByCreateDateAsc(List.of(ENCODED, NAME)))
        .thenReturn(List.of(USER));
    assertThat(availability.isAvailable(NAME)).isFalse();
    verifyNoInteractions(consultants, identity, matrix);
    assertThat(TenantContext.getCurrentTenantData()).isNull();
  }

  @Test
  void rejectsConsultantCollision() {
    when(consultants.findByUsernameAndDeleteDateIsNull(NAME)).thenReturn(Optional.of(CONSULTANT));
    assertThat(availability.isAvailable(NAME)).isFalse();
    verifyNoInteractions(identity, matrix);
  }

  @Test
  void rejectsEncodedConsultantCollisionInGlobalContext() {
    when(consultants.findByUsernameAndDeleteDateIsNull(NAME)).thenReturn(Optional.empty());
    when(consultants.findByUsernameAndDeleteDateIsNull(ENCODED))
        .thenAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenant())
                  .isEqualTo(TenantContext.TECHNICAL_TENANT_ID);
              return Optional.of(CONSULTANT);
            });
    assertThat(availability.isAvailable(NAME)).isFalse();
    verifyNoInteractions(identity, matrix);
    assertThat(TenantContext.getCurrentTenantData()).isNull();
  }

  @Test
  void rejectsIdentityCollision() {
    assertThat(availability.isAvailable(NAME)).isFalse();
    verifyNoInteractions(matrix);
  }

  @Test
  void rejectsEncodedMatrixCollision() {
    when(identity.isUsernameAvailable(NAME)).thenReturn(true);
    when(matrix.userExistsStrict(ENCODED)).thenReturn(true);
    assertThat(availability.isAvailable(NAME)).isFalse();
    verify(matrix, never()).userExistsStrict(NAME);
  }

  @Test
  void rejectsLegacyPlainMatrixCollision() {
    when(matrix.userExistsStrict(ENCODED)).thenReturn(false);
    when(identity.isUsernameAvailable(NAME)).thenReturn(true);
    when(matrix.userExistsStrict(NAME)).thenReturn(true);
    assertThat(availability.isAvailable(NAME)).isFalse();
  }

  @Test
  void databaseFailureRestoresEntireContextIncludingNullTenantId() {
    var caller = new TenantData(null, "caller");
    TenantContext.setCurrentTenantData(caller);
    when(users.findAllByUsernameInAndDeleteDateIsNullOrderByCreateDateAsc(List.of(ENCODED, NAME)))
        .thenThrow(new IllegalStateException("dependency down"));
    assertThatThrownBy(() -> availability.isAvailable(NAME))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(TenantContext.getCurrentTenantData()).isSameAs(caller);
    assertThat(caller.getTenantId()).isNull();
    assertThat(caller.getSubdomain()).isEqualTo("caller");
    verifyNoInteractions(identity, matrix);
  }

  @Test
  void consultantFailureRestoresAbsentContext() {
    when(consultants.findByUsernameAndDeleteDateIsNull(NAME))
        .thenThrow(new IllegalStateException("dependency down"));
    assertThatThrownBy(() -> availability.isAvailable(NAME))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(TenantContext.getCurrentTenantData()).isNull();
  }

  @Test
  void identityFailureIsUnavailable() {
    when(identity.isUsernameAvailable(NAME))
        .thenThrow(new IllegalStateException("dependency down"));
    assertThatThrownBy(() -> availability.isAvailable(NAME))
        .isInstanceOf(ServiceUnavailableException.class);
    verifyNoInteractions(matrix);
  }

  @Test
  void matrixFailureIsUnavailable() {
    when(identity.isUsernameAvailable(NAME)).thenReturn(true);
    when(matrix.userExistsStrict(ENCODED))
        .thenThrow(new ServiceUnavailableException("dependency down"));
    assertThatThrownBy(() -> availability.isAvailable(NAME))
        .isInstanceOf(ServiceUnavailableException.class);
  }
}
