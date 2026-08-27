'use client';

import React, { useRef, useMemo } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { OrbitControls, PerspectiveCamera, ContactShadows } from '@react-three/drei';
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
}

// Dust motes floating in warm sunbeam
function DustMotes() {
  const count = 300;
  const positions = useMemo(() => {
    const pos = new Float32Array(count * 3);
    for (let i = 0; i < count * 3; i += 3) {
      pos[i] = (Math.random() - 0.5) * 10;
      pos[i + 1] = Math.random() * 4 + 0.2;
      pos[i + 2] = (Math.random() - 0.5) * 10;
    }
    return pos;
  }, []);

  const ref = useRef<THREE.Points>(null);
  useFrame((state) => {
    if (!ref.current) return;
    const t = state.clock.getElapsedTime();
    ref.current.rotation.y = t * 0.015;
    const attr = ref.current.geometry.attributes.position;
    for (let i = 1; i < count * 3; i += 3) {
      let y = (attr.array as Float32Array)[i];
      y += Math.sin(t * 0.5 + i) * 0.0008;
      if (y > 5) y = 0.2;
      (attr.array as Float32Array)[i] = y;
    }
    attr.needsUpdate = true;
  });

  return (
    <points ref={ref}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[positions, 3]} />
      </bufferGeometry>
      <pointsMaterial size={0.04} color="#fde68a" transparent opacity={0.55} blending={THREE.AdditiveBlending} sizeAttenuation />
    </points>
  );
}

function WoodFloor() {
  return (
    <group>
      {/* Main floor */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0, 0]} receiveShadow>
        <planeGeometry args={[14, 14]} />
        <meshStandardMaterial color="#3d2817" roughness={0.75} metalness={0.05} />
      </mesh>
      {/* Wood planks detail - subtle lines */}
      {Array.from({ length: 14 }).map((_, i) => (
        <mesh key={i} rotation={[-Math.PI / 2, 0, 0]} position={[-7 + i + 0.5, 0.002, 0]} receiveShadow>
          <planeGeometry args={[0.98, 14]} />
          <meshStandardMaterial color={i % 2 === 0 ? "#4a3320" : "#3a2515"} roughness={0.8} metalness={0.02} />
        </mesh>
      ))}
    </group>
  );
}

function Walls() {
  return (
    <group>
      {/* Back wall - warm cream with wainscoting */}
      <mesh position={[0, 2.5, -5]} receiveShadow>
        <boxGeometry args={[14, 5, 0.2]} />
        <meshStandardMaterial color="#e8dcc6" roughness={0.9} />
      </mesh>
      {/* Wainscoting dark wood lower */}
      <mesh position={[0, 0.8, -4.89]} receiveShadow>
        <boxGeometry args={[14, 1.6, 0.25]} />
        <meshStandardMaterial color="#2a1a0f" roughness={0.6} metalness={0.1} />
      </mesh>
      {/* Left wall */}
      <mesh position={[-7, 2.5, 0]} rotation={[0, Math.PI / 2, 0]} receiveShadow>
        <boxGeometry args={[14, 5, 0.2]} />
        <meshStandardMaterial color="#e6d5b8" roughness={0.9} />
      </mesh>
      {/* Right wall */}
      <mesh position={[7, 2.5, 0]} rotation={[0, Math.PI / 2, 0]} receiveShadow>
        <boxGeometry args={[14, 5, 0.2]} />
        <meshStandardMaterial color="#e6d5b8" roughness={0.9} />
      </mesh>
      {/* Ceiling */}
      <mesh position={[0, 5, 0]} rotation={[Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[14, 14]} />
        <meshStandardMaterial color="#f5efe0" roughness={0.95} />
      </mesh>
      {/* Crown molding */}
      <mesh position={[0, 4.9, -4.9]}>
        <boxGeometry args={[14, 0.2, 0.3]} />
        <meshStandardMaterial color="#d4c4a8" roughness={0.7} />
      </mesh>
    </group>
  );
}

function Rug() {
  return (
    <group position={[0, 0.015, 0.5]}>
      <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[5, 4]} />
        <meshStandardMaterial color="#8b5a2b" roughness={0.95} />
      </mesh>
      {/* Rug pattern - concentric */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0.001, 0]}>
        <ringGeometry args={[0.5, 0.7, 32]} />
        <meshStandardMaterial color="#a67c52" roughness={0.9} transparent opacity={0.6} />
      </mesh>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0.002, 0]}>
        <ringGeometry args={[1.2, 1.35, 32]} />
        <meshStandardMaterial color="#d4a574" roughness={0.9} transparent opacity={0.4} />
      </mesh>
      {/* Fringe */}
      {Array.from({ length: 20 }).map((_, i) => (
        <mesh key={i} position={[-2.3 + i * 0.24, 0.003, 1.9]} rotation={[0, 0, 0]}>
          <boxGeometry args={[0.04, 0.01, 0.25]} />
          <meshStandardMaterial color="#d4c4a8" roughness={0.9} />
        </mesh>
      ))}
    </group>
  );
}

