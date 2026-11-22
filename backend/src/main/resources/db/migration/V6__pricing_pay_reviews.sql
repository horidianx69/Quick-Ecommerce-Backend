-- ================================================================ settings
-- Everything admin can tune. Still exactly one row (id = 1).
alter table platform_settings
    drop column delivery_fee_cents,
    drop column driver_pay_cents,
    -- What we keep from the shop's listed price: the shop is paid (100 - this)% of it.
    add column shop_cut_percent         integer not null default 15  check (shop_cut_percent between 0 and 90),
    -- Delivery fee by items subtotal: below mid_from -> low; below free_from -> mid; from free_from up -> free.
    add column delivery_fee_low_cents   integer not null default 599 check (delivery_fee_low_cents between 0 and 10000),
    add column delivery_fee_mid_cents   integer not null default 499 check (delivery_fee_mid_cents between 0 and 10000),
    add column delivery_mid_from_cents  integer not null default 5000 check (delivery_mid_from_cents between 0 and 1000000),
    add column free_delivery_from_cents integer not null default 10000 check (free_delivery_from_cents between 0 and 1000000),
    add column handling_fee_cents       integer not null default 199 check (handling_fee_cents between 0 and 10000),
    -- Orders with an items subtotal below this pay the small-cart fee.
    add column small_cart_below_cents   integer not null default 1500 check (small_cart_below_cents between 0 and 1000000),
    add column small_cart_fee_cents     integer not null default 299 check (small_cart_fee_cents between 0 and 10000),
    -- Late-night window, in each shop's own time zone. start > end means it runs past midnight.
    add column late_night_fee_cents     integer not null default 199 check (late_night_fee_cents between 0 and 10000),
    add column late_night_start         time    not null default '23:00',
    add column late_night_end           time    not null default '06:00',
    -- AUTO: on when open jobs near the shop are at least surge_ratio_percent/100 x the online drivers near it.
    add column surge_mode               text    not null default 'AUTO' check (surge_mode in ('AUTO', 'ON', 'OFF')),
    add column surge_fee_cents          integer not null default 199 check (surge_fee_cents between 0 and 10000),
    add column surge_ratio_percent      integer not null default 200 check (surge_ratio_percent between 100 and 1000),
    -- Driver pay per hour of engaged time (job accepted until it ends), plus an allowance per km.
    add column driver_hourly_cents      integer not null default 2199 check (driver_hourly_cents between 0 and 20000),
    add column driver_per_km_cents      integer not null default 35 check (driver_per_km_cents between 0 and 500),
    -- Tip buttons the customer app shows, in cents.
    add column tip_options_cents        text    not null default '100,200,500,1000',
    -- Violation rules.
    add column late_delivery_minutes    integer not null default 20 check (late_delivery_minutes between 5 and 240),
    add column location_lost_minutes    integer not null default 10 check (location_lost_minutes between 2 and 240),
    add column daily_violation_limit    integer not null default 3  check (daily_violation_limit between 1 and 100),
    add column monthly_violation_limit  integer not null default 6  check (monthly_violation_limit between 1 and 1000);

-- ================================================================= orders
-- Every fee is frozen on the order when it is placed, like the prices.
-- driver_pay_cents moves to driver_engagements: pay now depends on time worked.
alter table orders
    drop column driver_pay_cents,
    add column handling_fee_cents   integer not null default 0,
    add column small_cart_fee_cents integer not null default 0,
    add column late_night_fee_cents integer not null default 0,
    add column surge_fee_cents      integer not null default 0,
    add column tip_cents            integer not null default 0 check (tip_cents >= 0),
    -- null = nothing asked; ASKED = "Did you receive it?" is waiting; RECEIVED / NOT_RECEIVED = the answer.
    add column receipt_status       text check (receipt_status in ('ASKED', 'RECEIVED', 'NOT_RECEIVED'));

-- shop_price_cents is now the shop's payout per unit after our cut; a listing of a
-- cent or two pays the shop 0 cents, so allow 0.
alter table order_items drop constraint order_items_shop_price_cents_check;
alter table order_items add constraint order_items_shop_price_cents_check check (shop_price_cents >= 0);

