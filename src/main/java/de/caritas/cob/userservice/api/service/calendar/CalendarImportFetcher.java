package de.caritas.cob.userservice.api.service.calendar;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/** Reads the bytes of a counsellor's own calendar; the only way the import touches the network. */
public interface CalendarImportFetcher {

  /**
   * @param address the https address to read
   * @param validated the public addresses its host resolved to; the connection goes to one of these
   *     and nowhere else
   * @return the body, at most the size limit
   * @throws CalendarImportException when the calendar cannot be read
   */
  byte[] fetch(URI address, List<InetAddress> validated);
}
