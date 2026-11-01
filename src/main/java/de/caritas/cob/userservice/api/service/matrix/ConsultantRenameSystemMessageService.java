package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Posts a "{old} is now called {new}" system message into the chats of a counsellor whose published
 * display name changed (OBP 2.1 #119, ORISO-UserService#1533).
 *
 * <p>Without it, the advice seeker sees every message — old ones included — suddenly carry a
 * different name and cannot tell whether someone else took over. The event carries the type and
 * both names only; the web client words it in the reader's own language.
 *
 * <p><b>Which names.</b> Callers pass the names the advice seeker sees, i.e. {@link
 * de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver#resolveMatrixDisplayName}
 * before and after the change — never the real name. The advice seeker already saw both names in
 * this very room, so the event tells them nothing new about the counsellor. The names are still
 * never logged.
 *
 * <p><b>Which chats.</b> {@code IN_PROGRESS} sessions assigned to the counsellor. {@code NEW}
 * enquiries have no accepted conversation yet; closed and archived sessions get nothing. Group
 * chats and internal rooms are out of scope (#1533).
 *
 * <p>Best effort throughout: a failure is logged per session and never fails the profile update.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantRenameSystemMessageService {

  public static final String CONSULTANT_RENAMED_TYPE = "CONSULTANT_RENAMED";

  private static final List<SessionStatus> ACTIVE_STATUSES = List.of(SessionStatus.IN_PROGRESS);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  /**
   * Same as {@link #postRenameMessages}, deferred until the surrounding transaction has committed —
   * a rolled-back rename must not leave a message behind. Without an active transaction it posts
   * immediately.
   */
  public void postRenameMessagesAfterCommit(Consultant consultant, String oldName, String newName) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      postRenameMessages(consultant, oldName, newName);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            postRenameMessages(consultant, oldName, newName);
          }
        });
  }

  /**
   * Posts the rename notice into each active session of the counsellor. Does nothing when either
   * name is blank or both are equal after trimming (a case-only change counts as a change).
   */
  public void postRenameMessages(Consultant consultant, String oldName, String newName) {
    if (consultant == null || isBlank(oldName) || isBlank(newName)) {
      return;
    }
    var previous = oldName.trim();
    var next = newName.trim();
    if (previous.equals(next)) {
      return;
    }
    var body = buildBody(previous, next);
    if (body == null) {
      return;
    }

    List<Session> sessions;
    try {
      sessions = sessionRepository.findByConsultantAndStatusIn(consultant, ACTIVE_STATUSES);
    } catch (RuntimeException e) {
      log.warn(
          "Could not look up the sessions of consultant {} for the rename notice",
          consultant.getId(),
          e);
      return;
    }

    var posted = 0;
    for (var session : sessions) {
      try {
        if (matrixSessionSystemMessageService.postSystemMessage(session, body, true)) {
          posted++;
        }
      } catch (RuntimeException e) {
        log.warn(
            "Rename notice for session {} of consultant {} failed",
            session.getId(),
            consultant.getId(),
            e);
      }
    }
    log.info(
        "Rename notice posted for consultantId={} into {} of {} active sessions",
        consultant.getId(),
        posted,
        sessions.size());
  }

  private static String buildBody(String oldName, String newName) {
    var payload = new LinkedHashMap<String, String>();
    payload.put("type", CONSULTANT_RENAMED_TYPE);
    payload.put("oldName", oldName);
    payload.put("newName", newName);
    try {
      return MatrixSessionSystemMessageService.SYSTEM_NOTIFICATION_PREFIX
          + OBJECT_MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.warn("Could not serialize the rename notice", e);
      return null;
    }
  }
}
