'use client';

import React, { useRef, useMemo, useEffect } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { OrbitControls, PerspectiveCamera } from '@react-three/drei';
import * as THREE from 'three';
import { VinylStyleType } from '@/types';
import { VINYL_STYLES } from '@/lib/constants';

interface TurntableSceneProps {
  isPlaying: boolean;
  vinylStyle?: VinylStyleType;
  title?: string;
  recipientName?: string;
  senderName?: string;
}

// 1. DUST PARTICLES (250 shimmering particles)
function DustParticles() {
  const count = 250;
  const positions = useMemo(() => {
    const pos = new Float32Array(count * 3);
    for (let i = 0; i < count * 3; i += 3) {
      pos[i] = (Math.random() - 0.5) * 5;
      pos[i + 1] = Math.random() * 2.5 + 0.2;
      pos[i + 2] = (Math.random() - 0.5) * 5;
    }
    return pos;
  }, []);

  const pointsRef = useRef<THREE.Points>(null);

  useFrame((state) => {
    if (pointsRef.current) {
      const t = state.clock.getElapsedTime();
      pointsRef.current.rotation.y = t * 0.04;
      // Gentle floating oscillation
      const positionsAttr = pointsRef.current.geometry.attributes.position;
      for (let i = 1; i < count * 3; i += 3) {
        let y = positionsAttr.array[i];
        y += Math.sin(t + i) * 0.001;
        if (y > 3) y = 0.2;
        positionsAttr.array[i] = y;
      }
      positionsAttr.needsUpdate = true;
    }
  });

  return (
    <points ref={pointsRef}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[positions, 3]} />
      </bufferGeometry>
      <pointsMaterial
        size={0.035}
        color="#fbbf24"
        transparent
        opacity={0.7}
        blending={THREE.AdditiveBlending}
      />
    </points>
  );
}

