package de.caritas.cob.userservice.api.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.AbstractCacheManager;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheManagerConfig {

  public static final String AGENCY_CACHE = "agencyCache";
  public static final String CONSULTING_TYPE_CACHE = "consultingTypeCache";

  public static final String APPLICATION_SETTINGS_CACHE = "applicationSettingsCache";
  public static final String TENANT_CACHE = "tenantCache";
  public static final String TENANT_ADMIN_CACHE = "tenantAdminCache";
  public static final String TOPICS_CACHE = "topicsCache";

  public static final String ROCKET_CHAT_USER_CACHE = "rocketChatUserCache";

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

  @Value("${cache.rocketchat.configuration.maxEntriesLocalHeap}")
  private long rocketchatCacheMaxEntriesLocalHeap;

  @Value("${cache.rocketchat.configuration.eternal}")
  private boolean rocketchatCacheEternal;

  @Value("${cache.rocketchat.configuration.timeToIdleSeconds}")
  private long rocketchatCacheTimeToIdleSeconds;

  @Value("${cache.rocketchat.configuration.timeToLiveSeconds}")
  private long rocketchatCacheTimeToLiveSeconds;

  @Bean
  public CacheManager cacheManager(net.sf.ehcache.CacheManager ehCacheManager) {
    return new EhCache2CacheManager(ehCacheManager);
  }

  @Bean(destroyMethod = "shutdown")
  public net.sf.ehcache.CacheManager ehCacheManager() {
    var config = new net.sf.ehcache.config.Configuration();
    config.addCache(buildAgencyCacheConfiguration());
    config.addCache(buildConsultingTypeCacheConfiguration());
    config.addCache(buildTenantCacheConfiguration());
    config.addCache(buildTenantAdminCacheConfiguration());
    config.addCache(buildTopicCacheConfiguration());
    config.addCache(buildApplicationSettingsCacheConfiguration());

    config.addCache(buildRocketchatUserCacheConfiguration());
    return net.sf.ehcache.CacheManager.newInstance(config);
  }

  private CacheConfiguration buildAgencyCacheConfiguration() {
    var agencyCacheConfiguration = new CacheConfiguration();
    agencyCacheConfiguration.setName(AGENCY_CACHE);
    agencyCacheConfiguration.setMaxEntriesLocalHeap(agenciesMaxEntriesLocalHeap);
    agencyCacheConfiguration.setEternal(agenciesEternal);
    agencyCacheConfiguration.setTimeToIdleSeconds(agenciesTimeToIdleSeconds);
    agencyCacheConfiguration.setTimeToLiveSeconds(agenciesTimeToLiveSeconds);
    return agencyCacheConfiguration;
  }

  private CacheConfiguration buildConsultingTypeCacheConfiguration() {
    var consultingTypeCacheConfiguration = new CacheConfiguration();
    consultingTypeCacheConfiguration.setName(CONSULTING_TYPE_CACHE);
    consultingTypeCacheConfiguration.setMaxEntriesLocalHeap(consultingTypeMaxEntriesLocalHeap);
    consultingTypeCacheConfiguration.setEternal(consultingTypeEternal);
    consultingTypeCacheConfiguration.setTimeToIdleSeconds(consultingTypeTimeToIdleSeconds);
    consultingTypeCacheConfiguration.setTimeToLiveSeconds(consultingTypeTimeToLiveSeconds);
    return consultingTypeCacheConfiguration;
  }

  private CacheConfiguration buildTenantCacheConfiguration() {
    var tenantCacheConfiguration = new CacheConfiguration();
    tenantCacheConfiguration.setName(TENANT_CACHE);
    tenantCacheConfiguration.setMaxEntriesLocalHeap(tenantMaxEntriesLocalHeap);
    tenantCacheConfiguration.setEternal(tenantEternal);
    tenantCacheConfiguration.setTimeToIdleSeconds(tenantTimeToIdleSeconds);
    tenantCacheConfiguration.setTimeToLiveSeconds(tenantTimeToLiveSeconds);
    return tenantCacheConfiguration;
  }

  private CacheConfiguration buildTenantAdminCacheConfiguration() {
    var tenantCacheConfiguration = new CacheConfiguration();
    tenantCacheConfiguration.setName(TENANT_ADMIN_CACHE);
    tenantCacheConfiguration.setMaxEntriesLocalHeap(tenantMaxEntriesLocalHeap);
    tenantCacheConfiguration.setEternal(tenantEternal);
    tenantCacheConfiguration.setTimeToIdleSeconds(tenantTimeToIdleSeconds);
    tenantCacheConfiguration.setTimeToLiveSeconds(tenantTimeToLiveSeconds);
    return tenantCacheConfiguration;
  }

  private CacheConfiguration buildTopicCacheConfiguration() {
    var topicCacheConfiguration = new CacheConfiguration();
    topicCacheConfiguration.setName(TOPICS_CACHE);
    topicCacheConfiguration.setMaxEntriesLocalHeap(topicMaxEntriesLocalHeap);
    topicCacheConfiguration.setEternal(topicEternal);
    topicCacheConfiguration.setTimeToIdleSeconds(topicTimeToIdleSeconds);
    topicCacheConfiguration.setTimeToLiveSeconds(topicTimeToLiveSeconds);
    return topicCacheConfiguration;
  }

  private CacheConfiguration buildApplicationSettingsCacheConfiguration() {
    var appSettingsCacheConfiguration = new CacheConfiguration();
    appSettingsCacheConfiguration.setName(APPLICATION_SETTINGS_CACHE);
    appSettingsCacheConfiguration.setMaxEntriesLocalHeap(appSettingsMaxEntriesLocalHeap);
    appSettingsCacheConfiguration.setEternal(appSettingsEternal);
    appSettingsCacheConfiguration.setTimeToIdleSeconds(appSettingsTimeToIdleSeconds);
    appSettingsCacheConfiguration.setTimeToLiveSeconds(appSettingsTimeToLiveSeconds);
    return appSettingsCacheConfiguration;
  }

  private CacheConfiguration buildRocketchatUserCacheConfiguration() {
    var rocketchatCacheConfiguration = new CacheConfiguration();
    rocketchatCacheConfiguration.setName(ROCKET_CHAT_USER_CACHE);
    rocketchatCacheConfiguration.setMaxEntriesLocalHeap(rocketchatCacheMaxEntriesLocalHeap);
    rocketchatCacheConfiguration.setEternal(rocketchatCacheEternal);
    rocketchatCacheConfiguration.setTimeToIdleSeconds(rocketchatCacheTimeToIdleSeconds);
    rocketchatCacheConfiguration.setTimeToLiveSeconds(rocketchatCacheTimeToLiveSeconds);
    return rocketchatCacheConfiguration;
  }

  private static final class EhCache2CacheManager extends AbstractCacheManager {

    private final net.sf.ehcache.CacheManager cacheManager;

    private EhCache2CacheManager(net.sf.ehcache.CacheManager cacheManager) {
      this.cacheManager = cacheManager;
    }

    @Override
    protected Collection<? extends Cache> loadCaches() {
      var caches = new ArrayList<Cache>();
      for (String name : cacheManager.getCacheNames()) {
        caches.add(new EhCache2Cache(cacheManager.getEhcache(name)));
      }
      return caches;
    }

    @Override
    protected Cache getMissingCache(String name) {
      Ehcache cache = cacheManager.getEhcache(name);
      return cache == null ? null : new EhCache2Cache(cache);
    }
  }

  private static final class EhCache2Cache extends AbstractValueAdaptingCache {

    private final Ehcache cache;

    private EhCache2Cache(Ehcache cache) {
      super(true);
      this.cache = cache;
    }

    @Override
    public String getName() {
      return cache.getName();
    }

    @Override
    public Object getNativeCache() {
      return cache;
    }

    @Override
    protected Object lookup(Object key) {
      Element element = cache.get(key);
      return element == null ? null : element.getObjectValue();
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
      Element element = cache.get(key);
      if (element != null) {
        @SuppressWarnings("unchecked")
        T value = (T) fromStoreValue(element.getObjectValue());
        return value;
      }
      try {
        T value = valueLoader.call();
        put(key, value);
        return value;
      } catch (Exception ex) {
        throw new ValueRetrievalException(key, valueLoader, ex);
      }
    }

    @Override
    public void put(Object key, Object value) {
      cache.put(new Element(key, toStoreValue(value)));
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
      Element existing = cache.putIfAbsent(new Element(key, toStoreValue(value)));
      return existing == null ? null : toValueWrapper(existing.getObjectValue());
    }

    @Override
    public void evict(Object key) {
      cache.remove(key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
      return cache.remove(key);
    }

    @Override
    public void clear() {
      cache.removeAll();
    }

    @Override
    public boolean invalidate() {
      cache.removeAll();
      return true;
    }
  }
}
