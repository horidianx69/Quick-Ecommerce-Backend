-- The point customers' distances are measured from: the real location moved by
-- a few hundred metres in a random direction. Exact distances from several
-- places would otherwise let anyone work out where a shop is.
alter table shops
    add column public_latitude double precision,
    add column public_longitude double precision;

update shops
set public_latitude  = latitude + (random() - 0.5) * 0.006,
    public_longitude = longitude + (random() - 0.5) * 0.008;

alter table shops
    alter column public_latitude set not null,
    alter column public_longitude set not null;

-- Finds the approved shops inside a customer's bounding box.
create index shops_public_location_idx on shops (public_latitude, public_longitude) where status = 'APPROVED';
