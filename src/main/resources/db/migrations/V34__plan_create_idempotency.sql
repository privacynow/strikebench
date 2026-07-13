-- Every Plan-creation request keeps its own immutable payload identity, including when
-- equivalent active content resolves to a Plan created by another request.
CREATE TABLE plan_create_request (
    owner_key          TEXT NOT NULL,
    client_request_id  TEXT NOT NULL,
    input_hash         TEXT NOT NULL,
    plan_id            TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_key, client_request_id)
);

INSERT INTO plan_create_request(owner_key,client_request_id,input_hash,plan_id,created_at)
SELECT COALESCE(user_id, '__local__'),client_request_id,create_input_hash,id,created_at
FROM plans
ON CONFLICT DO NOTHING;

CREATE INDEX idx_plan_create_request_plan ON plan_create_request(plan_id);
