package de.caritas.cob.userservice.api.service.contactperson;

/** The validated contact persons of one counselling centre: its own pair and the Träger default. */
public record CentreContact(
    Long agencyId,
    Long tenantId,
    ContactPair own,
    ContactPair traegerDefault,
    String traegerContactEmail) {

  public ContactSource source() {
    if (own.isSet()) {
      return ContactSource.OWN;
    }
    return traegerDefault.isSet() ? ContactSource.TRAEGER_DEFAULT : ContactSource.NONE;
  }

  /** The pair counsellors see; {@link ContactPair#NONE} when only the admin fallback is left. */
  public ContactPair effective() {
    return switch (source()) {
      case OWN -> own;
      case TRAEGER_DEFAULT -> traegerDefault;
      case NONE -> ContactPair.NONE;
    };
  }
}
