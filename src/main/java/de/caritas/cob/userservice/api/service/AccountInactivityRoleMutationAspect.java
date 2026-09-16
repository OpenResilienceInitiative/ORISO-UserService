package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Serializes application role writes with the inactivity claim for the same identity. */
@Aspect
@Component
@RequiredArgsConstructor
public class AccountInactivityRoleMutationAspect {
  private final AccountInactivityService lifecycle;

  @Around(
      "execution(* de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater+.ensureRoles(..))"
          + " || execution(* de.caritas.cob.userservice.api.port.out.IdentityClient+.updateRole(..))"
          + " || execution(* de.caritas.cob.userservice.api.port.out.IdentityClient+.updateUserRole(..))"
          + " || execution(* de.caritas.cob.userservice.api.port.out.IdentityClient+.removeRoleIfPresent(..))")
  public Object protect(ProceedingJoinPoint call) {
    String identityId = (String) call.getArgs()[0];
    boolean[] entered = {false};
    try {
      lifecycle.withRoleMutation(
          identityId,
          () -> {
            entered[0] = true;
            try {
              call.proceed();
            } catch (RuntimeException | Error failure) {
              throw failure;
            } catch (Throwable failure) {
              throw new IllegalStateException("Role mutation failed", failure);
            }
          });
    } catch (IllegalStateException unavailable) {
      if (entered[0]) throw unavailable;
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Account lifecycle action in progress");
    }
    return null;
  }
}
