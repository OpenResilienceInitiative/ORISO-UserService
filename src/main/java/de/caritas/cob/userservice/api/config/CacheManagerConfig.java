package de.caritas.cob.userservice.api.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed cache configuration. Replaces the former Ehcache 2 bridge: the EOL Ehcache 2
 * uber-jar bundled vulnerable Jetty 9.4 and Jackson 2.11 classes (flagged by the image CVE gate)
 * and Spring Boot 4 no longer ships an Ehcache 2 integration.
 */
@Configuration
@EnableCaching
public class CacheManagerConfig {

  public static final String AGENCY_CACHE = "agencyCache";
  public static final String CONSULTING_TYPE_CACHE = "consultingTypeCache";

  public static final String APPLICATION_SETTINGS_CACHE = "applicationSettingsCache";
  public static final String TENANT_CACHE = "tenantCache";
  public static final String TENANT_ADMIN_CACHE = "tenantAdminCache";
  public static final String TOPICS_CACHE = "topicsCache";

  @Value("${cache.agencies.configuration.maxEntriesLocalHeap}")
  private long agenciesMaxEntriesLocalHeap;

  @Value("${cache.agencies.configuration.eternal}")
  private boolean agenciesEternal;

  @Value("${cache.agencies.configuration.timeToIdleSeconds}")
  private long agenciesTimeToIdleSeconds;

  @Value("${cache.agencies.configuration.timeToLiveSeconds}")
  private long agenciesTimeToLiveSeconds;

  @Value("${cache.consulting.type.configuration.maxEntriesLocalHeap}")
  private long consultingTypeMaxEntriesLocalHeap;

  @Value("${cache.consulting.type.configuration.eternal}")
  private boolean consultingTypeEternal;

  @Value("${cache.consulting.type.configuration.timeToIdleSeconds}")
  private long consultingTypeTimeToIdleSeconds;

  @Value("${cache.consulting.type.configuration.timeToLiveSeconds}")
  private long consultingTypeTimeToLiveSeconds;

  @Value("${cache.tenant.configuration.maxEntriesLocalHeap}")
  private long tenantMaxEntriesLocalHeap;

  @Value("${cache.tenant.configuration.eternal}")
  private boolean tenantEternal;

  @Value("${cache.tenant.configuration.timeToIdleSeconds}")
  private long tenantTimeToIdleSeconds;

  @Value("${cache.tenant.configuration.timeToLiveSeconds}")
  private long tenantTimeToLiveSeconds;

  @Value("${cache.topic.configuration.maxEntriesLocalHeap}")
  private long topicMaxEntriesLocalHeap;

  @Value("${cache.topic.configuration.eternal}")
  private boolean topicEternal;

  @Value("${cache.topic.configuration.timeToIdleSeconds}")
  private long topicTimeToIdleSeconds;

  @Value("${cache.topic.configuration.timeToLiveSeconds}")
  private long topicTimeToLiveSeconds;

  @Value("${cache.appsettings.configuration.maxEntriesLocalHeap}")
  private long appSettingsMaxEntriesLocalHeap;

  @Value("${cache.appsettings.configuration.eternal}")
  private boolean appSettingsEternal;

  @Value("${cache.appsettings.configuration.timeToIdleSeconds}")
  private long appSettingsTimeToIdleSeconds;

  @Value("${cache.appsettings.configuration.timeToLiveSeconds}")
  private long appSettingsTimeToLiveSeconds;

  @Bean
  public CacheManager cacheManager() {
    var cacheManager = new SimpleCacheManager();
    cacheManager.setCaches(
        List.of(
            buildCache(
                AGENCY_CACHE,
                agenciesMaxEntriesLocalHeap,
                agenciesEternal,
                agenciesTimeToIdleSeconds,
                agenciesTimeToLiveSeconds),
            buildCache(
                CONSULTING_TYPE_CACHE,
                consultingTypeMaxEntriesLocalHeap,
                consultingTypeEternal,
                consultingTypeTimeToIdleSeconds,
                consultingTypeTimeToLiveSeconds),
            buildCache(
                TENANT_CACHE,
                tenantMaxEntriesLocalHeap,
                tenantEternal,
                tenantTimeToIdleSeconds,
                tenantTimeToLiveSeconds),
            buildCache(
                TENANT_ADMIN_CACHE,
                tenantMaxEntriesLocalHeap,
                tenantEternal,
                tenantTimeToIdleSeconds,
                tenantTimeToLiveSeconds),
            buildCache(
                TOPICS_CACHE,
                topicMaxEntriesLocalHeap,
                topicEternal,
                topicTimeToIdleSeconds,
                topicTimeToLiveSeconds),
            buildCache(
                APPLICATION_SETTINGS_CACHE,
                appSettingsMaxEntriesLocalHeap,
                appSettingsEternal,
                appSettingsTimeToIdleSeconds,
                appSettingsTimeToLiveSeconds)));

    return cacheManager;
  }

  private CaffeineCache buildCache(
      String name,
      long maxEntries,
      boolean eternal,
      long timeToIdleSeconds,
      long timeToLiveSeconds) {
    var builder = Caffeine.newBuilder().maximumSize(maxEntries);
    if (!eternal) {
      if (timeToLiveSeconds > 0) {
        builder.expireAfterWrite(Duration.ofSeconds(timeToLiveSeconds));
      }
      if (timeToIdleSeconds > 0) {
        builder.expireAfterAccess(Duration.ofSeconds(timeToIdleSeconds));
      }
    }
    return new CaffeineCache(name, builder.build(), true);
  }
}
