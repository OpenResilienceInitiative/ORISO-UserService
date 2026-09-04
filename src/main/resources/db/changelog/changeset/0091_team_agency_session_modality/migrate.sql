-- ADR-006 addendum 2026-09-04: `is_team_session` has two meanings — an internal group chat
-- (created by CreateChatFacade) and a "Team-Beratungsstelle" 1:1 case (every counsellor of the
-- agency may see it). SessionService.saveSession used to derive INTERNAL_GROUP from the flag, so
-- every 1:1 case of a team agency was persisted as an internal group chat.
--
-- Group chats are recognised by what only the group-chat path produces (any one is enough):
--   * a group_chat_participant row whose chat_id is the session id (the creator is always added),
--   * a chat row sharing the session's Matrix room id (both rows carry the same room),
--   * the tenant system user "group-chat-system[-<tenant>]" as the session's user.
-- Everything else with is_team_session = 1 is a 1:1 case and gets its modality by registration
-- type, exactly as saveSession now defaults it. SELF_HELP rows are never touched.
--
-- Idempotent: the WHERE clause excludes every row a previous run has already corrected.
UPDATE session
SET conversation_type = CASE
                          WHEN registration_type = 'ANONYMOUS' THEN 'LIVE_CHAT'
                          ELSE 'AGENCY_COUNSELLING'
                        END
WHERE is_team_session = 1
  AND (conversation_type = 'INTERNAL_GROUP' OR conversation_type IS NULL)
  AND NOT EXISTS (SELECT 1 FROM group_chat_participant gcp WHERE gcp.chat_id = session.id)
  AND NOT EXISTS (SELECT 1 FROM chat c
                  WHERE c.matrix_room_id IS NOT NULL
                    AND c.matrix_room_id = session.matrix_room_id)
  AND session.user_id NOT LIKE 'group-chat-system%';
