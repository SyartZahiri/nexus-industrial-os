import { Bloom, EffectComposer, Vignette } from '@react-three/postprocessing';

/** Post-processing is its own chunk: only downloaded when the "Hoch" quality preset is active. */
export default function Effects() {
  return (
    <EffectComposer multisampling={4}>
      <Bloom mipmapBlur luminanceThreshold={1} luminanceSmoothing={0.2} intensity={0.75} radius={0.6} />
      <Vignette offset={0.25} darkness={0.55} />
    </EffectComposer>
  );
}
