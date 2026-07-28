# Appointment cleanup replica safety

`Organizer.deleteObsoleteAppointments()` performs one native database delete over
a fixed clock cutoff. It has no external side effects and does not require a
distributed scheduler claim.

`OrganizerMariaDbReplicaIT` runs two cleanup executions concurrently against the
same MariaDB schema after seeding 120 expired and 30 current appointments. Both
transactions complete successfully, every expired row is removed, and all current
rows remain.

The reusable MariaDB workflow runs this proof together with schema-drift and
statistics contracts. Its CI contract explicitly requires the replica proof, so
removing it makes the CI contract fail.
