-- The environment target label is descriptive and may be longer than a short code.
ALTER TABLE esocial_employers ALTER COLUMN environment TYPE VARCHAR(24);
