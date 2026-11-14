declare module '@nexus-core' {
  export function plantStart(seed: number): void;
  export function plantAdvance(simMs: number): number;
  export function plantFrame(): string;
  export function plantEventsFrom(from: number): string;
  export function plantCommand(json: string): string;
  export function plantQuery(json: string): string;
}
