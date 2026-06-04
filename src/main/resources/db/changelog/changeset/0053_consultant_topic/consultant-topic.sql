CREATE TABLE `userservice`.`consultant_topic` (
    `id` bigint(21) NOT NULL,
    `consultant_id` varchar(36) NOT NULL,
    `topic_id` bigint(21) unsigned NOT NULL,
    `create_date` datetime NOT NULL DEFAULT (UTC_TIMESTAMP),
    `update_date` datetime NOT NULL DEFAULT (UTC_TIMESTAMP),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_consultant_topic` (`consultant_id`, `topic_id`),
    KEY `consultant_id` (`consultant_id`),
    CONSTRAINT `consultant_topic_ibfk_1` FOREIGN KEY (`consultant_id`) REFERENCES `userservice`.`consultant` (`consultant_id`) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE SEQUENCE `userservice`.`sequence_consultant_topic`
INCREMENT BY 1
MINVALUE = 0
NOMAXVALUE
START WITH 0
CACHE 10;
