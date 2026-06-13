-- Renumber im_message.status: 2 (已撤回) → 1 (已撤回)
-- Makes status values continuous: 0=正常, 1=已撤回
-- Previous values used a non-continuous gap (0, 2) with no reason for skipping 1.
UPDATE im_message SET status = 1 WHERE status = 2;
