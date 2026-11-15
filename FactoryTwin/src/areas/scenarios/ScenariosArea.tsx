import { useState } from 'react';
import type { ScenarioList, ScenarioRun } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { send, useFactory } from '../../store/factoryStore';
import { clock, SCENARIO_STATE } from '../../ui/format';
import { CodeTag, Section } from '../../ui/kit';
import { Button } from '../../ui/primitives';
import { activeRun, lastRunOf } from './scenarioView';

const link = 'text-[12px] text-txt-2 underline decoration-line-2 underline-offset-4 hover:text-txt-1';

function RunSteps({ run }: { run: ScenarioRun }) {
  return (
    <ol className="mt-2 flex flex-col gap-1 border-l border-line pl-3">
      {run.steps.map((s, i) => (
        <li key={i} className="grid grid-cols-[64px_minmax(0,1fr)] gap-2 text-[12px]">
          <span className="font-mono text-txt-3 tnum">{clock(s.atMs / 1000)}</span>
          <span className="text-txt-1">{s.text}</span>
        </li>
      ))}
    </ol>
  );
}

/** Spec 15: five scripted interventions, started here and watched by the core until they pass or time out. */
export function ScenariosArea() {
  const running = useFactory((s) => s.sim.running);
  const { data, reload } = usePlantQuery<ScenarioList>({ query: 'scenarios' }, 1_000);
  const active = activeRun(data);
  const [busy, setBusy] = useState(false);
  const startScenario = async (id: string) => {
    setBusy(true);
    try {
      await send({ command: 'runScenario', scenario: id }); // a refusal (409, 404) shows its reason in the notice line
      reload();
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="flex flex-col gap-10 px-4 py-6 lg:px-6 lg:py-8">
      {!running && (
        <p className="text-[13px] text-txt-2">
          Die Simulation ist pausiert. Szenarien laufen in der Simulationszeit; starte die Simulation oben, Tempo 25× verkürzt das Warten.
        </p>
      )}
      <Section title="Szenarien">
        {!data ? (
          <p className="text-[13px] text-txt-3">Szenarien werden geladen …</p>
        ) : (
          <ul className="flex flex-col">
            {data.definitions.map((d) => {
              const last = lastRunOf(data, d.id);
              return (
                <li
                  key={d.id}
                  aria-label={`Szenario ${d.id}: ${d.title}`}
                  className="grid grid-cols-1 gap-x-6 gap-y-2 border-b border-line py-4 last:border-b-0 lg:grid-cols-[minmax(0,1fr)_200px_auto] lg:items-start"
                >
                  <div className="min-w-0">
                    <h3 className="text-[15px] font-semibold text-txt-1">
                      <span className="mr-2 font-mono text-txt-3">{d.id}</span>
                      {d.title}
                    </h3>
                    <p className="mt-1 text-[13px] text-txt-2">Auslöser: {d.trigger}</p>
                    <p className="text-[13px] text-txt-2">Erwartet: {d.expected}</p>
                    <p className="text-[12px] text-txt-3">Zeitgrenze {d.limitMinutes} min Simulationszeit</p>
                  </div>
                  <div className="flex flex-col gap-1">
                    {last ? (
                      <CodeTag map={SCENARIO_STATE} code={last.state} />
                    ) : (
                      <span className="text-[12px] text-txt-3">Noch nicht gelaufen</span>
                    )}
                    {last?.orderId && (
                      <span className="flex gap-3">
                        <a href={hrefOf('events', last.orderId)} className={link}>
                          Ereignisse
                        </a>
                        <a href={hrefOf('smartfactory', last.orderId)} className={link}>
                          Auftrag {last.orderId}
                        </a>
                      </span>
                    )}
                  </div>
                  <Button onClick={() => void startScenario(d.id)} disabled={busy || active !== null}>
                    Starten
                  </Button>
                </li>
              );
            })}
          </ul>
        )}
      </Section>
      <Section title="Verlauf">
        {!data || data.runs.length === 0 ? (
          <p className="text-[13px] text-txt-3">Noch kein Szenario gelaufen.</p>
        ) : (
          <ul className="flex flex-col">
            {data.runs.map((r) => {
              const d = data.definitions.find((x) => x.id === r.scenario);
              return (
                <li key={r.runId} aria-label={`Lauf ${r.runId}`} className="border-b border-line py-3 last:border-b-0">
                  <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
                    <span className="font-mono text-[12px] text-txt-3">{r.runId}</span>
                    <span className="text-[13px] text-txt-1">
                      {r.scenario} {d?.title}
                    </span>
                    <CodeTag map={SCENARIO_STATE} code={r.state} />
                    <span className="font-mono text-[12px] text-txt-3 tnum">ab {clock(r.startedAtMs / 1000)}</span>
                  </div>
                  <RunSteps run={r} />
                </li>
              );
            })}
          </ul>
        )}
      </Section>
    </div>
  );
}
