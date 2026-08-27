'use client';

/**
 * AnimeTurntablePlayer
 * ----------------------------------------------------------------------------
 * A single-canvas 3D "anime cel-shaded" vinyl record player.
 *
 * Design goals:
 *  - Beautiful, stylised-but-tangible turntable (toon bands for painted parts,
 *    physical materials for brass/chrome/glass so the metal still feels real).
 *  - Cheap to draw. This component deliberately avoids everything that made the
 *    old "cozy room" scene crawl: no HDR `Environment` download, no 2048²
 *    shadow map on 170 meshes, no per-frame ContactShadows, no per-vertex CPU
 *    particle loops, no full-page CSS backdrop-filter/film-grain overlays.
 *  - Self-throttling: rendering stops when the tab is hidden and when the scene
 *    is idle for a while (see `useIdleFrameloop`).
 */

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { OrbitControls, RoundedBox, Sparkles } from '@react-three/drei';
import * as THREE from 'three';
import { VinylStyleType } from '@/types';
import { VINYL_STYLES } from '@/lib/constants';

export interface AnimeTurntablePlayerProps {
  isPlaying: boolean;
  /** Needle is mid-fall toward the wax (drives the arm-drop animation). */
  isNeedleDropping?: boolean;
  vinylStyle?: VinylStyleType;
  title?: string;
  recipientName?: string;
  senderName?: string;
  /** Red "REC" glow on the power LED. */
  isRecording?: boolean;
  /** `low` trims particles/detail for small preview cards. */
  detail?: 'high' | 'low';
  className?: string;
}

/* ------------------------------------------------------------------ */
/* Texture helpers (module-level caches: built once, shared by all)   */
/* ------------------------------------------------------------------ */

type TexCache = Record<string, THREE.Texture | null>;
const texCache: TexCache = {};
const canRender = () => typeof document !== 'undefined';
const ctx2d = (w: number, h: number) => {
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  return { canvas, ctx: canvas.getContext('2d')! };
};

/** 4-band cel-shading ramp — the heart of the "anime" look. */
function toonGradient(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.toon) return texCache.toon;
  const steps = new Uint8Array([70, 140, 205, 255]);
  const tex = new THREE.DataTexture(steps, steps.length, 1, THREE.RedFormat);
  tex.minFilter = THREE.NearestFilter;
  tex.magFilter = THREE.NearestFilter;
  tex.generateMipmaps = false;
  tex.needsUpdate = true;
  texCache.toon = tex;
  return tex;
}

/** Warm painted walnut with soft grain streaks. */
function woodTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.wood) return texCache.wood;
  const { canvas, ctx } = ctx2d(512, 512);
  const g = ctx.createLinearGradient(0, 0, 0, 512);
  g.addColorStop(0, '#7a4a2c');
  g.addColorStop(0.55, '#6b3f26');
  g.addColorStop(1, '#5a3421');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 512, 512);
  for (let i = 0; i < 90; i++) {
    const y = (i / 90) * 512 + (Math.random() - 0.5) * 10;
    ctx.strokeStyle = `rgba(${38 + Math.random() * 26},${20 + Math.random() * 14},12,${0.05 + Math.random() * 0.14})`;
    ctx.lineWidth = 0.6 + Math.random() * 2.6;
    ctx.beginPath();
    for (let x = 0; x <= 512; x += 16) {
      ctx.lineTo(x, y + Math.sin(x * 0.012 + i) * 4 + (Math.random() - 0.5) * 2);
    }
    ctx.stroke();
  }
  // few bright "anime" highlight streaks
  for (let i = 0; i < 7; i++) {
    ctx.strokeStyle = 'rgba(255,225,190,0.07)';
    ctx.lineWidth = 8 + Math.random() * 16;
    ctx.beginPath();
    const y = Math.random() * 512;
    ctx.moveTo(0, y);
    ctx.lineTo(512, y + (Math.random() - 0.5) * 40);
    ctx.stroke();
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.wood = tex;
  return tex;
}

/** Brushed metal for the top plate / brass trim. */
function brushedTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.brushed) return texCache.brushed;
  const { canvas, ctx } = ctx2d(512, 512);
  ctx.fillStyle = '#2b2724';
  ctx.fillRect(0, 0, 512, 512);
  for (let i = 0; i < 900; i++) {
    const y = Math.random() * 512;
    ctx.strokeStyle = `rgba(255,255,255,${0.01 + Math.random() * 0.05})`;
    ctx.lineWidth = 0.5 + Math.random();
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(512, y + (Math.random() - 0.5) * 3);
    ctx.stroke();
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.brushed = tex;
  return tex;
}

/**
 * The record face: grooves + a pastel anime centre label carrying the
 * dedication. Regenerated only when the record's metadata changes.
 */
