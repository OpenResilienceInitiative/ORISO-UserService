package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class IdAllocationControllerTest {

  @Mock private TenantIdAllocationClient tenantIdAllocationClient;
  @Mock private AgencyIdAllocationClient agencyIdAllocationClient;

  @InjectMocks private IdAllocationController controller;

  @Test
  void validateIds_Should_ReturnBothStates_When_TenantAndAgencyIdGiven() {
    when(tenantIdAllocationClient.getAvailability(21L)).thenReturn(IdAllocationStatus.FREE);
    when(agencyIdAllocationClient.getAvailability(3L)).thenReturn(IdAllocationStatus.RESERVED);

    var response = controller.validateIds(21L, 3L).getBody();

    assertThat(response.tenant.id).isEqualTo(21L);
    assertThat(response.tenant.status).isEqualTo("FREE");
    assertThat(response.agency.id).isEqualTo(3L);
    assertThat(response.agency.status).isEqualTo("RESERVED");
  }

  @Test
  void validateIds_Should_OmitAgencyEntry_When_OnlyTenantIdGiven() {
    when(tenantIdAllocationClient.getAvailability(21L)).thenReturn(IdAllocationStatus.ASSIGNED);

    var response = controller.validateIds(21L, null).getBody();

    assertThat(response.tenant.status).isEqualTo("ASSIGNED");
    assertThat(response.agency).isNull();
  }

  @Test
  void validateIds_Should_PassUpstreamStatusThrough_When_OneServiceFails() {
    when(tenantIdAllocationClient.getAvailability(21L)).thenReturn(IdAllocationStatus.FREE);
    when(agencyIdAllocationClient.getAvailability(3L))
        .thenThrow(
            HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY,
                "bad gateway",
                new HttpHeaders(),
                new byte[0],
                StandardCharsets.UTF_8));

    var response = controller.validateIds(21L, 3L).getBody();

    assertThat(response.tenant.status).isEqualTo("FREE");
    assertThat(response.agency.status).isEqualTo("SERVICE_ERROR");
    assertThat(response.agency.upstreamStatus).isEqualTo(502);
  }

  @Test
  void validateIds_Should_MarkServiceError_When_ServiceUnreachable() {
    when(tenantIdAllocationClient.getAvailability(21L))
        .thenThrow(new ResourceAccessException("connection refused"));

    var response = controller.validateIds(21L, null).getBody();

    assertThat(response.tenant.status).isEqualTo("SERVICE_ERROR");
    assertThat(response.tenant.upstreamStatus).isNull();
  }

  @Test
  void validateIds_Should_ThrowBadRequest_When_NoIdGiven() {
    assertThatThrownBy(() -> controller.validateIds(null, null))
        .isInstanceOf(BadRequestException.class);
  }
}
