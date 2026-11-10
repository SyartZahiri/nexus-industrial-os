-- Values taken from FactoryTwin/src/sim/config.ts and initialState.ts.
-- Materials and bills of material are new: the former engine only had a single raw part counter.

insert into product (id, name, short_name, color) values
  ('GH200', 'Getriebegehäuse GH-200', 'GH-200', '#3987e5'),
  ('AW50',  'Antriebswelle AW-50',    'AW-50',  '#d95926'),
  ('FP12',  'Flanschplatte FP-12',    'FP-12',  '#199e70'),
  ('MT7',   'Motorträger MT-7',       'MT-7',   '#c98500');

-- Assembly cells and the QC cell have no dock of their own; they are fed through their infeed conveyor,
-- so dock_node points at that conveyor's dock.
insert into machine (id, name, kind, model, capabilities, pos_x, pos_z, dock_node) values
  ('CNC-01', 'Fräszentrum 1',    'CNC',   'VMC-850 · 3-Achs-Vertikal',  '{FRAESEN}',        -12,   -10,   'D_CNC01'),
  ('CNC-02', 'Fräszentrum 2',    'CNC',   'VMC-850 · 3-Achs-Vertikal',  '{FRAESEN}',         -4,   -10,   'D_CNC02'),
  ('CNC-03', 'Drehzentrum',      'CNC',   'TC-400 · CNC-Drehmaschine',  '{DREHEN}',           4,   -10,   'D_CNC03'),
  ('CNC-04', '5-Achs-Zentrum',   'CNC',   'UMC-500 · 5-Achs-Universal', '{DREHEN,FRAESEN}',  12,   -10,   'D_CNC04'),
  ('ROB-01', 'Montagezelle 1',   'ROBOT', 'Knickarm 6-Achs · 12 kg',    '{MONTAGE}',         -7.3,  10.9, 'D_CV01'),
  ('ROB-02', 'Montagezelle 2',   'ROBOT', 'Knickarm 6-Achs · 12 kg',    '{MONTAGE}',          1.7,  10.9, 'D_CV02'),
  ('QC-01',  'Qualitätsprüfung', 'QC',    'Vision- & Laser-Messzelle',  '{PRUEFEN}',         14.8,  12.6, 'D_CV03');

insert into routing_step (product_id, seq, process, base_time_s, preferred_machine_id) values
  ('GH200', 1, 'FRAESEN', 85, 'CNC-01'), ('GH200', 2, 'MONTAGE', 65, 'ROB-01'), ('GH200', 3, 'PRUEFEN', 28, 'QC-01'),
  ('AW50',  1, 'DREHEN',  70, 'CNC-03'), ('AW50',  2, 'FRAESEN', 45, 'CNC-02'), ('AW50',  3, 'PRUEFEN', 22, 'QC-01'),
  ('FP12',  1, 'FRAESEN', 55, 'CNC-02'), ('FP12',  2, 'PRUEFEN', 18, 'QC-01'),
  ('MT7',   1, 'DREHEN',  60, 'CNC-04'), ('MT7',   2, 'MONTAGE', 80, 'ROB-02'), ('MT7',   3, 'PRUEFEN', 30, 'QC-01');

insert into material (id, name, kind, reorder_point, reorder_qty) values
  ('RM-GH200',  'Gussrohling GH-200',          'RAW',      12, 40),
  ('RM-AW50',   'Rundstahl AW-50',             'RAW',      12, 40),
  ('RM-FP12',   'Plattenzuschnitt FP-12',      'RAW',      12, 40),
  ('RM-MT7',    'Schmiederohling MT-7',        'RAW',      12, 40),
  ('KIT-GH200', 'Lager- und Dichtsatz GH-200', 'KIT',       8, 30),
  ('KIT-MT7',   'Befestigungssatz MT-7',       'KIT',       8, 30),
  ('FG-GH200',  'Getriebegehäuse GH-200',      'FINISHED',  0,  0),
  ('FG-AW50',   'Antriebswelle AW-50',         'FINISHED',  0,  0),
  ('FG-FP12',   'Flanschplatte FP-12',         'FINISHED',  0,  0),
  ('FG-MT7',    'Motorträger MT-7',            'FINISHED',  0,  0);

insert into bom_item (product_id, seq, material_id, qty) values
  ('GH200', 1, 'RM-GH200', 1), ('GH200', 2, 'KIT-GH200', 1),
  ('AW50',  1, 'RM-AW50',  1),
  ('FP12',  1, 'RM-FP12',  1),
  ('MT7',   1, 'RM-MT7',   1), ('MT7',   2, 'KIT-MT7',   1);

insert into storage_location (id, name, zone, dock_node, capacity) values
  ('RAW-A', 'Rohlager Regal A',    'RAW',      'D_RAW', 120),
  ('RAW-B', 'Rohlager Regal B',    'RAW',      'D_RAW', 120),
  ('FG-A',  'Fertiglager Regal A', 'FINISHED', 'D_FG',  160);

insert into stock (material_id, location_id, on_hand) values
  ('RM-GH200', 'RAW-A', 30), ('RM-AW50', 'RAW-A', 30), ('RM-FP12', 'RAW-A', 30), ('RM-MT7', 'RAW-A', 30),
  ('KIT-GH200', 'RAW-B', 24), ('KIT-MT7', 'RAW-B', 24),
  ('FG-GH200', 'FG-A', 0), ('FG-AW50', 'FG-A', 0), ('FG-FP12', 'FG-A', 0), ('FG-MT7', 'FG-A', 0);
