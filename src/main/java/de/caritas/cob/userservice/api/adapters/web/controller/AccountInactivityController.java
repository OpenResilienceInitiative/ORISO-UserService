package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.security.Principal;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class AccountInactivityController {
  private final AccountInactivityService lifecycle;
  private final Clock clock;
  private final org.springframework.beans.factory.ObjectProvider<
          de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityBootstrap>
      bootstrap;

  @GetMapping({
    "/useradmin/account-inactivity/inventory",
    "/service/useradmin/account-inactivity/inventory"
  })
  public Map<String, Object> inventory(
      Principal principal,
      @RequestParam(defaultValue = "") String after,
      @RequestParam(defaultValue = "200") int limit) {
    requirePlatformAdmin(principal);
    if (limit < 1 || limit > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var source = bootstrap.getObject();
    return Map.of("inventory", source.report(), "issues", source.issues(after, limit));
  }

  @GetMapping({"/users/account-inactivity", "/service/users/account-inactivity"})
  public AccountInactivityService.Snapshot own(Principal principal) {
    return lifecycle
        .snapshot(token(principal).getToken().getSubject())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @PostMapping({"/users/account-inactivity/activity", "/service/users/account-inactivity/activity"})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void activity(Principal principal) {
    if (!lifecycle.admit(token(principal).getToken().getSubject(), clock.instant()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  /** Internal ingress access check; anonymous routes keep their own service authorization. */
  @GetMapping({"/users/account-inactivity/access", "/service/users/account-inactivity/access"})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void access() {}

  @GetMapping({
    "/useradmin/account-inactivity/candidates",
    "/service/useradmin/account-inactivity/candidates"
  })
  public List<AccountInactivityService.Candidate> candidates(
      Principal principal,
      @RequestParam(defaultValue = "") String after,
      @RequestParam(defaultValue = "200") int limit) {
    requirePlatformAdmin(principal);
    if (limit < 1 || limit > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return lifecycle.candidateReport(after, limit);
  }

  @GetMapping({
    "/useradmin/account-inactivity/{identityId}",
    "/service/useradmin/account-inactivity/{identityId}"
  })
  public Map<String, Object> report(Principal principal, @PathVariable String identityId) {
    requirePlatformAdmin(principal);
    return Map.of(
        "account",
        lifecycle
            .snapshot(identityId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)),
        "attempts",
        lifecycle.journal(identityId));
  }

  @PostMapping({
    "/useradmin/account-inactivity/{identityId}/reactivate",
    "/service/useradmin/account-inactivity/{identityId}/reactivate"
  })
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reactivate(Principal principal, @PathVariable String identityId) {
    requirePlatformAdmin(principal);
    try {
      if (!lifecycle.reactivate(identityId))
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    } catch (IllegalStateException invalidState) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    } catch (java.util.NoSuchElementException absent) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private JwtAuthenticationToken token(Principal principal) {
    if (principal instanceof JwtAuthenticationToken jwt) return jwt;
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }

  private void requirePlatformAdmin(Principal principal) {
    var jwt = token(principal).getToken();
    var realm = jwt.getClaim("realm_access");
    Object tenantId = jwt.getClaim("tenantId");
    boolean platform = "0".equals(String.valueOf(tenantId));
    if (!(platform
        && realm instanceof Map<?, ?> claims
        && claims.get("roles") instanceof Collection<?> roles
        && roles.contains("agency-admin")
        && roles.contains("tenant-admin"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}