function GramophoneTable() {
  return (
    <group position={[0, 0.45, 0.5]}>
      {/* Table top - rich mahogany */}
      <mesh position={[0, 0.45, 0]} castShadow receiveShadow>
        <boxGeometry args={[2.8, 0.12, 2.0]} />
        <meshStandardMaterial color="#2c1810" roughness={0.35} metalness={0.15} />
      </mesh>
      {/* Table edge trim brass */}
      <mesh position={[0, 0.38, 0]}>
        <boxGeometry args={[2.85, 0.04, 2.05]} />
        <meshStandardMaterial color="#b45309" metalness={0.8} roughness={0.3} />
      </mesh>
      {/* Legs - turned wood */}
      {[
        [-1.2, -0.2, -0.8],
        [1.2, -0.2, -0.8],
        [-1.2, -0.2, 0.8],
        [1.2, -0.2, 0.8],
      ].map((pos, i) => (
        <group key={i} position={pos as [number, number, number]}>
          <mesh position={[0, 0.1, 0]} castShadow>
            <cylinderGeometry args={[0.08, 0.1, 0.9, 16]} />
            <meshStandardMaterial color="#1f120b" roughness={0.5} />
          </mesh>
          <mesh position={[0, -0.35, 0]} castShadow>
            <cylinderGeometry args={[0.06, 0.08, 0.2, 16]} />
            <meshStandardMaterial color="#b45309" metalness={0.7} roughness={0.4} />
          </mesh>
        </group>
      ))}
      {/* Drawer */}
      <mesh position={[0, 0.2, 0.92]} castShadow>
        <boxGeometry args={[1.5, 0.35, 0.05]} />
        <meshStandardMaterial color="#3a2317" roughness={0.5} />
      </mesh>
      <mesh position={[0, 0.2, 0.96]}>
        <boxGeometry args={[0.15, 0.08, 0.02]} />
        <meshStandardMaterial color="#d97706" metalness={0.85} roughness={0.25} />
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

    // Base
    ctx.fillStyle = styleConfig.labelColor;
    ctx.fillRect(0, 0, 512, 512);

    // Gold rings
    ctx.strokeStyle = styleConfig.brassAccent || '#f59e0b';
    ctx.lineWidth = 7;
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
    ctx.arc(256, 256, 28, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(256, 256, 34, 0, Math.PI * 2);
    ctx.stroke();

    // Text
    ctx.fillStyle = '#fef3c7';
    ctx.textAlign = 'center';
    ctx.font = 'bold 18px serif';
    ctx.fillText('DIGITAL WAX', 256, 90);
    ctx.font = '12px monospace';
    ctx.fillStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.fillText('78 RPM • GRAMOPHONE MASTER', 256, 115);

    ctx.font = 'bold 20px serif';
    ctx.fillStyle = '#fff';
    const t = title.length > 20 ? title.slice(0, 18) + '...' : title;
    ctx.fillText(t.toUpperCase(), 256, 165);

    ctx.font = 'italic 15px serif';
    ctx.fillStyle = '#fde68a';
    if (recipientName) ctx.fillText(`For: ${recipientName}`, 256, 350);
    if (senderName) ctx.fillText(`From: ${senderName}`, 256, 375);

    ctx.font = 'bold 14px monospace';
    ctx.fillStyle = styleConfig.brassAccent || '#fbbf24';
    ctx.fillText('SIDE A • 1925', 256, 425);

    const tex = new THREE.CanvasTexture(canvas);
    tex.anisotropy = 8;
    return tex;
  }, [styleConfig, title, recipientName, senderName]);

  useFrame((_, delta) => {
    if (groupRef.current && isPlaying) {
      groupRef.current.rotation.y += delta * 2.8; // 78 RPM would be faster, but 33 feels cozy, use 2.8 rad/s ~ 27 RPM for gramophone
    }
  });

  return (
    <group position={[0, 1.05, 0.5]}>
      {/* Felt mat */}
      <mesh position={[0, 0.005, 0]}>
        <cylinderGeometry args={[0.75, 0.75, 0.01, 48]} />
        <meshStandardMaterial color="#1c1917" roughness={0.95} />
      </mesh>

      {/* Rotating group */}
      <group ref={groupRef}>
        {/* Vinyl */}
        <mesh position={[0, 0.02, 0]} castShadow receiveShadow>
          <cylinderGeometry args={[0.7, 0.7, 0.025, 48]} />
          <meshPhysicalMaterial
            color={styleConfig.baseColor}
            roughness={0.3}
            metalness={0.7}
            clearcoat={0.5}
            clearcoatRoughness={0.15}
            reflectivity={0.8}
          />
        </mesh>
        {/* Grooves */}
        {[0.25, 0.35, 0.45, 0.55, 0.65].map((r, i) => (
          <mesh key={i} position={[0, 0.034, 0]} rotation={[-Math.PI / 2, 0, 0]}>
            <ringGeometry args={[r - 0.008, r, 48]} />
            <meshBasicMaterial color={styleConfig.grooveColor} transparent opacity={0.6} />
          </mesh>
        ))}
        {/* Label */}
        {labelTexture && (
          <mesh position={[0, 0.036, 0]} rotation={[-Math.PI / 2, 0, 0]}>
            <circleGeometry args={[0.22, 48]} />
            <meshBasicMaterial map={labelTexture} />
          </mesh>
        )}
        {/* Spindle */}
        <mesh position={[0, 0.07, 0]}>
          <cylinderGeometry args={[0.015, 0.015, 0.08, 16]} />
          <meshStandardMaterial color="#fbbf24" metalness={0.95} roughness={0.1} />
        </mesh>
      </group>
    </group>
  );
}

