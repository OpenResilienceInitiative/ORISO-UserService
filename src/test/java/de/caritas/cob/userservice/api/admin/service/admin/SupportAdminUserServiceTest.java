package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.model.Admin;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class SupportAdminUserServiceTest {

  @InjectMocks private SupportAdminUserService supportAdminUserService;

  @Mock private RetrieveAdminService retrieveAdminService;

  @Mock private UserServiceMapper userServiceMapper;

  @Mock private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultantRepository;

  private Admin.AdminBase adminBase(String id) {
    return new Admin.AdminBase() {
      @Override
      public String getId() {
        return id;
      }

      @Override
      public String getFirstName() {
        return "Sam";
      }

      @Override
      public String getLastName() {
        return "Support";
      }

      @Override
      public String getEmail() {
        return "sam@support.example";
      }

      @Override
      public Long getTenantId() {
        return 0L;
      }

      @Override
      public Admin.AdminType getType() {
        return Admin.AdminType.SUPPORT;
      }

      @Override
      public LocalDateTime getUpdateDate() {
        return null;
      }
    };
  }

  @Test
  void findSupportAdminsByInfix_Should_QueryTheSupportTypeAndMapWithConsultantIdentities() {
    // Business reason: Global Support Admins have their OWN table (ADR-018) — the
    // listing must never mix in tenant or agency admins.
    PageRequest pageRequest = PageRequest.of(0, 10);
    Page<Admin.AdminBase> page = new PageImpl<>(List.of(adminBase("gsa-1")), pageRequest, 1);
    Admin fullAdmin = new Admin();
    fullAdmin.setId("gsa-1");
    fullAdmin.setType(Admin.AdminType.SUPPORT);
    when(retrieveAdminService.findAllByInfix("*", Admin.AdminType.SUPPORT, pageRequest))
        .thenReturn(page);
    when(retrieveAdminService.findAllById(Set.of("gsa-1"))).thenReturn(List.of(fullAdmin));
    when(consultantRepository.findActiveIdsByIdIn(Set.of("gsa-1"))).thenReturn(Set.of());
    when(userServiceMapper.mapOfAdmin(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("totalElements", 1));

    var result = supportAdminUserService.findSupportAdminsByInfix("*", pageRequest);

    assertThat(result).containsEntry("totalElements", 1);
    verify(retrieveAdminService).findAllByInfix("*", Admin.AdminType.SUPPORT, pageRequest);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> identityCaptor =
        ArgumentCaptor.forClass((Class<Set<String>>) (Class<?>) Set.class);
    verify(userServiceMapper)
        .mapOfAdmin(
            Mockito.eq(page),
            Mockito.eq(List.of(fullAdmin)),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyMap(),
            identityCaptor.capture());
    assertThat(identityCaptor.getValue()).isEmpty();
  }
}
