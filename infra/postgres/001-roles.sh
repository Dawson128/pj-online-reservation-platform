#!/usr/bin/env bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE campus FROM PUBLIC;
SQL
for module in 1 2 3; do
  app_var="APP_M${module}_PASSWORD"
  migrate_var="MIGRATE_M${module}_PASSWORD"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"     --set=app_role="app_m${module}" --set=migrate_role="migrate_m${module}"     --set=schema_name="m${module}" --set=app_password="${!app_var}" --set=migrate_password="${!migrate_var}" <<'SQL'
CREATE ROLE :"app_role" LOGIN PASSWORD :'app_password';
CREATE ROLE :"migrate_role" LOGIN PASSWORD :'migrate_password';
GRANT CONNECT ON DATABASE campus TO :"app_role", :"migrate_role";
CREATE SCHEMA :"schema_name" AUTHORIZATION :"migrate_role";
GRANT USAGE ON SCHEMA :"schema_name" TO :"app_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrate_role" IN SCHEMA :"schema_name" GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrate_role" IN SCHEMA :"schema_name" GRANT USAGE, SELECT ON SEQUENCES TO :"app_role";
SQL
done
