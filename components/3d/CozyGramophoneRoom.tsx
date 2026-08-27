'use client';

import React, { useRef, useMemo } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { OrbitControls, PerspectiveCamera, ContactShadows, Environment } from '@react-three/drei';
import * as THREE from 'three';
import { VinylStyleType } from '@/types';
import { VINYL_STYLES } from '@/lib/constants';

interface CozyGramophoneRoomProps {
  isPlaying: boolean;
  isNeedleDropping?: boolean;
  vinylStyle?: VinylStyleType;
  title?: string;
  recipientName?: string;
  senderName?: string;
  isRecording?: boolean;
}

// Procedural textures
function useWoodTexture() {
  return useMemo(() => {
    if (typeof document === 'undefined') return null;
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const ctx = canvas.getContext('2d')!;
    ctx.fillStyle = '#3d2817';
    ctx.fillRect(0, 0, 512, 512);
    // Wood grain
    for (let i = 0; i < 40; i++) {
      const y = (i / 40) * 512 + (Math.random() - 0.5) * 20;
      ctx.strokeStyle = `rgba(${60 + Math.random() * 20}, ${35 + Math.random() * 10}, ${15 + Math.random() * 10}, ${0.15 + Math.random() * 0.2})`;
      ctx.lineWidth = 1 + Math.random() * 3;
      ctx.beginPath();
      ctx.moveTo(0, y);
      for (let x = 0; x < 512; x += 20) {
        ctx.lineTo(x, y + Math.sin(x * 0.01 + i) * 5 + (Math.random() - 0.5) * 4);
      }
      ctx.stroke();
    }
    const tex = new THREE.CanvasTexture(canvas);
    tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
    tex.repeat.set(2, 4);
    return tex;
  }, []);
}

function useRugTexture() {
  return useMemo(() => {
    if (typeof document === 'undefined') return null;
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const ctx = canvas.getContext('2d')!;
    // Base
    ctx.fillStyle = '#7a4a2a';
    ctx.fillRect(0, 0, 512, 512);
    // Persian pattern - diamonds
    ctx.strokeStyle = '#a67c52';
    ctx.lineWidth = 2;
    for (let y = 0; y < 512; y += 64) {
      for (let x = 0; x < 512; x += 64) {
        ctx.save();
        ctx.translate(x + 32, y + 32);
        ctx.rotate(Math.PI / 4);
        ctx.strokeRect(-20, -20, 40, 40);
        ctx.restore();
      }
    }
    // Center medallion
    ctx.fillStyle = '#8b5a2b';
    ctx.beginPath();
    ctx.arc(256, 256, 80, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#d4a574';
    ctx.lineWidth = 3;
    ctx.stroke();
    const tex = new THREE.CanvasTexture(canvas);
    tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
    return tex;
  }, []);
}

function useBrickTexture() {
  return useMemo(() => {
    if (typeof document === 'undefined') return null;
    const canvas = document.createElement('canvas');
    canvas.width = 256;
    canvas.height = 256;
    const ctx = canvas.getContext('2d')!;
    ctx.fillStyle = '#4a2c17';
    ctx.fillRect(0, 0, 256, 256);
    ctx.fillStyle = '#5a3520';
    for (let y = 0; y < 256; y += 32) {
      const offset = (y / 32) % 2 === 0 ? 0 : 32;
      for (let x = -32; x < 256; x += 64) {
        ctx.fillRect(x + offset + 2, y + 2, 60, 28);
        ctx.strokeStyle = '#3a2010';
        ctx.lineWidth = 2;
        ctx.strokeRect(x + offset + 2, y + 2, 60, 28);
      }
    }
    const tex = new THREE.CanvasTexture(canvas);
    tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
    tex.repeat.set(1, 1.5);
    return tex;
  }, []);
}

function DustMotes() {
  const count = 400;
  const positions = useMemo(() => {
    const pos = new Float32Array(count * 3);
    for (let i = 0; i < count * 3; i += 3) {
      pos[i] = (Math.random() - 0.5) * 12;
      pos[i + 1] = Math.random() * 5 + 0.2;
      pos[i + 2] = (Math.random() - 0.5) * 12;
    }
    return pos;
  }, []);

  const ref = useRef<THREE.Points>(null);
  useFrame((state) => {
    if (!ref.current) return;
    const t = state.clock.getElapsedTime();
    ref.current.rotation.y = t * 0.01;
    const attr = ref.current.geometry.attributes.position;
    const arr = attr.array as Float32Array;
    for (let i = 1; i < count * 3; i += 3) {
      arr[i] += Math.sin(t * 0.3 + i) * 0.0006;
      if (arr[i] > 5.5) arr[i] = 0.2;
    }
    attr.needsUpdate = true;
  });

  return (
    <points ref={ref}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[positions, 3]} />
      </bufferGeometry>
      <pointsMaterial size={0.035} color="#fde68a" transparent opacity={0.5} blending={THREE.AdditiveBlending} sizeAttenuation depthWrite={false} />
    </points>
  );
}

function WoodFloor() {
  const woodTex = useWoodTexture();
  return (
    <group>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0, 0]} receiveShadow>
        <planeGeometry args={[16, 16]} />
        <meshStandardMaterial map={woodTex} color="#4a3320" roughness={0.65} metalness={0.05} />
      </mesh>
      {/* Plank lines */}
      {Array.from({ length: 16 }).map((_, i) => (
        <mesh key={i} rotation={[-Math.PI / 2, 0, 0]} position={[-8 + i + 0.5, 0.001, 0]} receiveShadow>
          <planeGeometry args={[0.02, 16]} />
          <meshStandardMaterial color="#2a1a0f" roughness={0.9} transparent opacity={0.3} />
        </mesh>
      ))}
    </group>
  );
}