function recordTexture(cfg: {
  baseColor: string;
  labelColor: string;
  grooveColor: string;
  brassAccent: string;
  title: string;
  recipientName?: string;
  senderName?: string;
}): THREE.Texture | null {
  if (!canRender()) return null;
  const key = `rec:${cfg.title}|${cfg.recipientName}|${cfg.senderName}|${cfg.labelColor}|${cfg.grooveColor}`;
  if (texCache[key]) return texCache[key];

  const S = 1024;
  const c = S / 2;
  const { canvas, ctx } = ctx2d(S, S);

  // Wax body
  ctx.fillStyle = cfg.baseColor;
  ctx.beginPath();
  ctx.arc(c, c, c, 0, Math.PI * 2);
  ctx.fill();

  // Grooves: fine concentric rings, denser toward the label
  for (let r = c * 0.46; r < c * 0.965; r += 2.1) {
    ctx.strokeStyle = cfg.grooveColor;
    ctx.globalAlpha = 0.32 + Math.random() * 0.4;
    ctx.lineWidth = 0.9;
    ctx.beginPath();
    ctx.arc(c, c, r, 0, Math.PI * 2);
    ctx.stroke();
  }
  // a couple of deeper "track change" bands
  ctx.globalAlpha = 0.85;
  for (const r of [c * 0.55, c * 0.68, c * 0.8, c * 0.9]) {
    ctx.strokeStyle = 'rgba(0,0,0,0.55)';
    ctx.lineWidth = 2.4;
    ctx.beginPath();
    ctx.arc(c, c, r, 0, Math.PI * 2);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;

  // Outer rim highlight
  ctx.strokeStyle = 'rgba(255,255,255,0.10)';
  ctx.lineWidth = 4;
  ctx.beginPath();
  ctx.arc(c, c, c - 3, 0, Math.PI * 2);
  ctx.stroke();

  // ---- centre label (pastel disc + hand-drawn sun) ----
  const lr = c * 0.4;
  const lg = ctx.createRadialGradient(c - lr * 0.3, c - lr * 0.35, lr * 0.1, c, c, lr);
  lg.addColorStop(0, '#fff7ef');
  lg.addColorStop(0.45, cfg.labelColor);
  lg.addColorStop(1, shade(cfg.labelColor, -0.28));
  ctx.fillStyle = lg;
  ctx.beginPath();
  ctx.arc(c, c, lr, 0, Math.PI * 2);
  ctx.fill();

  // label rings
  ctx.strokeStyle = cfg.brassAccent;
  ctx.lineWidth = 3;
  ctx.beginPath();
  ctx.arc(c, c, lr - 8, 0, Math.PI * 2);
  ctx.stroke();
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.arc(c, c, lr - 22, 0, Math.PI * 2);
  ctx.stroke();

  // rising-sun arcs (anime poster motif)
  ctx.save();
  ctx.beginPath();
  ctx.arc(c, c, lr - 26, 0, Math.PI * 2);
  ctx.clip();
  const sunY = c - lr * 0.18;
  const sg = ctx.createLinearGradient(0, sunY - lr * 0.5, 0, sunY + lr * 0.5);
  sg.addColorStop(0, 'rgba(255,236,190,0.95)');
  sg.addColorStop(1, 'rgba(255,170,120,0.85)');
  ctx.fillStyle = sg;
  ctx.beginPath();
  ctx.arc(c, sunY, lr * 0.42, Math.PI, 0);
  ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,0.55)';
  ctx.lineWidth = 2;
  for (let i = 1; i <= 4; i++) {
    const y = sunY + i * 9;
    ctx.beginPath();
    ctx.moveTo(c - lr, y);
    ctx.lineTo(c + lr, y);
    ctx.stroke();
  }
  ctx.restore();

  // typography
  ctx.textAlign = 'center';
  ctx.fillStyle = '#fff8ec';
  ctx.font = '600 30px Georgia, "Times New Roman", serif';
  const title = (cfg.title || 'A Voice Note').trim();
  ctx.fillText(clip(title, 22).toUpperCase(), c, c + lr * 0.55);
  ctx.font = '400 21px Georgia, serif';
  ctx.fillStyle = 'rgba(255,248,236,0.9)';
  if (cfg.recipientName) ctx.fillText(`for ${clip(cfg.recipientName, 20)}`, c, c + lr * 0.75);
  if (cfg.senderName) ctx.fillText(`from ${clip(cfg.senderName, 20)}`, c, c + lr * 0.94);
  ctx.font = '500 17px ui-monospace, monospace';
  ctx.fillStyle = cfg.brassAccent;
  ctx.fillText('33⅓ RPM · STEREO', c, c - lr * 0.66);

  // spindle hole
  ctx.fillStyle = '#0b0908';
  ctx.beginPath();
  ctx.arc(c, c, c * 0.033, 0, Math.PI * 2);
  ctx.fill();

  const tex = new THREE.CanvasTexture(canvas);
  tex.anisotropy = 8;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache[key] = tex;
  return tex;
}

