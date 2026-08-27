'use client';

import React, { useRef, useMemo } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { Float, OrbitControls, ContactShadows } from '@react-three/drei';
import * as THREE from 'three';

function VinylDisc({ isHovered }: { isHovered: boolean }) {
  const meshRef = useRef<THREE.Group>(null);

  // Realistic grooved wax surface texture.
  const grooveTexture = useMemo(() => {
    if (typeof document === 'undefined') return null;
    const size = 1024;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    const c = size / 2;

    ctx.fillStyle = '#0b0b0d';
    ctx.fillRect(0, 0, size, size);

    for (let r = c; r > c * 0.3; r -= 1.4) {
      const shade = 0.5 + Math.sin(r * 0.55) * 0.5;
      ctx.beginPath();
      ctx.arc(c, c, r, 0, Math.PI * 2);
      ctx.strokeStyle = '#2a2a30';
      ctx.globalAlpha = 0.14 + shade * 0.16;
      ctx.lineWidth = 1;
      ctx.stroke();
    }
    ctx.globalAlpha = 1;

    const sheen = ctx.createLinearGradient(0, 0, size, size);
    sheen.addColorStop(0, 'rgba(255,255,255,0.12)');
    sheen.addColorStop(0.5, 'rgba(255,255,255,0)');
    sheen.addColorStop(0.78, 'rgba(255,214,140,0.06)');
    sheen.addColorStop(1, 'rgba(0,0,0,0.2)');
    ctx.fillStyle = sheen;
    ctx.beginPath();
    ctx.arc(c, c, c, 0, Math.PI * 2);
    ctx.fill();

    const t = new THREE.CanvasTexture(canvas);
    t.anisotropy = 8;
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }, []);

  // Procedural center label texture.
  const labelTexture = useMemo(() => {
    if (typeof document === 'undefined') return null;
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;

    const grad = ctx.createRadialGradient(256, 220, 30, 256, 256, 256);
    grad.addColorStop(0, '#a8122f');
    grad.addColorStop(1, '#6b0f24');
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(256, 256, 256, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = '#e6b866';
    ctx.lineWidth = 5;
    ctx.beginPath();
    ctx.arc(256, 256, 236, 0, Math.PI * 2);
    ctx.stroke();
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(256, 256, 200, 0, Math.PI * 2);
    ctx.stroke();

    ctx.fillStyle = '#fdf6e3';
    ctx.font = '700 30px Georgia, serif';
    ctx.textAlign = 'center';
    ctx.fillText('VINYL VOICE', 256, 128);

    ctx.font = 'italic 17px Georgia, serif';
    ctx.fillStyle = '#f2cf87';
    ctx.fillText('33⅓ RPM · MASTER WAX', 256, 158);

    ctx.font = '600 20px "Courier New", monospace';
    ctx.fillStyle = '#fde9c8';
    ctx.fillText('SIDE A', 256, 372);

    ctx.font = '13px sans-serif';
    ctx.fillStyle = '#e6b866';
    ctx.fillText('TIMELESS MEMORY ARCHIVE', 256, 400);

    ctx.fillStyle = '#050505';
    ctx.beginPath();
    ctx.arc(256, 256, 22, 0, Math.PI * 2);
    ctx.fill();

    const texture = new THREE.CanvasTexture(canvas);
    texture.anisotropy = 8;
    texture.colorSpace = THREE.SRGBColorSpace;
    return texture;
  }, []);

  useFrame((_, delta) => {
    if (meshRef.current) {
      meshRef.current.rotation.y += delta * (isHovered ? 1.4 : 0.55);
    }
  });

  return (
    <group ref={meshRef} rotation={[0.32, 0, 0]}>
      {/* Wax body */}
      <mesh receiveShadow castShadow>
        <cylinderGeometry args={[2.5, 2.5, 0.07, 96]} />
        <meshPhysicalMaterial
          color="#0b0b0d"
          map={grooveTexture ?? undefined}
          roughness={0.26}
          metalness={0.4}
          clearcoat={1}
          clearcoatRoughness={0.14}
          reflectivity={0.7}
        />
      </mesh>

      {/* Glossy grooved top face */}
      <mesh position={[0, 0.037, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <circleGeometry args={[2.49, 96]} />
        <meshPhysicalMaterial
          map={grooveTexture ?? undefined}
          color="#0b0b0d"
          roughness={0.2}
          metalness={0.45}
          clearcoat={1}
          clearcoatRoughness={0.1}
        />
      </mesh>

      {/* Center label */}
      {labelTexture && (
        <mesh position={[0, 0.04, 0]} rotation={[-Math.PI / 2, 0, 0]}>
          <circleGeometry args={[0.86, 96]} />
          <meshStandardMaterial map={labelTexture} roughness={0.85} metalness={0.05} />
        </mesh>
      )}

      {/* Spindle */}
      <mesh position={[0, 0.09, 0]}>
        <cylinderGeometry args={[0.03, 0.03, 0.12, 24]} />
        <meshStandardMaterial color="#e5e7eb" metalness={0.95} roughness={0.15} />
      </mesh>
    </group>
  );
}

export default function FloatingVinylHero() {
  const [isHovered, setIsHovered] = React.useState(false);

  return (
    <div
      className="w-full h-full min-h-[380px] sm:min-h-[460px] relative cursor-grab active:cursor-grabbing"
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      <Canvas
        camera={{ position: [0, 1.6, 5], fov: 40 }}
        shadows
        dpr={[1, 2]}
        gl={{ antialias: true, alpha: true }}
      >
        <ambientLight intensity={0.55} />
        <directionalLight position={[4, 6, 4]} intensity={2} color="#fff7ec" castShadow />
        <pointLight position={[-3, 1, -2]} intensity={1.2} color="#f59e0b" />
        <pointLight position={[0, 3, 3]} intensity={1.1} color="#fde68a" />

        <Float speed={1.6} rotationIntensity={0.35} floatIntensity={0.7}>
          <VinylDisc isHovered={isHovered} />
        </Float>

        <ContactShadows position={[0, -2.1, 0]} opacity={0.5} scale={11} blur={2.8} far={4} color="#000000" />

        <OrbitControls
          enableZoom={false}
          enablePan={false}
          maxPolarAngle={Math.PI / 2 + 0.15}
          minPolarAngle={Math.PI / 5}
          rotateSpeed={0.6}
        />
      </Canvas>
    </div>
  );
}
