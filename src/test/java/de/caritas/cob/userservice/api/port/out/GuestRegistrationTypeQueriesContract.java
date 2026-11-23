package de.caritas.cob.userservice.api.port.out;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.DONE;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.User;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * ORISO-UserService#1697: the anonymous queue, the deactivation and the deletion of guests decide
 * "is a guest" from the stored registration facts (registration type ANONYMOUS, or the live-chat
 * postcode 00000 of the early REGISTERED guest sessions), never from the User-ID text.
 *
 * <p>Every earlier attempt to give guests an animal User-ID broke one of these paths because they
 * matched {@code LIKE 'Anonymous-%'} or parsed the number after {@code anon_}. The same scenarios
 * run on H2 in every build ({@link GuestRegistrationTypeQueriesIT}) and on the real MariaDB schema
 * built by Liquibase ({@link GuestRegistrationTypeQueriesMariaDbIT}).
 */
// Declared here as well: the subclasses' slice annotation alone did not open the test transaction.
@org.springframework.transaction.annotation.Transactional
abstract class GuestRegistrationTypeQueriesContract {

  private static final long TENANT = 1L;
  private static final long TOPIC = 916_970L;
  private static final int CONSULTING_TYPE = 1;

  @Autowired SessionRepository sessionRepository;
  @Autowired EntityManager entityManager;

  /** A new guest: animal User-ID, written with registration type and conversation type. */
  private Session animalGuest;

  /** An animal guest whose session predates the stored conversation type. */
  private Session animalGuestWithoutModality;

  /** An existing counter guest from before ORISO-Frontend#2346. */
  private Session counterGuest;

  /** An early live-chat / invite guest: REGISTERED, postcode 00000, timestamp User-ID. */
  private Session timestampGuest;

  private Session regularAsker;

  /** A regular registration that merely carries a guest-looking name: not a guest any more. */
  private Session regularAskerWithGuestLookingName;

  @BeforeEach
  void givenGuestsOfEveryNamingGenerationAndTwoRegularAskers() {
    var animal = new UsernameTranscoder().encodeUsername("katze_sam_4821");
    animalGuest = session(user(animal), ANONYMOUS, "00000", ConversationType.LIVE_CHAT);
    animalGuestWithoutModality =
        session(
            user(new UsernameTranscoder().encodeUsername("eule_mia_1203")),
            ANONYMOUS,
            // Not 00000: only the registration type marks this guest, so a postcode-only
            // predicate would drop it.
            "12345",
            null);
    counterGuest = session(user("anon_7"), ANONYMOUS, "00000", null);
    timestampGuest = session(user("Anonymous-1700000000000"), REGISTERED, "00000", null);
    regularAsker = session(user("enc.regular-" + UUID.randomUUID()), REGISTERED, "12345", null);
    regularAskerWithGuestLookingName =
        session(user("Anonymous-1700000000001"), REGISTERED, "12345", null);
    entityManager.flush();
  }

  @Test
  void theAnonymousQueue_Should_listEveryGuest_AndNoRegularAsker() {
    var queue =
        ids(
            sessionRepository
                .findAnonymousEnquiriesVisibleForConsultantsByTopicsOnly(
                    Set.of(TOPIC), NEW, nowInUtc().minusHours(1), ANONYMOUS, PageRequest.of(0, 50))
                .getContent());

    assertThat(queue)
        .contains(guestIds().toArray(Long[]::new))
        .doesNotContain(regularAsker.getId(), regularAskerWithGuestLookingName.getId());
  }

  @Test
  void openingAQueueEntry_Should_findEveryGuest_AndNoRegularAsker() {
    var opened =
        ids(
            sessionRepository.findVisibleAnonymousLiveChatEnquiriesForConsultantByIds(
                Set.copyOf(allIds()), Set.of(TOPIC), NEW, ANONYMOUS));

    assertThat(opened).containsExactlyInAnyOrderElementsOf(guestIds());
  }

  @Test
  void theQueuePosition_Should_countEveryGuest_AndNoRegularAsker() {
    long ahead =
        sessionRepository.countPendingEnquiriesAheadOf(
            NEW,
            nowInUtc().plusDays(1),
            Long.MAX_VALUE,
            CONSULTING_TYPE,
            TOPIC,
            null,
            nowInUtc().minusHours(1),
            ANONYMOUS);

    assertThat(ahead).isEqualTo(guestIds().size());
  }

  @Test
  void theDeactivation_Should_pickUpEveryGuest_AndNoRegularAsker() {
    var candidates =
        ids(sessionRepository.findLiveChatSessionsByStatusIn(Set.of(NEW, IN_PROGRESS), ANONYMOUS));

    assertThat(candidates)
        .contains(guestIds().toArray(Long[]::new))
        .doesNotContain(regularAsker.getId(), regularAskerWithGuestLookingName.getId());
  }

  /** The deletion seeds from registration type ANONYMOUS: animal and counter guests alike. */
  @Test
  void theDeletion_Should_pickUpAnimalGuestsExactlyLikeCounterGuests() {
    // The regular asker is DONE too, so only the registration type keeps it out.
    for (var session :
        List.of(
            animalGuest,
            animalGuestWithoutModality,
            counterGuest,
            regularAskerWithGuestLookingName)) {
      session.setStatus(DONE);
      sessionRepository.save(session);
    }
    entityManager.flush();

    var candidates =
        ids(sessionRepository.findByStatusInAndRegistrationType(Set.of(DONE), ANONYMOUS));

    assertThat(candidates)
        .contains(animalGuest.getId(), animalGuestWithoutModality.getId(), counterGuest.getId())
        .doesNotContain(regularAsker.getId(), regularAskerWithGuestLookingName.getId());
  }

  private List<Long> guestIds() {
    return List.of(
        animalGuest.getId(),
        animalGuestWithoutModality.getId(),
        counterGuest.getId(),
        timestampGuest.getId());
  }

  private List<Long> allIds() {
    var all = new java.util.ArrayList<>(guestIds());
    all.add(regularAsker.getId());
    all.add(regularAskerWithGuestLookingName.getId());
    return all;
  }

  private static List<Long> ids(List<Session> sessions) {
    return sessions.stream().map(Session::getId).collect(Collectors.toList());
  }

  private User user(String username) {
    var user = new User();
    user.setUserId(UUID.randomUUID().toString());
    user.setUsername(username);
    user.setEmail(user.getUserId() + "@example.invalid");
    user.setTenantId(TENANT);
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    user.setLanguageCode(LanguageCode.de);
    entityManager.persist(user);
    return user;
  }

  private Session session(
      User user, RegistrationType type, String postcode, ConversationType conversationType) {
    var session = new Session();
    session.setUser(user);
    session.setConsultingTypeId(CONSULTING_TYPE);
    session.setPostcode(postcode);
    session.setMainTopicId(TOPIC);
    session.setStatus(NEW);
    session.setTenantId(TENANT);
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setRegistrationType(type);
    session.setConversationType(conversationType);
    session.setCreateDate(nowInUtc().minusMinutes(1));
    session.setUpdateDate(nowInUtc());
    entityManager.persist(session);
    return session;
  }
}
