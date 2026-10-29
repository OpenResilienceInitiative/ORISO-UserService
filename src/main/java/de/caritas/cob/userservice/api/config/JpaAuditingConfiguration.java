package de.caritas.cob.userservice.api.config;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "utcAuditingDateTimeProvider")
public class JpaAuditingConfiguration {
  /** SQL datetime fields and the signed lifecycle snapshots both represent UTC wall time. */
  @Bean
  DateTimeProvider utcAuditingDateTimeProvider() {
    return () -> Optional.of(LocalDateTime.now(ZoneOffset.UTC));
  }
}