/** Sleeve artwork for the record jacket lying on the table. */
function sleeveTexture(cfg: { labelColor: string; brassAccent: string; title: string; recipientName?: string }): THREE.Texture | null {
  if (!canRender()) return null;
  const key = `sleeve:${cfg.title}|${cfg.recipientName}|${cfg.labelColor}`;
  if (texCache[key]) return texCache[key];
  const { canvas, ctx } = ctx2d(512, 512);
  const g = ctx.createLinearGradient(0, 0, 512, 512);
  g.addColorStop(0, '#ffe9d6');
  g.addColorStop(0.55, '#f6cfae');
  g.addColorStop(1, '#e6a98a');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 512, 512);

  // big sun
  const sun = ctx.createLinearGradient(0, 90, 0, 300);
  sun.addColorStop(0, cfg.brassAccent);
  sun.addColorStop(1, cfg.labelColor);
  ctx.fillStyle = sun;
  ctx.beginPath();
  ctx.arc(256, 250, 120, Math.PI, 0);
  ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.35)';
  for (let i = 0; i < 5; i++) {
    ctx.fillRect(136, 250 + i * 12, 240, 4);
  }
  // hills
  ctx.fillStyle = 'rgba(80,45,30,0.35)';
  ctx.beginPath();
  ctx.moveTo(0, 300);
  ctx.quadraticCurveTo(140, 250, 260, 300);
  ctx.quadraticCurveTo(390, 350, 512, 296);
  ctx.lineTo(512, 512);
  ctx.lineTo(0, 512);
  ctx.fill();

  ctx.fillStyle = '#3a2418';
  ctx.textAlign = 'left';
  ctx.font = '700 34px Georgia, serif';
  ctx.fillText('VYNYL', 40, 66);
  ctx.font = '400 19px ui-monospace, monospace';
  ctx.fillStyle = 'rgba(58,36,24,0.75)';
  ctx.fillText('VOICE MEMORY · SIDE A', 40, 92);

  ctx.textAlign = 'center';
  ctx.font = '600 27px Georgia, serif';
  ctx.fillStyle = '#3a2418';
  ctx.fillText(clip(cfg.title || 'A Voice Note', 26), 256, 452);
  if (cfg.recipientName) {
    ctx.font = 'italic 400 20px Georgia, serif';
    ctx.fillStyle = 'rgba(58,36,24,0.8)';
    ctx.fillText(`for ${clip(cfg.recipientName, 24)}`, 256, 480);
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache[key] = tex;
  return tex;
}

/** Soft ellipse used as a fake (free) contact shadow. */
function shadowTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.shadow) return texCache.shadow;
  const { canvas, ctx } = ctx2d(256, 128);
  const g = ctx.createRadialGradient(128, 64, 4, 128, 64, 120);
  g.addColorStop(0, 'rgba(0,0,0,0.55)');
  g.addColorStop(0.55, 'rgba(0,0,0,0.24)');
  g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 256, 128);
  const tex = new THREE.CanvasTexture(canvas);
  texCache.shadow = tex;
  return tex;
}

/** Radial glow sprite for LEDs, needle light and music notes. */
function glowTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.glow) return texCache.glow;
  const { canvas, ctx } = ctx2d(128, 128);
  const g = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, 'rgba(255,255,255,1)');
  g.addColorStop(0.25, 'rgba(255,225,160,0.75)');
  g.addColorStop(1, 'rgba(255,190,90,0)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 128, 128);
  const tex = new THREE.CanvasTexture(canvas);
  texCache.glow = tex;
  return tex;
}

/** Tiny procedural equirect "studio" so metals get reflections — no HDR fetch. */
function envTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.env) return texCache.env;
  const { canvas, ctx } = ctx2d(256, 128);
  const g = ctx.createLinearGradient(0, 0, 0, 128);
  g.addColorStop(0, '#f6e7d5');
  g.addColorStop(0.42, '#c8a68d');
  g.addColorStop(0.55, '#4d3b34');
  g.addColorStop(1, '#100d0b');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 256, 128);
  // two soft key-light strips -> believable specular highlights on brass
  ctx.fillStyle = 'rgba(255,246,225,0.95)';
  ctx.fillRect(24, 12, 66, 22);
  ctx.fillStyle = 'rgba(255,214,150,0.7)';
  ctx.fillRect(168, 18, 46, 16);
  const tex = new THREE.CanvasTexture(canvas);
  tex.mapping = THREE.EquirectangularReflectionMapping;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.env = tex;
  return tex;
}

/** Table-top wood: same grain, tiled wider than the plinth's. */
function woodFloorTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.woodFloor) return texCache.woodFloor;
  const base = woodTexture();
  if (!base) return null;
  const tex = base.clone();
  tex.repeat.set(5, 3.4);
  tex.needsUpdate = true;
  texCache.woodFloor = tex;
  return tex;
}

function clip(s: string, n: number) {
  const t = s.trim();
  return t.length > n ? `${t.slice(0, n - 1)}…` : t;
}

function shade(hex: string, amt: number) {
  const h = hex.replace('#', '');
  const num = parseInt(h.length === 3 ? h.split('').map((x) => x + x).join('') : h, 16);
  const adj = (v: number) => Math.max(0, Math.min(255, Math.round(v + 255 * amt)));
  const r = adj((num >> 16) & 255);
  const g = adj((num >> 8) & 255);
  const b = adj(num & 255);
  return `rgb(${r},${g},${b})`;
}

/* ------------------------------------------------------------------ */
/* Scene helpers                                                       */
/* ------------------------------------------------------------------ */

/** Installs a lightweight procedural environment map for metal reflections. */
function ProceduralEnv() {
  const { scene } = useThree();
  const tex = useMemo(() => envTexture(), []);
  useEffect(() => {
    if (!tex) return;
    scene.environment = tex;
    return () => {
      scene.environment = null;
    };
  }, [scene, tex]);
  return null;
}

const BAND = 3.4907; // 33⅓ RPM in rad/s

/** Eased 0→1 spin-up / spin-down so the platter has weight. */
function useSpinRamp(target: number) {
  const speed = useRef(0);
  useFrame((_, delta) => {
    const d = Math.min(delta, 0.05);
    const k = 1 - Math.exp(-d * (target > 0 ? 2.2 : 1.4));
    speed.current += (target - speed.current) * k;
    if (Math.abs(speed.current) < 0.0015) speed.current = 0;
  });
  return speed;
}

