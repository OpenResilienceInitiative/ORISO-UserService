package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsOrDefaultIfNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.NotificationsAware;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Resolves one current group member's address, language and email preference at delivery time. */
@Service
public class GroupAppointmentEmailRecipientService {
  public enum Role {
    PARTICIPANT,
    COUNSELOR
  }

  public record Recipient(String userId, String email, OrisoEmailRenderer.Tone tone) {}

  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final UserChatRepository userChats;
  private final GroupChatParticipantRepository counselors;
  private final String emailDummySuffix;

  public GroupAppointmentEmailRecipientService(
      UserRepository users,
      ConsultantRepository consultants,
      UserChatRepository userChats,
      GroupChatParticipantRepository counselors,
      @Value("${identity.email-dummy-suffix:}") String emailDummySuffix) {
    this.users = users;
    this.consultants = consultants;
    this.userChats = userChats;
    this.counselors = counselors;
    this.emailDummySuffix = emailDummySuffix;
  }

  public Optional<Recipient> resolve(Chat series, Role role, String userId) {
    if (series == null
        || series.getId() == null
        || series.getConversationType() != ConversationType.SELF_HELP
        || role == null
        || isBlank(userId)) {
      return Optional.empty();
    }
    return switch (role) {
      case PARTICIPANT -> resolveParticipant(series, userId);
      case COUNSELOR -> resolveCounselor(series.getId(), userId);
    };
  }

  private Optional<Recipient> resolveParticipant(Chat series, String userId) {
    return users
        .findByUserIdAndDeleteDateIsNull(userId)
        .filter(user -> userChats.findByChatAndUser(series, user).isPresent())
        .filter(user -> eligible(user, user.getEmail()))
        .map(
            user ->
                new Recipient(
                    userId,
                    user.getEmail(),
                    tone(user.getLanguageCode(), user.isLanguageFormal())));
  }

  private Optional<Recipient> resolveCounselor(Long seriesId, String userId) {
    if (counselors.findBySeriesIdAndConsultantId(seriesId, userId).isEmpty()) {
      return Optional.empty();
    }
    return consultants
        .findByIdAndDeleteDateIsNull(userId)
        .filter(consultant -> eligible(consultant, consultant.getEmail()))
        .map(
            consultant ->
                new Recipient(
                    userId,
                    consultant.getEmail(),
                    tone(consultant.getLanguageCode(), consultant.isLanguageFormal())));
  }

  private boolean eligible(NotificationsAware person, String email) {
    return person.isNotificationsEnabled()
        && deserializeNotificationSettingsOrDefaultIfNull(person).isAppointmentNotificationEnabled()
        && !isBlank(email)
        && (isBlank(emailDummySuffix) || !email.endsWith(emailDummySuffix));
  }

  private static OrisoEmailRenderer.Tone tone(LanguageCode language, boolean formal) {
    var tone = OrisoEmailRenderer.Tone.of(language);
    return tone == OrisoEmailRenderer.Tone.DE_FORMAL && !formal
        ? OrisoEmailRenderer.Tone.DE_INFORMAL
        : tone;
  }
}
