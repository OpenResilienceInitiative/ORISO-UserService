package de.caritas.cob.userservice.api.service.user;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserMobileToken;
import de.caritas.cob.userservice.api.port.out.UserMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

  private final @NonNull UserRepository userRepository;
  private final @NonNull UserMobileTokenRepository userMobileTokenRepository;
  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();
  private final AuditingHandler auditingHandler;

  /**
   * Deletes an user.
   *
   * @param user the user to be deleted
   */
  public void deleteUser(User user) {
    userRepository.delete(user);
  }

  /**
   * Creates a new {@link User}.
   *
   * @param userId the new user id
   * @param username the name for the user
   * @param email the email of the user
   * @param languageFormal flag for language formal
   * @return The created {@link User}
   */
  public User createUser(String userId, String username, String email, boolean languageFormal) {
    return createUser(userId, null, username, email, languageFormal);
  }

  /**
   * Creates a new {@link User}.
   *
   * @param userId the new user id
   * @param oldId an optional old user id
   * @param username the name for the user
   * @param email the email of the user
   * @param languageFormal flag for language formal
   * @return The created {@link User}
   */
  public User createUser(
      String userId, Long oldId, String username, String email, boolean languageFormal) {
    return createUser(userId, oldId, username, email, languageFormal, null);
  }

  public User createUser(
      String userId,
      Long oldId,
      String username,
      String email,
      boolean languageFormal,
      String preferredLanguage) {
    var user = new User(userId, oldId, username, email, languageFormal);
    user.setTenantId(TenantContext.getCurrentTenant());
    auditingHandler.markCreated(user);
    if (nonNull(preferredLanguage)) {
      user.setLanguageCode(LanguageCode.valueOf(preferredLanguage));
    }

    return userRepository.save(user);
  }

  /**
   * Loads an {@link User}.
   *
   * @param userId the id of the user to search for
   * @return An {@link Optional} with the {@link User}, if found
   */
  public Optional<User> getUser(String userId) {
    return userRepository.findByUserIdAndDeleteDateIsNull(userId);
  }

  public Optional<User> findDeletedById(String userId) {
    return userRepository.findById(userId).filter(user -> user.getDeleteDate() != null);
  }

  /**
   * Saves an {@link User} to the database.
   *
   * @param user the {@link User} to save
   * @return the saved {@link User}
   */
  public User saveUser(User user) {
    return userRepository.save(user);
  }

  /**
   * Finds an user via the {@link AuthenticatedUser}.
   *
   * @return Optional of user
   */
  public Optional<User> getUserViaAuthenticatedUser(AuthenticatedUser authenticatedUser) {
    return getUser(authenticatedUser.getUserId());
  }

  /**
   * Finds an user by the given username (searches for encoded and decoded version of it).
   *
   * <p>user.username is not unique (generated anonymous usernames repeat across restarts), so the
   * lookup returns the oldest matching row instead of failing on duplicates.
   *
   * @param username the username to search for
   * @return {@link Optional} of {@link User}
   */
  public Optional<User> findUserByUsername(String username) {
    return userRepository
        .findAllByUsernameInAndDeleteDateIsNullOrderByCreateDateAsc(
            List.of(
                usernameTranscoder.encodeUsername(username),
                usernameTranscoder.decodeUsername(username)))
        .stream()
        .findFirst();
  }

  /**
   * Finds active and soft-deleted users for a privileged identity-lifecycle operation.
   *
   * <p>The caller must still fail closed unless exactly one full identity tuple matches.
   */
  public List<User> findUsersByUsernameIncludingDeleted(String username) {
    return userRepository.findAllByUsernameInOrderByCreateDateAsc(
        List.of(
            usernameTranscoder.encodeUsername(username),
            usernameTranscoder.decodeUsername(username)));
  }

  public Optional<User> findUserByEmail(String email) {
    return userRepository.findByEmailAndDeleteDateIsNull(email);
  }

  /**
   * Adds a mobile client token of the current authenticated user in database.
   *
   * @param userId the id of the user
   * @param mobileToken the new mobile device identifier token
   */
  public void addMobileAppToken(String userId, String mobileToken) {
    if (isNotBlank(mobileToken)) {
      this.getUser(userId).ifPresent(user -> this.addUserToken(user, mobileToken));
    }
  }

  private void addUserToken(User user, String mobileToken) {
    verifyTokenDoesNotAlreadyExist(mobileToken);
    var userMobileToken = new UserMobileToken();
    userMobileToken.setUser(user);
    userMobileToken.setMobileAppToken(mobileToken);
    this.userMobileTokenRepository.save(userMobileToken);
    user.getUserMobileTokens().add(userMobileToken);
    this.saveUser(user);
  }

  private void verifyTokenDoesNotAlreadyExist(String mobileToken) {
    if (this.userMobileTokenRepository.findByMobileAppToken(mobileToken).isPresent()) {
      throw new ConflictException("Mobile Token already exists");
    }
  }
}
