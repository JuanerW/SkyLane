-- Dev seed for the quote module only (stations, devices, one test user). Apply after Flyway V1:
--   docker exec -i <db-container> psql -U skylane -d skylane < database/dev-seed-quote.sql
-- Mirrors the seed table in BE/README.md. The team's DevRunner should replace this.
INSERT INTO station (code, name, address_text, location) VALUES
  ('soma',    'SoMa Station',    'SoMa, San Francisco',            ST_SetSRID(ST_MakePoint(-122.3950, 37.7785), 4326)::geography),
  ('mission', 'Mission Station', 'Mission District, San Francisco', ST_SetSRID(ST_MakePoint(-122.4148, 37.7599), 4326)::geography),
  ('marina',  'Marina Station',  'Marina District, San Francisco',  ST_SetSRID(ST_MakePoint(-122.4368, 37.8008), 4326)::geography)
ON CONFLICT (code) DO NOTHING;

INSERT INTO device (code, station_id, device_type, battery_pct, location)
SELECT d.code, s.id, d.type, 100, s.location
FROM (VALUES
  ('R-101','soma','robot'),('R-102','soma','robot'),('R-103','soma','robot'),('DRN-101','soma','drone'),('DRN-102','soma','drone'),
  ('R-201','mission','robot'),('R-202','mission','robot'),('R-203','mission','robot'),('DRN-201','mission','drone'),('DRN-202','mission','drone'),
  ('R-301','marina','robot'),('R-302','marina','robot'),('R-303','marina','robot'),('DRN-301','marina','drone'),('DRN-302','marina','drone')
) AS d(code, st, type)
JOIN station s ON s.code = d.st
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_user (email, display_name) VALUES ('alice@skylane.dev', 'Alice')
ON CONFLICT (email) DO NOTHING;
