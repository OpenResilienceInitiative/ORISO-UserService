-- Restore the exact values captured immediately before migration 0093 changed the rows.
UPDATE session
JOIN session_modality_0093_backup backup ON backup.session_id = session.id
SET session.conversation_type = backup.previous_conversation_type;

DROP TABLE session_modality_0093_backup;