function PlatterAndRecord({
  isPlaying,
  isNeedleDropping,
  vinylStyle,
  title,
  recipientName,
  senderName,
}: {
  isPlaying: boolean;
  isNeedleDropping: boolean;
  vinylStyle: VinylStyleType;
  title: string;
  recipientName?: string;
  senderName?: string;
}) {
  const spin = useRef<THREE.Group>(null);
  const sheen = useRef<THREE.Mesh>(null);
  const style = VINYL_STYLES.find((s) => s.id === vinylStyle) || VINYL_STYLES[0];
  const gradient = useMemo(() => toonGradient(), []);
  const label = useMemo(
    () =>
      recordTexture({
        baseColor: style.baseColor,
        labelColor: style.labelColor,
        grooveColor: style.grooveColor,
        brassAccent: style.brassAccent,
        title,
        recipientName,
        senderName,
      }),
    [style, title, recipientName, senderName]
  );

  const speed = useSpinRamp(isPlaying || isNeedleDropping ? BAND : 0);

  useFrame((state, delta) => {
    const d = Math.min(delta, 0.05);
    if (spin.current) spin.current.rotation.y += speed.current * d;
    // The reflected highlight stays put in world space (a spinning record's
    // glare doesn't spin with it) — a small breathing drift sells the gloss.
    if (sheen.current) {
      const t = state.clock.elapsedTime;
      (sheen.current.material as THREE.MeshBasicMaterial).opacity =
        (isPlaying ? 0.2 : 0.12) + Math.sin(t * 0.7) * 0.03;
    }
  });

  return (
    <group position={[-0.55, 0.5, 0]}>
      {/* Brass platter rim */}
      <mesh position={[0, -0.045, 0]} receiveShadow>
        <cylinderGeometry args={[1.32, 1.35, 0.1, 64]} />
        <meshStandardMaterial color="#c98a3c" metalness={0.92} roughness={0.28} />
      </mesh>
      {/* Rotating group: mat, record, label, spindle */}
      <group ref={spin}>
        <mesh position={[0, 0.005, 0]}>
          <cylinderGeometry args={[1.27, 1.27, 0.012, 64]} />
          <meshToonMaterial color="#2a1f1a" gradientMap={gradient ?? undefined} />
        </mesh>
        {/* Wax body */}
        <mesh position={[0, 0.028, 0]} castShadow>
          <cylinderGeometry args={[1.22, 1.22, 0.028, 96]} />
          <meshPhysicalMaterial
            color={style.baseColor}
            roughness={0.22}
            metalness={0.15}
            clearcoat={0.85}
            clearcoatRoughness={0.08}
            reflectivity={0.6}
          />
        </mesh>
        {/* Grooved face + pastel label */}
        <mesh position={[0, 0.043, 0]} rotation={[-Math.PI / 2, 0, 0]}>
          <circleGeometry args={[1.217, 96]} />
          {label ? (
            <meshBasicMaterial map={label} transparent />
          ) : (
            <meshBasicMaterial color={style.baseColor} />
          )}
        </mesh>
        {/* 45 rpm adapter */}
        <mesh position={[0, 0.075, 0]}>
          <cylinderGeometry args={[0.075, 0.08, 0.05, 24]} />
          <meshStandardMaterial color="#f4f0e8" metalness={0.55} roughness={0.35} />
        </mesh>
      </group>
      {/* Static gloss sweep over the grooves */}
      <mesh ref={sheen} position={[0, 0.0465, 0]} rotation={[-Math.PI / 2, 0, 0.6]}>
        <circleGeometry args={[1.215, 48, 0, Math.PI * 2]} />
        <meshBasicMaterial
          color="#fff3d6"
          transparent
          opacity={0.14}
          blending={THREE.AdditiveBlending}
          depthWrite={false}
        />
      </mesh>
      {/* Spindle pin */}
      <mesh position={[0, 0.12, 0]}>
        <cylinderGeometry args={[0.026, 0.026, 0.16, 16]} />
        <meshStandardMaterial color="#efe7d8" metalness={1} roughness={0.12} />
      </mesh>
      {/* Drive belt hint */}
      <mesh position={[0, -0.09, 0]} rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[1.15, 0.012, 6, 64]} />
        <meshToonMaterial color="#1a1512" gradientMap={gradient ?? undefined} />
      </mesh>
    </group>
  );
}

