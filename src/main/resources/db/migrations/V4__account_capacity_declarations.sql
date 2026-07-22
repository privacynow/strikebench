ALTER TABLE public.account_objective_revision
    ADD COLUMN package_capacities jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN capacity_policy jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.account_objective_revision
    ADD CONSTRAINT account_objective_revision_package_capacities_array
        CHECK (jsonb_typeof(package_capacities) = 'array'),
    ADD CONSTRAINT account_objective_revision_capacity_policy_object
        CHECK (jsonb_typeof(capacity_policy) = 'object');
