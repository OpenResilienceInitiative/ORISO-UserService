-- Only synthetic group sessions with an unambiguous consultant owner are repaired.
-- Existing ownership and human-owned sessions must never be reassigned.
UPDATE session
SET tenant_id = (SELECT c.tenant_id FROM consultant c WHERE c.consultant_id = session.consultant_id)
WHERE tenant_id IS NULL
  AND conversation_type IN ('SELF_HELP', 'INTERNAL_GROUP')
  AND EXISTS (
    SELECT 1 FROM consultant c
    WHERE c.consultant_id = session.consultant_id
      AND c.tenant_id > 0
      AND (session.user_id = 'group-chat-system'
        OR session.user_id = CONCAT('group-chat-system-', c.tenant_id))
  );
