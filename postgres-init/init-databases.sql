-- Runs once, on first container start, because it's mounted into
-- /docker-entrypoint-initdb.d/ in the postgres container.
-- Creates one database per service that needs durable state.
-- notification-service is intentionally left out: it keeps its
-- log in memory, since a delivery log doesn't need to survive a restart
-- for this proof-of-concept.

CREATE DATABASE orderdb;
CREATE DATABASE inventorydb;
CREATE DATABASE paymentdb;
