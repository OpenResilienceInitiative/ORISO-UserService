package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Rot guard for the Matrix identifier redaction in {@link MatrixSynapseService}.
 *
 * <p>A one-off cleanup of log lines decays: the next person debugging a failed provisioning puts
 * the plain name back "just for now". This test reads the adapter's own source and fails when any
 * log statement or exception message in it passes a raw Matrix identifier (a username, a localpart,
 * a full Matrix user id or a display name) instead of the pseudonym from {@link
 * MatrixIdentifierRedactor}.
 *
 * <p>Deliberately source-based rather than behaviour-based: a behavioural test can only cover the
 * paths it exercises, while a counselling platform needs the guarantee on every path, including the
 * error branches that are hard to provoke. {@link
 * #everyLogAndExceptionSiteInTheAdapterIsRedacted()} is the net; the behavioural assertions below
 * prove the net is over something real.
 */
class MatrixSynapseServiceIdentifierRedactionTest {

  private static final Path ADAPTER_SOURCE =
      Path.of(
          "src/main/java/de/caritas/cob/userservice/api/adapters/matrix/MatrixSynapseService.java");

  /**
   * Identifier fragments that name a person on this platform. Matched case-insensitively against
   * every identifier appearing as an argument of a logging call or an exception constructor.
   */
  private static final List<String> PERSONAL_IDENTIFIER_FRAGMENTS =
      List.of(
          "username",
          "localpart",
          "displayname",
          "matrixuserid",
          "matrixid",
          "principal",
          "getuserid");

  private static final Pattern LOGGING_CALL =
      Pattern.compile("\\blog\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\(");

  private static final Pattern EXCEPTION_CONSTRUCTOR =
      Pattern.compile("\\bnew\\s+(?:[A-Za-z0-9_.]*\\.)?[A-Za-z0-9_]*Exception\\s*\\(");

  private static final Pattern REDACTION_CALL =
      Pattern.compile("\\b(?:redactor|MatrixIdentifierRedactor)\\s*\\.\\s*pseudonym\\s*\\(");

  private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

  @Test
  void everyLogAndExceptionSiteInTheAdapterIsRedacted() throws IOException {
    var source = stripCommentsAndLiterals(Files.readString(ADAPTER_SOURCE, StandardCharsets.UTF_8));

    var sites = new ArrayList<String>();
    sites.addAll(argumentListsOf(source, LOGGING_CALL));
    sites.addAll(argumentListsOf(source, EXCEPTION_CONSTRUCTOR));

    // Guard against a vacuous pass: if the scanner stops finding call sites (renamed logger,
    // reformatted source), this test must go red rather than silently assert nothing.
    assertThat(sites)
        .as("logging and exception sites found in %s", ADAPTER_SOURCE)
        .hasSizeGreaterThan(40);

    var violations =
        sites.stream()
            .map(MatrixSynapseServiceIdentifierRedactionTest::stripRedactionCalls)
            .flatMap(args -> personalIdentifiersIn(args).stream().map(id -> id + "  in  " + args))
            .toList();

    assertThat(violations)
        .as(
            "log statements and exception messages in MatrixSynapseService must pass "
                + "redactor.pseudonym(...) rather than a raw Matrix identifier")
        .isEmpty();
  }

  // -------------------------------------------------------------------------
  // The net above is static. These prove it sits over something real.
  // -------------------------------------------------------------------------

  private static final String MATRIX_BASE_URL = "https://matrix.example.com";
  private static final String USERNAME = "anna.beispiel";

  private MatrixIdentifierRedactor redactor;
  private RestTemplate restTemplate;
  private MockRestServiceServer mockServer;
  private MatrixSynapseService service;
  private Logger adapterLogger;
  private Level previousLevel;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    redactor = MatrixIdentifierRedactor.withKey("test-secret");
    restTemplate = new RestTemplate();
    mockServer = MockRestServiceServer.bindTo(restTemplate).build();

    var matrixConfig = new MatrixConfig();
    matrixConfig.setApiUrl(MATRIX_BASE_URL);
    matrixConfig.setServerName("matrix.example.com");

    service =
        new MatrixSynapseService(
            matrixConfig,
            restTemplate,
            restTemplate,
            mock(MatrixRoomClient.class),
            mock(MatrixMediaClient.class),
            redactor);

    adapterLogger = (Logger) LoggerFactory.getLogger(MatrixSynapseService.class);
    previousLevel = adapterLogger.getLevel();
    adapterLogger.setLevel(Level.TRACE);
    logAppender = new ListAppender<>();
    logAppender.start();
    adapterLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    adapterLogger.detachAppender(logAppender);
    adapterLogger.setLevel(previousLevel);
  }

  @Test
  void aFailedUserCreationNamesThePseudonymInBothItsLogLineAndItsExceptionMessage() {
    var registerUrl = MATRIX_BASE_URL + "/_synapse/admin/v1/register";
    mockServer
        .expect(requestTo(registerUrl))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"nonce\":\"abc\"}", MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(registerUrl))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withServerError());

    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> service.createUser(USERNAME, "pw", "Anna Beispiel"));

    var pseudonym = redactor.pseudonym(USERNAME);
    assertThat(thrown).hasMessageContaining(pseudonym).hasMessageNotContaining(USERNAME);
    assertThat(logMessages()).isNotEmpty().allSatisfy(m -> assertThat(m).doesNotContain(USERNAME));
    assertThat(logMessages()).anySatisfy(m -> assertThat(m).contains(pseudonym));
  }

  @Test
  void theSamePersonCarriesTheSameTokenWhetherTheCallSiteHeldTheLocalpartOrTheFullMatrixId() {
    assertThat(redactor.pseudonym("@" + USERNAME + ":matrix.example.com"))
        .isEqualTo(redactor.pseudonym(USERNAME))
        .isEqualTo(redactor.pseudonym("Anna.Beispiel"));
  }

  @Test
  void aPseudonymNeverContainsTheIdentifierAndDiffersBetweenPeople() {
    assertThat(redactor.pseudonym(USERNAME))
        .doesNotContain(USERNAME)
        .startsWith("mx#")
        .isNotEqualTo(redactor.pseudonym("bernd.beispiel"));
    assertThat(redactor.pseudonym(null)).isEqualTo("mx#null");
    assertThat(redactor.pseudonym("  ")).isEqualTo("mx#blank");
  }

  private List<String> logMessages() {
    return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static List<String> personalIdentifiersIn(String argumentList) {
    var found = new ArrayList<String>();
    var matcher = JAVA_IDENTIFIER.matcher(argumentList);
    while (matcher.find()) {
      var lower = matcher.group().toLowerCase(Locale.ROOT);
      if (PERSONAL_IDENTIFIER_FRAGMENTS.stream().anyMatch(lower::contains)) {
        found.add(matcher.group());
      }
    }
    return found;
  }

  /** Removes {@code redactor.pseudonym(&lt;anything&gt;)} calls, arguments included. */
  private static String stripRedactionCalls(String text) {
    var result = new StringBuilder(text);
    var matcher = REDACTION_CALL.matcher(result);
    while (matcher.find()) {
      int close = matchingParen(result.toString(), matcher.end() - 1);
      result.replace(matcher.start(), close + 1, "PSEUDONYM");
      matcher = REDACTION_CALL.matcher(result);
    }
    return result.toString();
  }

  /** The argument text of every call whose opening parenthesis the given pattern matches. */
  private static List<String> argumentListsOf(String source, Pattern callPattern) {
    var argumentLists = new ArrayList<String>();
    Matcher matcher = callPattern.matcher(source);
    while (matcher.find()) {
      int open = matcher.end() - 1;
      int close = matchingParen(source, open);
      if (close > open) {
        argumentLists.add(source.substring(open + 1, close));
      }
    }
    return argumentLists;
  }

  private static int matchingParen(String source, int openIndex) {
    int depth = 0;
    for (int i = openIndex; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * Replaces comments and string/char literal contents with blanks, so that prose and format
   * strings (which legitimately say "user") cannot trigger, and so that parenthesis matching is not
   * confused by brackets inside literals.
   */
  private static String stripCommentsAndLiterals(String source) {
    var out = new StringBuilder(source.length());
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
        while (i < source.length() && source.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
        i += 2;
        while (i + 1 < source.length()
            && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
          i++;
        }
        i += 2;
      } else if (c == '"' || c == '\'') {
        char quote = c;
        out.append(quote);
        i++;
        while (i < source.length() && source.charAt(i) != quote) {
          if (source.charAt(i) == '\\') {
            i++;
          }
          i++;
        }
        out.append(quote);
        i++;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }
}
