package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.admin.service.admin.SupportAdminUserService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SupportAdminControllerTest {

  @Mock private SupportAdminUserService supportAdminUserService;
  @Mock private AdminDtoMapper adminDtoMapper;

  @InjectMocks private SupportAdminController controller;

  @Test
  void search_delegatesToTheSupportListingAndMapsLikeTheOtherAdminTables() {
    // Business reason: the Admin panel reuses one response shape for all admin
    // tables — the GSA table must parse identically to tenant-admins/search.
    Map<String, Object> resultMap = Map.of("totalElements", 0);
    var dto = mock(AdminSearchResultDTO.class);
    when(supportAdminUserService.findSupportAdminsByInfix("*", PageRequest.of(0, 20)))
        .thenReturn(resultMap);
    when(adminDtoMapper.adminSearchResultOf(resultMap, "*", 1, 20, "FIRSTNAME", "ASC"))
        .thenReturn(dto);

    var response = controller.searchSupportAdmins("*", 1, 20, "FIRSTNAME", "ASC");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(dto);
  }
}