function Walls() {
  const brickTex = useBrickTexture();
  return (
    <group>
      {/* Back wall - cream */}
      <mesh position={[0, 2.5, -6]} receiveShadow>
        <boxGeometry args={[16, 5.5, 0.3]} />
        <meshStandardMaterial color="#e8dcc6" roughness={0.85} />
      </mesh>
      {/* Wainscoting */}
      <mesh position={[0, 0.9, -5.82]} receiveShadow>
        <boxGeometry args={[16, 1.8, 0.35]} />
        <meshStandardMaterial color="#1e120b" roughness={0.5} metalness={0.08} />
      </mesh>
      {/* Chair rail */}
      <mesh position={[0, 1.85, -5.78]} receiveShadow>
        <boxGeometry args={[16, 0.12, 0.4]} />
        <meshStandardMaterial color="#b45309" metalness={0.6} roughness={0.4} />
      </mesh>
      {/* Left wall */}
      <mesh position={[-8, 2.5, 0]} rotation={[0, Math.PI / 2, 0]} receiveShadow>
        <boxGeometry args={[16, 5.5, 0.3]} />
        <meshStandardMaterial color="#e6d5b8" roughness={0.9} />
      </mesh>
      {/* Right wall */}
      <mesh position={[8, 2.5, 0]} rotation={[0, Math.PI / 2, 0]} receiveShadow>
        <boxGeometry args={[16, 5.5, 0.3]} />
        <meshStandardMaterial color="#e6d5b8" roughness={0.9} />
      </mesh>
      {/* Ceiling with beams */}
      <mesh position={[0, 5.5, 0]} rotation={[Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[16, 16]} />
        <meshStandardMaterial color="#f5efe0" roughness={0.95} />
      </mesh>
      {/* Ceiling beams - dark wood */}
      {[-3, 0, 3].map((x, i) => (
        <mesh key={i} position={[x, 5.2, 0]} castShadow receiveShadow>
          <boxGeometry args={[0.25, 0.4, 16]} />
          <meshStandardMaterial color="#2a1a0f" roughness={0.6} />
        </mesh>
      ))}
      {/* Crown molding back */}
      <mesh position={[0, 5.3, -5.85]} castShadow>
        <boxGeometry args={[16, 0.25, 0.4]} />
        <meshStandardMaterial color="#d4c4a8" roughness={0.6} />
      </mesh>
    </group>
  );
}

function Rug() {
  const rugTex = useRugTexture();
  return (
    <group position={[0, 0.02, 0.5]}>
      <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[6, 5]} />
        <meshStandardMaterial map={rugTex} roughness={0.95} color="#ffffff" />
      </mesh>
      {/* Fringe */}
      {Array.from({ length: 24 }).map((_, i) => (
        <group key={i} position={[-2.85 + i * 0.25, 0.005, 2.4]}>
          <mesh rotation={[0, 0, 0]}>
            <boxGeometry args={[0.05, 0.01, 0.3]} />
            <meshStandardMaterial color="#e8dcc6" roughness={0.9} />
          </mesh>
        </group>
      ))}
      {Array.from({ length: 24 }).map((_, i) => (
        <group key={`b-${i}`} position={[-2.85 + i * 0.25, 0.005, -2.4]}>
          <mesh>
            <boxGeometry args={[0.05, 0.01, 0.3]} />
            <meshStandardMaterial color="#e8dcc6" roughness={0.9} />
          </mesh>
        </group>
      ))}
    </group>
  );
}

function GramophoneTable() {
  return (
    <group position={[0, 0.45, 0.5]}>
      <mesh position={[0, 0.45, 0]} castShadow receiveShadow>
        <boxGeometry args={[3.0, 0.14, 2.2]} />
        <meshStandardMaterial color="#1e1008" roughness={0.3} metalness={0.12} />
      </mesh>
      {/* Inlay */}
      <mesh position={[0, 0.521, 0]} receiveShadow>
        <boxGeometry args={[2.7, 0.01, 1.9]} />
        <meshStandardMaterial color="#3a2317" roughness={0.25} metalness={0.08} />
      </mesh>
      {/* Brass edge */}
      <mesh position={[0, 0.38, 0]}>
        <boxGeometry args={[3.05, 0.05, 2.25]} />
        <meshStandardMaterial color="#92400e" metalness={0.75} roughness={0.35} />
      </mesh>
      {/* Turned legs with detail */}
      {[
        [-1.3, -0.2, -0.9],
        [1.3, -0.2, -0.9],
        [-1.3, -0.2, 0.9],
        [1.3, -0.2, 0.9],
      ].map((pos, i) => (
        <group key={i} position={pos as [number, number, number]}>
          <mesh position={[0, 0.3, 0]} castShadow>
            <cylinderGeometry args={[0.09, 0.11, 0.6, 16]} />
            <meshStandardMaterial color="#1a0e07" roughness={0.5} />
          </mesh>
          <mesh position={[0, -0.05, 0]} castShadow>
            <cylinderGeometry args={[0.07, 0.09, 0.3, 16]} />
            <meshStandardMaterial color="#2a1a0f" roughness={0.5} />
          </mesh>
          <mesh position={[0, -0.3, 0]} castShadow>
            <cylinderGeometry args={[0.06, 0.07, 0.2, 16]} />
            <meshStandardMaterial color="#b45309" metalness={0.7} roughness={0.3} />
          </mesh>
        </group>
      ))}
      {/* Drawer with brass handle */}
      <mesh position={[0, 0.18, 1.02]} castShadow>
        <boxGeometry args={[1.6, 0.38, 0.06]} />
        <meshStandardMaterial color="#2e1c10" roughness={0.45} />
      </mesh>
      <mesh position={[0, 0.18, 1.06]} castShadow>
        <boxGeometry args={[0.18, 0.09, 0.03]} />
        <meshStandardMaterial color="#fbbf24" metalness={0.92} roughness={0.15} />
      </mesh>
    </group>
  );
}

