package de.caritas.cob.userservice.api.port.out;

import java.util.List;

/** Minimal provider-derived inventory; callers cannot choose its immutable rollout authority. */
public interface IdentityInactivityInventory {
  record Account(String id, Long tenantId, Long createdTimestamp, boolean eligibleHuman) {}

  record Page(List<Account> accounts, boolean hasMore) {
    public Page {
      accounts = List.copyOf(accounts);
    }
  }

  Page page(int first, int max);
}
