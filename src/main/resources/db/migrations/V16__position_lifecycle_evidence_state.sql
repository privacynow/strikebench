ALTER TABLE public.position_lifecycle_decision_receipt
    DROP CONSTRAINT position_lifecycle_receipt_verdict_check;

ALTER TABLE public.position_lifecycle_decision_receipt
    ADD CONSTRAINT position_lifecycle_receipt_verdict_check
    CHECK (verdict IN
        ('KEEP','HARVEST','REDUCE','DEFEND','ACCEPT_ASSIGNMENT','NEEDS_EVIDENCE'));
