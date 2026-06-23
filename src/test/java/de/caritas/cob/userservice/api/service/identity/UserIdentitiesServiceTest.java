package de.caritas.cob.userservice.api.service.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserIdentitiesServiceTest {

  private static final String USER_ID = "6205491b-042e-484b-b941-0910ae011da3";

  @Mock private AdminRepository adminRepository;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private IdentityClient identityClient;

  @InjectMocks private UserIdentitiesService userIdentitiesService;

  @Test
  void getUserIdentities_Should_ReturnHasAdminIdentityTrue_When_AdminExists() {
    when(adminRepository.existsById(USER_ID)).thenReturn(true);
    lenient()
        .when(consultantRepository.findByIdAndDeleteDateIsNull(USER_ID))
        .thenReturn(Optional.empty());
    lenient().when(identityClient.getRealmRoles(USER_ID)).thenReturn(List.of());

    var result = userIdentitiesService.getUserIdentities(USER_ID);

    assertThat(result.isHasAdminIdentity()).isTrue();
  }

  @Test
  void getUserIdentities_Should_ReturnHasConsultantIdentityTrue_When_ConsultantPresent() {
    lenient().when(adminRepository.existsById(USER_ID)).thenReturn(false);
    when(consultantRepository.findByIdAndDeleteDateIsNull(USER_ID))
        .thenReturn(Optional.of(new Consultant()));
    lenient().when(identityClient.getRealmRoles(USER_ID)).thenReturn(List.of());

    var result = userIdentitiesService.getUserIdentities(USER_ID);

    assertThat(result.isHasConsultantIdentity()).isTrue();
  }

  @Test
  void getUserIdentities_Should_ReturnBothFalseWithRoles_When_NeitherIdentityExists() {
    when(adminRepository.existsById(USER_ID)).thenReturn(false);
    when(consultantRepository.findByIdAndDeleteDateIsNull(USER_ID)).thenReturn(Optional.empty());
    when(identityClient.getRealmRoles(USER_ID)).thenReturn(List.of("user-admin"));

    var result = userIdentitiesService.getUserIdentities(USER_ID);

    assertThat(result.isHasAdminIdentity()).isFalse();
    assertThat(result.isHasConsultantIdentity()).isFalse();
    assertThat(result.getKeycloakRoles()).containsExactly("user-admin");
  }

  @Test
  void getUserIdentities_Should_PassThroughKeycloakRoles_From_IdentityClient() {
    var roles = List.of("user-admin", "consultant", "tenant-admin");
    lenient().when(adminRepository.existsById(USER_ID)).thenReturn(true);
    lenient()
        .when(consultantRepository.findByIdAndDeleteDateIsNull(USER_ID))
        .thenReturn(Optional.of(new Consultant()));
    when(identityClient.getRealmRoles(USER_ID)).thenReturn(roles);

    var result = userIdentitiesService.getUserIdentities(USER_ID);

    assertThat(result.getKeycloakRoles()).isEqualTo(roles);
  }
}
