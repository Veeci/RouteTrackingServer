-- MigrateIT (TC-1b-MIG-01): a migration that always fails, run after the real ones, to prove bin/migrate exits non-zero.
SELECT * FROM this_table_does_not_exist;
