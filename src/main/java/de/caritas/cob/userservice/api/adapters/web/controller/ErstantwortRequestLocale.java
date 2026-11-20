package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortTranslations;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Reads the existing UI language cookie without changing the counselling language. */
final class ErstantwortRequestLocale {
  private ErstantwortRequestLocale() {}

  static String current() {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes)) {
      return null;
    }
    var cookies = attributes.getRequest().getCookies();
    if (cookies == null) return null;
    for (var cookie : cookies) {
      if (cookie.getName().equals("lang")) {
        return ErstantwortTranslations.validLocale(cookie.getValue());
      }
    }
    return null;
  }
}
