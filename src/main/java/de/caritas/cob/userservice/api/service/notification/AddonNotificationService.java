package de.caritas.cob.userservice.api.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AddonNotificationRequestDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores an add-on notification (contract oriso-addon/v1, section 1) as an Activity Timeline event
 * of its recipient and hands delivery beyond the feed to {@link AddonNotificationMailer}.
 */
@Service
@RequiredArgsConstructor
public class AddonNotificationService {

  static final int MAX_TITLE_LENGTH = 120;
  static final int MAX_TEXT_LENGTH = 500;
  static final int MAX_LOCALES = 10;
  static final String DEFAULT_LOCALE = "de";

  private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern ADDON_NAME = Pattern.compile("[a-z][a-z0-9-]{1,40}");
  private static final Pattern APP_ID = Pattern.compile("[a-z][a-z0-9-]{0,40}");
  private static final Pattern LOCALE = Pattern.compile("[a-z]{2}(?:[_-][A-Za-z0-9]{2,8})?");
  private static final Pattern MARKUP = Pattern.compile("[<>]");
  private static final String BIDI_CONTROLS = "\\u202A-\\u202E\\u2066-\\u2069";
  private static final Pattern FORBIDDEN_IN_TITLE =
      Pattern.compile("[\\p{Cc}" + BIDI_CONTROLS + "]");
  private static final Pattern FORBIDDEN_IN_TEXT =
      Pattern.compile("[[\\p{Cc}&&[^\\n]]" + BIDI_CONTROLS + "]");

  private static final Long LEGACY_TENANT = 1L;

  enum RecipientKind {
    ASKER,
    CONSULTANT
  }

  enum Slot {
    ASKER_NAVIGATION("asker.navigation", RecipientKind.ASKER, false),
    CONSULTANT_NAVIGATION("consultant.navigation", RecipientKind.CONSULTANT, false),
    CONSULTANT_SESSION("consultant.session", RecipientKind.CONSULTANT, true),
    CONSULTANT_ASKER_PROFILE("consultant.asker-profile", RecipientKind.CONSULTANT, true);

    private final String id;
    private final RecipientKind audience;
    private final boolean needsSession;

    Slot(String id, RecipientKind audience, boolean needsSession) {
      this.id = id;
      this.audience = audience;
      this.needsSession = needsSession;
    }

    static Slot of(String id) {
      for (Slot slot : values()) {
        if (slot.id.equals(id)) {
          return slot;
        }
      }
      throw new BadRequestException("slot must be one of " + ids());
    }

    private static Set<String> ids() {
      var ids = new TreeSet<String>();
      for (Slot slot : values()) {
        ids.add(slot.id);
      }
      return ids;
    }
  }

  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull AddonNotificationMailer mailer;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Transactional
  public Long create(AddonNotificationRequestDTO request) {
    if (request == null) {
      throw new BadRequestException("body is missing");
    }
    var userId = require(request.userId(), USER_ID, "userId");
    var tenantId = request.tenantId();
    if (tenantId == null || tenantId <= 0) {
      throw new BadRequestException("tenantId must be a positive number");
    }
    var addon = require(request.addon(), ADDON_NAME, "addon");
    var appId = require(request.appId(), APP_ID, "appId");
    var slot = Slot.of(request.slot());
    var sessionId = sessionIdFor(slot, request.sessionId());
    var important = Boolean.TRUE.equals(request.important());
    var title = localized(request.title(), "title", MAX_TITLE_LENGTH, FORBIDDEN_IN_TITLE);
    var text = localized(request.text(), "text", MAX_TEXT_LENGTH, FORBIDDEN_IN_TEXT);

    var recipient = recipientKind(userId, tenantId);
    if (recipient != slot.audience) {
      throw new BadRequestException("slot " + slot.id + " does not fit the recipient's role");
    }

    var id =
        eventNotificationService.createAddonNotification(
            userId,
            tenantId,
            title.get(DEFAULT_LOCALE),
            text.get(DEFAULT_LOCALE),
            params(addon, appId, slot, sessionId, important, title, text),
            actionPath(slot, addon, appId),
            sessionId);
    if (important) {
      mailer.sendIfWanted(recipient, userId, tenantId);
    }
    return id;
  }

  private RecipientKind recipientKind(String userId, Long tenantId) {
    var asker = userRepository.findByUserIdAndDeleteDateIsNull(userId);
    if (asker.isPresent()) {
      requireTenant(asker.get().getTenantId(), tenantId);
      return RecipientKind.ASKER;
    }
    var consultant = consultantRepository.findByIdAndDeleteDateIsNull(userId);
    if (consultant.isPresent()) {
      requireTenant(consultant.get().getTenantId(), tenantId);
      return RecipientKind.CONSULTANT;
    }
    throw new NotFoundException("Recipient not found");
  }

  private static void requireTenant(Long recipientTenantId, Long requestedTenantId) {
    var effective = recipientTenantId == null ? LEGACY_TENANT : recipientTenantId;
    if (!Objects.equals(effective, requestedTenantId)) {
      throw new BadRequestException("recipient does not belong to tenantId");
    }
  }

  private static Long sessionIdFor(Slot slot, Long sessionId) {
    if (sessionId != null && sessionId <= 0) {
      throw new BadRequestException("sessionId must be a positive number");
    }
    if (slot.needsSession && sessionId == null) {
      throw new BadRequestException("sessionId is required for slot " + slot.id);
    }
    return sessionId;
  }

  private static String require(String value, Pattern pattern, String field) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new BadRequestException(field + " must match " + pattern.pattern());
    }
    return value;
  }

  private static Map<String, String> localized(
      Map<String, String> values, String field, int maxLength, Pattern forbidden) {
    if (values == null || values.isEmpty()) {
      throw new BadRequestException(field + " is missing");
    }
    if (values.size() > MAX_LOCALES) {
      throw new BadRequestException(field + " has more than " + MAX_LOCALES + " locales");
    }
    var result = new TreeMap<String, String>();
    values.forEach(
        (locale, raw) -> {
          if (locale == null || !LOCALE.matcher(locale).matches()) {
            throw new BadRequestException(field + " has an invalid locale");
          }
          var value = raw == null ? "" : raw.replace("\r\n", "\n").strip();
          if (value.isEmpty()) {
            throw new BadRequestException(field + "." + locale + " is empty");
          }
          if (value.codePointCount(0, value.length()) > maxLength) {
            throw new BadRequestException(
                field + "." + locale + " is longer than " + maxLength + " characters");
          }
          if (MARKUP.matcher(value).find() || forbidden.matcher(value).find()) {
            throw new BadRequestException(field + "." + locale + " must be plain text");
          }
          result.put(locale, value);
        });
    if (!result.containsKey(DEFAULT_LOCALE)) {
      throw new BadRequestException(field + "." + DEFAULT_LOCALE + " is required");
    }
    return result;
  }

  private String params(
      String addon,
      String appId,
      Slot slot,
      Long sessionId,
      boolean important,
      Map<String, String> title,
      Map<String, String> text) {
    var params = new LinkedHashMap<String, Object>();
    params.put("addon", addon);
    params.put("appId", appId);
    params.put("slot", slot.id);
    params.put("sessionId", sessionId);
    params.put("important", important);
    params.put("title", title);
    params.put("text", text);
    try {
      return objectMapper.writeValueAsString(params);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(
          "Add-on notification params are not serialisable", impossible);
    }
  }

  private static String actionPath(Slot slot, String addon, String appId) {
    return slot.needsSession ? null : "/addons/" + addon + "/" + appId;
  }
}
