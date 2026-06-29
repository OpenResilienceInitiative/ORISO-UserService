package de.caritas.cob.userservice.api.config.auth;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.HttpTenantFilter;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.IpPrivacyHeaderFilter;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.StatelessCsrfFilter;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

/** Provides the Keycloak/Spring Security configuration. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final String UUID_PATTERN =
      "\\b[0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\\b[0-9a-f]{12}\\b";
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
                .requestMatchers(csrfSecurityProperties.getWhitelist().getConfigUris())
                .permitAll()
                .requestMatchers(
                    "/users/askers/new",
                    "/conversations/askers/anonymous/new",
                    "/conversations/anonymous/availability",
                    "/users/consultants/{consultantId:" + UUID_PATTERN + "}",
                    "/users/consultants/languages",
                    "/users/magic-link/request",
                    "/users/magic-link/consume",
                    "/users/invitelinks/*/redeem")
                .permitAll()
                .requestMatchers(
                    HttpMethod.POST,
                    "/users/magic-link/request",
                    "/service/users/magic-link/request",
                    "/users/magic-link/consume",
                    "/service/users/magic-link/consume")
                .permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()
                .requestMatchers(
                    RegexRequestMatcher.regexMatcher(
                        HttpMethod.POST, ".*/users/magic-link/(request|consume)$"))
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/conversations/anonymous/{sessionId:[0-9]+}")
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
                .requestMatchers("/users/sessions/askers")
                .permitAll()
                .requestMatchers("/matrix/sync/**", "/service/matrix/sync/**")
                .permitAll()
                .requestMatchers(
                    "/users/email",
                    "/users/mails/messages/new",
                    "/users/drafts",
                    "/users/drafts/**",
                    "/users/event-notifications",
                    "/users/event-notifications/**",
                    "/users/chat/{chatId:[0-9]+}",
                    "/users/chat/e2e",
                    "/users/chat/{chatId:[0-9]+}/join",
                    "/users/chat/{chatId:[0-9]+}/members",
                    "/users/chat/{chatId:[0-9]+}/leave",
                    "/users/chat/{groupId:[\\dA-Za-z-,]+}/assign",
                    "/users/consultants/toggleWalkThrough",
                    "/matrix/**",
                    "/service/matrix/**")
                .hasAnyAuthority(USER_DEFAULT, CONSULTANT_DEFAULT)
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
                    "/users/sessions/{sessionId:[0-9]+}/enquiry/new",
                    "/appointments/sessions/{sessionId:[0-9]+}/enquiry/new",
                    "/users/askers/consultingType/new",
                    "/users/askers/session/new",
                    "/users/account",
                    "/users/mobiletoken",
                    "/users/sessions/{sessionId:[0-9]+}/data")
                .hasAuthority(USER_DEFAULT)
                .requestMatchers(
                    RegexRequestMatcher.regexMatcher(
                        HttpMethod.GET, "(/service)?/users/sessions/room\\?rcGroupIds=.+"))
                .hasAnyAuthority(ANONYMOUS_DEFAULT, USER_DEFAULT, CONSULTANT_DEFAULT)
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
                    "/conversations/consultants/**",
                    "/users/sessions/{sessionId:[0-9]+}/supervisors",
                    "/users/sessions/{sessionId:[0-9]+}/supervisors/{supervisorId:[0-9]+}",
                    "/service/users/sessions/{sessionId:[0-9]+}/supervisors",
                    "/service/users/sessions/{sessionId:[0-9]+}/supervisors/{supervisorId:[0-9]+}")
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
                .requestMatchers(
                    "/users/consultants/import",
                    "/users/askers/import",
                    "/users/askersWithoutSession/import",
                    "/users/sessions/rocketChatGroupId")
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
                    "/users/{chatUserId:[0-9A-Za-z]+}/chat/{chatId:[0-9]+}/ban")
                .hasAuthority(UPDATE_CHAT)
                .requestMatchers(HttpMethod.GET, "/useradmin/tenantadmins/search")
                .hasAnyAuthority(TENANT_ADMIN, USER_ADMIN)
                .requestMatchers("/useradmin/tenantadmins/", "/useradmin/tenantadmins/**")
                .hasAuthority(TENANT_ADMIN)
                .requestMatchers("/useradmin/data/*")
                .hasAnyAuthority(SINGLE_TENANT_ADMIN, RESTRICTED_AGENCY_ADMIN)
                .requestMatchers(HttpMethod.POST, "/useradmin/consultants/")
                .hasAnyAuthority(CONSULTANT_CREATE, TECHNICAL_DEFAULT)
                .requestMatchers(
                    HttpMethod.PUT,
                    "/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}",
                    "/service/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}")
                .hasAnyAuthority(CONSULTANT_UPDATE, TECHNICAL_DEFAULT)
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
                .requestMatchers("/useradmin", "/useradmin/**")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT)
                .requestMatchers("/users/consultants/search")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT)
                .requestMatchers("/users/supervisors/logs", "/service/users/supervisors/logs")
                .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT, TENANT_ADMIN, SINGLE_TENANT_ADMIN)
                .requestMatchers(
                    "/users/inactive-accounts/audit-logs",
                    "/service/users/inactive-accounts/audit-logs")
                .hasAuthority(TECHNICAL_DEFAULT)
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
                .requestMatchers(
                    HttpMethod.DELETE, "/useradmin/consultants/{consultantId:[0-9]+}/delete")
                .hasAnyAuthority(USER_ADMIN, RESTRICTED_AGENCY_ADMIN)
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
    return web ->
        web.ignoring()
            .requestMatchers(
                "/actuator/**", "/users/askers/new", "/matrix/sync/**", "/service/matrix/sync/**");
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
      http.addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
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
