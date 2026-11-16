import { expect, test } from '@playwright/test';

/**
 * Spec 14: 60 frames per second in the 3D view at 25×, on a usual laptop. Headless Chrome renders without a GPU, so
 * the test only measures and reports; it enforces a floor only when FPS_MIN is set (run headed on the laptop).
 */
test('FactoryTwin bei 25-fachem Tempo', async ({ page }, info) => {
  await page.goto('/#/twin');
  await expect(page.getByText(/Browser · verbunden/)).toBeVisible({ timeout: 60_000 });
  await page.getByRole('button', { name: '25×', exact: true }).click();
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  await page.waitForTimeout(5_000); // let the scene load and the plant fill up
  const fps = await page.evaluate(
    () =>
      new Promise<number>((resolve) => {
        let frames = 0;
        const t0 = performance.now();
        const tick = () => {
          frames++;
          const dt = performance.now() - t0;
          if (dt < 5_000) requestAnimationFrame(tick);
          else resolve((frames * 1000) / dt);
        };
        requestAnimationFrame(tick);
      }),
  );
  info.annotations.push({ type: 'fps', description: fps.toFixed(1) });
  console.log(`FactoryTwin bei 25×: ${fps.toFixed(1)} Bilder/s`);
  expect(fps).toBeGreaterThanOrEqual(Number(process.env.FPS_MIN ?? 0));
});
