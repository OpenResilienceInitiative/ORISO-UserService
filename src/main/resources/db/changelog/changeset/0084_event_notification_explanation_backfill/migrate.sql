-- #1010 task 1d: remove the counsellor-written handover explanation from stored notifications.
--
-- notifyGranted() and notifyPendingConsent() formatted the explanation into event_notification.text
-- as ". Explanation: <free text>" and kept it there indefinitely. The explanation is written by a
-- counsellor and can reference case content, which made this the one place counselling content sat
-- in plaintext. It stays available on demand through the handover-request API, so no information is
-- lost by dropping the copy.
--
-- Cuts at the first marker only: everything after it -- including a marker repeated inside the
-- explanation itself -- is removed. The leading part ("X took over your case. Reason: Y") is
-- generated text and stays, so existing feed cards keep rendering until the frontend switches to
-- params (task 1b/1c). LEFT and LOCATE behave identically on MariaDB and on H2 in MariaDB mode.
-- LOCATE returns the 1-based position of the marker's leading '.', so LEFT(text, LOCATE(...))
-- keeps that period and the sentence stays properly terminated.
UPDATE event_notification
SET text = LEFT(text, LOCATE('. Explanation: ', text))
WHERE text IS NOT NULL
  AND LOCATE('. Explanation: ', text) > 0
