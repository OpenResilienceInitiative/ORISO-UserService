package de.caritas.cob.userservice.api.config.auth;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.HttpTenantFilter;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.StatelessCsrfFilter;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Provides the Keycloak/Spring Security configuration. */
@Configuration
public class SecurityConfig {

  private static final String UUID_PATTERN =
      "\\b[0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\\b[0-9a-f]{12}\\b";
  public static final String APPOINTMENTS_APPOINTMENT_ID = "/appointments/{appointmentId:";

  private final CsrfSecurityProperties csrfSecurityProperties;

  @Value("${multitenancy.enabled}")
  private boolean multitenancy;

  private HttpTenantFilter tenantFilter;

  /**
   * Processes HTTP requests and checks for a valid spring security authentication for the
   * (Keycloak) principal (authorization header).
   */
  public SecurityConfig(
      CsrfSecurityProperties csrfSecurityProperties,
      @Nullable HttpTenantFilter tenantFilter) {
    this.csrfSecurityProperties = csrfSecurityProperties;
    this.tenantFilter = tenantFilter;
  }

  /**
   * Configure spring security filter chain: disable default Spring Boot CSRF token behavior and add
   * custom {@link StatelessCsrfFilter}, set all sessions to be fully stateless, define necessary
   * Keycloak roles for specific REST API paths
   */
  @Bean
  @SuppressWarnings("java:S4502") // Disabling CSRF protections is security-sensitive
  public SecurityFilterChain filterChain(
      HttpSecurity http, RoleAuthorizationAuthorityMapper authorityMapper) throws Exception {
    var httpSecurity =
        http.csrf(csrf -> csrf.disable())
            .addFilterBefore(new StatelessCsrfFilter(csrfSecurityProperties), CsrfFilter.class);

    if (multitenancy && tenantFilter != null) {
      httpSecurity = httpSecurity.addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
    }

    httpSecurity
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
        .requestMatchers(csrfSecurityProperties.getWhitelist().getConfigUris())
        .permitAll()
        .requestMatchers(
            "/users/askers/new",
            "/conversations/askers/anonymous/new",
            "/users/consultants/{consultantId:" + UUID_PATTERN + "}",
            "/users/consultants/languages")
        .permitAll()
        .requestMatchers(HttpMethod.GET, "/conversations/anonymous/{sessionId:[0-9]+}")
        .hasAnyAuthority(ANONYMOUS_DEFAULT)
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
        .requestMatchers("/matrix/sync/**")
        .permitAll()
        .requestMatchers(
            "/users/email",
            "/users/mails/messages/new",
            "/users/chat/{chatId:[0-9]+}",
            "/users/chat/e2e",
            "/users/chat/{chatId:[0-9]+}/join",
            "/users/chat/{chatId:[0-9]+}/members",
            "/users/chat/{chatId:[0-9]+}/leave",
            "/users/chat/{groupId:[\\dA-Za-z-,]+}/assign",
            "/users/consultants/toggleWalkThrough",
            "/matrix/**")
        .hasAnyAuthority(USER_DEFAULT, CONSULTANT_DEFAULT)
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
        .requestMatchers(new RegexRequestMatcher("/users/sessions/room.*", HttpMethod.GET.name()))
        .hasAnyAuthority(ANONYMOUS_DEFAULT, USER_DEFAULT, CONSULTANT_DEFAULT)
        .requestMatchers(HttpMethod.GET, "/users/sessions/room/{sessionId:[0-9]+}")
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
        .requestMatchers("/conversations/anonymous/{sessionId:[0-9]+}/finish")
        .hasAnyAuthority(CONSULTANT_DEFAULT, ANONYMOUS_DEFAULT)
        .requestMatchers("/users/sessions/{sessionId:[0-9]+}/consultant/{consultantId:[0-9A-Za-z-]+}")
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
        .requestMatchers("/useradmin/tenantadmins/", "/useradmin/tenantadmins/**")
        .hasAuthority(TENANT_ADMIN)
        .requestMatchers("/useradmin/data/*")
        .hasAnyAuthority(SINGLE_TENANT_ADMIN, RESTRICTED_AGENCY_ADMIN)
        .requestMatchers(HttpMethod.POST, "/useradmin/consultants/")
        .hasAnyAuthority(CONSULTANT_CREATE, TECHNICAL_DEFAULT)
        .requestMatchers(HttpMethod.PUT, "/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}")
        .hasAnyAuthority(CONSULTANT_UPDATE, TECHNICAL_DEFAULT)
        .requestMatchers(
            HttpMethod.PUT, "/useradmin/consultants/{consultantId:" + UUID_PATTERN + "}/agencies")
        .hasAnyAuthority(CONSULTANT_UPDATE, TECHNICAL_DEFAULT)
        .requestMatchers("/useradmin", "/useradmin/**")
        .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT)
        .requestMatchers("/users/consultants/search")
        .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT)
        .requestMatchers("/users/supervisors/logs", "/service/users/supervisors/logs")
        .hasAnyAuthority(USER_ADMIN, TECHNICAL_DEFAULT, TENANT_ADMIN, SINGLE_TENANT_ADMIN)
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
        .requestMatchers(HttpMethod.DELETE, APPOINTMENTS_APPOINTMENT_ID + UUID_PATTERN + "}")
        .hasAuthority(CONSULTANT_DEFAULT)
        .requestMatchers("/users/sessions/{sessionId:[0-9]+}/dearchive", "/users/mails/reassignment")
        .hasAnyAuthority(USER_DEFAULT, CONSULTANT_DEFAULT)
        .requestMatchers("/userstatistics", "/userstatistics/**")
        .permitAll()
        .requestMatchers(HttpMethod.DELETE, "/useradmin/consultants/{consultantId:[0-9]+}/delete")
        .hasAnyAuthority(USER_ADMIN, RESTRICTED_AGENCY_ADMIN)
        .requestMatchers(HttpMethod.GET, "/actuator/health")
        .permitAll()
        .requestMatchers(HttpMethod.GET, "/actuator/health/*")
        .permitAll()
        .requestMatchers(HttpMethod.POST, "/actuator/loggers")
        .permitAll()
        .requestMatchers(HttpMethod.POST, "/actuator/loggers/*")
        .permitAll()
        .requestMatchers(HttpMethod.GET, "/users/{username}")
        .permitAll()
        .anyRequest()
        .denyAll());

    httpSecurity.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(authorityMapper))));

    return httpSecurity.build();
  }

  private JwtAuthenticationConverter jwtAuthenticationConverter(RoleAuthorizationAuthorityMapper authorityMapper) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setPrincipalClaimName("preferred_username");
    converter.setJwtGrantedAuthoritiesConverter(jwt -> authorityMapper.mapAuthorities(extractRoleAuthorities(jwt)));
    return converter;
  }

  private Collection<? extends GrantedAuthority> extractRoleAuthorities(Jwt jwt) {
    List<GrantedAuthority> authorities = new ArrayList<>();

    Object realmAccess = jwt.getClaims().get("realm_access");
    if (realmAccess instanceof Map<?, ?> realmAccessMap) {
      Object roles = realmAccessMap.get("roles");
      if (roles instanceof Collection<?> rolesCollection) {
        rolesCollection.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(SimpleGrantedAuthority::new)
            .forEach(authorities::add);
      }
    }

    Object resourceAccess = jwt.getClaims().get("resource_access");
    if (resourceAccess instanceof Map<?, ?> resourceAccessMap) {
      for (Object client : resourceAccessMap.values()) {
        if (client instanceof Map<?, ?> clientMap) {
          Object roles = clientMap.get("roles");
          if (roles instanceof Collection<?> rolesCollection) {
            rolesCollection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
          }
        }
      }
    }

    return authorities;
  }
}