function VinylPlatter({
  isPlaying,
  vinylStyle = 'classic_red',
  title = 'Anniversary',
  recipientName = 'Eleanor',
  senderName = 'Arthur',
}: {
  isPlaying: boolean;
  vinylStyle?: VinylStyleType;
  title?: string;
  recipientName?: string;
  senderName?: string;
}) {
  const groupRef = useRef<THREE.Group>(null);
  const styleConfig = VINYL_STYLES.find((s) => s.id === vinylStyle) || VINYL_STYLES[0];

  const labelTexture = useMemo(() => {
    if (typeof document === 'undefined') return null;
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    ctx.fillStyle = styleConfig.labelColor;
    ctx.fillRect(0, 0, 512, 512);
    // Gold rings
    ctx.strokeStyle = styleConfig.brassAccent || '#f59e0b';
    ctx.lineWidth = 8;
    ctx.beginPath();
    ctx.arc(256, 256, 235, 0, Math.PI * 2);
    ctx.stroke();
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(256, 256, 210, 0, Math.PI * 2);
    ctx.stroke();
    // Inner
    ctx.fillStyle = '#0c0a09';
    ctx.beginPath();
    ctx.arc(256, 256, 30, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(256, 256, 36, 0, Math.PI * 2);
    ctx.stroke();
    // Text
    ctx.fillStyle = '#fef3c7';
    ctx.textAlign = 'center';
    ctx.font = 'bold 20px serif';
    ctx.fillText('GRAMOPHONE ARCHIVE', 256, 92);
    ctx.font = '11px monospace';
    ctx.fillStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.fillText('78 RPM • BRASS HORN MASTER', 256, 115);
    ctx.font = 'bold 22px serif';
    ctx.fillStyle = '#fff';
    const t = title.length > 20 ? title.slice(0, 18) + '...' : title;
    ctx.fillText(t.toUpperCase(), 256, 170);
    ctx.font = 'italic 15px serif';
    ctx.fillStyle = '#fde68a';
    if (recipientName) ctx.fillText(`For: ${recipientName}`, 256, 350);
    if (senderName) ctx.fillText(`From: ${senderName}`, 256, 375);
    ctx.font = 'bold 13px monospace';
    ctx.fillStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.fillText('SIDE A • 1925', 256, 430);
    const tex = new THREE.CanvasTexture(canvas);
    tex.anisotropy = 16;
    return tex;
  }, [styleConfig, title, recipientName, senderName]);

  useFrame((_, delta) => {
    if (groupRef.current && isPlaying) {
      groupRef.current.rotation.y += delta * 2.8;
    }
  });

  return (
    <group position={[0, 1.12, 0.5]}>
      <mesh position={[0, 0.005, 0]} receiveShadow>
        <cylinderGeometry args={[0.8, 0.8, 0.012, 48]} />
        <meshStandardMaterial color="#0f0a08" roughness={0.95} />
      </mesh>
      <group ref={groupRef}>
        <mesh position={[0, 0.025, 0]} castShadow receiveShadow>
          <cylinderGeometry args={[0.72, 0.72, 0.03, 64]} />
          <meshPhysicalMaterial color={styleConfig.baseColor} roughness={0.25} metalness={0.75} clearcoat={0.6} clearcoatRoughness={0.12} reflectivity={0.85} />
        </mesh>
        {[0.28, 0.38, 0.48, 0.58, 0.68].map((r, i) => (
          <mesh key={i} position={[0, 0.041, 0]} rotation={[-Math.PI / 2, 0, 0]}>
            <ringGeometry args={[r - 0.009, r, 64]} />
            <meshStandardMaterial color={styleConfig.grooveColor} transparent opacity={0.65} roughness={0.4} metalness={0.5} />
          </mesh>
        ))}
        {labelTexture && (
          <mesh position={[0, 0.042, 0]} rotation={[-Math.PI / 2, 0, 0]}>
            <circleGeometry args={[0.24, 64]} />
            <meshStandardMaterial map={labelTexture} roughness={0.4} metalness={0.2} />
          </mesh>
        )}
        <mesh position={[0, 0.08, 0]} castShadow>
          <cylinderGeometry args={[0.018, 0.018, 0.09, 20]} />
          <meshPhysicalMaterial color="#fbbf24" metalness={0.98} roughness={0.08} clearcoat={1} />
        </mesh>
      </group>
    </group>
  );
}

function GramophoneBase() {
  return (
    <group position={[0, 1.0, 0.5]}>
      <mesh position={[0, 0, 0]} castShadow receiveShadow>
        <boxGeometry args={[1.7, 0.55, 1.3]} />
        <meshStandardMaterial color="#1a0e07" roughness={0.35} metalness={0.1} />
      </mesh>
      <mesh position={[0, 0.285, 0]} receiveShadow>
        <boxGeometry args={[1.65, 0.02, 1.25]} />
        <meshStandardMaterial color="#2e1c10" roughness={0.28} metalness={0.08} />
      </mesh>
      {[
        [-0.85, 0, -0.65],
        [0.85, 0, -0.65],
        [-0.85, 0, 0.65],
        [0.85, 0, 0.65],
      ].map((p, i) => (
        <mesh key={i} position={p as [number, number, number]} castShadow>
          <boxGeometry args={[0.09, 0.57, 0.09]} />
          <meshStandardMaterial color="#7c3a0a" metalness={0.8} roughness={0.25} />
        </mesh>
      ))}
      {/* Crank */}
      <group position={[0.9, -0.05, 0.25]}>
        <mesh rotation={[0, 0, Math.PI / 2]} castShadow>
          <cylinderGeometry args={[0.045, 0.045, 0.3, 16]} />
          <meshStandardMaterial color="#3a1f0f" roughness={0.55} />
        </mesh>
        <mesh position={[0.15, 0, 0]} castShadow>
          <cylinderGeometry args={[0.025, 0.025, 0.18, 12]} />
          <meshStandardMaterial color="#fbbf24" metalness={0.95} roughness={0.12} />
        </mesh>
        <mesh position={[0.15, 0.09, 0]} castShadow>
          <boxGeometry args={[0.09, 0.05, 0.05]} />
          <meshStandardMaterial color="#0f0a08" roughness={0.5} />
        </mesh>
      </group>
      <mesh position={[0, 0.15, -0.7]} castShadow>
        <cylinderGeometry args={[0.07, 0.09, 0.4, 16]} />
        <meshStandardMaterial color="#5a2a0a" metalness={0.7} roughness={0.3} />
      </mesh>
      <mesh position={[0, -0.05, 0.66]} castShadow>
        <boxGeometry args={[0.7, 0.14, 0.025]} />
        <meshPhysicalMaterial color="#fbbf24" metalness={0.95} roughness={0.15} clearcoat={0.8} />
      </mesh>
    </group>
  );
}

function GramophoneHorn({ isPlaying, isNeedleDropping }: { isPlaying: boolean; isNeedleDropping?: boolean }) {
  const hornRef = useRef<THREE.Group>(null);
  const glowRef = useRef<THREE.PointLight>(null);

  useFrame((state) => {
    if (hornRef.current && isPlaying) {
      const t = state.clock.getElapsedTime();
      hornRef.current.position.y = Math.sin(t * 20) * 0.0025;
      hornRef.current.rotation.z = Math.sin(t * 14) * 0.0015;
    }
    if (glowRef.current) {
      const t = state.clock.getElapsedTime();
      glowRef.current.intensity = (isPlaying || isNeedleDropping) ? 1.5 + Math.sin(t * 8) * 0.4 : 0;
    }
  });

  return (
    <group ref={hornRef} position={[0, 1.5, -0.15]}>
      {/* Throat */}
      <group position={[0, -0.22, 0.32]}>
        <mesh rotation={[Math.PI / 2, 0, 0]} castShadow>
          <cylinderGeometry args={[0.045, 0.07, 0.45, 20]} />
          <meshPhysicalMaterial color="#b45309" metalness={0.9} roughness={0.18} clearcoat={0.5} />
        </mesh>
      </group>

      {/* Horn using Lathe for more realistic flare */}
      <group position={[0, 0.1, -0.2]}>
        {/* Segment 1 */}
        <mesh rotation={[0.28, 0, 0]} castShadow>
          <cylinderGeometry args={[0.07, 0.16, 0.65, 28]} />
          <meshPhysicalMaterial color="#c45a0a" metalness={0.92} roughness={0.18} clearcoat={0.6} side={THREE.DoubleSide} />
        </mesh>
      </group>

      <group position={[0, 0.48, -0.52]}>
        <mesh rotation={[0.32, 0, 0]} castShadow>
          <cylinderGeometry args={[0.16, 0.38, 0.75, 32]} />
          <meshPhysicalMaterial color="#d97706" metalness={0.93} roughness={0.16} clearcoat={0.7} side={THREE.DoubleSide} />
        </mesh>
      </group>

      <group position={[0, 0.92, -0.98]}>
        <mesh rotation={[0.38, 0, 0]} castShadow>
          <cylinderGeometry args={[0.38, 0.92, 0.85, 36]} />
          <meshPhysicalMaterial color="#f59e0b" metalness={0.94} roughness={0.14} clearcoat={0.8} side={THREE.DoubleSide} />
        </mesh>
      </group>

      {/* Bell rim */}
      <mesh position={[0, 1.35, -1.38]} rotation={[0.38, 0, 0]} castShadow>
        <torusGeometry args={[0.92, 0.07, 20, 64]} />
        <meshPhysicalMaterial color="#f59e0b" metalness={0.96} roughness={0.12} clearcoat={1} />
      </mesh>

      {/* Inner bell depth */}
      <mesh position={[0, 1.32, -1.33]} rotation={[0.38, 0, 0]}>
        <circleGeometry args={[0.88, 48]} />
        <meshStandardMaterial color="#1a0a00" roughness={0.8} metalness={0.2} side={THREE.DoubleSide} />
      </mesh>

      {/* Support strut with decorative curve */}
      <mesh position={[0, 0.35, -0.08]} rotation={[0.55, 0, 0]} castShadow>
        <cylinderGeometry args={[0.018, 0.018, 1.2, 12]} />
        <meshStandardMaterial color="#5a2a0a" metalness={0.75} roughness={0.25} />
      </mesh>

      {/* Decorative brass joint */}
      <mesh position={[0, 0.05, -0.15]} castShadow>
        <sphereGeometry args={[0.06, 16, 16]} />
        <meshPhysicalMaterial color="#fbbf24" metalness={0.98} roughness={0.1} />
      </mesh>

      {(isPlaying || isNeedleDropping) && (
        <>
          <pointLight ref={glowRef} position={[0, 1.3, -1.25]} color="#fbbf24" intensity={1.5} distance={3} decay={2} />
          <pointLight position={[0, 0.55, -0.45]} color="#ff8c00" intensity={0.8} distance={2} decay={2} />
        </>
      )}
    </group>
  );
}

function AnimatedGramophoneTonearm({ isPlaying, isNeedleDropping }: { isPlaying: boolean; isNeedleDropping?: boolean }) {
  const yawRef = useRef<THREE.Group>(null);
  const pitchRef = useRef<THREE.Group>(null);

  useFrame((_, delta) => {
    if (!yawRef.current || !pitchRef.current) return;
    const targetYaw = isPlaying || isNeedleDropping ? 0.38 : -0.6;
    const targetPitch = isPlaying || isNeedleDropping ? -0.02 : 0.32;
    const speed = delta * 3.2;
    yawRef.current.rotation.y = THREE.MathUtils.lerp(yawRef.current.rotation.y, targetYaw, speed);
    pitchRef.current.rotation.x = THREE.MathUtils.lerp(pitchRef.current.rotation.x, targetPitch, speed);
  });

  return (
    <group position={[0.58, 1.22, 0.22]}>
      <mesh position={[0, 0, 0]} castShadow>
        <cylinderGeometry args={[0.13, 0.15, 0.16, 24]} />
        <meshPhysicalMaterial color="#d97706" metalness={0.92} roughness={0.18} clearcoat={0.6} />
      </mesh>
      <group ref={yawRef} rotation={[0, -0.6, 0]}>
        <mesh position={[0, 0.09, 0]} castShadow>
          <boxGeometry args={[0.11, 0.11, 0.11]} />
          <meshStandardMaterial color="#7c2d12" metalness={0.85} roughness={0.22} />
        </mesh>
        <group ref={pitchRef} position={[0, 0.09, 0]} rotation={[0.32, 0, 0]}>
          <mesh position={[0, 0, -0.58]} rotation={[Math.PI / 2, 0, 0]} castShadow>
            <cylinderGeometry args={[0.02, 0.024, 1.16, 14]} />
            <meshPhysicalMaterial color="#f59e0b" metalness={0.95} roughness={0.12} clearcoat={0.8} />
          </mesh>
          <group position={[0, -0.01, -1.16]} rotation={[0, -0.22, 0]}>
            <mesh castShadow>
              <boxGeometry args={[0.075, 0.045, 0.18]} />
              <meshStandardMaterial color="#0f0a08" metalness={0.7} roughness={0.28} />
            </mesh>
            <mesh position={[0, -0.035, -0.02]} castShadow>
              <coneGeometry args={[0.012, 0.055, 14]} />
              <meshPhysicalMaterial color="#fef3c7" metalness={0.99} roughness={0.04} />
            </mesh>
            {(isPlaying || isNeedleDropping) && (
              <pointLight position={[0, -0.035, -0.02]} color="#fbbf24" intensity={0.6} distance={0.7} />
            )}
          </group>
        </group>
      </group>
    </group>
  );
}

function Fireplace() {
  const brickTex = useBrickTexture();
  const fireRef = useRef<THREE.Mesh>(null);
  const lightRef = useRef<THREE.PointLight>(null);

  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (fireRef.current) {
      const s = 1 + Math.sin(t * 9) * 0.07;
      fireRef.current.scale.set(s, s, s);
      (fireRef.current.material as THREE.MeshStandardMaterial).emissiveIntensity = 1.4 + Math.sin(t * 11) * 0.5;
    }
    if (lightRef.current) {
      lightRef.current.intensity = 2.8 + Math.sin(t * 7) * 0.8;
    }
  });

  return (
    <group position={[0, 0.9, -5.7]}>
      <mesh position={[0, 0.7, 0]} castShadow receiveShadow>
        <boxGeometry args={[2.6, 2.0, 0.5]} />
        <meshStandardMaterial map={brickTex} roughness={0.85} color="#ffffff" />
      </mesh>
      <mesh position={[0, 0.55, 0.28]} receiveShadow>
        <boxGeometry args={[1.4, 1.0, 0.06]} />
        <meshStandardMaterial color="#050201" roughness={0.98} />
      </mesh>
      <mesh ref={fireRef} position={[0, 0.4, 0.32]}>
        <boxGeometry args={[0.9, 0.55, 0.12]} />
        <meshStandardMaterial color="#ff5a00" emissive="#ff3300" emissiveIntensity={1.5} roughness={0.7} />
      </mesh>
      {/* Logs */}
      <mesh position={[-0.18, 0.18, 0.36]} rotation={[0, 0, 0.35]} castShadow>
        <cylinderGeometry args={[0.07, 0.07, 0.6, 10]} />
        <meshStandardMaterial color="#1e0f08" roughness={0.92} />
      </mesh>
      <mesh position={[0.18, 0.18, 0.36]} rotation={[0, 0, -0.35]} castShadow>
        <cylinderGeometry args={[0.06, 0.06, 0.55, 10]} />
        <meshStandardMaterial color="#1e0f08" roughness={0.92} />
      </mesh>
      {/* Mantel */}
      <mesh position={[0, 1.75, 0.2]} castShadow receiveShadow>
        <boxGeometry args={[2.9, 0.14, 0.6]} />
        <meshStandardMaterial color="#140a06" roughness={0.35} metalness={0.08} />
      </mesh>
      {/* Decor */}
      <group position={[0, 1.95, 0.2]}>
        <mesh castShadow>
          <cylinderGeometry args={[0.2, 0.2, 0.09, 24]} />
          <meshPhysicalMaterial color="#d97706" metalness={0.85} roughness={0.2} clearcoat={0.6} />
        </mesh>
        <mesh position={[0, 0.05, 0]}>
          <circleGeometry args={[0.15, 24]} />
          <meshStandardMaterial color="#fef3c7" roughness={0.3} metalness={0.4} />
        </mesh>
      </group>
      {/* Candles */}
      {[-0.8, 0.8].map((x, i) => (
        <group key={i} position={[x, 1.95, 0.15]}>
          <mesh castShadow>
            <cylinderGeometry args={[0.04, 0.04, 0.25, 12]} />
            <meshStandardMaterial color="#f5efe0" roughness={0.8} />
          </mesh>
          <pointLight position={[0, 0.2, 0]} color="#fde68a" intensity={0.4} distance={1.5} />
        </group>
      ))}
      <pointLight ref={lightRef} position={[0, 0.6, 1.0]} color="#ff5a00" intensity={2.8} distance={7} decay={2} castShadow shadow-mapSize-width={512} shadow-mapSize-height={512} />
    </group>
  );
}