function Tonearm({ isPlaying, isNeedleDropping }: { isPlaying: boolean; isNeedleDropping: boolean }) {
  const yaw = useRef<THREE.Group>(null);
  const pitch = useRef<THREE.Group>(null);
  const glow = useRef<THREE.Sprite>(null);
  const progress = useRef(0);
  const gradient = useMemo(() => toonGradient(), []);
  const glowTex = useMemo(() => glowTexture(), []);

  useFrame((state, delta) => {
    const d = Math.min(delta, 0.05);
    const armDown = isPlaying || isNeedleDropping;
    if (armDown) progress.current = Math.min(1, progress.current + d * 0.014);
    else progress.current = Math.max(0, progress.current - d * 0.6);

    const t = progress.current;
    // Yaw solves the triangle pivot→spindle→stylus: 2.49 rad puts the needle on
    // the lead-in groove, 2.23 has it riding in toward the label, 3.31 parks it
    // on the rest with the counterweight swung back.
    const targetYaw = armDown ? 2.49 - t * 0.26 : 3.31;
    const targetPitch = isPlaying ? -0.02 : isNeedleDropping ? 0.03 : 0.3;

    if (yaw.current) {
      yaw.current.rotation.y = THREE.MathUtils.lerp(yaw.current.rotation.y, targetYaw, 1 - Math.exp(-d * 4));
    }
    if (pitch.current) {
      pitch.current.rotation.x = THREE.MathUtils.lerp(pitch.current.rotation.x, targetPitch, 1 - Math.exp(-d * (isNeedleDropping ? 2.4 : 5)));
    }
    if (glow.current) {
      const pulse = 0.55 + Math.sin(state.clock.elapsedTime * 3.2) * 0.18;
      const mat = glow.current.material as THREE.SpriteMaterial;
      mat.opacity = THREE.MathUtils.lerp(mat.opacity, armDown ? pulse : 0, 1 - Math.exp(-d * 6));
      const s = armDown ? 0.42 : 0.001;
      glow.current.scale.setScalar(THREE.MathUtils.lerp(glow.current.scale.x, s, 1 - Math.exp(-d * 6)));
    }
  });

  return (
    <group position={[1.7, 0.58, -1.05]}>
      {/* Pivot base */}
      <mesh position={[0, 0, 0]} castShadow>
        <cylinderGeometry args={[0.2, 0.24, 0.14, 32]} />
        <meshStandardMaterial color="#c98a3c" metalness={0.9} roughness={0.26} />
      </mesh>
      <mesh position={[0, 0.1, 0]}>
        <cylinderGeometry args={[0.12, 0.12, 0.1, 24]} />
        <meshToonMaterial color="#241d18" gradientMap={gradient ?? undefined} />
      </mesh>
      {/* Arm rest — cradle under the stylus when the arm is parked */}
      <mesh position={[0.4, 0.05, 2.32]}>
        <cylinderGeometry args={[0.022, 0.03, 0.16, 12]} />
        <meshStandardMaterial color="#8a5a2b" metalness={0.8} roughness={0.3} />
      </mesh>
      <mesh position={[0.4, 0.12, 2.32]} rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[0.045, 0.014, 6, 16, Math.PI]} />
        <meshStandardMaterial color="#c98a3c" metalness={0.92} roughness={0.22} />
      </mesh>

      <group ref={yaw} rotation={[0, 3.31, 0]}>
        {/* Counterweight */}
        <mesh position={[0, 0.14, 0.45]} rotation={[Math.PI / 2, 0, 0]}>
          <cylinderGeometry args={[0.12, 0.12, 0.2, 24]} />
          <meshToonMaterial color="#2c241f" gradientMap={gradient ?? undefined} />
        </mesh>
        <mesh position={[0, 0.14, 0.33]} rotation={[Math.PI / 2, 0, 0]}>
          <cylinderGeometry args={[0.126, 0.126, 0.02, 24]} />
          <meshStandardMaterial color="#c98a3c" metalness={0.95} roughness={0.2} />
        </mesh>

        <group ref={pitch} position={[0, 0.14, 0]} rotation={[0.3, 0, 0]}>
          {/* Arm tube — reach solved against the 1.22-unit record radius */}
          <mesh position={[0, 0, -1.12]} rotation={[Math.PI / 2, 0, 0]} castShadow>
            <cylinderGeometry args={[0.021, 0.021, 2.25, 16]} />
            <meshStandardMaterial color="#e8e3d8" metalness={1} roughness={0.18} />
          </mesh>
          {/* Headshell + cartridge */}
          <group position={[0, -0.01, -2.2]} rotation={[0, -0.24, 0]}>
            <mesh position={[0, 0, -0.055]}>
              <boxGeometry args={[0.08, 0.05, 0.19]} />
              <meshToonMaterial color="#241d18" gradientMap={gradient ?? undefined} />
            </mesh>
            <mesh position={[0, -0.035, -0.05]}>
              <boxGeometry args={[0.055, 0.035, 0.1]} />
              <meshStandardMaterial color="#f0c877" metalness={0.95} roughness={0.12} />
            </mesh>
            <mesh position={[0, -0.062, -0.08]} rotation={[0.25, 0, 0]}>
              <coneGeometry args={[0.01, 0.05, 12]} />
              <meshStandardMaterial color="#fffdf7" metalness={0.9} roughness={0.05} />
            </mesh>
            {/* Warm light where the stylus meets the wax */}
            <sprite ref={glow} position={[0, -0.075, -0.085]} scale={0.001}>
              <spriteMaterial
                map={glowTex ?? undefined}
                color="#ffd89a"
                transparent
                opacity={0}
                depthWrite={false}
                blending={THREE.AdditiveBlending}
              />
            </sprite>
            {isPlaying && (
              <pointLight position={[0, -0.09, -0.08]} color="#ffcf8f" intensity={0.5} distance={0.9} decay={2} />
            )}
          </group>
        </group>
      </group>
    </group>
  );
}

