-- #983: remove the zero-content draft rows that the frontend's autosave persisted whenever the
-- composer was empty (autosave also fires on unmount, so merely opening a conversation stored a
-- row). The drafts badge counted every row while the drafts centre only showed drafts that
-- resolve to a session, so the badge stuck and nothing the user could do would clear it.
--
-- Emptiness mirrors DraftContent.hasContent, which in turn mirrors the frontend's
-- hasDraftContent: markup tags and HTML whitespace entities are stripped before the check, so
-- TipTap's empty document (<p></p>, <p><br></p>) counts as empty. An end-to-end encrypted draft
-- is opaque base64 ciphertext without markup and can therefore never be matched here.
--
-- Deliberately conservative: rows consisting solely of control whitespace (tab, newline) or of
-- literal U+00A0/U+200B are left alone, because expressing those portably across MariaDB and the
-- H2 test engine needs backslash escapes the two engines read differently. No client ever wrote
-- such a draft, and DraftMessageService now rejects them on write.
DELETE FROM draft_message
WHERE TRIM(
        REGEXP_REPLACE(
          REGEXP_REPLACE(COALESCE(text, ''), '<[^>]*>', ' '),
          '&([nN][bB][sS][pP]|#160);', ' '
        )
      ) = ''