function Armchair() {
  return (
    <group position={[3.2, 0, -2.8]} rotation={[0, -0.7, 0]}>
      <mesh position={[0, 0.35, 0]} castShadow receiveShadow>
        <boxGeometry args={[1.3, 0.45, 1.1]} />
        <meshStandardMaterial color="#4a2f1b" roughness={0.85} />
      </mesh>
      <mesh position={[0, 0.95, -0.45]} castShadow receiveShadow>
        <boxGeometry args={[1.3, 1.0, 0.22]} />
        <meshStandardMaterial color="#5c3a21" roughness={0.85} />
      </mesh>
      <mesh position={[-0.7, 0.65, 0]} castShadow>
        <boxGeometry args={[0.18, 0.6, 1.1]} />
        <meshStandardMaterial color="#3a2515" roughness={0.8} />
      </mesh>
      <mesh position={[0.7, 0.65, 0]} castShadow>
        <boxGeometry args={[0.18, 0.6, 1.1]} />
        <meshStandardMaterial color="#3a2515" roughness={0.8} />
      </mesh>
      <mesh position={[0, 0.68, 0]} castShadow receiveShadow>
        <boxGeometry args={[1.05, 0.18, 0.85]} />
        <meshStandardMaterial color="#7a4a2a" roughness={0.9} />
      </mesh>
      <mesh position={[0.35, 0.75, 0.25]} rotation={[0, 0, 0.25]} castShadow>
        <boxGeometry args={[0.65, 0.1, 0.75]} />
        <meshStandardMaterial color="#a67c52" roughness={0.95} />
      </mesh>
      {/* Pillow */}
      <mesh position={[-0.2, 0.85, -0.15]} rotation={[0.1, 0, -0.15]} castShadow>
        <boxGeometry args={[0.5, 0.35, 0.18]} />
        <meshStandardMaterial color="#8b5a2b" roughness={0.9} />
      </mesh>
    </group>
  );
}

