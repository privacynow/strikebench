-- A second database for the jar-based DOM / live test suites, which spawn the real jar
-- against Postgres. (The JUnit integration suite uses ephemeral Testcontainers instead.)
-- Runs once, on first container init, as the POSTGRES_USER (owner = strikebench).
CREATE DATABASE strikebench_test;