// 2. VINYL DISC WITH CUSTOM LABEL
function TurntablePlatterAndDisc({
  isPlaying,
  vinylStyle = 'classic_red',
  title = 'Anniversary Note',
  recipientName = 'Loved One',
  senderName = 'With Love',
}: {
  isPlaying: boolean;
  vinylStyle?: VinylStyleType;
  title?: string;
  recipientName?: string;
  senderName?: string;
}) {
  const platterGroupRef = useRef<THREE.Group>(null);
  const styleConfig = VINYL_STYLES.find((s) => s.id === vinylStyle) || VINYL_STYLES[0];

  // Dynamic Center Label Texture
  const centerLabelTexture = useMemo(() => {
    if (typeof document === 'undefined') return null;
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;

    // Background color based on style
    ctx.fillStyle = styleConfig.labelColor;
    ctx.fillRect(0, 0, 512, 512);

    // Outer concentric gold rings
    ctx.strokeStyle = styleConfig.brassAccent || '#f59e0b';
    ctx.lineWidth = 6;
    ctx.beginPath();
    ctx.arc(256, 256, 235, 0, Math.PI * 2);
    ctx.stroke();

    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(256, 256, 215, 0, Math.PI * 2);
    ctx.stroke();

    // Center spindle cutout ring
    ctx.fillStyle = '#0c0a09';
    ctx.beginPath();
    ctx.arc(256, 256, 32, 0, Math.PI * 2);
    ctx.fill();

    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(256, 256, 36, 0, Math.PI * 2);
    ctx.stroke();

    // Text Engraving
    ctx.fillStyle = '#fef3c7';
    ctx.textAlign = 'center';

    // Header
    ctx.font = 'bold 20px serif';
    ctx.fillText('DIGITAL WAX ARCHIVE', 256, 95);

    ctx.font = '13px monospace';
    ctx.fillStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.fillText('33⅓ RPM • STEREO MASTER', 256, 120);

    // Title
    ctx.font = 'bold 22px serif';
    ctx.fillStyle = '#ffffff';
    const displayTitle = title.length > 22 ? title.slice(0, 20) + '...' : title;
    ctx.fillText(displayTitle.toUpperCase(), 256, 175);

    // Dedication
    ctx.font = 'italic 16px serif';
    ctx.fillStyle = '#fde68a';
    if (recipientName) {
      ctx.fillText(`For: ${recipientName}`, 256, 355);
    }
    if (senderName) {
      ctx.fillText(`From: ${senderName}`, 256, 380);
    }

    // Footer Side info
    ctx.font = 'bold 15px monospace';
    ctx.fillStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.fillText('SIDE A • 1925', 256, 430);

    const texture = new THREE.CanvasTexture(canvas);
    texture.anisotropy = 8;
    return texture;
  }, [styleConfig, title, recipientName, senderName]);

  // Rotate platter at 33.3 RPM when isPlaying is true
  // 33.333 RPM = 33.333 * 2 * PI / 60 = ~3.49 rad/sec
  useFrame((_, delta) => {
    if (platterGroupRef.current && isPlaying) {
      platterGroupRef.current.rotation.y += delta * 3.49;
    }
  });

  return (
    <group position={[-0.45, 0.55, 0]}>
      {/* Platter Rim (Heavy Machined Brass & Rubber) */}
      <mesh position={[0, -0.05, 0]} castShadow receiveShadow>
        <cylinderGeometry args={[2.0, 2.05, 0.1, 64]} />
        <meshStandardMaterial color="#b45309" metalness={0.85} roughness={0.3} />
      </mesh>

      {/* Rotating Platter & Vinyl Record */}
      <group ref={platterGroupRef}>
        {/* Felt Slipmat */}
        <mesh position={[0, 0.005, 0]}>
          <cylinderGeometry args={[1.95, 1.95, 0.01, 64]} />
          <meshStandardMaterial color="#1c1917" roughness={0.9} />
        </mesh>

        {/* Vinyl Disc */}
        <mesh position={[0, 0.02, 0]} castShadow receiveShadow>
          <cylinderGeometry args={[1.9, 1.9, 0.02, 64]} />
          <meshPhysicalMaterial
            color={styleConfig.baseColor}
            roughness={0.35}
            metalness={0.8}
            clearcoat={0.4}
            clearcoatRoughness={0.1}
            reflectivity={0.9}
          />
        </mesh>

        {/* Concentric Vinyl Grooves */}
        {[0.8, 1.05, 1.3, 1.55, 1.75].map((r, i) => (
          <mesh key={i} position={[0, 0.032, 0]} rotation={[-Math.PI / 2, 0, 0]}>
            <ringGeometry args={[r - 0.015, r, 64]} />
            <meshBasicMaterial color={styleConfig.grooveColor} transparent opacity={0.65} />
          </mesh>
        ))}

        {/* Center Label */}
        {centerLabelTexture && (
          <mesh position={[0, 0.034, 0]} rotation={[-Math.PI / 2, 0, 0]}>
            <circleGeometry args={[0.65, 64]} />
            <meshBasicMaterial map={centerLabelTexture} />
          </mesh>
        )}

        {/* Center Brass Spindle Pin */}
        <mesh position={[0, 0.08, 0]}>
          <cylinderGeometry args={[0.04, 0.04, 0.15, 32]} />
          <meshStandardMaterial color="#fbbf24" metalness={0.95} roughness={0.15} />
        </mesh>
      </group>
    </group>
  );
}

