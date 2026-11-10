/**
 * Fixed DOM container for the floating 3D labels. Without it drei's <Html> attaches to the canvas
 * event target, which only exists after the first frame, and every label rebuilds its React root.
 */
export const labelPortal: { current: HTMLDivElement } = { current: null as unknown as HTMLDivElement };
