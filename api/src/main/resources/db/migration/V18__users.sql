-- Accounts exist so route history can belong to someone.
-- Health sensitivities are deliberately not stored here: the client sends
-- trigger weights with each request and the server never retains them, which
-- keeps the most sensitive field out of the database entirely.
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE CHECK (email <> ''),
    password_hash TEXT NOT NULL CHECK (password_hash <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX app_user_email_lower_idx ON app_user (lower(email));
