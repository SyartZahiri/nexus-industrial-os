import { Environment, Lightformer } from '@react-three/drei';
import { Canvas } from '@react-three/fiber';
import { lazy, Suspense } from 'react';
import * as THREE from 'three';
import { plant, useFactory } from '../store/factoryStore';
import { Hall } from './Hall';
import { LoadingZone } from './LoadingZone';
import { AgvFleet, Chargers, ConveyorModel, Warehouse } from './Logistics';
import { CncMachine, QcStation, RobotCell } from './Machines';
import { CameraRig, Labels, SelectionOverlay } from './Overlay';
import { Workpieces } from './Workpieces';

const Effects = lazy(() => import('./Effects'));

function Lights({ shadows }: { shadows: boolean; }) {
  const quality = useFactory((s) => s.quality);
  const map = quality === 'high' ? 2048 : 1024;
  return (
    <>
      <hemisphereLight args={['#c3d4ec', '#1a1d22', 0.85]} />
      <ambientLight intensity={0.18} />
      <directionalLight
        position={[22, 38, 16]}
        intensity={2.1}
        color="#f3f6ff"
        castShadow={shadows}
        shadow-mapSize={[map, map]}
        shadow-camera-left={-38}
        shadow-camera-right={38}
        shadow-camera-top={26}
        shadow-camera-bottom={-26}
        shadow-camera-near={1}
        shadow-camera-far={90}
        shadow-bias={-0.0004}
        shadow-normalBias={0.03}
      />
      <directionalLight position={[-30, 20, -20]} intensity={0.35} color="#7aa7ff" />
    </>
  );
}

export function FactoryScene() {
  const quality = useFactory((s) => s.quality);
  const select = useFactory((s) => s.select);
  const shadows = quality !== 'performance';
  const dpr: [number, number] = quality === 'high' ? [1, 2] : quality === 'balanced' ? [1, 1.5] : [0.75, 1];

  return (
    <Canvas
      shadows={shadows ? { type: THREE.PCFShadowMap } : false}
      dpr={dpr}
      camera={{ position: [24, 30, 40], fov: 38, near: 0.3, far: 400 }}
      gl={{ antialias: quality !== 'high', powerPreference: 'high-performance', toneMapping: THREE.ACESFilmicToneMapping, toneMappingExposure: 1.15 }}
      onPointerMissed={(e) => {
        if (e.type === 'click') select(null);
      }}
      className="!absolute inset-0"
    >
      <color attach="background" args={['#06080b']} />
      <fog attach="fog" args={['#06080b', 70, 160]} />
      <Lights shadows={shadows} />
      {quality !== 'performance' && (
        <Environment resolution={128} frames={1}>
          <Lightformer intensity={1.4} position={[0, 10, 0]} rotation-x={Math.PI / 2} scale={[40, 6, 1]} color="#dfe9ff" />
          <Lightformer intensity={0.6} position={[-20, 5, -10]} rotation-y={Math.PI / 2} scale={[20, 4, 1]} color="#7dd3fc" />
          <Lightformer intensity={0.5} position={[20, 4, 10]} rotation-y={-Math.PI / 2} scale={[20, 4, 1]} color="#fde68a" />
        </Environment>
      )}
      <Suspense fallback={null}>
        <Hall />
        {plant.state.machines.map((m) =>
          m.kind === 'cnc' ? <CncMachine key={m.id} id={m.id} /> : m.kind === 'robot' ? <RobotCell key={m.id} id={m.id} /> : <QcStation key={m.id} id={m.id} />,
        )}
        {plant.state.conveyors.map((c) => (
          <ConveyorModel key={c.id} id={c.id} />
        ))}
        <Warehouse kind="raw" />
        <Warehouse kind="finished" />
        <Chargers />
        <LoadingZone />
        <AgvFleet />
        <Workpieces />
        <SelectionOverlay />
        <Labels />
      </Suspense>
      <CameraRig />
      {quality === 'high' && (
        <Suspense fallback={null}>
          <Effects />
        </Suspense>
      )}
    </Canvas>
  );
}
