'use client';

import React, { useRef, useMemo } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { Float, OrbitControls } from '@react-three/drei';
import * as THREE from 'three';

function VinylDisc({ isHovered }: { isHovered: boolean }) {
  const meshRef = useRef<THREE.Group>(null);

  // Generate procedural center label texture
  const labelTexture = useMemo(() => {
    if (typeof document === 'undefined') return null;
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;

    // Dark crimson background
    ctx.fillStyle = '#831843';
    ctx.fillRect(0, 0, 512, 512);

    // Outer gold border ring
    ctx.strokeStyle = '#f59e0b';
    ctx.lineWidth = 8;
    ctx.beginPath();
    ctx.arc(256, 256, 240, 0, Math.PI * 2);
    ctx.stroke();

    // Inner gold ring
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(256, 256, 180, 0, Math.PI * 2);
    ctx.stroke();

    // Center spindle hole circle
    ctx.fillStyle = '#0c0a09';
    ctx.beginPath();
    ctx.arc(256, 256, 32, 0, Math.PI * 2);
    ctx.fill();

    // Gold Text
    ctx.fillStyle = '#fef3c7';
    ctx.font = 'bold 28px serif';
    ctx.textAlign = 'center';
    ctx.fillText('VINYL VOICE', 256, 120);

    ctx.font = 'italic 18px serif';
    ctx.fillStyle = '#fbbf24';
    ctx.fillText('33⅓ RPM • MASTER WAX', 256, 150);

    ctx.font = 'bold 22px monospace';
    ctx.fillStyle = '#fde68a';
    ctx.fillText('SIDE A — 1925', 256, 380);

    ctx.font = '14px sans-serif';
    ctx.fillStyle = '#d97706';
    ctx.fillText('TIMELESS MEMORY ARCHIVE', 256, 420);

    const texture = new THREE.CanvasTexture(canvas);
    texture.anisotropy = 8;
    return texture;
  }, []);

  useFrame((_, delta) => {
    if (meshRef.current) {
      meshRef.current.rotation.y += delta * (isHovered ? 1.5 : 0.6);
    }
  });

  return (
    <group ref={meshRef}>
      {/* Outer Vinyl Black Disc */}
      <mesh receiveShadow castShadow>
        <cylinderGeometry args={[2.5, 2.5, 0.05, 64]} />
        <meshPhysicalMaterial
          color="#111111"
          roughness={0.35}
          metalness={0.8}
          clearcoat={0.5}
          clearcoatRoughness={0.1}
          reflectivity={0.9}
        />
      </mesh>

      {/* Concentric Vinyl Grooves (subtle rings) */}
      {[0.9, 1.2, 1.5, 1.8, 2.1, 2.35].map((radius, i) => (
        <mesh key={i} position={[0, 0.026, 0]} rotation={[-Math.PI / 2, 0, 0]}>
          <ringGeometry args={[radius - 0.02, radius, 64]} />
          <meshBasicMaterial color="#242424" transparent opacity={0.6} />
        </mesh>
      ))}

      {/* Center Label (Top) */}
      {labelTexture && (
        <mesh position={[0, 0.027, 0]} rotation={[-Math.PI / 2, 0, 0]}>
          <circleGeometry args={[0.82, 64]} />
          <meshBasicMaterial map={labelTexture} />
        </mesh>
      )}

      {/* Center Spindle Metal Ring */}
      <mesh position={[0, 0.03, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[0.08, 0.16, 32]} />
        <meshStandardMaterial color="#f59e0b" metalness={0.9} roughness={0.2} />
      </mesh>
    </group>
  );
}

function FloatingDust() {
  const particlesCount = 120;
  const positions = useMemo(() => {
    const arr = new Float32Array(particlesCount * 3);
    for (let i = 0; i < particlesCount * 3; i += 3) {
      arr[i] = (Math.random() - 0.5) * 8;
      arr[i + 1] = (Math.random() - 0.5) * 6;
      arr[i + 2] = (Math.random() - 0.5) * 8;
    }
    return arr;
  }, []);

  const pointsRef = useRef<THREE.Points>(null);

  useFrame((state) => {
    if (pointsRef.current) {
      pointsRef.current.rotation.y = state.clock.getElapsedTime() * 0.03;
      pointsRef.current.rotation.x = Math.sin(state.clock.getElapsedTime() * 0.02) * 0.1;
    }
  });

  return (
    <points ref={pointsRef}>
      <bufferGeometry>
        <bufferAttribute
          attach="attributes-position"
          args={[positions, 3]}
        />
      </bufferGeometry>
      <pointsMaterial
        size={0.045}
        color="#fbbf24"
        transparent
        opacity={0.65}
        blending={THREE.AdditiveBlending}
      />
    </points>
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
        camera={{ position: [0, 2.2, 4.2], fov: 42 }}
        shadows
        gl={{ antialias: true, alpha: true }}
      >
        <ambientLight intensity={0.7} />
        <spotLight
          position={[4, 6, 4]}
          angle={0.4}
          penumbra={0.9}
          intensity={2.5}
          color="#fef3c7"
          castShadow
        />
        <pointLight position={[-3, -2, -2]} intensity={1.2} color="#d97706" />
        <pointLight position={[0, 3, 2]} intensity={1.8} color="#fbbf24" />

        <Float speed={2} rotationIntensity={0.6} floatIntensity={0.8}>
          <VinylDisc isHovered={isHovered} />
        </Float>

        <FloatingDust />

        <OrbitControls
          enableZoom={false}
          enablePan={false}
          maxPolarAngle={Math.PI / 2 + 0.2}
          minPolarAngle={Math.PI / 4}
          rotateSpeed={0.6}
        />
      </Canvas>
    </div>
  );
}