function Bookshelf() {
  return (
    <group position={[-7.65, 1.3, -1.2]}>
      <mesh position={[0, 0, 0]} castShadow receiveShadow>
        <boxGeometry args={[0.5, 3.2, 2.8]} />
        <meshStandardMaterial color="#1a0e07" roughness={0.55} />
      </mesh>
      {[ -0.9, 0, 0.9].map((y, i) => (
        <mesh key={i} position={[0.18, y, 0]} castShadow receiveShadow>
          <boxGeometry args={[0.4, 0.06, 2.6]} />
          <meshStandardMaterial color="#2e1c10" roughness={0.45} />
        </mesh>
      ))}
      {Array.from({ length: 12 }).map((_, i) => {
        const y = -1.1 + (i % 3) * 0.9 + Math.random() * 0.1;
        const z = -1.0 + (i * 0.2) % 2.0;
        const h = 0.45 + Math.random() * 0.25;
        return (
          <mesh key={i} position={[0.18, y + h / 2 - 0.2, z]} rotation={[0, 0, (Math.random() - 0.5) * 0.15]} castShadow>
            <boxGeometry args={[0.28, h, 0.09]} />
            <meshStandardMaterial color={["#8b4513", "#5c3a21", "#a0522d", "#6b4423", "#7a3a1a"][i % 5]} roughness={0.65} />
          </mesh>
        );
      })}
    </group>
  );
}

