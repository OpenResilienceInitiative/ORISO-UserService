package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.GuestJoinAttempt;
import de.caritas.cob.userservice.api.model.GuestJoinTarget;
import de.caritas.cob.userservice.api.port.out.GuestJoinAttemptRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits the immutable retry binding before the caller can perform any provider write. */
@Service
public class GuestJoinAttemptStore {
  private final GuestJoinAttemptRepository repository;
  private final TransactionTemplate transaction;

  public GuestJoinAttemptStore(
      GuestJoinAttemptRepository repository, PlatformTransactionManager manager) {
    this.repository = repository;
    this.transaction = new TransactionTemplate(manager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public GuestJoinAttempt prepare(
      GuestJoinCapability capability,
      GuestJoinTarget target,
      String username,
      String avatarKey,
      boolean languageFormal,
      LocalDateTime expiresAt) {
    if (capability == null
        || target == null
        || target.inviteLinkId() == null
        || target.inviteLinkId() <= 0
        || target.tenantId() == null
        || target.tenantId() <= 0
        || target.topicId() == null
        || target.topicId() <= 0
        || target.consultingTypeId() == null
        || target.consultingTypeId() < 0
        || username == null
        || !username.matches("[a-z0-9_]{3,30}")
        || avatarKey == null
        || avatarKey.isBlank()
        || avatarKey.length() > 128
        || expiresAt == null) {
      throw new BadRequestException("Invalid guest Join binding");
    }
    String hash = capability.attemptHash();
    GuestJoinAttempt attempt;
    try {
      attempt =
          transaction.execute(
              status ->
                  repository
                      .findByKeyHash(hash)
                      .orElseGet(
                          () ->
                              repository.saveAndFlush(
                                  GuestJoinAttempt.prepare(
                                      hash,
                                      target,
                                      username,
                                      avatarKey,
                                      languageFormal,
                                      LocalDateTime.now(ZoneOffset.UTC),
                                      expiresAt))));
    } catch (DataIntegrityViolationException conflict) {
      // The failed INSERT transaction is over. Never reload in its rollback-only transaction.
      attempt = transaction.execute(status -> repository.findByKeyHash(hash).orElse(null));
      if (attempt == null) throw conflict;
    }
    Objects.requireNonNull(attempt);
    if (!attempt.getInviteLinkId().equals(target.inviteLinkId())
        || !attempt.getTenantId().equals(target.tenantId())
        || !attempt.getTopicId().equals(target.topicId())
        || !attempt.getConsultingTypeId().equals(target.consultingTypeId())
        || !attempt.getOriginalUsername().equals(username)
        || !attempt.getOriginalAvatarKey().equals(avatarKey)
        || attempt.isLanguageFormal() != languageFormal) {
      throw new ConflictException("Guest Join retry key is already bound to another request");
    }
    // Neither replay nor expiry creates a replacement row. Eligibility is checked by Join.
    return attempt;
  }
}
