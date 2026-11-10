create table sim_run (
  id         bigserial primary key,
  seed       bigint      not null,
  started_at timestamptz not null default now()
);

-- SmartFactory -------------------------------------------------------------
create table product (
  id         text primary key,
  name       text not null,
  short_name text not null,
  color      text not null
);

create table material (
  id            text primary key,
  name          text not null,
  kind          text not null check (kind in ('RAW', 'KIT', 'FINISHED')),
  reorder_point int  not null default 0 check (reorder_point >= 0),
  reorder_qty   int  not null default 0 check (reorder_qty >= 0)
);

create table machine (
  id           text primary key,
  name         text not null,
  kind         text not null check (kind in ('CNC', 'ROBOT', 'QC')),
  model        text not null,
  capabilities text[] not null,
  pos_x        double precision not null,
  pos_z        double precision not null,
  dock_node    text,
  status       text not null default 'IDLE',
  version      int  not null default 0
);

create table routing_step (
  product_id           text not null references product,
  seq                  int  not null,
  process              text not null check (process in ('DREHEN', 'FRAESEN', 'MONTAGE', 'PRUEFEN')),
  base_time_s          int  not null check (base_time_s > 0),
  preferred_machine_id text not null references machine,
  primary key (product_id, seq)
);

create table bom_item (
  product_id  text not null,
  seq         int  not null,
  material_id text not null references material,
  qty         int  not null check (qty > 0),
  primary key (product_id, seq, material_id),
  foreign key (product_id, seq) references routing_step
);

create table maintenance_window (
  id         bigserial primary key,
  machine_id text   not null references machine,
  start_ms   bigint not null,
  end_ms     bigint not null,
  check (end_ms > start_ms)
);

create table production_order (
  id              text primary key,
  product_id      text   not null references product,
  quantity        int    not null check (quantity > 0),
  priority        text   not null check (priority in ('NORMAL', 'HOCH', 'EILIG')),
  status          text   not null default 'CREATED',
  due_at_ms       bigint not null,
  created_at_ms   bigint not null,
  completed_at_ms bigint,
  good            int    not null default 0,
  scrapped        int    not null default 0,
  version         int    not null default 0
);

create table operation (
  id               text primary key,
  order_id         text not null references production_order,
  seq              int  not null,
  process          text not null,
  machine_id       text references machine,
  status           text not null default 'PLANNED',
  planned_start_ms bigint,
  planned_end_ms   bigint,
  started_at_ms    bigint,
  ended_at_ms      bigint,
  unique (order_id, seq)
);

create table machine_state_interval (
  id         bigserial primary key,
  machine_id text   not null references machine,
  status     text   not null,
  from_ms    bigint not null,
  to_ms      bigint
);
create index machine_state_interval_machine on machine_state_interval (machine_id, from_ms);

-- FlowPilot ----------------------------------------------------------------
create table graph_node (
  id   text primary key,
  x    double precision not null,
  z    double precision not null,
  dock boolean not null default false
);

create table graph_edge (
  id        text primary key,
  from_node text not null references graph_node,
  to_node   text not null references graph_node,
  length    double precision not null check (length > 0),
  blocked   boolean not null default false,
  unique (from_node, to_node)
);

create table storage_location (
  id        text primary key,
  name      text not null,
  zone      text not null check (zone in ('RAW', 'FINISHED')),
  dock_node text not null,
  capacity  int  not null check (capacity > 0)
);

create table stock (
  material_id text not null references material,
  location_id text not null references storage_location,
  on_hand     int  not null default 0 check (on_hand >= 0),
  reserved    int  not null default 0 check (reserved >= 0),
  primary key (material_id, location_id),
  constraint stock_reserved_within_on_hand check (reserved <= on_hand)
);

create function stock_within_capacity() returns trigger language plpgsql as $$
declare
  total int;
  cap   int;
begin
  -- Lock the location first: concurrent transactions changing different stock rows of the same location
  -- are serialised here, so each one sums the other's committed rows.
  select capacity into cap from storage_location where id = new.location_id for no key update;
  select coalesce(sum(on_hand), 0) into total from stock where location_id = new.location_id;
  if total > cap then
    raise exception 'Kapazität von Lagerplatz % überschritten: % > %', new.location_id, total, cap
      using errcode = 'check_violation';
  end if;
  return null;
end $$;

create constraint trigger stock_capacity after insert or update of on_hand on stock
  for each row execute function stock_within_capacity();

create table reservation (
  id            text primary key,
  request_id    text   not null unique,
  order_id      text   not null,
  material_id   text   not null,
  location_id   text   not null,
  qty           int    not null check (qty > 0),
  status        text   not null default 'ACTIVE' check (status in ('ACTIVE', 'RELEASED', 'CONSUMED')),
  created_at_ms bigint not null,
  foreign key (material_id, location_id) references stock
);

create table agv (
  id       text primary key,
  name     text not null,
  capacity int  not null default 1 check (capacity > 0),
  status   text not null default 'IDLE',
  node_id  text not null references graph_node,
  battery  double precision not null default 100 check (battery between 0 and 100),
  enabled  boolean not null default true,
  version  int  not null default 0
);

create table transport_order (
  id             text primary key,
  request_id     text   not null unique,
  kind           text   not null check (kind in ('SUPPLY', 'WIP', 'PUTAWAY')),
  order_id       text,
  material_id    text,
  qty            int    not null check (qty > 0),
  from_node      text   not null references graph_node,
  to_node        text   not null references graph_node,
  priority       text   not null check (priority in ('NORMAL', 'HOCH', 'EILIG')),
  status         text   not null default 'CREATED',
  agv_id         text references agv,
  reservation_id text references reservation,
  created_at_ms  bigint not null,
  version        int    not null default 0
);
-- must match TransportStatus.ACTIVE
create unique index transport_one_active_per_agv on transport_order (agv_id)
  where agv_id is not null and status in ('ASSIGNED', 'TO_PICKUP', 'LOADING', 'IN_TRANSIT', 'UNLOADING');

-- Events ---------------------------------------------------------------------
create table event (
  event_id       uuid primary key,
  seq            bigserial unique,
  run_id         bigint not null references sim_run,
  sim_time_ms    bigint not null,
  type           text   not null,
  source         text   not null,
  severity       text   not null check (severity in ('INFO', 'SUCCESS', 'WARNING', 'ERROR')),
  correlation_id text,
  payload        jsonb  not null
);
create index event_type on event (type, seq);
create index event_correlation on event (correlation_id, seq);

create table processed_event (
  consumer text not null,
  event_id uuid not null,
  primary key (consumer, event_id)
);
