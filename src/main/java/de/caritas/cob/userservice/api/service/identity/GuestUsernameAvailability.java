package de.caritas.cob.userservice.api.service.identity;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Read-only check of the global guest username namespace; does not reserve or allocate names. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuestUsernameAvailability {
  private final UserRepository userRepository;
  private final ConsultantRepository consultantRepository;
  private final IdentityUsernameAvailability identityUsernameAvailability;
  private final MatrixUserClient matrixUserClient;
  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  /** Returns true only when every backing system confirms the validated username is free. */
  public boolean isAvailable(String username) {
    try {
      return !existsInDatabase(username)
          && identityUsernameAvailability.isUsernameAvailable(username)
          && !matrixUserClient.userExistsStrict(usernameTranscoder.encodeUsername(username))
          && !matrixUserClient.userExistsStrict(username);
    } catch (ServiceUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      // Dependency errors may contain credentials. Do not expose or log their messages.
      log.warn("Guest username availability failed, cause type {}", exception.getClass().getName());
      throw new ServiceUnavailableException("Guest username availability could not be determined");
    }
  }

  private boolean existsInDatabase(String username) {
    var caller = TenantContext.getCurrentTenantData();
    try {
      TenantContext.setCurrentTenantData(new TenantData(TenantContext.TECHNICAL_TENANT_ID, null));
      return !userRepository
              .findAllByUsernameInAndDeleteDateIsNullOrderByCreateDateAsc(
                  List.of(
                      usernameTranscoder.encodeUsername(username),
                      usernameTranscoder.decodeUsername(username)))
              .isEmpty()
          || consultantRepository.findByUsernameAndDeleteDateIsNull(username).isPresent()
          || consultantRepository
              .findByUsernameAndDeleteDateIsNull(usernameTranscoder.encodeUsername(username))
              .isPresent();
    } finally {
      if (caller == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenantData(caller);
      }
    }
  }
}
