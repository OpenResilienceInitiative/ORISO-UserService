package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.AskerResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DeletionPauseRequestDTO;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class UserAdminAskerControllerDelegate {

  private final @NonNull AskerUserAdminFacade askerUserAdminFacade;
  private final @NonNull AuthenticatedUser authenticatedUser;

  ResponseEntity<Void> markAskerForDeletion(String askerId) {
    askerUserAdminFacade.markAskerForDeletion(askerId);
    return ResponseEntity.ok().build();
  }

  ResponseEntity<Void> pauseAskerDeletion(
      String askerId, DeletionPauseRequestDTO deletionPauseRequestDTO) {
    askerUserAdminFacade.pauseAskerDeletion(
        askerId,
        deletionPauseRequestDTO.getReason(),
        deletionPauseRequestDTO.getMonths(),
        authenticatedUser.getUserId());
    return ResponseEntity.ok().build();
  }

  ResponseEntity<AskerResponseDTO> getAsker(String askerId) {
    return ResponseEntity.ok(askerUserAdminFacade.getAsker(askerId));
  }
}
