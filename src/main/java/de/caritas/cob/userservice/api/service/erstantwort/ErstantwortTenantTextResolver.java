package de.caritas.cob.userservice.api.service.erstantwort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualContent;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the Träger-authored Erstantwort texts at send time (ADR-018 §6, §8). Per field: the
 * reader's locale, else the original publication language (named by {@code <lang>__meta.src}), else
 * English, else the Träger's German variant, else nothing — and nothing means the platform default.
 * The editor stores HTML, the bubble renders plain text, so markup is removed here, before the
 * event freezes. Never throws: an unreachable TenantService must not cost anyone their enquiry.
 */
@Slf4j
@Component
public class ErstantwortTenantTextResolver {

  static final String META_SUFFIX = "__meta";
  static final int MAX_DEADLINE_DAYS = 30;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final long DEFAULT_LOOKUP_TIMEOUT_MS = 2_000;
  private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

  /**
   * The lookup runs after the enquiry is persisted, so it gets its own hard bound: Keycloak and
   * TenantService each carry their own, longer HTTP timeouts, which would otherwise add up on the
   * enquiry request. Worker threads are daemons and idle ones die; when all are busy the lookup is
   * skipped (platform defaults) instead of queued.
   */
  private static final ThreadPoolExecutor LOOKUP_EXECUTOR =
      new ThreadPoolExecutor(
          0,
          8,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          task -> {
            var thread =
                new Thread(task, "erstantwort-tenant-lookup-" + THREAD_NUMBER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          });

  private final @NonNull TenantErstantwortContentClient client;
  private final long lookupTimeoutMs;

  @Autowired
  public ErstantwortTenantTextResolver(
      @NonNull TenantErstantwortContentClient client,
      @Value("${erstantwort.tenant-lookup.timeout-ms:" + DEFAULT_LOOKUP_TIMEOUT_MS + "}")
          long lookupTimeoutMs) {
    this.client = client;
    this.lookupTimeoutMs = lookupTimeoutMs > 0 ? lookupTimeoutMs : DEFAULT_LOOKUP_TIMEOUT_MS;
  }

  public ErstantwortTenantTextResolver(@NonNull TenantErstantwortContentClient client) {
    this(client, DEFAULT_LOOKUP_TIMEOUT_MS);
  }

  public ErstantwortTenantTexts resolve(Long tenantId, String locale, boolean informal) {
    if (tenantId == null || tenantId <= 0) {
      return ErstantwortTenantTexts.none();
    }
    try {
      var content = readWithinBound(tenantId);
      return content == null ? ErstantwortTenantTexts.none() : resolve(content, locale, informal);
    } catch (RuntimeException | TimeoutException exception) {
      log.warn(
          "Erstantwort Träger texts unavailable for tenant {}, using platform defaults ({})",
          tenantId,
          rootCauseName(exception));
      return ErstantwortTenantTexts.none();
    }
  }

  /** Class name only: messages and stack traces from the client may carry tenant payloads. */
  private static String rootCauseName(Exception exception) {
    var cause = exception.getCause();
    return (cause != null ? cause : exception).getClass().getSimpleName();
  }

  private MultilingualContent readWithinBound(Long tenantId) throws TimeoutException {
    Future<MultilingualContent> lookup = LOOKUP_EXECUTOR.submit(() -> client.readContent(tenantId));
    try {
      return lookup.get(lookupTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      lookup.cancel(true);
      throw timeout;
    } catch (InterruptedException interrupted) {
      lookup.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading Träger texts", interrupted);
    } catch (ExecutionException failed) {
      throw new IllegalStateException(failed.getCause());
    }
  }

  static ErstantwortTenantTexts resolve(
      MultilingualContent content, String locale, boolean informal) {
    var days = content.getErstantwortResponseDeadlineDays();
    return ErstantwortTenantTexts.builder()
        .greeting(pick(content.getErstantwortGreeting(), locale, informal))
        .whoReadsAlong(pick(content.getErstantwortWhoReadsAlong(), locale, informal))
        .emergencyAddition(pick(content.getErstantwortEmergencyAddition(), locale, informal))
        .freeNotice(pick(content.getErstantwortFreeNotice(), locale, informal))
        .closing(pick(content.getErstantwortClosing(), locale, informal))
        .responseDeadlineDays(days != null && days >= 1 && days <= MAX_DEADLINE_DAYS ? days : null)
        .build();
  }

  /**
   * @return plain text, or {@code null} when no usable text exists in any allowed language.
   */
  static String pick(Map<String, String> texts, String locale, boolean informal) {
    if (texts == null || texts.isEmpty()) {
      return null;
    }
    var candidates = new ArrayList<String>();
    addVariants(candidates, locale, informal);
    var source = sourceLanguage(texts);
    if (source != null) {
      addVariants(candidates, source, informal);
    }
    addVariants(candidates, "en", informal);
    addVariants(candidates, "de", informal);
    for (var key : candidates) {
      var plain = toPlainText(texts.get(key));
      if (!plain.isEmpty()) {
        return plain;
      }
    }
    return null;
  }

  private static void addVariants(ArrayList<String> candidates, String lang, boolean informal) {
    if (lang == null) {
      return;
    }
    var base = lang.endsWith("@informal") ? "de" : lang;
    if ("de".equals(base)) {
      if (informal || lang.endsWith("@informal")) {
        candidates.add("de@informal");
      }
      candidates.add("de");
    } else {
      candidates.add(lang);
    }
  }

  private static String sourceLanguage(Map<String, String> texts) {
    for (var entry : texts.entrySet()) {
      if (entry.getKey() == null || !entry.getKey().endsWith(META_SUFFIX)) {
        continue;
      }
      try {
        JsonNode src = MAPPER.readTree(entry.getValue()).path("src");
        if (src.isTextual() && !src.asText().isBlank()) {
          return src.asText();
        }
      } catch (Exception ignored) {
        // Unreadable metadata only costs the source-language hop, never the send.
      }
    }
    return null;
  }

  /** Block ends and {@code <br>} become line breaks, tags go, entities are decoded, trimmed. */
  static String toPlainText(String html) {
    if (StringUtils.isBlank(html)) {
      return "";
    }
    Document doc = Jsoup.parseBodyFragment(html);
    doc.outputSettings().prettyPrint(false);
    // wholeText() already renders <br> as a line break.
    for (Element block : doc.select("p, div, li, h1, h2, h3, h4, h5, h6, blockquote")) {
      block.after(new TextNode("\n"));
    }
    var lines = new ArrayList<String>();
    for (var line : doc.body().wholeText().replace(' ', ' ').split("\n", -1)) {
      lines.add(line.strip());
    }
    return String.join("\n", lines).replaceAll("\n{3,}", "\n\n").strip();
  }
}
