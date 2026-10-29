package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.DeletedAskerSessionDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.DeletedAskerSession;
import de.caritas.cob.userservice.api.service.session.DeletedAskerSessionService;
import io.swagger.annotations.Api;
import java.time.ZoneOffset;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ORISO-Frontend#1159: placeholders for the calling counsellor's deleted advice-seeker chats. Only
 * the counsellor's own placeholders are ever returned; the rows hold no personal data.
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "deleted-asker-session-controller")
public class DeletedAskerSessionController {

  private final @NonNull DeletedAskerSessionService deletedAskerSessionService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @GetMapping({
    "/users/sessions/consultants/deleted-askers",
    "/service/users/sessions/consultants/deleted-askers"
  })
  public ResponseEntity<List<DeletedAskerSessionDTO>> getDeletedAskerSessions() {
    return ResponseEntity.ok(
        deletedAskerSessionService.findForConsultant(authenticatedUser.getUserId()).stream()
            .map(DeletedAskerSessionController::toDto)
            .toList());
  }

  private static DeletedAskerSessionDTO toDto(DeletedAskerSession placeholder) {
    return new DeletedAskerSessionDTO()
        .formerSessionId(placeholder.getFormerSessionId())
        .agencyId(placeholder.getAgencyId())
        .deletedAt(placeholder.getDeletedAt().atOffset(ZoneOffset.UTC));
  }
}
