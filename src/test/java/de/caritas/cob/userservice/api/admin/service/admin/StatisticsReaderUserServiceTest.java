package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.NoContentException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.service.auth.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ORISO-UserService#1666: statistics readers are managed only as their own admin type. */
@ExtendWith(MockitoExtension.class)
class StatisticsReaderUserServiceTest {

  @Mock private CreateAdminService createAdminService;
  @Mock private UpdateAdminService updateAdminService;
  @Mock private DeleteAdminService deleteAdminService;
  @Mock private RetrieveAdminService retrieveAdminService;
  @Mock private UserServiceMapper userServiceMapper;
  @Mock private PasswordResetService passwordResetService;

  @InjectMocks private StatisticsReaderUserService service;

  @Test
  void createsTheReaderAndSendsTheSetPasswordMail() {
    var dto = new CreateAdminDTO().username("stats.reader").email("Stats@Example.org");
    when(createAdminService.createNewStatisticsReader(any())).thenReturn(reader());

    var response = service.create(dto);

    assertThat(response.getEmbedded().getId()).isEqualTo("reader-id");
    assertThat(dto.getEmail()).isEqualTo("stats@example.org");
    verify(passwordResetService)
        .requestPasswordReset(eq("stats.reader"), any(), eq(PasswordResetApplication.ADMIN));
  }

  @Test
  void findsOnlyStatisticsReaders() {
    when(retrieveAdminService.findAdmin("tenant-admin-id", Admin.AdminType.STATISTICS))
        .thenThrow(new NoContentException("not found"));

    assertThatThrownBy(() -> service.find("tenant-admin-id"))
        .isInstanceOf(NoContentException.class);
  }

  @Test
  void deletesOnlyStatisticsReaders() {
    service.delete("reader-id");

    verify(deleteAdminService).deleteStatisticsReader("reader-id");
    verify(deleteAdminService, never()).deleteTenantAdmin(any());
    verify(deleteAdminService, never()).deleteAgencyAdmin(any());
  }

  @Test
  void updatesOnlyStatisticsReaders() {
    var patch = new PatchAdminDTO().firstname("A").lastname("B").email("X@Example.org");
    when(updateAdminService.patchStatisticsReader(eq("reader-id"), any())).thenReturn(reader());

    service.update("reader-id", patch);

    assertThat(patch.getEmail()).isEqualTo("x@example.org");
    verify(updateAdminService).patchStatisticsReader("reader-id", patch);
  }

  private static Admin reader() {
    return Admin.builder()
        .id("reader-id")
        .type(Admin.AdminType.STATISTICS)
        .tenantId(0L)
        .username("stats.reader")
        .email("stats@example.org")
        .firstName("Stat")
        .lastName("Reader")
        .build();
  }
}
