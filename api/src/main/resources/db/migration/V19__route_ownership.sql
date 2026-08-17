-- Routes belong to the account that created them.
-- V13 already declared route.user_id but left it unconstrained and unwritten,
-- so this gives the existing column a real referent rather than adding one.
-- Anonymous routes stay possible: the planner works before signing up, and
-- those rows simply have no owner and are readable by nobody afterwards.
ALTER TABLE route
    ADD CONSTRAINT route_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

CREATE INDEX route_user_id_idx ON route (user_id) WHERE user_id IS NOT NULL;

-- trigger_profile was never written to by any code path. Trigger weights are
-- supplied per request and deliberately not retained, so the table is dropped
-- rather than left as an invitation to start storing health data.
ALTER TABLE route DROP COLUMN IF EXISTS profile_id;
DROP TABLE IF EXISTS trigger_profile;