-- For the daily admin metrics.
create index orders_created_idx on orders (created_at);
create index orders_delivered_idx on orders (delivered_at) where delivered_at is not null;

-- ============================================================ driver work
-- One row per time a driver holds an order: from accepting (or being assigned)
-- until it is delivered, given back, taken off them, or cancelled.
-- Engaged time and pay are worked out once, when the row ends.
create table driver_engagements (
    id              bigint generated always as identity primary key,
    order_id        bigint not null references orders (id),
    driver_id       bigint not null references users (id),
    accepted_at     timestamptz not null,
    picked_up_at    timestamptz,
    ended_at        timestamptz,
    end_reason      text check (end_reason in ('DELIVERED', 'RELEASED', 'UNASSIGNED', 'CANCELLED')),
    -- Straight-line distances at the start, x1.3 for roads when paid.
    to_shop_km      double precision not null,
    to_customer_km  double precision not null,
    engaged_seconds integer,
    paid_km         double precision,
    pay_cents       integer,
    location_lost   boolean not null default false,
    code_tries_used boolean not null default false
);
-- One open engagement per order and per driver.
create unique index engagements_open_order on driver_engagements (order_id) where ended_at is null;
create unique index engagements_open_driver on driver_engagements (driver_id) where ended_at is null;
create index engagements_driver_ended on driver_engagements (driver_id, ended_at);

create table driver_violations (
    id          bigint generated always as identity primary key,
    driver_id   bigint not null references users (id),
    order_id    bigint references orders (id),
    type        text not null check (type in ('LATE_DELIVERY', 'LOCATION_LOST', 'NOT_RECEIVED', 'JOB_RELEASED', 'CODE_TRIES_USED')),
    detail      text,
    occurred_at timestamptz not null
);
create index violations_driver_time on driver_violations (driver_id, occurred_at);

-- What admin must look at. DAILY_LIMIT: too many violations in one day (a warning).
-- BAN_REVIEW: too many in 30 days ("suspend this driver?"). NOT_RECEIVED: a customer says the order never came.
create table driver_alerts (
    id              bigint generated always as identity primary key,
    driver_id       bigint not null references users (id),
    order_id        bigint references orders (id),
    kind            text not null check (kind in ('DAILY_LIMIT', 'BAN_REVIEW', 'NOT_RECEIVED')),
    day             date,
    violation_count integer not null,
    created_at      timestamptz not null default now(),
    resolved_at     timestamptz,
    resolution      text check (resolution in ('DISMISSED', 'SUSPENDED'))
);
create unique index alerts_daily_once on driver_alerts (driver_id, day) where kind = 'DAILY_LIMIT';
create unique index alerts_ban_review_open on driver_alerts (driver_id) where kind = 'BAN_REVIEW' and resolved_at is null;
create index alerts_open on driver_alerts (created_at) where resolved_at is null;

-- Admin's record that a driver's pay for a two-week period was paid.
create table driver_payouts (
    driver_id    bigint not null references users (id),
    period_start date not null,
    amount_cents integer not null,
    paid_at      timestamptz not null default now(),
    primary key (driver_id, period_start)
);

-- ================================================================ reviews
create table reviews (
    id           bigint generated always as identity primary key,
    order_id     bigint not null references orders (id),
    author_id    bigint not null references users (id),
    subject_type text not null check (subject_type in ('SHOP', 'PRODUCT', 'DRIVER', 'CUSTOMER')),
    -- shop id, catalog product id, or the driver's / customer's user id
    subject_id   bigint not null,
    stars        integer not null check (stars between 1 and 5),
    comment      text,
    created_at   timestamptz not null default now(),
    unique (order_id, author_id, subject_type, subject_id)
);
create index reviews_subject on reviews (subject_type, subject_id, id);

-- Running totals, so feeds can show an average without counting reviews every time.
create table rating_totals (
    subject_type text not null,
    subject_id   bigint not null,
    stars_sum    bigint not null,
    reviews      integer not null,
    primary key (subject_type, subject_id)
);
