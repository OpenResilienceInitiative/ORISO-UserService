package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.facade.TeamDiscussionFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US#473 / ADR-016: Team-Besprechung endpoints. Consultant-only (SecurityConfig); participation
 * right = enquiry visibility right, enforced in the facade.
 */
@RestController
@RequestMapping("/users/sessions")
@RequiredArgsConstructor
public class TeamDiscussionController {

  private final @NonNull TeamDiscussionFacade teamDiscussionFacade;
  private final @NonNull AuthenticatedUser authenticatedUser;

  /** Opens (and lazily creates) the enquiry's team discussion; joins the caller into the room. */
  @PostMapping("/{sessionId}/team-discussion")
  public ResponseEntity<TeamDiscussionResponseDTO> getOrCreate(@PathVariable Long sessionId) {
    var view = teamDiscussionFacade.getOrCreateDiscussion(sessionId, authenticatedUser.getUserId());
    return ResponseEntity.ok(TeamDiscussionResponseDTO.from(view));
  }

  /**
   * ORISO-Frontend#2491: switches the team chat of a still open enquiry off (CLOSED, read-only for
   * everyone). 204 also when there is no discussion or it is CLOSED already; 409 when ARCHIVED.
   */
  @DeleteMapping("/{sessionId}/team-discussion")
  public ResponseEntity<Void> close(@PathVariable Long sessionId) {
    teamDiscussionFacade.closeDiscussion(sessionId, authenticatedUser.getUserId());
    return ResponseEntity.noContent().build();
  }

  /**
   * ORISO-Frontend#2491: read-only join. Joins the caller into the existing discussion without
   * changing its status — the way to read a CLOSED or ARCHIVED team chat. 200 with the discussion,
   * 204 when none exists.
   */
  @PostMapping("/{sessionId}/team-discussion/join")
  public ResponseEntity<TeamDiscussionResponseDTO> join(@PathVariable Long sessionId) {
    return teamDiscussionFacade
        .joinDiscussion(sessionId, authenticatedUser.getUserId())
        .map(view -> ResponseEntity.ok(TeamDiscussionResponseDTO.from(view)))
        .orElseGet(() -> new ResponseEntity<>(HttpStatus.NO_CONTENT));
  }

  /** Returns the discussion if one exists — including ARCHIVED (read-only archive access). */
  @GetMapping("/{sessionId}/team-discussion")
  public ResponseEntity<TeamDiscussionResponseDTO> get(@PathVariable Long sessionId) {
    return teamDiscussionFacade
        .getDiscussion(sessionId, authenticatedUser.getUserId())
        .map(view -> ResponseEntity.ok(TeamDiscussionResponseDTO.from(view)))
        .orElseGet(() -> new ResponseEntity<>(HttpStatus.NO_CONTENT));
  }

  public record TeamDiscussionResponseDTO(
      String matrixRoomId, String status, LocalDateTime archiveDate, LocalDateTime closedDate) {

    static TeamDiscussionResponseDTO from(TeamDiscussionFacade.TeamDiscussionView view) {
      return new TeamDiscussionResponseDTO(
          view.matrixRoomId(), view.status().name(), view.archiveDate(), view.closedDate());
    }
  }
}
