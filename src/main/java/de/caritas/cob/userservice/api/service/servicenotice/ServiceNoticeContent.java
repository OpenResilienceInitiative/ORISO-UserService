package de.caritas.cob.userservice.api.service.servicenotice;

import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** The campaign's template values, shared so the sent mail is exactly what the preview showed. */
final class ServiceNoticeContent {

  static final String TEMPLATE = "systemhinweis";
  static final Pattern UNRESOLVED = Pattern.compile("\\{\\{[a-zA-Z0-9.]+}}", Pattern.MULTILINE);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

  private ServiceNoticeContent() {}

  static Map<String, String> maintenanceValues(ServiceNoticeCampaign campaign) {
    var values = new LinkedHashMap<String, String>();
    values.put("maintenanceDate", campaign.getMaintenanceDate().toString());
    values.put("maintenanceStart", campaign.getMaintenanceStart().format(TIME));
    values.put("maintenanceEnd", campaign.getMaintenanceEnd().format(TIME));
    values.put("statusUrl", campaign.getStatusUrl());
    return values;
  }
}