function GramophoneBase() {
  return (
    <group position={[0, 0.95, 0.5]}>
      {/* Main wooden gramophone box */}
      <mesh position={[0, 0, 0]} castShadow receiveShadow>
        <boxGeometry args={[1.6, 0.5, 1.2]} />
        <meshStandardMaterial color="#23120b" roughness={0.4} metalness={0.1} />
      </mesh>
      {/* Top veneer */}
      <mesh position={[0, 0.26, 0]} receiveShadow>
        <boxGeometry args={[1.55, 0.02, 1.15]} />
        <meshStandardMaterial color="#3a2317" roughness={0.3} metalness={0.1} />
      </mesh>
      {/* Brass corners */}
      {[
        [-0.8, 0, -0.6],
        [0.8, 0, -0.6],
        [-0.8, 0, 0.6],
        [0.8, 0, 0.6],
      ].map((p, i) => (
        <mesh key={i} position={p as [number, number, number]} castShadow>
          <boxGeometry args={[0.08, 0.52, 0.08]} />
          <meshStandardMaterial color="#b45309" metalness={0.85} roughness={0.25} />
        </mesh>
      ))}
      {/* Crank handle on side */}
      <group position={[0.85, -0.05, 0.2]}>
        <mesh rotation={[0, 0, Math.PI / 2]} castShadow>
          <cylinderGeometry args={[0.04, 0.04, 0.25, 16]} />
          <meshStandardMaterial color="#451a03" roughness={0.6} />
        </mesh>
        <mesh position={[0.12, 0, 0]} castShadow>
          <cylinderGeometry args={[0.02, 0.02, 0.15, 12]} />
          <meshStandardMaterial color="#fbbf24" metalness={0.9} roughness={0.2} />
        </mesh>
        <mesh position={[0.12, 0.08, 0]} castShadow>
          <boxGeometry args={[0.08, 0.04, 0.04]} />
          <meshStandardMaterial color="#1c1917" roughness={0.5} />
        </mesh>
      </group>
      {/* Front horn support */}
      <mesh position={[0, 0.15, -0.65]} castShadow>
        <cylinderGeometry args={[0.06, 0.08, 0.35, 16]} />
        <meshStandardMaterial color="#78350f" metalness={0.7} roughness={0.35} />
      </mesh>
      {/* Brand plate */}
      <mesh position={[0, -0.05, 0.61]} castShadow>
        <boxGeometry args={[0.6, 0.12, 0.02]} />
        <meshStandardMaterial color="#fbbf24" metalness={0.9} roughness={0.2} />
      </mesh>
    </group>
  );
}

