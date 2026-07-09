UPDATE session_supervisor ss
JOIN `session` s ON ss.session_id = s.id
SET ss.matrix_room_id = NULL
WHERE ss.is_active = 1
AND ss.matrix_room_id = s.matrix_room_id;
