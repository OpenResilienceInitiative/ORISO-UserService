package de.caritas.cob.userservice.api.config;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Checks every public origin that outgoing mail links to, once, before any bean is created.
 *
 * <p>These origins used to fall back to {@code app.base.url}, which itself defaulted to {@code
 * http://localhost:8082}, so an environment without the variable started fine and mailed links
 * nobody could open (ORISO-Helm#368). Now each one is required, and a missing, relative or template
 * value stops startup with a message that names every offending variable at once.
 *
 * <p>Running as a {@link BeanFactoryPostProcessor} is what makes it "at once": the consuming beans
 * would otherwise fail one by one on an unresolvable placeholder, one restart per variable.
 */
@Component
public class PublicUrlStartupValidator implements BeanFactoryPostProcessor, EnvironmentAware {

  /** Profiles that may point at loopback or example hosts. */
  private static final Set<String> NON_DEPLOYED_PROFILES = Set.of("local", "testing");

  /** Reserved example domains (RFC 2606); a real deployment never mails links to them. */
  private static final List<String> EXAMPLE_DOMAINS =
      List.of("example.com", "example.org", "example.net");

  private record PublicUrl(String property, String envVar, boolean required, String missing) {}

  // Each "missing" message follows the must-be-set convention ConfigEnvExampleContractTest scans
  // for, which ties every required variable to config.env.example and required-environment.md.
  private static final List<PublicUrl> PUBLIC_URLS =
      List.of(
          new PublicUrl(
              "app.base.url", "APP_BASE_URL", true, "app.base.url must be set (APP_BASE_URL)"),
          new PublicUrl(
              "system.notification.frontend.base-url",
              "SYSTEM_NOTIFICATION_FRONTEND_BASE_URL",
              true,
              "system.notification.frontend.base-url must be set"
                  + " (SYSTEM_NOTIFICATION_FRONTEND_BASE_URL)"),
          new PublicUrl(
              "dpa.sign.frontend.base-url",
              "DPA_SIGN_FRONTEND_BASE_URL",
              true,
              "dpa.sign.frontend.base-url must be set (DPA_SIGN_FRONTEND_BASE_URL)"),
          new PublicUrl(
              "magic.link.frontend.base-url",
              "MAGIC_LINK_FRONTEND_BASE_URL",
              true,
              "magic.link.frontend.base-url must be set (MAGIC_LINK_FRONTEND_BASE_URL)"),
          new PublicUrl(
              "account.invite.app.frontend.base-url",
              "ACCOUNT_INVITE_APP_FRONTEND_BASE_URL",
              true,
              "account.invite.app.frontend.base-url must be set"
                  + " (ACCOUNT_INVITE_APP_FRONTEND_BASE_URL)"),
          new PublicUrl(
              "account.invite.admin.frontend.base-url",
              "ACCOUNT_INVITE_ADMIN_FRONTEND_BASE_URL",
              true,
              "account.invite.admin.frontend.base-url must be set"
                  + " (ACCOUNT_INVITE_ADMIN_FRONTEND_BASE_URL)"),
          // Blank keeps self-service password reset switched off (fail closed), as before.
          new PublicUrl(
              "password.reset.frontend.base-url", "PASSWORD_RESET_FRONTEND_BASE_URL", false, null),
          new PublicUrl(
              "password.reset.admin.frontend.base-url",
              "PASSWORD_RESET_ADMIN_FRONTEND_BASE_URL",
              false,
              null));

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    validate(environment);
  }

  /** Throws one {@link IllegalStateException} listing every public origin that is unusable. */
  public static void validate(Environment environment) {
    boolean deployed = !environment.matchesProfiles(NON_DEPLOYED_PROFILES.toArray(String[]::new));
    List<String> problems = new ArrayList<>();
    for (PublicUrl url : PUBLIC_URLS) {
      String value = read(environment, url.property());
      if (isBlank(value)) {
        if (url.required()) {
          problems.add(url.missing());
        }
        continue;
      }
      String shapeProblem = shapeProblem(value.trim(), deployed);
      if (shapeProblem != null) {
        problems.add(
            url.property() + " " + shapeProblem + ", got '" + value + "' (" + url.envVar() + ")");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Public URLs used in outgoing mail are not configured for this environment:\n  - "
              + String.join("\n  - ", problems));
    }
  }

  /** An unresolvable {@code ${VAR}} counts as missing, not as a crash with a generic message. */
  private static String read(Environment environment, String property) {
    try {
      return environment.getProperty(property);
    } catch (IllegalArgumentException unresolvable) {
      return null;
    }
  }

  private static String shapeProblem(String value, boolean deployed) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException malformed) {
      return "is not a valid URL";
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!("http".equals(scheme) || "https".equals(scheme)) || isBlank(uri.getHost())) {
      return "must be an absolute http(s) URL with a host";
    }
    // Routes are appended to these origins; a query or fragment would swallow them.
    if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
      return "must carry no query or fragment";
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (deployed && isPlaceholder(host)) {
      return "is a template placeholder, not this environment's host";
    }
    return null;
  }

  private static boolean isPlaceholder(String host) {
    return host.contains("your-domain")
        || EXAMPLE_DOMAINS.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
  }
}