function FloorLamp() {
  return (
    <group position={[3.6, 0, -1.2]}>
      <mesh position={[0, 0.06, 0]} castShadow receiveShadow>
        <cylinderGeometry args={[0.28, 0.32, 0.12, 24]} />
        <meshStandardMaterial color="#1a0e07" roughness={0.5} metalness={0.15} />
      </mesh>
      <mesh position={[0, 1.1, 0]} castShadow>
        <cylinderGeometry args={[0.035, 0.035, 2.2, 14]} />
        <meshStandardMaterial color="#7c3a0a" metalness={0.75} roughness={0.25} />
      </mesh>
      <mesh position={[0, 2.25, 0]} castShadow>
        <cylinderGeometry args={[0.4, 0.58, 0.6, 28]} />
        <meshStandardMaterial color="#e8dcc6" roughness={0.88} transparent opacity={0.9} side={THREE.DoubleSide} />
      </mesh>
      <mesh position={[0, 2.25, 0]}>
        <cylinderGeometry args={[0.38, 0.55, 0.58, 28]} />
        <meshStandardMaterial color="#f5efe0" emissive="#fde68a" emissiveIntensity={0.15} roughness={0.9} transparent opacity={0.2} side={THREE.DoubleSide} />
      </mesh>
      <pointLight position={[0, 2.0, 0]} color="#fde68a" intensity={2.2} distance={6} decay={2} castShadow shadow-mapSize-width={512} shadow-mapSize-height={512} />
    </group>
  );
}

