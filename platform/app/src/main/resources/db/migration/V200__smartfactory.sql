-- SmartFactory (M1-C). V1 created production_order, operation, machine and machine_state_interval.

-- One container an operation needs: SUPPLY of a material from stock, or WIP from the previous step.
-- id is the request id of the transport that first brought it; a move to another machine keeps the row.
create table operation_material (
  id           text primary key,
  order_id     text    not null references production_order,
  step_seq     int     not null,
  kind         text    not null check (kind in ('SUPPLY', 'WIP')),
  material_id  text    references material,
  quantity     int     not null check (quantity > 0),
  -- null while a WIP container waits for a conveyor hand-over
  transport_id text,
  delivered    boolean not null default false,
  foreign key (order_id, step_seq) references operation (order_id, seq)
);
create index operation_material_transport on operation_material (transport_id);

alter table operation add column good  int not null default 0 check (good >= 0);
alter table operation add column scrap int not null default 0 check (scrap >= 0);
create index operation_machine on operation (machine_id) where status not in ('DONE', 'CANCELLED');

-- the product the machine ran last; a different one needs setup
alter table machine add column last_product_id text references product;
