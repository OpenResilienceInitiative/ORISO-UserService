package de.caritas.cob.userservice.api.config.auth;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.HttpTenantFilter;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.IpPrivacyHeaderFilter;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.StatelessCsrfFilter;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

/** Provides the Keycloak/Spring Security configuration. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final String UUID_PATTERN =
      "\\b[0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\\b[0-9a-f]{12}\\b";

  /**
   * A public consultant id is either a UUID or a public slug (lowercase words joined by single
   * hyphens). The negative lookahead keeps the sibling literal routes under {@code
   * /users/consultants/*} (search, absences, import, sessions, languages) out of the permitAll
   * match — they carry their own role rules below and are also seeded as reserved slugs. Only
   * non-capturing groups: Spring's PathPattern rejects capture groups in {var:regex} constraints.
   */
  private static final String PUBLIC_CONSULTANT_ID_PATTERN =
      "(?:"
          + UUID_PATTERN
          + ")|(?:(?!(?:search|absences|import|sessions|languages)$)[a-z]+(?:-[a-z]+)*)";

  public static final String APPOINTMENTS_APPOINTMENT_ID = "/appointments/{appointmentId:";

  private final JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter =
      new JwtGrantedAuthoritiesConverter();

  private final CsrfSecurityProperties csrfSecurityProperties;

  @Value("${multitenancy.enabled}")
  private boolean multitenancy;

  @Value("${keycloak.principal-attribute:preferred_username}")
  private String principalAttribute;

  private final @Nullable HttpTenantFilter tenantFilter;
  private final @Nullable IpPrivacyHeaderFilter ipPrivacyHeaderFilter;

  /**
   * Processes HTTP requests and checks for a valid spring security authentication for the
   * (Keycloak) principal (authorization header).
   */
  public SecurityConfig(
      CsrfSecurityProperties csrfSecurityProperties,
      @Nullable HttpTenantFilter tenantFilter,
      @Nullable IpPrivacyHeaderFilter ipPrivacyHeaderFilter) {
    this.csrfSecurityProperties = csrfSecurityProperties;
    this.tenantFilter = tenantFilter;
    this.ipPrivacyHeaderFilter = ipPrivacyHeaderFilter;
  }

  /**
   * Configure spring security filter chain: disable default Spring Boot CSRF token behavior and add
   * custom {@link StatelessCsrfFilter}, set all sessions to be fully stateless, define necessary
   * Keycloak roles for specific REST API paths.
   */
  @Bean
  @SuppressWarnings("java:S4502") // Disabling CSRF protections is security-sensitive
  public SecurityFilterChain filterChain(
      HttpSecurity http, Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable);
    http.addFilterBefore(new StatelessCsrfFilter(csrfSecurityProperties), CsrfFilter.class);
    if (ipPrivacyHeaderFilter != null) {
      http.addFilterBefore(ipPrivacyHeaderFilter, StatelessCsrfFilter.class);
    }
    enableTenantFilterIfMultitenancyEnabled(http);

    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

    http.authorizeHttpRequests(
        authorize ->
            authorize
                .requestMatchers(
                    "/users/docs",
                    "/users/docs/**",
                    "/v2/api-docs",
                    "/configuration/ui",
                    "/swagger-resources/**",
                    "/configuration/security",
                    "/swagger-ui.html",
                    "/webjars/**")
                .permitAll()
                .requestMatchers(
                    "/users/askers/new",
                    "/conversations/askers/anonymous/new",
                    "/service/conversations/askers/anonymous/new",
                    "/conversations/anonymous/availability",
                    "/service/conversations/anonymous/availability",
                    "/users/consultants/{consultantId:" + PUBLIC_CONSULTANT_ID_PATTERN + "}",
                    "/users/consultants/languages",
                    "/error-reports",
                    "/service/error-reports",
                    "/users/magic-link/request",
                    "/users/magic-link/consume",
                    "/users/password-reset/request",
                    "/users/password-reset/confirm",
                    "/users/invitelinks/*/redeem")
                .permitAll()
                .requestMatchers(
                    HttpMethod.POST,
                    "/users/magic-link/request",
                    "/service/users/magic-link/request",
                    "/users/magic-link/consume",
                    "/service/users/magic-link/consume",
                    "/users/password-reset/request",
                    "/service/users/password-reset/request",
                    "/users/password-reset/confirm",
                    "/service/users/password-reset/confirm")
                .permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()
                .requestMatchers(
                    RegexRequestMatcher.regexMatcher(
                        HttpMethod.POST, ".*/users/magic-link/(request|consume)$"))
                .permitAll()
                // Password-reset request/confirm are already permitted by the exact requestMatchers
                // above (with and without the /service prefix); no broad regex needed.
                .requestMatchers(HttpMethod.GET, "/conversations/anonymous/{sessionId:[0-9]+}")
                .hasAnyAuthority(ANONYMOUS_DEFAULT, USER_DEFAULT)
                .requestMatchers(
                    HttpMethod.GET, "/service/conversations/anonymous/{sessionId:[0-9]+}")
                .hasAnyAuthority(ANONYMOUS_DEFAULT, USER_DEFAULT)
                .requestMatchers("/users/notifications")
                .hasAnyAuthority(NOTIFICATIONS_TECHNICAL)
                .requestMatchers("/users/data")
                .hasAnyAuthority(
                    ANONYMOUS_DEFAULT,
                    USER_DEFAULT,
                    CONSULTANT_DEFAULT,
                    SINGLE_TENANT_ADMIN,
                    TENANT_ADMIN,
                    RESTRICTED_AGENCY_ADMIN)
                .requestMatchers(HttpMethod.GET, APPOINTMENTS_APPOINTMENT_ID + UUID_PATTERN + "}")
                .permitAll()
                .requestMatchers(
                    "/users/email",
                    "/users/drafts",
                    "/users/drafts/**",
                    "/users/event-notifications",
                    "/users/event-notifications/**",
                    "/users/notifications/do-not-disturb",
                    "/users/chat/{chatId:[0-9]+}",
                    "/users/chat/{chatId:[0-9]+}/join",
                    "/users/chat/{chatId:[0-9]+}/members",
                    "/users/chat/{chatId:[0-9]+}/leave",
                    "/users/chat/{matrixRoomId}/assign",
                    "/users/consultants/toggleWalkThrough",
                    "/matrix/**",
                    "/service/matrix/**")
                .hasAnyAuthority(USER_DEFAULT, CONSULTANT_DEFAULT)
                .requestMatchers("/users/tutorials/progress", "/users/tutorials/progress/**")
                .hasAnyAuthority(
                    USER_DEFAULT,
                    CONSULTANT_DEFAULT,
                    SINGLE_TENANT_ADMIN,
                    TENANT_ADMIN,
                    RESTRICTED_AGENCY_ADMIN)
                .requestMatchers("/users/system-notification-emails/test")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT, TENANT_ADMIN, SINGLE_TENANT_ADMIN)
                .requestMatchers("/users/chat/{chatId:[0-9]+}/verify")
                .hasAnyAuthority(CONSULTANT_DEFAULT)
                .requestMatchers("/users/password/change")
                .hasAnyAuthority(
                    USER_DEFAULT,
                    CONSULTANT_DEFAULT,
                    SINGLE_TENANT_ADMIN,
                    TENANT_ADMIN,
                    RESTRICTED_AGENCY_ADMIN)
                .requestMatchers("/users/twoFactorAuth", "/users/2fa/**", "/users/mobile/app/token")
                .hasAnyAuthority(
                    SINGLE_TENANT_ADMIN,
                    TENANT_ADMIN,
                    USER_DEFAULT,
                    CONSULTANT_DEFAULT,
                    RESTRICTED_AGENCY_ADMIN)
                .requestMatchers("/users/statistics/registration")
                .hasAnyAuthority(SINGLE_TENANT_ADMIN, TENANT_ADMIN)
                .requestMatchers(
                    HttpMethod.GET,
                    "/users/statistics/consultant",
                    "/service/users/statistics/consultant")
                .hasAuthority(CONSULTANT_DEFAULT)
                .requestMatchers(
                    "/users/sessions/{sessionId:[0-9]+}/enquiry/new",
                    "/appointments/sessions/{sessionId:[0-9]+}/enquiry/new",
                    "/users/askers/consultingType/new",
                    "/users/askers/session/new",
                    "/users/account",
                    "/users/mobiletoken",
                    "/users/sessions/{sessionId:[0-9]+}/data",
                    "/users/sessions/{sessionId:[0-9]+}/case-handover/{requestId:[0-9]+}/client-consent",
                    "/service/users/sessions/{sessionId:[0-9]+}/case-handover/{requestId:[0-9]+}/client-consent",
                    "/users/sessions/{sessionId:[0-9]+}/supervision/opt-out",
                    "/service/users/sessions/{sessionId:[0-9]+}/supervision/opt-out")
                .hasAuthority(USER_DEFAULT)
                .requestMatchers(
                    RegexRequestMatcher.regexMatcher(
                        HttpMethod.GET, "(/service)?/users/sessions/room\\?matrixRoomIds=.+"))
                .hasAnyAuthority(ANONYMOUS_DEFAULT, USER_DEFAULT, CONSULTANT_DEFAULT)
                .requestMatchers(HttpMethod.GET, "/users/sessions/askers")
                .hasAnyAuthority(ANONYMOUS_DEFAULT, USER_DEFAULT)
                .requestMatchers(
                    HttpMethod.GET,
                    "/users/sessions/room/{sessionId:[0-9]+}",
                    "/service/users/sessions/room/{sessionId:[0-9]+}")
                .hasAnyAuthority(ANONYMOUS_DEFAULT, USER_DEFAULT, CONSULTANT_DEFAULT)
                .requestMatchers(HttpMethod.GET, "/users/chat/room/{chatId:[0-9]+}")
                .hasAnyAuthority(USER_DEFAULT, CONSULTANT_DEFAULT)
                .requestMatchers(
                    "/users/sessions/open",
                    "/users/sessions/consultants/new",
                    "/users/sessions/new/{sessionId:[0-9]+}",
                    "/users/consultants/absences",
                    "/users/sessions/consultants",
                    "/users/sessions/teams",
                    "/conversations/askers/anonymous/{sessionId:[0-9]+}/accept",
                    "/service/conversations/askers/anonymous/{sessionId:[0-9]+}/accept",
                    "/conversations/consultants/**",
                    "/service/conversations/consultants/**",
                    "/users/case-handover/reasons",
                    "/service/users/case-handover/reasons",
                    "/users/case-handover/candidates",
                    "/service/users/case-handover/candidates",
                    "/users/case-handover/batch",
                    "/service/users/case-handover/batch",
                    "/users/chat-series/**",
                    "/service/users/chat-series/**",
                    "/users/sessions/{sessionId:[0-9]+}/case-handover",
                    "/service/users/sessions/{sessionId:[0-9]+}/case-handover",
                    "/users/sessions/{sessionId:[0-9]+}/supervisors",
                    "/users/sessions/{sessionId:[0-9]+}/supervisors/{supervisorId:[0-9]+}",
                    "/service/users/sessions/{sessionId:[0-9]+}/supervisors",
                    "/service/users/sessions/{sessionId:[0-9]+}/supervisors/{supervisorId:[0-9]+}",
                    "/users/sessions/{sessionId:[0-9]+}/team-discussion",
                    "/service/users/sessions/{sessionId:[0-9]+}/team-discussion")
                .hasAuthority(CONSULTANT_DEFAULT)
                .requestMatchers(
                    "/conversations/anonymous/{sessionId:[0-9]+}/finish",
                    "/service/conversations/anonymous/{sessionId:[0-9]+}/finish")
                .hasAnyAuthority(CONSULTANT_DEFAULT, ANONYMOUS_DEFAULT, USER_DEFAULT)
                .requestMatchers(
                    "/users/sessions/{sessionId:[0-9]+}/consultant/{consultantId:[0-9A-Za-z-]+}")
                .hasAnyAuthority(ASSIGN_CONSULTANT_TO_ENQUIRY, ASSIGN_CONSULTANT_TO_SESSION)
                .requestMatchers("/users/consultants")
                .hasAuthority(VIEW_AGENCY_CONSULTANTS)
                .requestMatchers("/users/consultants/import")
                .hasAuthority(TECHNICAL_DEFAULT)
                .requestMatchers("/liveproxy/send")
                .hasAnyAuthority(USER_DEFAULT, CONSULTANT_DEFAULT, ANONYMOUS_DEFAULT)
                .requestMatchers("/users/messages/key")
                .hasAuthority(TECHNICAL_DEFAULT)
                .requestMatchers("/users/chat/new", "/users/chat/v2/new")
                .hasAuthority(CREATE_NEW_CHAT)
                .requestMatchers("/users/chat/{chatId:[0-9]+}/start")
                .hasAuthority(START_CHAT)
                .requestMatchers("/users/chat/{chatId:[0-9]+}/stop")
                .hasAuthority(STOP_CHAT)
                .requestMatchers(
                    "/users/chat/{chatId:[0-9]+}/update",
                    "/users/{matrixUserId}/chat/{chatId:[0-9]+}/ban")
                .hasAuthority(UPDATE_CHAT)
                .requestMatchers(HttpMethod.GET, "/useradmin/tenantadmins/search")
                .hasAnyAuthority(TENANT_ADMIN, USER_ADMIN)
                .requestMatchers(
                    "/useradmin/tenantadmins",
                    "/useradmin/tenantadmins/**",
                    "/service/useradmin/tenantadmins",
                    "/service/useradmin/tenantadmins/**")
                .hasAuthority(TENANT_ADMIN)
                .requestMatchers("/useradmin/data", "/service/useradmin/data")
                .hasAnyAuthority(SINGLE_TENANT_ADMIN, RESTRICTED_AGENCY_ADMIN)
                .requestMatchers(
                    HttpMethod.POST, "/useradmin/consultants", "/service/useradmin/consultants")
                .hasAnyAuthority(CONSULTANT_CREATE, TECHNICAL_DEFAULT)
                .requestMatchers(
                    HttpMethod.PUT,
                    "/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}",
                    "/service/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}")
                .hasAnyAuthority(CONSULTANT_UPDATE, TECHNICAL_DEFAULT)
                // Consultant deletion: restricted agency admins (Beratungsstellen-Admins) may
                // delete consultants of their own agencies; the agency scoping is enforced in
                // ConsultantAdminFacade#markConsultantForDeletion. Must stay above the
                // /useradmin/** catch-all to take effect (first match wins).
                .requestMatchers(
                    HttpMethod.DELETE,
                    "/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}",
                    "/service/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}")
                .hasAnyAuthority(USER_ADMIN, RESTRICTED_AGENCY_ADMIN, TECHNICAL_DEFAULT)
                .requestMatchers(
                    HttpMethod.PUT,
                    "/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}/agencies")
                .hasAnyAuthority(CONSULTANT_UPDATE, TECHNICAL_DEFAULT)
                .requestMatchers(
                    HttpMethod.POST,
                    "/useradmin/askers/{askerId:" + UUID_PATTERN + "}/deletion/pause",
                    "/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}/deletion/pause",
                    "/service/useradmin/askers/{askerId:" + UUID_PATTERN + "}/deletion/pause",
                    "/service/useradmin/consultants/{consultantId:"
                        + UUID_PATTERN
                        + "}/deletion/pause")
                .hasAnyAuthority(
                    USER_ADMIN, RESTRICTED_AGENCY_ADMIN, TENANT_ADMIN, SINGLE_TENANT_ADMIN)
                .requestMatchers(
                    HttpMethod.POST,
                    "/useradmin/admins/{adminId:" + UUID_PATTERN + "}/grant-consultant-identity",
                    "/service/useradmin/admins/{adminId:"
                        + UUID_PATTERN
                        + "}/grant-consultant-identity")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT)
                .requestMatchers(
                    HttpMethod.GET,
                    "/useradmin/users/{userId:" + UUID_PATTERN + "}/identities",
                    "/service/useradmin/users/{userId:" + UUID_PATTERN + "}/identities")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT)
                .requestMatchers(
                    HttpMethod.GET,
                    "/useradmin/statistics/dashboard",
                    "/service/useradmin/statistics/dashboard")
                .hasAnyAuthority(
                    USER_ADMIN, TENANT_ADMIN, SINGLE_TENANT_ADMIN, RESTRICTED_AGENCY_ADMIN)
                .requestMatchers(
                    HttpMethod.GET,
                    "/useradmin/statistics/tutorials",
                    "/service/useradmin/statistics/tutorials")
                .hasAnyAuthority(TENANT_ADMIN, SINGLE_TENANT_ADMIN)
                .requestMatchers(
                    "/useradmin", "/useradmin/**", "/service/useradmin", "/service/useradmin/**")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT)
                .requestMatchers("/users/consultants/search")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT)
                .requestMatchers("/users/supervisors/logs", "/service/users/supervisors/logs")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT, TENANT_ADMIN, SINGLE_TENANT_ADMIN)
                .requestMatchers(
                    "/users/case-handover/reason-policies",
                    "/service/users/case-handover/reason-policies")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT, TENANT_ADMIN, SINGLE_TENANT_ADMIN)
                .requestMatchers("/users/case-handover/logs", "/service/users/case-handover/logs")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT, TENANT_ADMIN, SINGLE_TENANT_ADMIN)
                .requestMatchers(
                    "/users/inactive-accounts/audit-logs",
                    "/service/users/inactive-accounts/audit-logs")
                .access(this::canAccessInactiveAccountAuditLogs)
                .requestMatchers(
                    "/users/consultants/sessions/{sessionId:[0-9]+}",
                    "/users/sessions/{sessionId:[0-9]+}/archive",
                    "/users/sessions/{sessionId:[0-9]+}")
                .hasAnyAuthority(CONSULTANT_DEFAULT)
                .requestMatchers("/appointments")
                .hasAnyAuthority(CONSULTANT_DEFAULT, TECHNICAL_DEFAULT)
                .requestMatchers("/appointments/booking/{id:[0-9]+}")
                .hasAnyAuthority(CONSULTANT_DEFAULT, TECHNICAL_DEFAULT)
                .requestMatchers(HttpMethod.PUT, APPOINTMENTS_APPOINTMENT_ID + UUID_PATTERN + "}")
                .hasAuthority(CONSULTANT_DEFAULT)
                .requestMatchers(
                    HttpMethod.DELETE, APPOINTMENTS_APPOINTMENT_ID + UUID_PATTERN + "}")
                .hasAuthority(CONSULTANT_DEFAULT)
                .requestMatchers(
                    "/users/sessions/{sessionId:[0-9]+}/dearchive", "/users/mails/reassignment")
                .hasAnyAuthority(USER_DEFAULT, CONSULTANT_DEFAULT)
                .requestMatchers("/userstatistics", "/userstatistics/**")
                .hasAuthority(TECHNICAL_DEFAULT)
                .requestMatchers(HttpMethod.GET, "/actuator/health")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health/*")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/actuator/loggers")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/actuator/loggers/*")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/users/availability/{username}")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/users/{username}")
                .hasAuthority(TECHNICAL_DEFAULT)
                .anyRequest()
                .denyAll());

    http.oauth2ResourceServer(
        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
    return http.build();
  }

  @Bean
  public WebSecurityCustomizer webSecurityCustomizer() {
    return web -> web.ignoring().requestMatchers("/actuator/**", "/users/availability/**");
  }

  @Bean
  public JwtDecoder jwtDecoder(IdentityConfig identityConfig) {
    return NimbusJwtDecoder.withJwkSetUri(identityConfig.getOpenIdConnectUrl("certs")).build();
  }

  @Bean
  public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter(
      RoleAuthorizationAuthorityMapper authorityMapper) {
    return jwt -> {
      Collection<GrantedAuthority> authorities = grantedAuthorities(jwt, authorityMapper);
      return new JwtAuthenticationToken(jwt, authorities, principalName(jwt));
    };
  }

  /**
   * Adds additional filter for tenant feature if enabled that sets tenant_id into current thread.
   *
   * @param http - http security
   */
  private void enableTenantFilterIfMultitenancyEnabled(HttpSecurity http) {
    if (multitenancy && tenantFilter != null) {
      http.addFilterAfter(tenantFilter, SecurityContextHolderAwareRequestFilter.class);
    }
  }

  private Collection<GrantedAuthority> grantedAuthorities(
      Jwt jwt, RoleAuthorizationAuthorityMapper authorityMapper) {
    var authorities = new HashSet<GrantedAuthority>();
    Collection<GrantedAuthority> jwtAuthorities = jwtGrantedAuthoritiesConverter.convert(jwt);
    if (jwtAuthorities != null) {
      authorities.addAll(jwtAuthorities);
    }

    Set<GrantedAuthority> roleAuthorities =
        extractKeycloakRoles(jwt).stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toSet());
    authorities.addAll(authorityMapper.mapAuthorities(roleAuthorities));
    return authorities;
  }

  private AuthorizationDecision canAccessInactiveAccountAuditLogs(
      Supplier<? extends Authentication> authenticationSupplier,
      RequestAuthorizationContext requestContext) {
    Authentication authentication = authenticationSupplier.get();
    boolean isTechnicalAccount =
        authentication.getAuthorities().stream()
            .anyMatch(authority -> TECHNICAL_DEFAULT.equals(authority.getAuthority()));

    if (isTechnicalAccount) {
      return new AuthorizationDecision(true);
    }

    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
      return new AuthorizationDecision(false);
    }

    Jwt jwt = jwtAuthentication.getToken();
    Set<String> roles = extractKeycloakRoles(jwt);
    boolean hasPlatformAdminRoles =
        roles.contains(UserRole.AGENCY_ADMIN.getValue())
            && roles.contains(UserRole.TENANT_ADMIN.getValue());

    return new AuthorizationDecision(hasPlatformAdminRoles && isPlatformTenant(jwt));
  }

  private boolean isPlatformTenant(Jwt jwt) {
    Object tenantId = jwt.getClaims().get("tenantId");
    if (tenantId instanceof Number number) {
      try {
        return new BigDecimal(number.toString()).compareTo(BigDecimal.ZERO) == 0;
      } catch (NumberFormatException ignored) {
        return false;
      }
    }
    return tenantId != null && "0".equals(tenantId.toString());
  }

  @SuppressWarnings("unchecked")
  private Set<String> extractKeycloakRoles(Jwt jwt) {
    var roles = new HashSet<String>();
    Object realmAccess = jwt.getClaims().get("realm_access");
    if (realmAccess instanceof Map<?, ?> realmAccessMap) {
      addRoles(roles, realmAccessMap.get("roles"));
    }

    Object resourceAccess = jwt.getClaims().get("resource_access");
    if (resourceAccess instanceof Map<?, ?> resourceAccessMap) {
      resourceAccessMap.values().stream()
          .filter(Map.class::isInstance)
          .map(Map.class::cast)
          .forEach(clientAccess -> addRoles(roles, clientAccess.get("roles")));
    }
    return roles;
  }

  private void addRoles(Set<String> roles, Object rolesClaim) {
    if (rolesClaim instanceof Collection<?> roleCollection) {
      roleCollection.stream().filter(Objects::nonNull).map(Object::toString).forEach(roles::add);
    }
  }

  private String principalName(Jwt jwt) {
    String principal = jwt.getClaimAsString(principalAttribute);
    return principal == null ? jwt.getSubject() : principal;
  }
}
