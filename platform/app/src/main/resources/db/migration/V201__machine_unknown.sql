-- M1-D3: a machine the OPC UA gateway cannot reach is UNKNOWN (Spec 9)
alter table machine drop constraint machine_status_check;
alter table machine add constraint machine_status_check check (status in
  ('IDLE', 'SETUP', 'RUNNING', 'BLOCKED', 'STOPPED', 'FAULT', 'REPAIR', 'MAINTENANCE', 'UNKNOWN'));
alter table machine_state_interval drop constraint machine_state_interval_status_check;
alter table machine_state_interval add constraint machine_state_interval_status_check check (status in
  ('IDLE', 'SETUP', 'RUNNING', 'BLOCKED', 'STOPPED', 'FAULT', 'REPAIR', 'MAINTENANCE', 'UNKNOWN'));
