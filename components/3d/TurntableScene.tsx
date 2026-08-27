'use client';

import React, { useRef, useMemo } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { OrbitControls, PerspectiveCamera, ContactShadows } from '@react-three/drei';
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

/**
 * Builds a realistic vinyl surface texture: thousands of fine concentric grooves
 * with a soft radial sheen so the disc reads as pressed wax rather than flat plastic.
 */
function useVinylTexture(baseColor: string, grooveColor: string) {
  return useMemo(() => {
    if (typeof document === 'undefined') return null;
    const size = 1024;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    const center = size / 2;

    ctx.fillStyle = baseColor;
    ctx.fillRect(0, 0, size, size);

    for (let r = center; r > center * 0.28; r -= 1.4) {
      const shade = 0.5 + Math.sin(r * 0.5) * 0.5;
      ctx.beginPath();
      ctx.arc(center, center, r, 0, Math.PI * 2);
      ctx.strokeStyle = grooveColor;
      ctx.globalAlpha = 0.12 + shade * 0.14;
      ctx.lineWidth = 1;
      ctx.stroke();
    }
    ctx.globalAlpha = 1;

    const sheen = ctx.createLinearGradient(0, 0, size, size);
    sheen.addColorStop(0, 'rgba(255,255,255,0.10)');
    sheen.addColorStop(0.5, 'rgba(255,255,255,0)');
    sheen.addColorStop(0.75, 'rgba(255,255,255,0.05)');
    sheen.addColorStop(1, 'rgba(0,0,0,0.15)');
    ctx.fillStyle = sheen;
    ctx.beginPath();
    ctx.arc(center, center, center, 0, Math.PI * 2);
    ctx.fill();

    const texture = new THREE.CanvasTexture(canvas);
    texture.anisotropy = 8;
    texture.colorSpace = THREE.SRGBColorSpace;
    return texture;
  }, [baseColor, grooveColor]);
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
  const discRef = useRef<THREE.Group>(null);
  const styleConfig = VINYL_STYLES.find((s) => s.id === vinylStyle) || VINYL_STYLES[0];
  const vinylTexture = useVinylTexture(styleConfig.baseColor, styleConfig.grooveColor);

  const centerLabelTexture = useMemo(() => {
    if (typeof document === 'undefined') return null;
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;

    const grad = ctx.createRadialGradient(256, 220, 40, 256, 256, 256);
    grad.addColorStop(0, styleConfig.labelColor);
    grad.addColorStop(1, styleConfig.grooveColor);
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(256, 256, 256, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = styleConfig.brassAccent || '#f59e0b';
    ctx.lineWidth = 4;
    ctx.beginPath();
    ctx.arc(256, 256, 236, 0, Math.PI * 2);
    ctx.stroke();
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(256, 256, 224, 0, Math.PI * 2);
    ctx.stroke();

    ctx.fillStyle = '#fdf6e3';
    ctx.textAlign = 'center';
    ctx.font = '600 22px Georgia, serif';
    ctx.fillText('VINYL VOICE NOTES', 256, 120);

    ctx.font = '13px "Courier New", monospace';
    ctx.fillStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.fillText('33⅓ RPM · STEREO MASTER', 256, 148);

    ctx.fillStyle = '#ffffff';
    ctx.font = '700 24px Georgia, serif';
    const displayTitle = title.length > 24 ? title.slice(0, 22) + '…' : title;
    ctx.fillText(displayTitle.toUpperCase(), 256, 200);

    ctx.font = 'italic 17px Georgia, serif';
    ctx.fillStyle = '#fde68a';
    if (recipientName) ctx.fillText(`For ${recipientName}`, 256, 330);
    if (senderName) ctx.fillText(`From ${senderName}`, 256, 356);

    ctx.font = '600 14px "Courier New", monospace';
    ctx.fillStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.fillText('SIDE A', 256, 412);

    ctx.fillStyle = '#050505';
    ctx.beginPath();
    ctx.arc(256, 256, 20, 0, Math.PI * 2);
    ctx.fill();

    const texture = new THREE.CanvasTexture(canvas);
    texture.anisotropy = 8;
    texture.colorSpace = THREE.SRGBColorSpace;
    return texture;
  }, [styleConfig, title, recipientName, senderName]);

  useFrame((_, delta) => {
    if (discRef.current && isPlaying) {
      discRef.current.rotation.y += delta * 3.49; // 33⅓ RPM
    }
  });

  return (
    <group ref={discRef} position={[0, 0.32, 0]}>
      {/* Wax body */}
      <mesh castShadow receiveShadow>
        <cylinderGeometry args={[2.3, 2.3, 0.05, 96]} />
        <meshPhysicalMaterial
          color={styleConfig.baseColor}
          map={vinylTexture ?? undefined}
          roughness={0.28}
          metalness={0.35}
          clearcoat={0.9}
          clearcoatRoughness={0.18}
          reflectivity={0.6}
        />
      </mesh>

      {/* Grooved glossy top face */}
      <mesh position={[0, 0.026, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <circleGeometry args={[2.29, 96]} />
        <meshPhysicalMaterial
          map={vinylTexture ?? undefined}
          color={styleConfig.baseColor}
          roughness={0.22}
          metalness={0.4}
          clearcoat={1}
          clearcoatRoughness={0.12}
        />
      </mesh>

      {/* Center label */}
      {centerLabelTexture && (
        <mesh position={[0, 0.028, 0]} rotation={[-Math.PI / 2, 0, 0]}>
          <circleGeometry args={[0.82, 96]} />
          <meshStandardMaterial map={centerLabelTexture} roughness={0.85} metalness={0.05} />
        </mesh>
      )}

      {/* Brass spindle pin */}
      <mesh position={[0, 0.09, 0]}>
        <cylinderGeometry args={[0.028, 0.028, 0.16, 24]} />
        <meshStandardMaterial color="#e5e7eb" metalness={0.95} roughness={0.15} />
      </mesh>
    </group>
  );
}

// 3. HIGH-FIDELITY ANIMATED TONEARM WITH NEEDLE
function AnimatedTonearm({ isPlaying }: { isPlaying: boolean }) {
  const yawRef = useRef<THREE.Group>(null);
  const pitchRef = useRef<THREE.Group>(null);
  const brass = useMemo(() => new THREE.Color('#c99a3f'), []);

  useFrame((_, delta) => {
    if (!yawRef.current || !pitchRef.current) return;
    const targetYaw = isPlaying ? 0.34 : -0.32;
    const targetPitch = isPlaying ? 0 : 0.12;
    const s = Math.min(1, delta * 3);
    yawRef.current.rotation.y = THREE.MathUtils.lerp(yawRef.current.rotation.y, targetYaw, s);
    pitchRef.current.rotation.x = THREE.MathUtils.lerp(pitchRef.current.rotation.x, targetPitch, s);
  });

  return (
    <group position={[2.15, 0.35, -2.0]}>
      {/* Mounting base */}
      <mesh position={[0, 0, 0]} castShadow>
        <cylinderGeometry args={[0.28, 0.32, 0.14, 40]} />
        <meshStandardMaterial color="#1b1712" metalness={0.6} roughness={0.35} />
      </mesh>
      <mesh position={[0, 0.09, 0]}>
        <cylinderGeometry args={[0.16, 0.16, 0.06, 32]} />
        <meshStandardMaterial color={brass} metalness={0.95} roughness={0.2} />
      </mesh>

      {/* Arm rest post */}
      <mesh position={[0.32, 0.06, 0.4]} castShadow>
        <cylinderGeometry args={[0.03, 0.04, 0.3, 16]} />
        <meshStandardMaterial color="#2a2a2a" metalness={0.7} roughness={0.4} />
      </mesh>

      <group ref={yawRef} position={[0, 0.14, 0]} rotation={[0, -0.32, 0]}>
        {/* Counterweight */}
        <mesh position={[0, 0, 0.5]} rotation={[Math.PI / 2, 0, 0]} castShadow>
          <cylinderGeometry args={[0.16, 0.16, 0.3, 32]} />
          <meshStandardMaterial color="#1a1a1a" metalness={0.8} roughness={0.3} />
        </mesh>
        <mesh position={[0, 0, 0.2]}>
          <boxGeometry args={[0.12, 0.12, 0.12]} />
          <meshStandardMaterial color={brass} metalness={0.9} roughness={0.22} />
        </mesh>

        <group ref={pitchRef}>
          {/* Slim polished arm tube */}
          <mesh position={[0, 0, -1.15]} rotation={[Math.PI / 2, 0, 0]} castShadow>
            <cylinderGeometry args={[0.022, 0.022, 2.4, 24]} />
            <meshStandardMaterial color="#d8d8dc" metalness={0.95} roughness={0.16} />
          </mesh>

          {/* Headshell + cartridge + stylus */}
          <group position={[0, -0.03, -2.35]} rotation={[0, -0.22, 0]}>
            <mesh position={[0, 0, -0.08]} castShadow>
              <boxGeometry args={[0.1, 0.07, 0.2]} />
              <meshStandardMaterial color="#111111" metalness={0.7} roughness={0.35} />
            </mesh>
            <mesh position={[0, -0.05, -0.12]}>
              <boxGeometry args={[0.05, 0.04, 0.1]} />
              <meshStandardMaterial color={brass} metalness={0.95} roughness={0.12} />
            </mesh>
            <mesh position={[0, -0.09, -0.15]} rotation={[0.25, 0, 0]}>
              <coneGeometry args={[0.01, 0.05, 16]} />
              <meshStandardMaterial color="#f8fafc" metalness={0.9} roughness={0.1} />
            </mesh>
            {isPlaying && (
              <pointLight position={[0, -0.09, -0.15]} color="#fcd34d" intensity={0.5} distance={0.9} />
            )}
          </group>
        </group>
      </group>
    </group>
  );
}

// 4. POLISHED MAHOGANY TURNTABLE CHASSIS
function TurntableBody() {
  const brass = useMemo(() => new THREE.Color('#c99a3f'), []);
  return (
    <group>
      {/* Matte plinth */}
      <mesh position={[0, 0.05, 0]} castShadow receiveShadow>
        <boxGeometry args={[6.2, 0.5, 5.2]} />
        <meshStandardMaterial color="#161311" roughness={0.55} metalness={0.2} />
      </mesh>

      {/* Walnut veneer top */}
      <mesh position={[0, 0.31, 0]} receiveShadow>
        <boxGeometry args={[6.0, 0.06, 5.0]} />
        <meshStandardMaterial color="#3a241a" roughness={0.4} metalness={0.15} />
      </mesh>

      {/* Recessed platter well */}
      <mesh position={[0, 0.3, 0]} receiveShadow>
        <cylinderGeometry args={[2.5, 2.5, 0.04, 96]} />
        <meshStandardMaterial color="#0d0b0a" roughness={0.7} metalness={0.2} />
      </mesh>

      {/* Brushed metal platter */}
      <mesh position={[0, 0.29, 0]} castShadow receiveShadow>
        <cylinderGeometry args={[2.4, 2.44, 0.14, 96]} />
        <meshStandardMaterial color="#2b2b30" metalness={0.85} roughness={0.35} />
      </mesh>

      {/* Control knobs */}
      {[[-2.4, -1.9], [-2.0, -1.9]].map((p, i) => (
        <mesh key={i} position={[p[0], 0.35, p[1]]} castShadow>
          <cylinderGeometry args={[0.13, 0.13, 0.05, 32]} />
          <meshStandardMaterial color={brass} metalness={0.9} roughness={0.25} />
        </mesh>
      ))}

      {/* Isolation feet */}
      {[[-2.6, -2.1], [2.6, -2.1], [-2.6, 2.1], [2.6, 2.1]].map((p, i) => (
        <mesh key={i} position={[p[0], -0.24, p[1]]} castShadow>
          <cylinderGeometry args={[0.22, 0.26, 0.16, 24]} />
          <meshStandardMaterial color="#0a0a0a" roughness={0.9} />
        </mesh>
      ))}

      {/* Brand badge */}
      <mesh position={[0, 0.32, 2.2]}>
        <boxGeometry args={[1.1, 0.12, 0.02]} />
        <meshStandardMaterial color={brass} metalness={0.9} roughness={0.25} />
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
  return (
    <div className="w-full h-full min-h-[420px] sm:min-h-[520px] lg:min-h-[580px] relative">
      <Canvas
        shadows
        dpr={[1, 2]}
        gl={{ antialias: true, alpha: true }}
        camera={{ position: [0, 4.2, 5.6], fov: 42 }}
      >
        <PerspectiveCamera makeDefault position={[0, 4.2, 5.6]} fov={42} />

        <ambientLight intensity={0.5} />

        {/* Key light with soft shadows */}
        <directionalLight
          position={[4, 8, 4]}
          intensity={2.2}
          color="#fff7ec"
          castShadow
          shadow-mapSize-width={2048}
          shadow-mapSize-height={2048}
          shadow-bias={-0.0002}
        />

        {/* Warm amber rim + fill */}
        <pointLight position={[-4, 3, -2]} color="#f59e0b" intensity={1.1} distance={16} />
        <pointLight position={[0, 2.5, 4]} color="#fde68a" intensity={0.7} distance={14} />

        <group position={[0, -0.4, 0]}>
          <TurntableBody />
          <TurntablePlatterAndDisc
            isPlaying={isPlaying}
            vinylStyle={vinylStyle}
            title={title}
            recipientName={recipientName}
            senderName={senderName}
          />
          <AnimatedTonearm isPlaying={isPlaying} />
          <ContactShadows
            position={[0, -0.32, 0]}
            opacity={0.55}
            scale={16}
            blur={2.6}
            far={5}
            color="#000000"
          />
        </group>

        <OrbitControls
          enablePan={false}
          enableZoom
          maxPolarAngle={Math.PI / 2 - 0.08}
          minPolarAngle={Math.PI / 7}
          minDistance={3.6}
          maxDistance={9}
          dampingFactor={0.06}
          enableDamping
          autoRotate={!isPlaying}
          autoRotateSpeed={0.4}
        />
      </Canvas>
    </div>
  );
}
