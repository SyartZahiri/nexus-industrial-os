import { useState } from 'react';
import type { FrameTransport, Network } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { sceneOf, send, twin, useFactory } from '../../store/factoryStore';
import type { SceneAgv } from '../../twin/types';
import { nf } from '../../ui/format';
import { control, DataTable, Field, Section, StateTag, type Column } from '../../ui/kit';
import { AgvDetails } from '../../ui/panels/DetailPanel';
import { Button } from '../../ui/primitives';
import { NetworkMap } from './NetworkMap';
import { edgeAction } from './network';

const agvColumns: Column<SceneAgv>[] = [
  { key: 'id', label: 'AGV', value: (a) => a.id, mono: true, width: '88px' },
  { key: 'state', label: 'Zustand', value: (a) => a.state, render: (a) => <StateTag kind="agv" status={a.state} />, width: '140px' },
  { key: 'battery', label: 'Akku', value: (a) => a.battery, render: (a) => `${nf(a.battery)} %`, align: 'right', mono: true, width: '80px' },
  { key: 'transport', label: 'Transport', value: (a) => a.transportId, render: (a) => a.transportId ?? '–', mono: true },
  { key: 'blocked', label: 'Wartet auf', value: (a) => a.blockedBy, render: (a) => a.blockedBy ?? '–', mono: true },
];

const transportColumns: Column<FrameTransport>[] = [
  { key: 'id', label: 'Transport', value: (t) => t.id, mono: true, width: '112px' },
  { key: 'kind', label: 'Art', value: (t) => t.kind, mono: true, width: '88px' },
  { key: 'status', label: 'Stand', value: (t) => t.status, mono: true },
  { key: 'agv', label: 'AGV', value: (t) => t.agvId, render: (t) => t.agvId ?? '–', mono: true },
  { key: 'route', label: 'Von → Nach', value: (t) => `${t.from} → ${t.to}`, mono: true },
  {
    key: 'order',
    label: 'Auftrag',
    value: (t) => t.orderId,
    mono: true,
    render: (t) =>
      t.orderId ? (
        <a href={hrefOf('smartfactory', t.orderId)} className="underline decoration-line-2 underline-offset-4">
          {t.orderId}
        </a>
      ) : (
        '–'
      ),
  },
];

/** The fleet, open transports and the path network with blocking; the AGV of the link opens its details beside. */
export function FlowPilotArea({ agvId }: { agvId: string | null }) {
  useFactory((s) => s.scene);
  const agvs = useFactory((s) => sceneOf(s).agvs);
  const transports = twin.latest?.transports ?? [];
  const { data: network, reload } = usePlantQuery<Network>({ query: 'network' });
  const [edgeId, setEdgeId] = useState<string | null>(null);
  const edge = network?.edges.find((e) => e.id === edgeId) ?? null;
  const agv = agvId ? (agvs.find((a) => a.id === agvId) ?? null) : null;
  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-10 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Flotte" className="col-span-12 xl:col-span-8">
        {agvId && agvs.length > 0 && !agv && (
          <p role="alert" className="mb-3 text-[13px] text-txt-1">
            Ein AGV <span className="font-mono">{agvId}</span> gibt es nicht.
          </p>
        )}
        <DataTable
          label="AGV-Flotte"
          rows={agvs}
          columns={agvColumns}
          rowKey={(a) => a.id}
          onRowClick={(a) => {
            window.location.hash = hrefOf('flowpilot', a.id);
          }}
          empty="Noch keine Daten der Anlage."
        />
      </Section>
      <Section title={agv ? agv.id : 'Auswahl'} className="col-span-12 xl:col-span-4">
        {agv ? <AgvDetails a={agv} /> : <p className="text-[13px] text-txt-3">Wähle ein AGV für Akku, Auftrag und Befehle.</p>}
      </Section>
      <Section title="Wegenetz" className="col-span-12 xl:col-span-8">
        {network ? (
          <NetworkMap network={network} agvs={agvs} selected={edgeId} onSelect={setEdgeId} />
        ) : (
          <p className="text-[13px] text-txt-3">Wegenetz wird geladen …</p>
        )}
      </Section>
      <Section title="Kante sperren" className="col-span-12 xl:col-span-4">
        {network && (
          <div className="flex flex-col gap-3">
            <Field label="Kante">
              <select className={control} value={edgeId ?? ''} onChange={(e) => setEdgeId(e.target.value || null)}>
                <option value="">Kante wählen</option>
                {network.edges.map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.id} ({e.from} → {e.to}){e.blocked ? ', gesperrt' : ''}
                  </option>
                ))}
              </select>
            </Field>
            {edge && (
              <Button
                onClick={async () => {
                  await send(edgeAction(edge).command);
                  reload();
                }}
              >
                {edgeAction(edge).label}
              </Button>
            )}
            <p className="text-[12px] leading-relaxed text-txt-3">
              Eine gesperrte Kante plant FlowPilot um. Ohne gültige Route wartet der Transport und meldet route.blocked im Event Monitor.
            </p>
          </div>
        )}
      </Section>
      <Section title="Offene Transporte" className="col-span-12">
        <DataTable label="Offene Transporte" rows={transports} columns={transportColumns} rowKey={(t) => t.id} empty="Keine offenen Transporte." />
      </Section>
    </div>
  );
}
