createuser signal --createdb -h localhost -U postgres
createdb signal -O signal -h localhost -U postgres
psql -U postgres -d signal -c "CREATE EXTENSION IF NOT EXISTS pgcrypto" -h localhost
psql -U postgres -d signal -c "CREATE EXTENSION IF NOT EXISTS postgis" -h localhost
psql -U signal   -d signal -c "CREATE SCHEMA IF NOT EXISTS signal" -h localhost
