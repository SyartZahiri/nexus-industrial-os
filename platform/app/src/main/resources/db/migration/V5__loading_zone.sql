-- Loading zone (RouteMind, Spec 15a): a one-way detour from the ring at J_20_3 into the south-east corner of the
-- hall and back to LS_19.3. Coordinates match FactoryTwin/src/twin/layout.ts (DETOURS, dock D_LZ).
insert into graph_node (id, x, z, dock) values
  ('D_LZ_1', 23.2, 5.5, false),
  ('D_LZ_2', 23.2, 14, false),
  ('D_LZ', 22, 15.4, true),
  ('D_LZ_4', 20.8, 14, false);

insert into graph_edge (id, from_node, to_node, length) values
  ('J_20_3>D_LZ_1', 'J_20_3', 'D_LZ_1', 4.061),
  ('D_LZ_1>D_LZ_2', 'D_LZ_1', 'D_LZ_2', 8.500),
  ('D_LZ_2>D_LZ', 'D_LZ_2', 'D_LZ', 1.844),
  ('D_LZ>D_LZ_4', 'D_LZ', 'D_LZ_4', 1.844),
  ('D_LZ_4>LS_19.3', 'D_LZ_4', 'LS_19.3', 11.102);

create table loading_zone (
  id        text primary key,
  name      text not null,
  dock_node text not null references graph_node
);
insert into loading_zone (id, name, dock_node) values ('LZ-1', 'Verladezone Süd', 'D_LZ');

alter table transport_order
  drop constraint transport_order_kind_check,
  add constraint transport_order_kind_check check (kind in ('SUPPLY', 'WIP', 'PUTAWAY', 'SHIPMENT')),
  drop constraint transport_order_from_kind_check,
  add constraint transport_order_from_kind_check
    check (from_kind in ('STORAGE', 'MACHINE_IN', 'MACHINE_OUT', 'CONVEYOR', 'CHARGER', 'LOADING')),
  drop constraint transport_order_to_kind_check,
  add constraint transport_order_to_kind_check
    check (to_kind in ('STORAGE', 'MACHINE_IN', 'MACHINE_OUT', 'CONVEYOR', 'CHARGER', 'LOADING'));
