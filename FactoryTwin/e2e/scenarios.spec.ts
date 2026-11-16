import { expect, test, type Page } from '@playwright/test';

/** The runtime this configuration is meant to test: the server config talks to 5179, the browser config to 5173. */
function expectedMode(baseURL: string | undefined): 'Browser' | 'Server' {
  return baseURL?.includes(':5179') ? 'Server' : 'Browser';
}

/**
 * A fresh run of the plant at 25×, so every scenario starts from the same seed and state. On the server all tests
 * share one plant whose clock keeps running across a reset, so Start is only pressed when the simulation stands.
 */
async function freshPlant(page: Page, baseURL: string | undefined) {
  await page.goto('/#/scenarios');
  await expect(page.getByText(`${expectedMode(baseURL)} · verbunden`), 'falsche Betriebsart: ein wiederverwendeter Server?').toBeVisible({
    timeout: 60_000,
  });
  await page.getByRole('button', { name: /^Reset/ }).click();
  await page.getByRole('button', { name: 'Ja', exact: true }).click();
  await expect(page.getByText('Noch kein Szenario gelaufen.')).toBeVisible(); // the page has seen the new run
  await page.getByRole('button', { name: '25×', exact: true }).click();
  const start = page.getByRole('button', { name: 'Start', exact: true });
  if (await start.isVisible()) await start.click();
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeVisible();
}

const SCENARIOS: [id: string, title: string][] = [
  ['A', 'Maschinenausfall'],
  ['B', 'AGV-Ausfall'],
  ['C', 'Materialengpass'],
  ['D', 'Expressauftrag'],
  ['E', 'Kompletter Durchlauf'],
];

for (const [id, title] of SCENARIOS) {
  test(`Szenario ${id}: ${title}`, async ({ page, baseURL }) => {
    test.setTimeout(id === 'E' ? 20 * 60_000 : 8 * 60_000);
    await freshPlant(page, baseURL);
    const row = page.getByRole('listitem', { name: `Szenario ${id}: ${title}` });
    await row.getByRole('button', { name: `Szenario ${id} starten` }).click();
    // E needs up to six simulated hours: at 25× that is under 15 real minutes, usually far less
    await expect(row.getByText('Bestanden', { exact: true })).toBeVisible({ timeout: id === 'E' ? 15 * 60_000 : 6 * 60_000 });

    // the chain of the scenario in the event monitor, filtered by its order
    await row.getByRole('link', { name: 'Ereignisse' }).click();
    await expect(page).toHaveURL(/#\/events\/PO-/);
    await expect(page.getByRole('cell', { name: 'scenario.started' })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'scenario.finished' })).toBeVisible();
  });
}