function WindowWithLight() {
  return (
    <group position={[-7.8, 1.9, 1.8]} rotation={[0, Math.PI / 2, 0]}>
      <mesh castShadow receiveShadow>
        <boxGeometry args={[2.0, 2.4, 0.18]} />
        <meshStandardMaterial color="#1a0e07" roughness={0.6} />
      </mesh>
      <mesh position={[0, 0, 0.12]}>
        <planeGeometry args={[1.7, 2.1]} />
        <meshPhysicalMaterial color="#87ceeb" emissive="#87ceeb" emissiveIntensity={0.18} roughness={0.15} metalness={0.1} transparent opacity={0.55} transmission={0.3} />
      </mesh>
      {/* Mullions */}
      <mesh position={[0, 0, 0.13]}>
        <boxGeometry args={[0.06, 2.1, 0.02]} />
        <meshStandardMaterial color="#1a0e07" roughness={0.5} />
      </mesh>
      <mesh position={[0, 0, 0.13]}>
        <boxGeometry args={[1.7, 0.06, 0.02]} />
        <meshStandardMaterial color="#1a0e07" roughness={0.5} />
      </mesh>
      {/* Curtains */}
      <mesh position={[-0.7, 0, 0.18]} castShadow>
        <boxGeometry args={[0.35, 2.4, 0.08]} />
        <meshStandardMaterial color="#5c1a1a" roughness={0.95} />
      </mesh>
      <mesh position={[0.7, 0, 0.18]} castShadow>
        <boxGeometry args={[0.35, 2.4, 0.08]} />
        <meshStandardMaterial color="#5c1a1a" roughness={0.95} />
      </mesh>
      {/* Light rays */}
      <spotLight position={[0, 0.5, 1.2]} angle={0.65} penumbra={0.8} intensity={2.0} color="#fffbeb" distance={14} castShadow shadow-mapSize-width={1024} shadow-mapSize-height={1024} />
      {/* Volumetric beam planes */}
      <mesh position={[0, 0, 0.8]} rotation={[0, 0, 0]}>
        <planeGeometry args={[1.5, 2.0]} />
        <meshBasicMaterial color="#fde68a" transparent opacity={0.04} side={THREE.DoubleSide} blending={THREE.AdditiveBlending} depthWrite={false} />
      </mesh>
    </group>
  );
}

function SideTableWithDecor() {
  return (
    <group position={[-2.6, 0, 1.0]}>
      <mesh position={[0, 0.38, 0]} castShadow receiveShadow>
        <cylinderGeometry args={[0.5, 0.5, 0.76, 24]} />
        <meshStandardMaterial color="#1e1008" roughness={0.4} metalness={0.1} />
      </mesh>
      <mesh position={[0, 0.82, 0]} castShadow>
        <cylinderGeometry args={[0.38, 0.38, 0.14, 28]} />
        <meshPhysicalMaterial color="#0a0a0a" roughness={0.25} metalness={0.65} clearcoat={0.5} />
      </mesh>
      {/* Plant */}
      <group position={[0.22, 0.92, 0.12]}>
        <mesh castShadow receiveShadow>
          <cylinderGeometry args={[0.14, 0.17, 0.28, 16]} />
          <meshStandardMaterial color="#7a4a2a" roughness={0.75} />
        </mesh>
        <mesh position={[0, 0.28, 0]} castShadow>
          <sphereGeometry args={[0.2, 14, 14]} />
          <meshStandardMaterial color="#2d5016" roughness={0.75} />
        </mesh>
        <mesh position={[0.08, 0.35, 0.05]} castShadow>
          <sphereGeometry args={[0.12, 12, 12]} />
          <meshStandardMaterial color="#3a6b1e" roughness={0.75} />
        </mesh>
      </group>
      {/* Books */}
      <mesh position={[-0.15, 0.82, -0.1]} rotation={[0, 0.2, 0]} castShadow>
        <boxGeometry args={[0.3, 0.06, 0.22]} />
        <meshStandardMaterial color="#5c3a21" roughness={0.7} />
      </mesh>
    </group>
  );
}

