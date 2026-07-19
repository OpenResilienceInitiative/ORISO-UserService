package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.support.SupportRoomService;
import de.caritas.cob.userservice.api.service.support.SupportRoomService.SupportRoomItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SupportRoomControllerTest {

  @Mock private SupportRoomService supportRoomService;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private SupportRoomController controller;

  @Test
  void active_returnsOnlyTheCallersRooms() {
    // Business reason: support rooms are strictly participant-scoped — nobody can
    // enumerate other people's support sessions.
    var item = mock(SupportRoomItem.class);
    when(supportRoomService.activeFor(authenticatedUser)).thenReturn(List.of(item));

    var response = controller.active();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsExactly(item);
  }

  @Test
  void terminate_delegatesWithTheAuthenticatedCaller() {
    var response = controller.terminate("sr-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(supportRoomService).terminate(authenticatedUser, "sr-1");
  }
}
