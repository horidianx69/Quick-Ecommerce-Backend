alter table order_payments add column review_reason text;
alter table stripe_transfers add column review_reason text;

create index order_payments_review_idx on order_payments (order_id, kind) where state = 'REVIEW_REQUIRED';
create index stripe_transfers_review_idx on stripe_transfers (id) where state = 'REVIEW_REQUIRED';
