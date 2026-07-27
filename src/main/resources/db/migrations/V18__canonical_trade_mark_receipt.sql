ALTER TABLE trade_marks
    ADD COLUMN current_receipt_json jsonb;

ALTER TABLE trade_marks
    ADD CONSTRAINT trade_marks_current_receipt_json_object
    CHECK (current_receipt_json IS NULL
        OR jsonb_typeof(current_receipt_json) = 'object');
