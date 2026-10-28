package de.caritas.cob.userservice.api.actions.chat;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Chat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Shuts down the Matrix room of a group chat when the chat is stopped or deleted.
 *
 * <p>Uses the Synapse admin room deletion API (via {@link MatrixSynapseService#purgeRoom(String)}),
 * which removes (kicks) all members from the room and purges it. Clients observe the membership
 * change ({@code Room.myMembership} turning {@code leave}) and can render a "group chat stopped"
 * state instead of showing a dead room as live. This is the same mechanism the account deletion
 * workflow already uses, so there is one room teardown mechanism in the codebase.
 *
 * <p>For an ordinary chat the shutdown is best-effort: the database deletion of the chat is the
 * source of truth, so a failing Matrix call is logged but never fails the stop/delete operation. A
 * members-only room is purged strictly (see {@link #purgeStrictly}). Historical records without a
 * Matrix room id are skipped because there is no remote room to shut down.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatrixChatShutdownService {

  private final MatrixSynapseService matrixSynapseService;

  /**
   * Best-effort shutdown of the Matrix room belonging to the given chat.
   *
   * @param chat the group chat being stopped or deleted
   */
  public void shutdownRoom(Chat chat) {
    var matrixRoomId = chat.getMatrixRoomId();
    if (isBlank(matrixRoomId)) {
      log.debug("Chat {} has no Matrix room id; skipping Matrix room shutdown", chat.getId());
      return;
    }

    if (chat.isMembersOnly()) {
      purgeStrictly(chat, matrixRoomId);
      return;
    }

    try {
      if (matrixSynapseService.purgeRoom(matrixRoomId)) {
        log.info("Shut down Matrix room {} of stopped group chat {}", matrixRoomId, chat.getId());
      } else {
        log.warn(
            "Could not shut down Matrix room {} of group chat {}; members may still see the room"
                + " as active",
            matrixRoomId,
            chat.getId());
      }
    } catch (Exception e) {
      log.warn(
          "Matrix room shutdown failed for room {} of group chat {}: {}",
          matrixRoomId,
          chat.getId(),
          e.getMessage());
    }
  }

  /**
   * A members-only room is private and encrypted: a room that stays purge-less keeps everyone in it
   * while the chat, its participants and the way to try again are gone from the database. So a
   * failed purge answers 502 and the caller, which runs this in the transaction that deletes the
   * chat, rolls back; the stop can then simply be repeated. A room Synapse no longer knows counts
   * as purged.
   */
  private void purgeStrictly(Chat chat, String matrixRoomId) {
    MatrixSynapseService.RoomPurgeOutcome outcome;
    try {
      outcome = matrixSynapseService.purgeRoomOrConfirmGone(matrixRoomId);
    } catch (Exception e) {
      log.warn(
          "Matrix room purge failed for members-only chat {}: {}", chat.getId(), e.getMessage());
      outcome = MatrixSynapseService.RoomPurgeOutcome.FAILED;
    }
    if (outcome == MatrixSynapseService.RoomPurgeOutcome.FAILED) {
      log.warn(
          "Could not purge the Matrix room of members-only chat {}; the chat is kept",
          chat.getId());
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY);
    }
  }
}