function Chassis({ isRecording, isPlaying }: { isRecording: boolean; isPlaying: boolean }) {
  const gradient = useMemo(() => toonGradient(), []);
  const wood = useMemo(() => woodTexture(), []);
  const brushed = useMemo(() => brushedTexture(), []);
  const led = useRef<THREE.MeshStandardMaterial>(null);
  const btn = useRef<THREE.MeshStandardMaterial>(null);

  useFrame((state) => {
    const t = state.clock.elapsedTime;
    if (led.current) {
      const color = isRecording ? '#ff5a5a' : isPlaying ? '#7ef0b8' : '#c9b48f';
      led.current.color.set(color);
      led.current.emissive.set(color);
      led.current.emissiveIntensity = isRecording
        ? 1.4 + Math.sin(t * 7) * 0.7
        : isPlaying
        ? 1.1 + Math.sin(t * 2.4) * 0.28
        : 0.35;
    }
    if (btn.current) {
      btn.current.emissiveIntensity = isPlaying ? 0.75 + Math.sin(t * 2.4) * 0.18 : 0.16;
    }
  });

  const woodMat = (
    <meshToonMaterial
      color="#f3d9bf"
      map={wood ?? undefined}
      gradientMap={gradient ?? undefined}
      transparent={false}
    />
  );

  return (
    <group>
      {/* Plinth with rounded anime edges */}
      <RoundedBox args={[4.6, 0.52, 3.3]} radius={0.075} smoothness={3} position={[0, 0.26, 0]} castShadow receiveShadow>
        {woodMat}
      </RoundedBox>
      {/* Cel outline — one extra draw call, big anime payoff */}
      <RoundedBox args={[4.68, 0.59, 3.38]} radius={0.075} smoothness={3} position={[0, 0.26, 0]}>
        <meshBasicMaterial color="#1b120d" side={THREE.BackSide} />
      </RoundedBox>
      {/* Brushed top plate */}
      <RoundedBox args={[4.42, 0.045, 3.14]} radius={0.03} smoothness={2} position={[0, 0.545, 0]} receiveShadow>
        <meshStandardMaterial color="#39312b" map={brushed ?? undefined} metalness={0.6} roughness={0.5} />
      </RoundedBox>
      {/* Feet */}
      {([[-2.05, -0.05, -1.4], [2.05, -0.05, -1.4], [-2.05, -0.05, 1.4], [2.05, -0.05, 1.4]] as const).map((p, i) => (
        <mesh key={i} position={p as unknown as [number, number, number]}>
          <cylinderGeometry args={[0.17, 0.2, 0.12, 20]} />
          <meshToonMaterial color="#211a16" gradientMap={gradient ?? undefined} />
        </mesh>
      ))}

      {/* Start / stop button */}
      <group position={[1.1, 0.575, 1.32]}>
        <mesh position={[0, 0.005, 0]}>
          <cylinderGeometry args={[0.19, 0.21, 0.03, 28]} />
          <meshStandardMaterial color="#b8813a" metalness={0.9} roughness={0.3} />
        </mesh>
        <mesh position={[0, 0.035, 0]}>
          <cylinderGeometry args={[0.15, 0.16, 0.04, 28]} />
          <meshStandardMaterial
            ref={btn}
            color="#f7c988"
            emissive="#ffa64d"
            emissiveIntensity={0.2}
            metalness={0.2}
            roughness={0.4}
          />
        </mesh>
      </group>

      {/* Pitch slider */}
      <group position={[2.15, 0.575, -0.15]} rotation={[0, -0.16, 0]}>
        <mesh position={[0, 0.002, 0]}>
          <boxGeometry args={[0.1, 0.01, 0.9]} />
          <meshToonMaterial color="#191412" gradientMap={gradient ?? undefined} />
        </mesh>
        <mesh position={[0.005, 0.03, 0.12]}>
          <boxGeometry args={[0.16, 0.05, 0.1]} />
          <meshStandardMaterial color="#e6ddcd" metalness={0.75} roughness={0.3} />
        </mesh>
        {[-0.34, -0.17, 0, 0.17, 0.34].map((z, i) => (
          <mesh key={i} position={[-0.06, 0.008, z]}>
            <boxGeometry args={[0.03, 0.006, 0.012]} />
            <meshStandardMaterial color="#cbb694" metalness={0.7} roughness={0.4} />
          </mesh>
        ))}
      </group>

      {/* Power LED + brand plate */}
      <mesh position={[2.12, 0.575, -0.8]}>
        <sphereGeometry args={[0.038, 12, 12]} />
        <meshStandardMaterial ref={led} color="#c9b48f" emissive="#c9b48f" emissiveIntensity={0.3} roughness={0.3} />
      </mesh>
      <mesh position={[-0.55, 0.3, 1.655]}>
        <boxGeometry args={[1.15, 0.14, 0.02]} />
        <meshStandardMaterial color="#c98a3c" metalness={0.95} roughness={0.24} />
      </mesh>

      {/* Open dust cover on its rear hinge */}
      <group position={[0, 0.55, -1.66]} rotation={[-1.94, 0, 0]}>
        <mesh position={[0, 0.78, 0]}>
          <boxGeometry args={[4.42, 0.03, 0.05]} />
          <meshStandardMaterial color="#c98a3c" metalness={0.9} roughness={0.3} />
        </mesh>
        <mesh position={[-2.2, 0.39, 0]}>
          <boxGeometry args={[0.04, 0.8, 0.05]} />
          <meshStandardMaterial color="#c98a3c" metalness={0.9} roughness={0.3} />
        </mesh>
        <mesh position={[2.2, 0.39, 0]}>
          <boxGeometry args={[0.04, 0.8, 0.05]} />
          <meshStandardMaterial color="#c98a3c" metalness={0.9} roughness={0.3} />
        </mesh>
        <mesh position={[0, 0.39, 0]}>
          <planeGeometry args={[4.36, 0.76]} />
          <meshPhysicalMaterial
            color="#dff0ff"
            transparent
            opacity={0.16}
            roughness={0.06}
            metalness={0}
            side={THREE.DoubleSide}
          />
        </mesh>
        {/* anime glass streaks */}
        <mesh position={[-0.5, 0.5, 0.004]} rotation={[0, 0, 0.35]}>
          <planeGeometry args={[0.5, 0.05]} />
          <meshBasicMaterial color="#ffffff" transparent opacity={0.5} blending={THREE.AdditiveBlending} depthWrite={false} />
        </mesh>
        <mesh position={[0.4, 0.3, 0.004]} rotation={[0, 0, 0.35]}>
          <planeGeometry args={[0.9, 0.03]} />
          <meshBasicMaterial color="#fff6e0" transparent opacity={0.35} blending={THREE.AdditiveBlending} depthWrite={false} />
        </mesh>
      </group>
    </group>
  );
}