// 3. HIGH-FIDELITY ANIMATED TONEARM WITH NEEDLE
function AnimatedTonearm({ isPlaying }: { isPlaying: boolean }) {
  const tonearmGroupRef = useRef<THREE.Group>(null);
  const armPitchRef = useRef<THREE.Group>(null);

  // Smoothly interpolate tonearm rotation using useFrame spring physics
  useFrame((_, delta) => {
    if (!tonearmGroupRef.current || !armPitchRef.current) return;

    // Target angles:
    // Rest: Yaw = -0.4 rad, Pitch = 0.18 rad (lifted)
    // Playing: Yaw = 0.38 rad (over groove), Pitch = -0.01 rad (needle touching wax)
    const targetYaw = isPlaying ? 0.36 : -0.42;
    const targetPitch = isPlaying ? -0.01 : 0.18;

    const lerpSpeed = delta * 3.2;

    tonearmGroupRef.current.rotation.y = THREE.MathUtils.lerp(
      tonearmGroupRef.current.rotation.y,
      targetYaw,
      lerpSpeed
    );

    armPitchRef.current.rotation.x = THREE.MathUtils.lerp(
      armPitchRef.current.rotation.x,
      targetPitch,
      lerpSpeed
    );
  });

  return (
    <group position={[1.5, 0.65, -1.0]}>
      {/* Tonearm Base Gimbal (Polished Brass Base Ring) */}
      <mesh position={[0, 0, 0]}>
        <cylinderGeometry args={[0.25, 0.3, 0.2, 32]} />
        <meshStandardMaterial color="#d97706" metalness={0.9} roughness={0.25} />
      </mesh>

      {/* Arm Rest Post */}
      <mesh position={[-0.35, 0.1, 0.55]}>
        <cylinderGeometry args={[0.03, 0.04, 0.25, 16]} />
        <meshStandardMaterial color="#78350f" metalness={0.8} roughness={0.3} />
      </mesh>
      <mesh position={[-0.35, 0.23, 0.55]} rotation={[0, 0, Math.PI / 2]}>
        <torusGeometry args={[0.04, 0.015, 8, 16, Math.PI]} />
        <meshStandardMaterial color="#d97706" metalness={0.9} roughness={0.2} />
      </mesh>

      {/* Rotating Tonearm Assembly (Yaw) */}
      <group ref={tonearmGroupRef} rotation={[0, -0.42, 0]}>
        {/* Gimbal Bearing Cube */}
        <mesh position={[0, 0.18, 0]}>
          <boxGeometry args={[0.16, 0.16, 0.16]} />
          <meshStandardMaterial color="#b45309" metalness={0.9} roughness={0.2} />
        </mesh>

        {/* Counterbalance Weight (Back) */}
        <mesh position={[0, 0.18, 0.45]} rotation={[Math.PI / 2, 0, 0]}>
          <cylinderGeometry args={[0.14, 0.14, 0.25, 32]} />
          <meshStandardMaterial color="#451a03" metalness={0.7} roughness={0.4} />
        </mesh>

        {/* Arm Pitch Pivot Group (Lifting & Lowering needle) */}
        <group ref={armPitchRef} position={[0, 0.18, 0]} rotation={[0.18, 0, 0]}>
          {/* Main Brass Tonearm Tube (Long polished rod pointing forward) */}
          <mesh position={[0, 0, -1.25]} rotation={[Math.PI / 2, 0, 0]}>
            <cylinderGeometry args={[0.025, 0.025, 2.5, 16]} />
            <meshStandardMaterial color="#f59e0b" metalness={0.92} roughness={0.18} />
          </mesh>

          {/* S-Bend or Cartridge Head at front */}
          <group position={[0, -0.02, -2.5]} rotation={[0, -0.25, 0]}>
            {/* Headshell (Vintage Angled Brass Block) */}
            <mesh position={[0, 0, -0.1]}>
              <boxGeometry args={[0.09, 0.06, 0.22]} />
              <meshStandardMaterial color="#1c1917" metalness={0.8} roughness={0.3} />
            </mesh>

            {/* Brass Needle Cartridge */}
            <mesh position={[0, -0.04, -0.1]}>
              <boxGeometry args={[0.06, 0.04, 0.12]} />
              <meshStandardMaterial color="#fbbf24" metalness={0.95} roughness={0.1} />
            </mesh>

            {/* Stylus Needle Tip */}
            <mesh position={[0, -0.08, -0.14]} rotation={[0.2, 0, 0]}>
              <coneGeometry args={[0.012, 0.06, 16]} />
              <meshStandardMaterial color="#fef3c7" metalness={0.99} roughness={0.05} />
            </mesh>

            {/* Needle Glow Light Source */}
            {isPlaying && (
              <pointLight position={[0, -0.08, -0.14]} color="#fbbf24" intensity={0.6} distance={0.8} />
            )}
          </group>
        </group>
      </group>
    </group>
  );
}

