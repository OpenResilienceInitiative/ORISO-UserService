package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualContent;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ErstantwortTenantTextResolverTest {

  private static final String META_DE = "{\"mt\":true,\"src\":\"de\",\"at\":\"2026-10-01\"}";

  private static MultilingualContent greeting(Map<String, String> texts) {
    return new MultilingualContent().erstantwortGreeting(texts);
  }

  private static String resolveGreeting(Map<String, String> texts, String locale, boolean inf) {
    return ErstantwortTenantTextResolver.resolve(greeting(texts), locale, inf).getGreeting();
  }

  @Test
  void readerLocaleTextWins() {
    var texts = Map.of("de", "<p>Hallo</p>", "en", "<p>Hello</p>", "en__meta", META_DE);
    assertThat(resolveGreeting(texts, "en", false)).isEqualTo("Hello");
  }

  @Test
  void fallsBackToTheSourceLanguageNamedInTheMeta() {
    var texts = Map.of("fr", "<p>Bonjour</p>", "fr__meta", "{\"src\":\"fr\"}", "en", "<p>Hi</p>");
    // reader speaks Turkish, nothing there: the original (fr) wins over the translation (en)
    assertThat(resolveGreeting(texts, "tr", false)).isEqualTo("Bonjour");
  }

  @Test
  void englishComesAfterTheOriginalAndBeforeTheGermanDefault() {
    // original (fr) missing for this field, reader speaks Turkish: English beats German
    var texts = Map.of("de", "<p>Hallo</p>", "en", "<p>Hello</p>", "fr__meta", "{\"src\":\"fr\"}");
    assertThat(resolveGreeting(texts, "tr", false)).isEqualTo("Hello");
    // without any English the German variant still catches
    assertThat(resolveGreeting(Map.of("de", "<p>Hallo</p>"), "tr", false)).isEqualTo("Hallo");
    // the original still wins over English
    var withOriginal = Map.of("de", "<p>Hallo</p>", "en", "<p>Hello</p>", "de__meta", META_DE);
    assertThat(resolveGreeting(withOriginal, "tr", false)).isEqualTo("Hallo");
  }

  @Test
  void fallsBackToTheGermanVariantMatchingLanguageFormal() {
    var texts = Map.of("de", "<p>Guten Tag</p>", "de@informal", "<p>Hallo</p>");
    assertThat(resolveGreeting(texts, "ru", true)).isEqualTo("Hallo");
    assertThat(resolveGreeting(texts, "ru", false)).isEqualTo("Guten Tag");
    assertThat(resolveGreeting(texts, null, true)).isEqualTo("Hallo");
  }

  @Test
  void blankReaderTextFallsThroughToTheOriginal() {
    var texts = Map.of("en", "<p> </p>", "de", "<p>Hallo</p>");
    assertThat(resolveGreeting(texts, "en", false)).isEqualTo("Hallo");
  }

  @Test
  void nothingUsableMeansPlatformDefault() {
    assertThat(resolveGreeting(Map.of("en", "<p></p>"), "de", false)).isNull();
    assertThat(resolveGreeting(Map.of("de__meta", META_DE), "de", false)).isNull();
    assertThat(resolveGreeting(null, "de", false)).isNull();
    assertThat(resolveGreeting(Map.of(), "de", false)).isNull();
  }

  @Test
  void strippingProducesPlainTextOnly() {
    var html = "<p>Zeile&nbsp;eins &amp; <b>zwei</b></p><p>Drei<br>vier &lt;ok&gt;</p>";
    assertThat(ErstantwortTenantTextResolver.toPlainText(html))
        .isEqualTo("Zeile eins & zwei\nDrei\nvier <ok>");
    assertThat(ErstantwortTenantTextResolver.toPlainText("<script>x()</script><p>a</p>"))
        .doesNotContain("<");
    assertThat(ErstantwortTenantTextResolver.toPlainText(null)).isEmpty();
  }

  @Test
  void deadlineOnlyWhenOneToThirty() {
    for (var days : new Integer[] {null, 0, -1, 31}) {
      var content = new MultilingualContent().erstantwortResponseDeadlineDays(days);
      assertThat(
              ErstantwortTenantTextResolver.resolve(content, "de", false).getResponseDeadlineDays())
          .isNull();
    }
    for (var days : new Integer[] {1, 5, 30}) {
      var content = new MultilingualContent().erstantwortResponseDeadlineDays(days);
      assertThat(
              ErstantwortTenantTextResolver.resolve(content, "de", false).getResponseDeadlineDays())
          .isEqualTo(days);
    }
  }

  @Test
  void allFiveFieldsAreResolved() {
    var content =
        new MultilingualContent()
            .erstantwortGreeting(Map.of("de", "<p>g</p>"))
            .erstantwortWhoReadsAlong(Map.of("de", "<p>w</p>"))
            .erstantwortEmergencyAddition(Map.of("de", "<p>e</p>"))
            .erstantwortFreeNotice(Map.of("de", "<p>f</p>"))
            .erstantwortClosing(Map.of("de", "<p>c</p>"));
    var texts = ErstantwortTenantTextResolver.resolve(content, "de", false);
    assertThat(texts.getGreeting()).isEqualTo("g");
    assertThat(texts.getWhoReadsAlong()).isEqualTo("w");
    assertThat(texts.getEmergencyAddition()).isEqualTo("e");
    assertThat(texts.getFreeNotice()).isEqualTo("f");
    assertThat(texts.getClosing()).isEqualTo("c");
  }

  @Test
  void tenantServiceDownFallsBackWithoutThrowing() {
    var client = mock(TenantErstantwortContentClient.class);
    when(client.readContent(anyLong())).thenThrow(new IllegalStateException("down"));
    var texts = new ErstantwortTenantTextResolver(client).resolve(1L, "de", false);
    assertThat(texts).isEqualTo(ErstantwortTenantTexts.none());
  }

  @Test
  void failureLogNamesTheRootCauseWithoutMessageOrStackTrace() {
    var logger = (Logger) LoggerFactory.getLogger(ErstantwortTenantTextResolver.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var client = mock(TenantErstantwortContentClient.class);
      when(client.readContent(anyLong())).thenThrow(new IllegalArgumentException("secret-detail"));
      new ErstantwortTenantTextResolver(client).resolve(3L, "de", false);
      assertThat(appender.list).hasSize(1);
      var event = appender.list.get(0);
      assertThat(event.getFormattedMessage())
          .contains("tenant 3")
          .contains("IllegalArgumentException")
          .doesNotContain("secret-detail");
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void saturatedLookupPoolFallsBackWithoutThrowing() throws Exception {
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(8);
    var client = mock(TenantErstantwortContentClient.class);
    when(client.readContent(anyLong()))
        .thenAnswer(
            call -> {
              started.countDown();
              release.await(30, TimeUnit.SECONDS);
              return null;
            });
    var blockers = new ArrayList<Thread>();
    try {
      for (int i = 0; i < 8; i++) {
        var blocker =
            new Thread(
                () -> new ErstantwortTenantTextResolver(client, 20_000).resolve(1L, "de", false));
        blocker.setDaemon(true);
        blocker.start();
        blockers.add(blocker);
      }
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      var texts = new ErstantwortTenantTextResolver(client, 20_000).resolve(2L, "de", false);
      assertThat(texts.getGreeting()).isNull();
      assertThat(texts.getResponseDeadlineDays()).isNull();
    } finally {
      release.countDown();
      for (var blocker : blockers) {
        blocker.join(5_000);
      }
    }
  }

  @Test
  void noTenantMeansNoLookup() {
    var client = mock(TenantErstantwortContentClient.class);
    assertThat(
            new ErstantwortTenantTextResolver(client)
                .resolve((Long) null, "de", false)
                .getClosing())
        .isNull();
    verifyNoInteractions(client);
  }

  @Test
  void emptyTenantContentFallsBack() {
    var client = mock(TenantErstantwortContentClient.class);
    when(client.readContent(5L)).thenReturn(null);
    assertThat(new ErstantwortTenantTextResolver(client).resolve(5L, "de", false).getGreeting())
        .isNull();
  }

  @Test
  void slowTenantServiceFallsBackWithinTheBound() {
    var client = mock(TenantErstantwortContentClient.class);
    when(client.readContent(anyLong()))
        .thenAnswer(
            call -> {
              Thread.sleep(5_000);
              return new MultilingualContent().erstantwortGreeting(Map.of("de", "<p>late</p>"));
            });
    var resolver = new ErstantwortTenantTextResolver(client, 200);
    var started = System.nanoTime();
    var texts = resolver.resolve(1L, "de", false);
    var elapsedMs = (System.nanoTime() - started) / 1_000_000;
    assertThat(texts.getGreeting()).isNull();
    assertThat(elapsedMs).isLessThan(1_000);
  }

  @Test
  void fastTenantServiceStillResolvesUnderTheBound() {
    var client = mock(TenantErstantwortContentClient.class);
    when(client.readContent(7L))
        .thenReturn(new MultilingualContent().erstantwortGreeting(Map.of("de", "<p>Hallo</p>")));
    assertThat(
            new ErstantwortTenantTextResolver(client, 2_000).resolve(7L, "de", false).getGreeting())
        .isEqualTo("Hallo");
  }
}
