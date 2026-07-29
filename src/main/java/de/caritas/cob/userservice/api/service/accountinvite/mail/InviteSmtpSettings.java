package de.caritas.cob.userservice.api.service.accountinvite.mail;

/** Resolved global SMTP connection settings used to deliver account-invite mails. */
public record InviteSmtpSettings(
    String host, int port, boolean secure, String username, String password, String from) {}
