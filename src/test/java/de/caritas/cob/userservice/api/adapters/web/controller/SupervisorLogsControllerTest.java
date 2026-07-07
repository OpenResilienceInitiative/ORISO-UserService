package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.SupervisorLogsService;
import de.caritas.cob.userservice.api.service.SupervisorLogsService.SupervisorLogEntry;
import de.caritas.cob.userservice.api.service.SupervisorLogsService.SupervisorLogsResult;
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
class SupervisorLogsControllerTest {

  @Mock private SupervisorLogsService supervisorLogsService;

  @InjectMocks private SupervisorLogsController controller;

  @Test
  void listSupervisorLogs_happyPath_delegatesPageAndPerPageToService() {
    // Business reason: supervisor audit pages must preserve caller pagination when querying logs.
    when(supervisorLogsService.listSupervisorLogs(3, 25)).thenReturn(sampleResult());
    ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> perPageCaptor = ArgumentCaptor.forClass(Integer.class);

    controller.listSupervisorLogs(3, 25);

    verify(supervisorLogsService).listSupervisorLogs(pageCaptor.capture(), perPageCaptor.capture());
    assertEquals(3, pageCaptor.getValue());
    assertEquals(25, perPageCaptor.getValue());
  }

  @Test
  void listSupervisorLogs_happyPath_mapsResultIntoResponseDto() {
    // Business reason: admin views need totals and paging metadata alongside log entries.
    var entry = SupervisorLogEntry.builder().relationId(5L).sessionId(10L).action("ADDED").build();
    var result =
        SupervisorLogsResult.builder().data(List.of(entry)).total(50L).page(3).perPage(25).build();
    when(supervisorLogsService.listSupervisorLogs(3, 25)).thenReturn(result);

    var response = controller.listSupervisorLogs(3, 25);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().data.size());
    assertEquals(50L, response.getBody().total);
    assertEquals(3, response.getBody().page);
    assertEquals(25, response.getBody().perPage);
  }

  @Test
  void listSupervisorLogs_paginationParameters_haveMinAndMaxAnnotations() throws Exception {
    // Business reason: page and page size constraints prevent invalid pagination requests.
    Method method =
        SupervisorLogsController.class.getMethod("listSupervisorLogs", int.class, int.class);

    assertTrue(method.getParameters()[0].isAnnotationPresent(Min.class));
    assertTrue(method.getParameters()[1].isAnnotationPresent(Min.class));
    assertTrue(method.getParameters()[1].isAnnotationPresent(Max.class));
    assertEquals(1, method.getParameters()[0].getAnnotation(Min.class).value());
    assertEquals(1, method.getParameters()[1].getAnnotation(Min.class).value());
    assertEquals(200, method.getParameters()[1].getAnnotation(Max.class).value());
  }

  private SupervisorLogsResult sampleResult() {
    return SupervisorLogsResult.builder().data(List.of()).total(0L).page(1).perPage(20).build();
  }
}
