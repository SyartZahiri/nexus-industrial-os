import { expect, test } from '@playwright/test';

function expectedMode(baseURL: string | undefined): 'Browser' | 'Server' {
  return baseURL?.includes(':5179') ? 'Server' : 'Browser';
}

test('RouteMind zeigt Karte, Fahrzeuge und die erste Planung', async ({ page, baseURL }) => {
  test.setTimeout(6 * 60_000);
  await page.goto('/#/routemind');
  await expect(page.getByText(`${expectedMode(baseURL)} · verbunden`)).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('region', { name: 'Karte der Touren' })).toBeVisible();
  await expect(page.locator('.maplibregl-canvas')).toBeVisible();
  await expect(page.getByText(/OpenStreetMap-Mitwirkende/).first()).toBeVisible();
  await expect(page.getByRole('table', { name: 'Lieferfahrzeuge' }).getByRole('row')).toHaveCount(5); // head + 4
  await page.getByRole('button', { name: '25×', exact: true }).click();
  const start = page.getByRole('button', { name: 'Start', exact: true });
  if (await start.isVisible()) await start.click();
  // waves are planned from 30 simulated minutes on; the first plan needs goods, about one simulated hour
  await expect(page.getByText(/Noch keine Planung/)).toBeHidden({ timeout: 5 * 60_000 });
  await expect(page.getByText(/Löser: (Heuristik|OR-Tools)/)).toBeVisible();
});
