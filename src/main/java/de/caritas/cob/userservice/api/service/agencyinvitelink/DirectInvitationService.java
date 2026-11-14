package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.DIRECT_MEDIA;
import static de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService.SYSTEM_NOTIFICATION_PREFIX;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Direct invitations with an appointment (ORISO-Frontend#2252): the appointment is the same {@code
 * session_appointment} as "Termin anlegen"; everybody who opens the link shares one appointment
 * conversation (or joins the 1:1 the invitation was created from); the link expires after the end
 * plus its validity; a person in the conversation can ask for a new link, and the counsellor can
 * issue one. All link times are UTC.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectInvitationService {

  public static final String RENEWED_TYPE = "DIRECT_INVITATION_RENEWED";
  private static final String COUNSELLOR = InviteLinkKind.COUNSELLOR.name();
  private static final ObjectMapper JSON = new ObjectMapper();

  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull SessionAppointmentRepository appointments;
  private final @NonNull DirectInvitationMemberRepository members;
  private final @NonNull SessionRepository sessions;
  private final @NonNull DirectInvitationRoomMembership rooms;
  private final @NonNull EventNotificationService notifications;
  private final @NonNull MatrixSessionSystemMessageService systemMessages;
  private final @NonNull SessionArchiveService archive;

  @Value("${app.base.url:}")
  String appBaseUrl;

  public static LocalDateTime nowUtc() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  /** The schedule of an invitation with an appointment; empty for a link without one. */
  public Optional<DirectInvitationSchedule> scheduleOf(AgencyInviteLink link) {
    return appointmentOf(link)
        .map(
            a ->
                DirectInvitationSchedule.of(
                    utc(a.getStartTime()), utc(a.getEndTime()), link.getValidityAfterEndMinutes()));
  }

  public Optional<SessionAppointment> appointmentOf(AgencyInviteLink link) {
    return link.getAppointmentId() == null
        ? Optional.empty()
        : appointments.findById(link.getAppointmentId());
  }

  /** A new invitation gets a new appointment that has no conversation yet. */
  public void attachAppointment(
      AgencyInviteLink link, LocalDateTime start, LocalDateTime end, Integer validity) {
    var schedule = DirectInvitationSchedule.of(start, end, validity);
    requireFuture(schedule);
    var appointment = new SessionAppointment();
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    appointment.setStartTime(start.toInstant(ZoneOffset.UTC));
    appointment.setEndTime(end.toInstant(ZoneOffset.UTC));
    link.setAppointmentId(appointments.save(appointment).getId());
    apply(link, schedule);
  }

  /** An invitation from a 1:1 meets at an appointment of that conversation. */
  public void attachExistingAppointment(
      AgencyInviteLink link, SessionAppointment appointment, Integer validity) {
    if (appointment.getStatus() == SessionAppointment.Status.CANCELLED) {
      throw new ConflictException("A cancelled appointment takes no invitation.");
    }
    var schedule =
        DirectInvitationSchedule.of(
            utc(appointment.getStartTime()), utc(appointment.getEndTime()), validity);
    link.setAppointmentId(appointment.getId());
    apply(link, schedule);
  }

  /** Moves the appointment and/or changes the validity; the expiry follows. */
  @Transactional
  public void change(
      AgencyInviteLink link, LocalDateTime start, LocalDateTime end, Integer validity) {
    if (link.getAppointmentId() == null) {
      throw new ConflictException("The link has no appointment.");
    }
    var appointment =
        appointments
            .findByIdForUpdate(link.getAppointmentId())
            .orElseThrow(() -> new NotFoundException("Appointment not found"));
    var current =
        DirectInvitationSchedule.of(
            utc(appointment.getStartTime()),
            utc(appointment.getEndTime()),
            link.getValidityAfterEndMinutes());
    var schedule =
        new DirectInvitationSchedule(
            start != null ? start : current.start(),
            end != null ? end : current.end(),
            validity != null ? validity : current.validityAfterEndMinutes());
    if (start != null || end != null) {
      requireFuture(schedule);
      appointment.setStartTime(schedule.start().toInstant(ZoneOffset.UTC));
      appointment.setEndTime(schedule.end().toInstant(ZoneOffset.UTC));
      appointments.save(appointment);
    }
    apply(link, schedule);
  }

  /**
   * The first person creates the appointment conversation; everybody after them joins it. The
   * appointment row is locked, so two first visits create one conversation.
   */
  @Transactional
  public Opened openAppointmentConversation(
      AgencyInviteLink link, User user, Supplier<NewRegistrationResponseDto> newConversation) {
    var appointment =
        appointments
            .findByIdForUpdate(link.getAppointmentId())
            .orElseThrow(DirectLinkInactiveException::new);
    if (appointment.getStatus() == SessionAppointment.Status.CANCELLED) {
      throw new DirectLinkInactiveException();
    }
    boolean guest = user.getTemporaryAccountSnapshot() != null;
    var session = appointment.getSession();
    if (session == null) {
      var created = newConversation.get();
      if (created == null
          || created.getMatrixRoomId() == null
          || created.getMatrixRoomId().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
      }
      session =
          sessions
              .findById(created.getSessionId())
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.BAD_GATEWAY, "conversation_room_unavailable"));
      appointment.setSession(session);
      appointments.save(appointment);
      members.save(member(session, user, link, guest));
      return new Opened(session, true);
    }
    if (session.getUser() != null
        && Objects.equals(session.getUser().getUserId(), user.getUserId())) {
      return new Opened(session, false);
    }
    var existing = members.findBySessionIdAndUserId(session.getId(), user.getUserId());
    if (existing.isPresent() && existing.get().getRemovedAt() == null) {
      return new Opened(session, false);
    }
    if (link.isJoinsExistingSession()) {
      rooms.addInvitedGuestToExistingRoom(session, user, link.getId());
    } else {
      rooms.addToAppointmentRoom(session, user);
    }
    var conversation = session;
    var row = existing.orElseGet(() -> member(conversation, user, link, guest));
    row.setRemovedAt(null);
    row.setRevokeDueAt(null);
    row.setGuest(guest);
    row.setInviteLinkId(link.getId());
    row.setJoinedAt(nowUtc());
    members.save(row);
    return new Opened(session, true);
  }

  /**
   * "Neuen Link senden": a new token for the same appointment; the old one answers "expired". An
   * expired invitation is switched on again and its appointment conversation reopened. The new link
   * goes into the conversation as a system notice.
   */
  @Transactional
  public AgencyInviteLink renew(Consultant owner, Long linkId, Integer validity) {
    var link =
        links
            .findByIdAndConsultantIdAndLinkKindAndChatTypeIn(
                linkId, owner.getId(), COUNSELLOR, DIRECT_MEDIA)
            .orElseThrow(() -> new NotFoundException("Direct link %s not found", linkId));
    var appointment =
        appointmentOf(link)
            .orElseThrow(() -> new ConflictException("A link without appointment never expires."));
    if (validity != null) {
      DirectInvitationSchedule.requireValidity(validity);
    }
    var schedule =
        DirectInvitationSchedule.of(
            utc(appointment.getStartTime()),
            utc(appointment.getEndTime()),
            validity != null ? validity : link.getValidityAfterEndMinutes());
    var now = nowUtc();
    var expiresAt = schedule.renewedExpiry(now);
    if (!expiresAt.isAfter(now)) {
      throw new ConflictException("The new link would already be expired; choose a validity.");
    }
    link.setPreviousToken(link.getToken());
    link.setToken(CounsellorDirectLinkService.generateToken());
    link.setValidityAfterEndMinutes(schedule.validityAfterEndMinutes());
    link.setExpiresAt(expiresAt);
    if (InviteLinkStatus.EXPIRED.name().equals(link.getStatus())) {
      link.setStatus(InviteLinkStatus.ACTIVE.name());
    }
    link.setUpdateDate(now);
    var saved = links.save(link);
    var session = appointment.getSession();
    if (session != null) {
      if (!link.isJoinsExistingSession()) {
        archive.reopenBySystem(session);
      }
      postRenewedNotice(session, saved);
    }
    return saved;
  }

  /** A switched-off invitation is switched on again: its appointment conversation reopens. */
  @Transactional
  public void reopenConversation(AgencyInviteLink link) {
    if (link.isJoinsExistingSession()) {
      return;
    }
    appointmentOf(link).map(SessionAppointment::getSession).ifPresent(archive::reopenBySystem);
  }

  /**
   * "Neuen Link anfragen": only a person in the invitation's conversation may ask; anybody else
   * learns nothing (404). The expired and the replaced token both work.
   */
  @Transactional(readOnly = true)
  public void requestRenewal(String token, User user) {
    var link =
        links
            .findByToken(token)
            .or(() -> links.findFirstByPreviousToken(token))
            .filter(l -> COUNSELLOR.equals(l.getLinkKind()))
            .orElseThrow(() -> new NotFoundException("Invite link not found"));
    var appointment =
        appointmentOf(link)
            .orElseThrow(() -> new ConflictException("A link without appointment never expires."));
    var session = appointment.getSession();
    if (session == null || !isInConversation(session, user)) {
      throw new NotFoundException("Invite link not found");
    }
    notifications.createDirectInvitationRenewalRequestedNotification(
        session, link.getId(), user.getUserId(), token);
  }

  /** The invitation's people: the asker of the conversation and everybody who came in by link. */
  private boolean isInConversation(Session session, User user) {
    if (session.getUser() != null
        && Objects.equals(session.getUser().getUserId(), user.getUserId())) {
      return true;
    }
    return members.findBySessionIdAndUserId(session.getId(), user.getUserId()).isPresent();
  }

  private void postRenewedNotice(Session session, AgencyInviteLink link) {
    try {
      var payload = new LinkedHashMap<String, Object>();
      payload.put("type", RENEWED_TYPE);
      payload.put("linkId", link.getId());
      payload.put("url", entryUrl(link.getToken()));
      if (link.getExpiresAt() != null) {
        payload.put("expiresAt", link.getExpiresAt().atOffset(ZoneOffset.UTC).toString());
      }
      var posted =
          systemMessages.postSystemMessage(
              session, SYSTEM_NOTIFICATION_PREFIX + JSON.writeValueAsString(payload), true);
      if (!posted) {
        log.warn("The renewed link of invitation {} was not posted", link.getId());
      }
    } catch (JsonProcessingException | RuntimeException failure) {
      log.warn(
          "The renewed link of invitation {} could not be posted: {}",
          link.getId(),
          failure.getClass().getSimpleName());
    }
  }

  public String entryUrl(String token) {
    var base = appBaseUrl == null ? "" : appBaseUrl.replaceAll("/+$", "");
    return base + "/invite/" + token;
  }

  private static void apply(AgencyInviteLink link, DirectInvitationSchedule schedule) {
    link.setValidityAfterEndMinutes(schedule.validityAfterEndMinutes());
    link.setExpiresAt(schedule.expiresAt());
    link.setScheduledAt(schedule.start());
  }

  private static void requireFuture(DirectInvitationSchedule schedule) {
    if (!schedule.start().isAfter(nowUtc())) {
      throw new BadRequestException("An appointment must start in the future.");
    }
  }

  private static DirectInvitationMember member(
      Session session, User user, AgencyInviteLink link, boolean guest) {
    return DirectInvitationMember.builder()
        .sessionId(session.getId())
        .userId(user.getUserId())
        .inviteLinkId(link.getId())
        .guest(guest)
        .joinedAt(nowUtc())
        .build();
  }

  static LocalDateTime utc(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** The conversation the person is in now, and whether this visit put them there. */
  public record Opened(Session session, boolean created) {}
}
