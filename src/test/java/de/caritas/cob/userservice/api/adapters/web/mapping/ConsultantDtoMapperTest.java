package de.caritas.cob.userservice.api.adapters.web.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConsultantDtoMapperTest {

  @Mock private IdentityClient identityClient;

  @Test
  void consultantDtoOf_Should_MapMasterTableFields() {
    // given
    ConsultantDtoMapper consultantDtoMapper = new ConsultantDtoMapper();
    ReflectionTestUtils.setField(consultantDtoMapper, "identityClient", identityClient);
    when(identityClient.userHasRole("consultant-id", UserRole.GROUP_CHAT_CONSULTANT.getValue()))
        .thenReturn(false);

    // when
    var consultant = consultantDtoMapper.consultantDtoOf(consultantMap());

    // then
    assertThat(consultant.getDisplayName()).isEqualTo("Public Name");
    assertThat(consultant.getPublicName()).isEqualTo("Public Name");
    assertThat(consultant.getVacated()).isTrue();
    assertThat(consultant.getAdminRights()).isTrue();
    assertThat(consultant.getRoleInOrg()).isEqualTo("Counsellor Admin");
  }

  private Map<String, Object> consultantMap() {
    Map<String, Object> consultantMap = new HashMap<>();
    consultantMap.put("id", "consultant-id");
    consultantMap.put("email", "consultant@example.org");
    consultantMap.put("firstName", "First");
    consultantMap.put("lastName", "Last");
    consultantMap.put("username", "consultant");
    consultantMap.put("status", "ACTIVE");
    consultantMap.put("absenceMessage", "absence");
    consultantMap.put("isAbsent", false);
    consultantMap.put("isLanguageFormal", true);
    consultantMap.put("isTeamConsultant", false);
    consultantMap.put("isSupervisor", true);
    consultantMap.put("createdAt", "2026-06-08T10:00:00");
    consultantMap.put("updatedAt", "2026-06-08T10:00:00");
    consultantMap.put("deletedAt", "2026-06-09T10:00:00");
    consultantMap.put("displayName", "Public Name");
    consultantMap.put("tenantId", 1L);
    consultantMap.put("tenantName", "Tenant");
    consultantMap.put("agencies", new ArrayList<Map<String, Object>>());
    return consultantMap;
  }
}
