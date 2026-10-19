package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CounsellorAgencyAdminGrantServiceTest {

  @Mock private IdentityClient identityClient;
  @Mock private AdminRepository adminRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;

  @InjectMocks private CounsellorAgencyAdminGrantService service;

  @Test
  void grantAgencyAdmin_Should_StoreThePlainUsernameOnTheAdminRow_When_TheConsultantRowIsEncoded() {
    var consultant =
        Consultant.builder()
            .id("consultant-1")
            .tenantId(79L)
            .username(new UsernameTranscoder().encodeUsername("ada.lovelace"))
            .firstName("Ada")
            .lastName("Lovelace")
            .email("ada@example.org")
            .build();
    assertThat(consultant.getUsername()).startsWith("enc.");
    when(adminRepository.findById("consultant-1")).thenReturn(Optional.empty());
    when(adminRepository.save(any(Admin.class))).thenAnswer(call -> call.getArgument(0));

    service.grantAgencyAdmin(consultant, 275L);

    var saved = ArgumentCaptor.forClass(Admin.class);
    verify(adminRepository).save(saved.capture());
    assertThat(saved.getValue().getUsername()).isEqualTo("ada.lovelace");
  }
}