function TableAndProps({
  detail,
  title,
  recipientName,
}: {
  detail: 'high' | 'low';
  title: string;
  recipientName?: string;
}) {
  const gradient = useMemo(() => toonGradient(), []);
  const wood = useMemo(() => woodFloorTexture(), []);
  const shadow = useMemo(() => shadowTexture(), []);
  const sleeve = useMemo(
    () => sleeveTexture({ labelColor: '#b45309', brassAccent: '#f59e0b', title, recipientName }),
    [title, recipientName]
  );

  return (
    <group>
      {/* Table surface the player rests on (top face flush with y=0) */}
      <RoundedBox args={[9.6, 0.34, 6.4]} radius={0.06} smoothness={2} position={[0, -0.17, 0.2]} receiveShadow>
        <meshToonMaterial color="#efd0b3" map={wood ?? undefined} gradientMap={gradient ?? undefined} />
      </RoundedBox>
      {/* Free "contact" shadow instead of a shadow pass under the whole table */}
      <mesh position={[0, 0.006, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[6.9, 4.4]} />
        <meshBasicMaterial map={shadow ?? undefined} transparent opacity={0.7} depthWrite={false} />
      </mesh>

      {/* Record sleeve lying next to the player */}
      <group position={[3.05, 0.035, 1.15]} rotation={[-Math.PI / 2, 0, -0.34]}>
        <mesh position={[0, 0, -0.02]}>
          <boxGeometry args={[2.05, 2.05, 0.04]} />
          <meshToonMaterial color="#e7b48f" gradientMap={gradient ?? undefined} />
        </mesh>
        <mesh position={[0, 0, 0.005]}>
          <planeGeometry args={[1.98, 1.98]} />
          {sleeve ? <meshBasicMaterial map={sleeve} /> : <meshBasicMaterial color="#f0c39c" />}
        </mesh>
      </group>
      {/* A second sleeve peeking under it */}
      {detail === 'high' && (
        <group position={[3.35, 0.005, 1.95]} rotation={[-Math.PI / 2, 0, 0.22]}>
          <mesh>
            <boxGeometry args={[2.05, 2.05, 0.03]} />
            <meshToonMaterial color="#c98f6c" gradientMap={gradient ?? undefined} />
          </mesh>
        </group>
      )}

      {/* Small stack of spare records for scale */}
      {detail === 'high' && (
        <group position={[-3.35, -0.005, 1.6]} rotation={[0, 0.4, 0]}>
          {[0, 0.05, 0.1].map((y, i) => (
            <mesh key={i} position={[0, y + 0.03, 0]} rotation={[0, 0, i * 0.25]}>
              <cylinderGeometry args={[1.15, 1.15, 0.022, 48]} />
              <meshToonMaterial color={i % 2 ? '#3b2b23' : '#4a3126'} gradientMap={gradient ?? undefined} />
            </mesh>
          ))}
        </group>
      )}
    </group>
  );
}

/** Floating music notes while playing — a light, purely sprite-based touch. */
function MusicNotes({ active }: { active: boolean }) {
  const group = useRef<THREE.Group>(null);
  const tex = useMemo(() => glowTexture(), []);
  useFrame((state) => {
    if (!group.current) return;
    const t = state.clock.elapsedTime;
    group.current.children.forEach((child, i) => {
      const phase = (t * 0.35 + i * 0.37) % 1;
      child.position.y = 0.9 + phase * 1.7;
      child.position.x = Math.sin((t + i) * 1.1) * 0.22 + (i - 1) * 0.34;
      const mat = (child as THREE.Sprite).material as THREE.SpriteMaterial;
      mat.opacity = active ? Math.sin(phase * Math.PI) * 0.7 : 0;
      const s = 0.16 + Math.sin(phase * Math.PI) * 0.08;
      child.scale.setScalar(s);
    });
  });
  return (
    <group ref={group} position={[-0.6, 0, 0.2]}>
      {[0, 1, 2, 3].map((i) => (
        <sprite key={i} scale={0.001}>
          <spriteMaterial
            map={tex ?? undefined}
            color={i % 2 ? '#ffd9a0' : '#ffb0c8'}
            transparent
            opacity={0}
            depthWrite={false}
            blending={THREE.AdditiveBlending}
          />
        </sprite>
      ))}
    </group>
  );
}

/* ------------------------------------------------------------------ */
/* Frameloop throttle: don't burn GPU while nobody is looking         */
/* ------------------------------------------------------------------ */

function useIdleFrameloop(active: boolean) {
  const [parked, setParked] = useState(false);
  const activeRef = useRef(active);
  activeRef.current = active;

  useEffect(() => {
    if (typeof window === 'undefined') return;
    let idle: ReturnType<typeof setTimeout> | null = null;

    const schedulePark = () => {
      if (idle) clearTimeout(idle);
      idle = setTimeout(() => {
        // Only park when nothing is animating (record spinning, REC light…).
        if (!activeRef.current && !document.hidden) setParked(true);
      }, 2600);
    };

    const wake = () => {
      setParked(false);
      schedulePark();
    };

    schedulePark();
    const events: (keyof WindowEventMap)[] = ['pointermove', 'pointerdown', 'wheel', 'keydown', 'scroll', 'resize'];
    events.forEach((e) => window.addEventListener(e, wake, { passive: true }));

    const onVisibility = () => setParked(document.hidden);
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      if (idle) clearTimeout(idle);
      events.forEach((e) => window.removeEventListener(e, wake));
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  // Anything in motion must render every frame; a still scene can park.
  if (active) return 'always' as const;
  return parked ? ('never' as const) : ('always' as const);
}

/* ------------------------------------------------------------------ */
/* Public component                                                    */
/* ------------------------------------------------------------------ */

export function AnimeTurntablePlayer({
  isPlaying,
  isNeedleDropping = false,
  vinylStyle = 'classic_red',
  title = 'A Voice Note',
  recipientName,
  senderName,
  isRecording = false,
  detail = 'high',
  className = '',
}: AnimeTurntablePlayerProps) {
  const frameloop = useIdleFrameloop(isPlaying || isNeedleDropping || isRecording);
  const spinning = isPlaying || isNeedleDropping;

  return (
    <div
      className={`relative w-full h-full overflow-hidden ${className}`}
      style={{
        // Layered radial washes stand in for the old HDR + fog: pure CSS, no GPU cost.
        background:
          'radial-gradient(120% 90% at 50% 6%, #fff0d8 0%, #ffd9b8 18%, #e7a98f 42%, #7c4f57 72%, #2b1a22 100%)',
      }}
    >
      {/* Sky "poster" arcs — static CSS, replaces a 3D wall */}
      <div
        className="pointer-events-none absolute inset-0 opacity-45"
        style={{
          background:
            'repeating-linear-gradient(0deg, rgba(255,255,255,0.05) 0px, rgba(255,255,255,0.05) 1px, transparent 1px, transparent 46px)',
        }}
      />
      <div
        className="pointer-events-none absolute inset-0"
        style={{ boxShadow: 'inset 0 -60px 120px -40px rgba(28,12,20,0.85), inset 0 40px 90px -50px rgba(255,240,210,0.6)' }}
      />

      <Canvas
        frameloop={frameloop}
        dpr={[1, 1.6]}
        shadows={{ type: THREE.PCFSoftShadowMap }}
        gl={{ antialias: true, alpha: true, powerPreference: 'high-performance' }}
        camera={{ position: [0.2, 3.0, 5.1], fov: 38 }}
      >
        <ProceduralEnv />

        <ambientLight intensity={0.7} color="#ffe9cf" />
        <hemisphereLight args={['#fff3e0', '#4a2f2a', 0.55]} />
        <directionalLight
          position={[4.2, 6.4, 3.4]}
          intensity={1.6}
          color="#fff4e2"
          castShadow
          shadow-mapSize-width={1024}
          shadow-mapSize-height={1024}
          shadow-camera-left={-4.5}
          shadow-camera-right={4.5}
          shadow-camera-top={4.5}
          shadow-camera-bottom={-4.5}
          shadow-camera-far={18}
          shadow-bias={-0.0004}
        />
        {/* Cool rim light from behind-left: the classic anime double-edge */}
        <directionalLight position={[-5, 2.6, -4]} intensity={0.75} color="#9fd0ff" />
        <pointLight position={[0, 1.4, 2.8]} intensity={0.55} distance={7} decay={2} color="#ffb877" />

        <group position={[0, -0.35, 0]}>
          <TableAndProps detail={detail} title={title} recipientName={recipientName} />
          <Chassis isRecording={isRecording} isPlaying={spinning} />
          <PlatterAndRecord
            isPlaying={isPlaying}
            isNeedleDropping={isNeedleDropping}
            vinylStyle={vinylStyle}
            title={title}
            recipientName={recipientName}
            senderName={senderName}
          />
          <Tonearm isPlaying={isPlaying} isNeedleDropping={isNeedleDropping} />
          <MusicNotes active={isPlaying} />
          {detail === 'high' && (
            <Sparkles
              count={38}
              scale={[7.4, 2.6, 4.6]}
              position={[0, 1.5, 0]}
              size={2.4}
              speed={0.22}
              opacity={0.5}
              color="#ffe4b0"
              noise={0.6}
            />
          )}
        </group>

        <OrbitControls
          enablePan={false}
          enableDamping
          dampingFactor={0.08}
          minPolarAngle={Math.PI / 9}
          maxPolarAngle={Math.PI / 2.15}
          minDistance={3.6}
          maxDistance={8.4}
          target={[0, 0.45, 0]}
        />
      </Canvas>

      {/* Minimal status chrome (no blur, no grain) */}
      <div className="pointer-events-none absolute left-3 top-3 flex items-center gap-2">
        <span className="rounded-full border border-white/25 bg-black/35 px-2.5 py-1 font-mono text-[10px] uppercase tracking-wider text-amber-100">
          {spinning ? (isNeedleDropping ? 'needle dropping' : '33⅓ rpm · playing') : 'idle · drag to orbit'}
        </span>
        {isRecording && (
          <span className="flex items-center gap-1.5 rounded-full border border-red-300/40 bg-red-600/85 px-2.5 py-1 font-mono text-[10px] uppercase tracking-wider text-white">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-white" /> rec
          </span>
        )}
      </div>
    </div>
  );
}

export default AnimeTurntablePlayer;