function PictureFrames() {
  return (
    <group>
      {[
        { pos: [2, 2.8, -5.8] as [number, number, number], size: [0.8, 0.6] as [number, number], color: '#2c1810' },
        { pos: [0, 3.0, -5.8] as [number, number, number], size: [0.6, 0.8] as [number, number], color: '#3a2317' },
        { pos: [-2, 2.7, -5.8] as [number, number, number], size: [0.7, 0.5] as [number, number], color: '#2c1810' },
      ].map((f, i) => (
        <group key={i} position={f.pos}>
          <mesh castShadow>
            <boxGeometry args={[f.size[0] + 0.08, f.size[1] + 0.08, 0.04]} />
            <meshStandardMaterial color="#b45309" metalness={0.6} roughness={0.4} />
          </mesh>
          <mesh position={[0, 0, 0.03]}>
            <planeGeometry args={f.size} />
            <meshStandardMaterial color={f.color} roughness={0.8} />
          </mesh>
        </group>
      ))}
    </group>
  );
}

export default function CozyGramophoneRoom({
  isPlaying,
  isNeedleDropping = false,
  vinylStyle = 'classic_red',
  title = 'Our Anniversary Letter',
  recipientName = 'Eleanor',
  senderName = 'Arthur',
  isRecording = false,
}: CozyGramophoneRoomProps) {
  return (
    <div className="w-full h-full min-h-[580px] sm:min-h-[680px] lg:min-h-[780px] relative rounded-3xl overflow-hidden bg-[#0c0a09] border border-amber-900/30 shadow-[0_0_80px_rgba(217,119,6,0.15)]">
      <Canvas
        shadows
        gl={{ antialias: true, alpha: true, toneMapping: THREE.ACESFilmicToneMapping, toneMappingExposure: 1.15, shadowMap: { enabled: true, type: THREE.PCFSoftShadowMap } } as any}
        camera={{ position: [2.8, 2.0, 3.5], fov: 38 }}
        style={{ background: 'radial-gradient(ellipse at 30% 20%, #1f1a15 0%, #0c0a09 70%)' }}
      >
        <PerspectiveCamera makeDefault position={[3.0, 2.1, 3.6]} fov={38} />
        
        {/* Realistic environment for brass reflections */}
        <Environment preset="apartment" background={false} />

        {/* Cozy lighting setup */}
        <ambientLight intensity={0.38} color="#fde68a" />
        <directionalLight
          position={[5, 7, 4]}
          intensity={1.0}
          color="#fffbeb"
          castShadow
          shadow-mapSize-width={2048}
          shadow-mapSize-height={2048}
          shadow-camera-left={-8}
          shadow-camera-right={8}
          shadow-camera-top={8}
          shadow-camera-bottom={-8}
          shadow-camera-far={20}
          shadow-bias={-0.00015}
        />
        {/* Warm fill lights */}
        <pointLight position={[0, 1.2, -5.5]} color="#ff5a00" intensity={1.4} distance={9} decay={2} />
        <pointLight position={[3.6, 2.2, -1.2]} color="#fbbf24" intensity={1.2} distance={7} decay={2} />
        <pointLight position={[-7, 2, 1.8]} color="#87ceeb" intensity={0.6} distance={8} decay={2} />

        <WoodFloor />
        <Walls />
        <Rug />
        <ContactShadows position={[0, 0.02, 0.5]} opacity={0.6} scale={12} blur={2.8} far={5} color="#000000" />

        <GramophoneTable />
        <GramophoneBase />
        <VinylPlatter
          isPlaying={isPlaying || isNeedleDropping || isRecording}
          vinylStyle={vinylStyle}
          title={title}
          recipientName={recipientName}
          senderName={senderName}
        />
        <AnimatedGramophoneTonearm isPlaying={isPlaying} isNeedleDropping={isNeedleDropping || isRecording} />
        <GramophoneHorn isPlaying={isPlaying} isNeedleDropping={isNeedleDropping || isRecording} />

        <Fireplace />
        <Armchair />
        <Bookshelf />
        <FloorLamp />
        <WindowWithLight />
        <SideTableWithDecor />
        <PictureFrames />

        <DustMotes />

        <OrbitControls
          enablePan={false}
          minPolarAngle={Math.PI / 10}
          maxPolarAngle={Math.PI / 2.1}
          minDistance={1.4}
          maxDistance={7.5}
          target={[0, 1.1, 0.3]}
          dampingFactor={0.07}
          enableDamping
          autoRotate={false}
          autoRotateSpeed={0.15}
        />
      </Canvas>

      {/* Cozy overlays */}
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute inset-0 bg-gradient-to-t from-[#0c0a09]/50 via-transparent to-[#0c0a09]/15" />
        <div className="absolute inset-0 bg-gradient-to-r from-[#0c0a09]/20 via-transparent to-[#0c0a09]/20" />
        {/* Film grain */}
        <div className="absolute inset-0 opacity-[0.04] mix-blend-overlay" style={{ backgroundImage: `url("data:image/svg+xml,%3Csvg viewBox='0 0 512 512' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noise'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noise)'/%3E%3C/svg%3E")` }} />
        {/* Vignette */}
        <div className="absolute inset-0 rounded-3xl shadow-[inset_0_0_120px_rgba(0,0,0,0.8)]" />
      </div>

      {/* Recording indicator */}
      {isRecording && (
        <div className="absolute top-4 left-1/2 -translate-x-1/2 z-20 px-4 py-2 rounded-full bg-red-600/90 backdrop-blur-md border border-red-400 text-white font-mono text-xs flex items-center gap-2 animate-pulse shadow-xl">
          <span className="w-2.5 h-2.5 rounded-full bg-white animate-ping" />
          <span>● REC • Capturing in cozy room</span>
        </div>
      )}
    </div>
  );
}