function GramophoneHorn({ isPlaying, isNeedleDropping }: { isPlaying: boolean; isNeedleDropping?: boolean }) {
  const hornRef = useRef<THREE.Group>(null);

  useFrame((state) => {
    if (hornRef.current && isPlaying) {
      // Subtle vibration when playing
      const t = state.clock.getElapsedTime();
      hornRef.current.position.y = Math.sin(t * 18) * 0.003;
      hornRef.current.rotation.z = Math.sin(t * 12) * 0.002;
    }
  });

  return (
    <group ref={hornRef} position={[0, 1.4, -0.2]}>
      {/* Horn throat - connects tonearm to horn */}
      <group position={[0, -0.2, 0.3]}>
        <mesh rotation={[Math.PI / 2, 0, 0]} castShadow>
          <cylinderGeometry args={[0.04, 0.06, 0.4, 16]} />
          <meshStandardMaterial color="#b45309" metalness={0.85} roughness={0.25} />
        </mesh>
      </group>

      {/* Horn segments - classic gramophone flared horn */}
      {/* Segment 1 - narrow */}
      <mesh position={[0, 0.1, -0.15]} rotation={[0.3, 0, 0]} castShadow>
        <cylinderGeometry args={[0.06, 0.14, 0.6, 24]} />
        <meshStandardMaterial color="#d97706" metalness={0.88} roughness={0.22} side={THREE.DoubleSide} />
      </mesh>

      {/* Segment 2 - mid */}
      <mesh position={[0, 0.45, -0.45]} rotation={[0.35, 0, 0]} castShadow>
        <cylinderGeometry args={[0.14, 0.32, 0.7, 24]} />
        <meshStandardMaterial color="#f59e0b" metalness={0.9} roughness={0.2} side={THREE.DoubleSide} />
      </mesh>

      {/* Segment 3 - wide bell */}
      <mesh position={[0, 0.85, -0.85]} rotation={[0.4, 0, 0]} castShadow>
        <cylinderGeometry args={[0.32, 0.85, 0.8, 32]} />
        <meshStandardMaterial color="#fbbf24" metalness={0.92} roughness={0.18} side={THREE.DoubleSide} />
      </mesh>

      {/* Bell rim - thick brass */}
      <mesh position={[0, 1.25, -1.2]} rotation={[0.4, 0, 0]} castShadow>
        <torusGeometry args={[0.85, 0.06, 16, 48]} />
        <meshStandardMaterial color="#f59e0b" metalness={0.9} roughness={0.2} />
      </mesh>

      {/* Inner bell - darker for depth */}
      <mesh position={[0, 1.22, -1.15]} rotation={[0.4, 0, 0]}>
        <circleGeometry args={[0.82, 32]} />
        <meshStandardMaterial color="#451a03" roughness={0.7} metalness={0.3} side={THREE.DoubleSide} />
      </mesh>

      {/* Brass support strut */}
      <mesh position={[0, 0.3, -0.1]} rotation={[0.5, 0, 0]} castShadow>
        <cylinderGeometry args={[0.015, 0.015, 1.1, 8]} />
        <meshStandardMaterial color="#78350f" metalness={0.75} roughness={0.3} />
      </mesh>

      {/* Glow when playing */}
      {(isPlaying || isNeedleDropping) && (
        <>
          <pointLight position={[0, 1.2, -1.1]} color="#fbbf24" intensity={1.2} distance={2.5} />
          <pointLight position={[0, 0.5, -0.4]} color="#d97706" intensity={0.6} distance={1.5} />
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
    const targetYaw = isPlaying || isNeedleDropping ? 0.35 : -0.55;
    const targetPitch = isPlaying || isNeedleDropping ? -0.02 : 0.25;
    const speed = delta * 3.0;
    yawRef.current.rotation.y = THREE.MathUtils.lerp(yawRef.current.rotation.y, targetYaw, speed);
    pitchRef.current.rotation.x = THREE.MathUtils.lerp(pitchRef.current.rotation.x, targetPitch, speed);
  });

  return (
    <group position={[0.55, 1.15, 0.2]}>
      {/* Base pivot */}
      <mesh position={[0, 0, 0]} castShadow>
        <cylinderGeometry args={[0.12, 0.14, 0.15, 20]} />
        <meshStandardMaterial color="#d97706" metalness={0.9} roughness={0.2} />
      </mesh>

      <group ref={yawRef} rotation={[0, -0.55, 0]}>
        <mesh position={[0, 0.08, 0]} castShadow>
          <boxGeometry args={[0.1, 0.1, 0.1]} />
          <meshStandardMaterial color="#92400e" metalness={0.85} roughness={0.25} />
        </mesh>

        <group ref={pitchRef} position={[0, 0.08, 0]} rotation={[0.25, 0, 0]}>
          {/* Tonearm tube - brass */}
          <mesh position={[0, 0, -0.55]} rotation={[Math.PI / 2, 0, 0]} castShadow>
            <cylinderGeometry args={[0.018, 0.022, 1.1, 12]} />
            <meshStandardMaterial color="#f59e0b" metalness={0.92} roughness={0.15} />
          </mesh>

          {/* Headshell */}
          <group position={[0, -0.01, -1.1]} rotation={[0, -0.2, 0]}>
            <mesh castShadow>
              <boxGeometry args={[0.07, 0.04, 0.16]} />
              <meshStandardMaterial color="#1c1917" metalness={0.7} roughness={0.3} />
            </mesh>
            <mesh position={[0, -0.03, -0.02]} castShadow>
              <coneGeometry args={[0.01, 0.05, 12]} />
              <meshStandardMaterial color="#fef3c7" metalness={0.99} roughness={0.05} />
            </mesh>
            {(isPlaying || isNeedleDropping) && (
              <pointLight position={[0, -0.03, -0.02]} color="#fbbf24" intensity={0.5} distance={0.6} />
            )}
          </group>
        </group>
      </group>
    </group>
  );
}

function Fireplace() {
  const fireRef = useRef<THREE.Mesh>(null);
  useFrame((state) => {
    if (fireRef.current) {
      const t = state.clock.getElapsedTime();
      const scale = 1 + Math.sin(t * 8) * 0.08;
      fireRef.current.scale.set(scale, scale, scale);
      (fireRef.current.material as THREE.MeshStandardMaterial).emissiveIntensity = 1.2 + Math.sin(t * 10) * 0.4;
    }
  });

  return (
    <group position={[0, 0.9, -4.7]}>
      {/* Brick surround */}
      <mesh position={[0, 0.6, 0]} castShadow receiveShadow>
        <boxGeometry args={[2.2, 1.8, 0.4]} />
        <meshStandardMaterial color="#4a2c17" roughness={0.85} />
      </mesh>
      {/* Inner dark */}
      <mesh position={[0, 0.5, 0.22]} receiveShadow>
        <boxGeometry args={[1.2, 0.9, 0.05]} />
        <meshStandardMaterial color="#0c0a09" roughness={0.95} />
      </mesh>
      {/* Fire */}
      <mesh ref={fireRef} position={[0, 0.35, 0.25]}>
        <boxGeometry args={[0.8, 0.5, 0.1]} />
        <meshStandardMaterial color="#ff6b00" emissive="#ff4500" emissiveIntensity={1.5} roughness={0.8} />
      </mesh>
      {/* Logs */}
      <mesh position={[-0.15, 0.15, 0.28]} rotation={[0, 0, 0.3]} castShadow>
        <cylinderGeometry args={[0.06, 0.06, 0.5, 8]} />
        <meshStandardMaterial color="#2a1810" roughness={0.9} />
      </mesh>
      <mesh position={[0.15, 0.15, 0.28]} rotation={[0, 0, -0.3]} castShadow>
        <cylinderGeometry args={[0.05, 0.05, 0.45, 8]} />
        <meshStandardMaterial color="#2a1810" roughness={0.9} />
      </mesh>
      {/* Mantel */}
      <mesh position={[0, 1.55, 0.15]} castShadow receiveShadow>
        <boxGeometry args={[2.5, 0.12, 0.5]} />
        <meshStandardMaterial color="#1f120b" roughness={0.4} />
      </mesh>
      {/* Mantel decor - clock */}
      <mesh position={[0, 1.75, 0.15]} castShadow>
        <cylinderGeometry args={[0.18, 0.18, 0.08, 20]} />
        <meshStandardMaterial color="#d97706" metalness={0.8} roughness={0.3} />
      </mesh>
      {/* Light from fire */}
      <pointLight position={[0, 0.5, 0.8]} color="#ff6b00" intensity={2.5} distance={6} decay={2} />
    </group>
  );
}

function Armchair() {
  return (
    <group position={[2.8, 0, -2.5]} rotation={[0, -0.6, 0]}>
      {/* Seat */}
      <mesh position={[0, 0.4, 0]} castShadow receiveShadow>
        <boxGeometry args={[1.2, 0.4, 1.0]} />
        <meshStandardMaterial color="#5c3a21" roughness={0.85} />
      </mesh>
      {/* Back */}
      <mesh position={[0, 0.9, -0.4]} castShadow receiveShadow>
        <boxGeometry args={[1.2, 0.9, 0.2]} />
        <meshStandardMaterial color="#5c3a21" roughness={0.85} />
      </mesh>
      {/* Arms */}
      <mesh position={[-0.65, 0.6, 0]} castShadow>
        <boxGeometry args={[0.15, 0.5, 1.0]} />
        <meshStandardMaterial color="#4a2f1b" roughness={0.8} />
      </mesh>
      <mesh position={[0.65, 0.6, 0]} castShadow>
        <boxGeometry args={[0.15, 0.5, 1.0]} />
        <meshStandardMaterial color="#4a2f1b" roughness={0.8} />
      </mesh>
      {/* Cushion */}
      <mesh position={[0, 0.65, 0]} castShadow>
        <boxGeometry args={[1.0, 0.15, 0.8]} />
        <meshStandardMaterial color="#8b5a2b" roughness={0.9} />
      </mesh>
      {/* Throw blanket */}
      <mesh position={[0.3, 0.7, 0.2]} rotation={[0, 0, 0.2]} castShadow>
        <boxGeometry args={[0.6, 0.08, 0.7]} />
        <meshStandardMaterial color="#a67c52" roughness={0.95} />
      </mesh>
    </group>
  );
}

function Bookshelf() {
  return (
    <group position={[-6.7, 1.2, -1]}>
      {/* Frame */}
      <mesh position={[0, 0, 0]} castShadow receiveShadow>
        <boxGeometry args={[0.4, 3, 2.5]} />
        <meshStandardMaterial color="#2a1a0f" roughness={0.6} />
      </mesh>
      {/* Shelves */}
      {[ -0.8, 0, 0.8].map((y, i) => (
        <mesh key={i} position={[0.15, y, 0]} castShadow>
          <boxGeometry args={[0.35, 0.05, 2.3]} />
          <meshStandardMaterial color="#3a2317" roughness={0.5} />
        </mesh>
      ))}
      {/* Books */}
      {Array.from({ length: 8 }).map((_, i) => (
        <mesh key={i} position={[0.15, -0.9 + (i % 3) * 0.25, -0.9 + i * 0.22]} rotation={[0, 0, (Math.random() - 0.5) * 0.2]} castShadow>
          <boxGeometry args={[0.25, 0.4 + Math.random() * 0.2, 0.08]} />
          <meshStandardMaterial color={["#8b4513", "#5c3a21", "#a0522d", "#6b4423"][i % 4]} roughness={0.7} />
        </mesh>
      ))}
    </group>
  );
}

function FloorLamp() {
  return (
    <group position={[3.2, 0, -1]}>
      {/* Base */}
      <mesh position={[0, 0.05, 0]} castShadow>
        <cylinderGeometry args={[0.25, 0.28, 0.1, 20]} />
        <meshStandardMaterial color="#2a1a0f" roughness={0.5} />
      </mesh>
      {/* Pole */}
      <mesh position={[0, 1.0, 0]} castShadow>
        <cylinderGeometry args={[0.03, 0.03, 2.0, 12]} />
        <meshStandardMaterial color="#b45309" metalness={0.7} roughness={0.3} />
      </mesh>
      {/* Shade */}
      <mesh position={[0, 2.0, 0]} castShadow>
        <cylinderGeometry args={[0.35, 0.5, 0.5, 24]} />
        <meshStandardMaterial color="#e8dcc6" roughness={0.9} transparent opacity={0.85} side={THREE.DoubleSide} />
      </mesh>
      {/* Light */}
      <pointLight position={[0, 1.8, 0]} color="#fde68a" intensity={1.8} distance={5} decay={2} />
    </group>
  );
}

function WindowWithLight() {
  return (
    <group position={[-6.9, 1.8, 1.5]} rotation={[0, Math.PI / 2, 0]}>
      {/* Frame */}
      <mesh castShadow>
        <boxGeometry args={[1.8, 2.2, 0.15]} />
        <meshStandardMaterial color="#2a1a0f" roughness={0.6} />
      </mesh>
      {/* Glass - emissive soft */}
      <mesh position={[0, 0, 0.1]}>
        <planeGeometry args={[1.5, 1.9]} />
        <meshStandardMaterial color="#87ceeb" emissive="#87ceeb" emissiveIntensity={0.15} roughness={0.2} metalness={0.1} transparent opacity={0.6} />
      </mesh>
      {/* Curtains */}
      <mesh position={[-0.6, 0, 0.15]} castShadow>
        <boxGeometry args={[0.3, 2.2, 0.05]} />
        <meshStandardMaterial color="#8b4513" roughness={0.95} />
      </mesh>
      <mesh position={[0.6, 0, 0.15]} castShadow>
        <boxGeometry args={[0.3, 2.2, 0.05]} />
        <meshStandardMaterial color="#8b4513" roughness={0.95} />
      </mesh>
      {/* Sunbeam light */}
      <spotLight position={[0, 0, 1]} angle={0.6} penumbra={0.7} intensity={1.5} color="#fffbeb" distance={12} castShadow />
    </group>
  );
}

function SideTableWithDecor() {
  return (
    <group position={[-2.2, 0, 0.8]}>
      <mesh position={[0, 0.35, 0]} castShadow receiveShadow>
        <cylinderGeometry args={[0.45, 0.45, 0.7, 20]} />
        <meshStandardMaterial color="#2c1810" roughness={0.45} />
      </mesh>
      {/* Stack of records */}
      <mesh position={[0, 0.75, 0]} castShadow>
        <cylinderGeometry args={[0.35, 0.35, 0.12, 24]} />
        <meshStandardMaterial color="#121212" roughness={0.3} metalness={0.6} />
      </mesh>
      {/* Plant */}
      <group position={[0.2, 0.85, 0.1]}>
        <mesh castShadow>
          <cylinderGeometry args={[0.12, 0.15, 0.25, 16]} />
          <meshStandardMaterial color="#8b5a2b" roughness={0.8} />
        </mesh>
        <mesh position={[0, 0.25, 0]} castShadow>
          <sphereGeometry args={[0.18, 12, 12]} />
          <meshStandardMaterial color="#2d5016" roughness={0.8} />
        </mesh>
      </group>
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
}: CozyGramophoneRoomProps) {
  const controlsRef = useRef<any>(null);

  return (
    <div className="w-full h-full min-h-[520px] sm:min-h-[620px] lg:min-h-[720px] relative rounded-3xl overflow-hidden bg-[#0c0a09] border border-amber-900/30 shadow-2xl">
      <Canvas
        shadows
        gl={{ antialias: true, alpha: true, toneMapping: THREE.ACESFilmicToneMapping, toneMappingExposure: 1.1 }}
        camera={{ position: [2.2, 1.8, 2.8], fov: 42 }}
        style={{ background: 'radial-gradient(ellipse at center, #1c1917 0%, #0c0a09 100%)' }}
      >
        <PerspectiveCamera makeDefault position={[2.5, 1.9, 3.2]} fov={42} />

        {/* Cozy warm lighting */}
        <ambientLight intensity={0.45} color="#fde68a" />
        <directionalLight
          position={[4, 6, 3]}
          intensity={0.8}
          color="#fffbeb"
          castShadow
          shadow-mapSize-width={2048}
          shadow-mapSize-height={2048}
          shadow-camera-far={20}
          shadow-bias={-0.0002}
        />
        {/* Warm fill from fireplace side */}
        <pointLight position={[0, 1, -4]} color="#ff6b00" intensity={1.2} distance={8} decay={2} />
        {/* Lamp warm */}
        <pointLight position={[3, 2, -1]} color="#fbbf24" intensity={1.0} distance={6} />

        {/* Room */}
        <WoodFloor />
        <Walls />
        <Rug />
        <ContactShadows position={[0, 0.02, 0.5]} opacity={0.5} scale={10} blur={2.5} far={4} color="#0c0a09" />

        {/* Furniture & Decor */}
        <GramophoneTable />
        <GramophoneBase />
        <VinylPlatter
          isPlaying={isPlaying || isNeedleDropping}
          vinylStyle={vinylStyle}
          title={title}
          recipientName={recipientName}
          senderName={senderName}
        />
        <AnimatedGramophoneTonearm isPlaying={isPlaying} isNeedleDropping={isNeedleDropping} />
        <GramophoneHorn isPlaying={isPlaying} isNeedleDropping={isNeedleDropping} />

        <Fireplace />
        <Armchair />
        <Bookshelf />
        <FloorLamp />
        <WindowWithLight />
        <SideTableWithDecor />

        {/* Cozy dust motes in sunbeam */}
        <DustMotes />

        {/* Controls - cozy constrained orbit */}
        <OrbitControls
          ref={controlsRef}
          enablePan={false}
          minPolarAngle={Math.PI / 8}
          maxPolarAngle={Math.PI / 2.2}
          minDistance={1.5}
          maxDistance={6.5}
          target={[0, 1.0, 0.3]}
          dampingFactor={0.06}
          enableDamping
          autoRotate={false}
        />
      </Canvas>

      {/* Vignette overlay for cozy feel */}
      <div className="absolute inset-0 pointer-events-none bg-gradient-to-t from-[#0c0a09]/40 via-transparent to-[#0c0a09]/20" />
      <div className="absolute inset-0 pointer-events-none opacity-[0.03]" style={{ backgroundImage: `url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)'/%3E%3C/svg%3E")` }} />
    </div>
  );
}
