package de.caritas.cob.userservice.api.config;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.core.GrantedAuthorityDefaults;

@Configuration
@EnableMethodSecurity(securedEnabled = true, prePostEnabled = true)
public class GlobalMethodSecurityConfig {

  @Bean
  public GrantedAuthorityDefaults grantedAuthorityDefaults() {
    return new GrantedAuthorityDefaults(AuthorityValue.PREFIX);
  }
}
