import { describe, expect, it } from 'vitest';
import { pickMachine } from './pick';

const machines = [{ id: 'CNC-01' }, { id: 'CNC-02' }];

describe('pickMachine', () => {
  it('finds the machine of the link', () => {
    expect(pickMachine(machines, 'CNC-02')).toEqual({ machine: { id: 'CNC-02' }, unknown: null });
  });

  it('anUnknownMachineIdIsSaidAndTheListStays', () => {
    expect(pickMachine(machines, 'CNC-99')).toEqual({ machine: null, unknown: 'CNC-99' });
    expect(pickMachine(machines, null)).toEqual({ machine: null, unknown: null });
    expect(pickMachine([], 'CNC-01')).toEqual({ machine: null, unknown: null }); // no scene yet: not unknown, just not loaded
  });
});