// 4. POLISHED MAHOGANY TURNTABLE CHASSIS
function TurntableBody() {
  return (
    <group position={[0, 0, 0]}>
      {/* Main Polished Mahogany Plinth Box */}
      <mesh position={[0, 0.2, 0]} castShadow receiveShadow>
        <boxGeometry args={[4.4, 0.45, 3.8]} />
        <meshStandardMaterial
          color="#23120b"
          roughness={0.4}
          metalness={0.15}
        />
      </mesh>

      {/* Top Polished Veneer Inset */}
      <mesh position={[0, 0.43, 0]} receiveShadow>
        <boxGeometry args={[4.2, 0.02, 3.6]} />
        <meshStandardMaterial
          color="#351a0f"
          roughness={0.25}
          metalness={0.1}
        />
      </mesh>

      {/* Brass Corner Protectors (4 corners) */}
      {[
        [-2.2, 0.2, -1.9],
        [2.2, 0.2, -1.9],
        [-2.2, 0.2, 1.9],
        [2.2, 0.2, 1.9],
      ].map((pos, i) => (
        <mesh key={i} position={pos as [number, number, number]}>
          <boxGeometry args={[0.15, 0.46, 0.15]} />
          <meshStandardMaterial color="#d97706" metalness={0.85} roughness={0.3} />
        </mesh>
      ))}

      {/* Rubber Turntable Isolation Feet (4 feet) */}
      {[
        [-1.8, -0.08, -1.5],
        [1.8, -0.08, -1.5],
        [-1.8, -0.08, 1.5],
        [1.8, -0.08, 1.5],
      ].map((pos, i) => (
        <mesh key={i} position={pos as [number, number, number]} castShadow>
          <cylinderGeometry args={[0.2, 0.25, 0.15, 32]} />
          <meshStandardMaterial color="#0c0a09" roughness={0.9} />
        </mesh>
      ))}

      {/* Brass Power Switch Toggle */}
      <group position={[-1.7, 0.45, 1.4]}>
        <mesh position={[0, 0.02, 0]}>
          <cylinderGeometry args={[0.1, 0.1, 0.04, 24]} />
          <meshStandardMaterial color="#b45309" metalness={0.9} roughness={0.2} />
        </mesh>
        <mesh position={[0, 0.07, 0]}>
          <boxGeometry args={[0.03, 0.08, 0.03]} />
          <meshStandardMaterial color="#fbbf24" metalness={0.95} roughness={0.1} />
        </mesh>
      </group>

      {/* 33 / 45 RPM Speed Dial */}
      <group position={[-1.2, 0.45, 1.4]}>
        <mesh position={[0, 0.02, 0]}>
          <cylinderGeometry args={[0.09, 0.09, 0.03, 24]} />
          <meshStandardMaterial color="#d97706" metalness={0.9} roughness={0.2} />
        </mesh>
      </group>

      {/* Engraved Brass Brand Badge on Chassis Front */}
      <mesh position={[0, 0.2, 1.91]}>
        <boxGeometry args={[1.2, 0.15, 0.02]} />
        <meshStandardMaterial color="#fbbf24" metalness={0.9} roughness={0.2} />
      </mesh>
    </group>
  );
}

export default function TurntableScene({
  isPlaying,
  vinylStyle = 'classic_red',
  title = 'Our Anniversary Letter',
  recipientName = 'Eleanor',
  senderName = 'Arthur',
}: TurntableSceneProps) {
  const controlsRef = useRef<any>(null);

  return (
    <div className="w-full h-full min-h-[420px] sm:min-h-[520px] lg:min-h-[580px] relative">
      <Canvas
        shadows
        gl={{ antialias: true, alpha: true }}
        camera={{ position: [0, 3.8, 5.2], fov: 45 }}
      >
        <PerspectiveCamera makeDefault position={[0, 3.8, 5.2]} fov={45} />

        {/* Ambient & Cinematic Lighting */}
        <ambientLight intensity={0.65} />
        
        {/* Amber Spotlight casting dramatic warm shadows */}
        <spotLight
          position={[3, 7, 3]}
          angle={0.55}
          penumbra={0.8}
          intensity={3.2}
          color="#fffbeb"
          castShadow
          shadow-mapSize-width={1024}
          shadow-mapSize-height={1024}
          shadow-bias={-0.0001}
        />

        {/* Golden Warm Point Light */}
        <pointLight position={[-2, 3, 1]} color="#fbbf24" intensity={1.8} distance={10} />

        {/* Under-glow Amber Fill */}
        <pointLight position={[0, -1, 0]} color="#d97706" intensity={1.0} distance={5} />

        {/* 3D Turntable Hierarchy */}
        <group position={[0, -0.3, 0]}>
          <TurntableBody />
          <TurntablePlatterAndDisc
            isPlaying={isPlaying}
            vinylStyle={vinylStyle}
            title={title}
            recipientName={recipientName}
            senderName={senderName}
          />
          <AnimatedTonearm isPlaying={isPlaying} />
        </group>

        {/* Drifting Golden Dust Particles */}
        <DustParticles />

        {/* Camera Orbit Controls */}
        <OrbitControls
          ref={controlsRef}
          enablePan={false}
          maxPolarAngle={Math.PI / 2 - 0.05} // Prevent going below table
          minPolarAngle={Math.PI / 8}
          minDistance={3.2}
          maxDistance={8.5}
          dampingFactor={0.05}
          enableDamping
        />
      </Canvas>
    </div>
  );
}
