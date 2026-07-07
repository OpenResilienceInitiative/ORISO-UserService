package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService;
import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService.InactiveAccountAuditLogEntry;
import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService.InactiveAccountAuditLogsResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class InactiveAccountAuditLogsControllerTest {

  @Mock private InactiveAccountAuditLogsService inactiveAccountAuditLogsService;

  @InjectMocks private InactiveAccountAuditLogsController controller;

  @Test
  void listAuditLogs_happyPath_delegatesAllParametersToService() {
    // Business reason: audit log queries must forward full filter context to the service layer.
    var result = sampleResult();
    when(inactiveAccountAuditLogsService.listAuditLogs(2, 15, "consultant", "abc-123"))
        .thenReturn(result);
    ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> perPageCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> accountIdCaptor = ArgumentCaptor.forClass(String.class);

    controller.listAuditLogs(2, 15, "consultant", "abc-123");

    verify(inactiveAccountAuditLogsService)
        .listAuditLogs(
            pageCaptor.capture(),
            perPageCaptor.capture(),
            roleCaptor.capture(),
            accountIdCaptor.capture());
    assertEquals(2, pageCaptor.getValue());
    assertEquals(15, perPageCaptor.getValue());
    assertEquals("consultant", roleCaptor.getValue());
    assertEquals("abc-123", accountIdCaptor.getValue());
  }

  @Test
  void listAuditLogs_happyPath_mapsResultIntoResponseDto() {
    // Business reason: operations teams need stable pagination metadata in the API response.
    var entry = InactiveAccountAuditLogEntry.builder().id(1L).accountId("user-1").build();
    var result =
        InactiveAccountAuditLogsResult.builder()
            .data(List.of(entry))
            .total(99L)
            .page(2)
            .perPage(15)
            .build();
    when(inactiveAccountAuditLogsService.listAuditLogs(2, 15, null, null)).thenReturn(result);

    var response = controller.listAuditLogs(2, 15, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().data.size());
    assertEquals(99L, response.getBody().total);
    assertEquals(2, response.getBody().page);
    assertEquals(15, response.getBody().perPage);
  }

  @Test
  void listAuditLogs_accountRoleProvided_forwardsRoleToService() {
    // Business reason: role filter narrows audit entries to consultant or user inactivity events.
    when(inactiveAccountAuditLogsService.listAuditLogs(1, 20, "USER", null))
        .thenReturn(sampleResult());

    controller.listAuditLogs(1, 20, "USER", null);

    verify(inactiveAccountAuditLogsService).listAuditLogs(1, 20, "USER", null);
  }

  @Test
  void listAuditLogs_accountIdProvided_forwardsAccountIdToService() {
    // Business reason: account-id filter supports targeted investigation of specific accounts.
    when(inactiveAccountAuditLogsService.listAuditLogs(1, 20, null, "acc-42"))
        .thenReturn(sampleResult());

    controller.listAuditLogs(1, 20, null, "acc-42");

    verify(inactiveAccountAuditLogsService).listAuditLogs(1, 20, null, "acc-42");
  }

  @Test
  void listAuditLogs_pageParameter_hasMinAnnotation() throws Exception {
    // Business reason: page must be at least 1 to prevent invalid pagination requests.
    Method method =
        InactiveAccountAuditLogsController.class.getMethod(
            "listAuditLogs", int.class, int.class, String.class, String.class);

    assertTrue(method.getParameters()[0].isAnnotationPresent(Min.class));
    assertEquals(1, method.getParameters()[0].getAnnotation(Min.class).value());
  }

  @Test
  void listAuditLogs_perPageParameter_hasMinAndMaxAnnotations() throws Exception {
    // Business reason: page size bounds keep audit log queries safe and predictable.
    Method method =
        InactiveAccountAuditLogsController.class.getMethod(
            "listAuditLogs", int.class, int.class, String.class, String.class);

    assertTrue(method.getParameters()[1].isAnnotationPresent(Min.class));
    assertTrue(method.getParameters()[1].isAnnotationPresent(Max.class));
    assertEquals(1, method.getParameters()[1].getAnnotation(Min.class).value());
    assertEquals(200, method.getParameters()[1].getAnnotation(Max.class).value());
  }

  private InactiveAccountAuditLogsResult sampleResult() {
    return InactiveAccountAuditLogsResult.builder()
        .data(List.of())
        .total(0L)
        .page(1)
        .perPage(20)
        .build();
  }
}
