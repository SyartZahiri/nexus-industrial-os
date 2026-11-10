import type { SimulationEngine } from '../sim/engine';
import type { DomainEvent, DomainEventType, FactoryCommands, FactoryTwinApi, RoutePlanner } from './contracts';

export const API_VERSION = '1.0.0';

/**
 * Creates the public API facade around an engine. External systems (and the browser console via
 * `window.FactoryTwin`) only ever see this facade.
 */
export function createFactoryApi(getEngine: () => SimulationEngine): FactoryTwinApi {
  const commandNames: (keyof FactoryCommands)[] = [
    'play',
    'pause',
    'setSpeed',
    'reset',
    'startMachine',
    'stopMachine',
    'injectFault',
    'repair',
    'maintain',
    'configureMachine',
    'startConveyor',
    'stopConveyor',
    'setConveyorSpeed',
    'setAgvEnabled',
    'sendAgvToCharge',
    'createOrder',
    'cancelOrder',
    'setOrderPriority',
    'setRules',
    'orderRawMaterial',
    'acknowledgeAlarm',
  ];
  const commands = {} as Record<string, unknown>;
  for (const name of commandNames) {
    commands[name] = (...args: unknown[]) => (getEngine()[name] as (...a: unknown[]) => unknown).apply(getEngine(), args);
  }
  return {
    version: API_VERSION,
    commands: commands as unknown as FactoryCommands,
    subscribe<T extends DomainEventType>(type: T | '*', handler: (e: Extract<DomainEvent, { type: T }>) => void) {
      return getEngine().bus.on(type, handler);
    },
    getTelemetry: () => getEngine().getTelemetry(),
    setRoutePlanner: (planner: RoutePlanner | null) => getEngine().setRoutePlanner(planner),
  };
}
