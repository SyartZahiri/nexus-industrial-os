import { hrefOf } from '../../shell/route';
import { sceneOf, useFactory } from '../../store/factoryStore';
import { PROCESS_LABEL } from '../../twin/config';
import type { SceneMachine } from '../../twin/types';
import { nf, pct } from '../../ui/format';
import { DataTable, Section, StateTag, type Column } from '../../ui/kit';
import { MachineDetails } from '../../ui/panels/DetailPanel';
import { pickMachine } from './pick';

const columns: Column<SceneMachine>[] = [
  { key: 'id', label: 'Maschine', value: (m) => m.id, mono: true, width: '96px' },
  { key: 'name', label: 'Name', value: (m) => m.name },
  { key: 'status', label: 'Zustand', value: (m) => m.status, render: (m) => <StateTag kind="machine" status={m.status} />, width: '120px' },
  { key: 'abilities', label: 'Fähigkeiten', value: (m) => m.capabilities.map((c) => PROCESS_LABEL[c]).join(', ') },
  {
    key: 'progress',
    label: 'Fortschritt',
    value: (m) => (m.job ? m.job.progress / m.job.duration : null),
    render: (m) => (m.job ? pct(m.job.progress / m.job.duration) : '–'),
    align: 'right',
    mono: true,
    width: '96px',
  },
  {
    key: 'temp',
    label: 'Temperatur',
    value: (m) => m.sensors.temperature,
    render: (m) => `${nf(m.sensors.temperature, 1)} °C`,
    align: 'right',
    mono: true,
    width: '104px',
  },
  { key: 'wear', label: 'Verschleiss', value: (m) => m.wear, render: (m) => pct(m.wear), align: 'right', mono: true, width: '96px' },
];

/** Every machine as a row; the machine of the link (#/machines/CNC-02) shows its sensors and commands beside. */
export function MachinesArea({ id }: { id: string | null }) {
  const machines = useFactory((s) => sceneOf(s).machines);
  const { machine, unknown } = pickMachine(machines, id);
  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-8 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Alle Maschinen" className="col-span-12 xl:col-span-8">
        {unknown && (
          <p role="alert" className="mb-3 text-[13px] text-txt-1">
            Eine Maschine <span className="font-mono">{unknown}</span> gibt es nicht. Wähle eine aus der Liste.
          </p>
        )}
        <DataTable
          label="Maschinen"
          rows={machines}
          columns={columns}
          rowKey={(m) => m.id}
          onRowClick={(m) => {
            window.location.hash = hrefOf('machines', m.id);
          }}
          empty="Noch keine Daten der Anlage."
        />
      </Section>
      <Section title="Auswahl" className="col-span-12 xl:col-span-4">
        {machine ? (
          <MachineDetails m={machine} onClose={() => (window.location.hash = hrefOf('machines'))} />
        ) : (
          <p className="text-[13px] text-txt-3">Wähle eine Maschine, um Sensoren, Auftrag und Befehle zu sehen.</p>
        )}
      </Section>
    </div>
  );
}
