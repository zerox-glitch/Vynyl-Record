'use client';

/**
 * TurntablePlayer — a clean, realistic 3D vinyl record player.
 * ----------------------------------------------------------------------------
 * Replaces the old "anime cel-shaded" look with physically-based materials:
 *
 *  - A glossy PVC record: procedural groove texture + bump relief, clearcoat
 *    lacquer, a static (non-spinning) light streak so the glare reads like a
 *    real window reflection while the grooves spin underneath.
 *  - A brushed-aluminium platter, rubber mat, spindle, walnut plinth and a
 *    J-shaped tonearm (TubeGeometry along a curve) with counterweight,
 *    headshell, cartridge and stylus that cues down onto the wax.
 *  - Realistic behaviour: 33⅓ RPM spin ramp, tonearm swings out of its rest,
 *    the stylus lowers and rides the groove (with a faint tracking shimmer),
 *    then returns home on stop. No sleeves, spare discs or floating notes —
 *    the scene stays clean.
 *
 * Performance rules (inherited): no HDR downloads, one 1024² shadow map,
 * module-level texture caches, and the render loop parks itself when the tab
 * is hidden or the scene is idle (see useIdleFrameloop).
 */

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { OrbitControls, RoundedBox } from '@react-three/drei';
import * as THREE from 'three';
import { VinylStyleType } from '@/types';
import { VINYL_STYLES } from '@/lib/constants';

export interface TurntablePlayerProps {
  isPlaying: boolean;
  /** Needle is mid-fall toward the wax (drives the cueing animation). */
  isNeedleDropping?: boolean;
  vinylStyle?: VinylStyleType;
  title?: string;
  recipientName?: string;
  senderName?: string;
  /** Red "REC" glow on the power LED. */
  isRecording?: boolean;
  /** `low` trims geometry resolution for small preview cards. */
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

/** Neutral "studio" equirect so chrome/brass pick up believable reflections. */
function studioEnvTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.env) return texCache.env;
  const { canvas, ctx } = ctx2d(256, 128);
  const g = ctx.createLinearGradient(0, 0, 0, 128);
  g.addColorStop(0, '#f7f3ec');
  g.addColorStop(0.3, '#d8d2c8');
  g.addColorStop(0.62, '#5c534c');
  g.addColorStop(1, '#12100e');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 256, 128);
  // Soft key strips: a cool window band and a warm fill band.
  const strips: Array<[number, number, number, number, string]> = [
    [30, 8, 60, 26, 'rgba(255,255,255,0.95)'],
    [150, 14, 48, 20, 'rgba(255,225,180,0.9)'],
    [96, 96, 90, 10, 'rgba(255,190,120,0.35)'],
  ];
  for (const [x, y, w, h, c] of strips) {
    ctx.fillStyle = c;
    ctx.fillRect(x, y, w, h);
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.mapping = THREE.EquirectangularReflectionMapping;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.env = tex;
  return tex;
}

/**
 * The record face: polished wax, fine pressed grooves and a printed centre
 * label carrying the dedication. Regenerated only per record configuration.
 */
function vinylFaceTexture(cfg: {
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

  const wax = shade(cfg.baseColor, -0.62); // vinyl wax is near-black
  const waxEdge = shade(cfg.baseColor, -0.72);

  // Wax body with a subtle radial tint (thicker acetate toward the rim).
  const body = ctx.createRadialGradient(c, c - S * 0.02, S * 0.05, c, c, c);
  body.addColorStop(0, wax);
  body.addColorStop(0.82, wax);
  body.addColorStop(1, waxEdge);
  ctx.fillStyle = body;
  ctx.beginPath();
  ctx.arc(c, c, c, 0, Math.PI * 2);
  ctx.fill();

  // --- Pressed grooves: concentric micro-rings with slight radius jitter ---
  const grooveBase = shade(cfg.grooveColor, -0.4);
  ctx.save();
  ctx.globalCompositeOperation = 'source-atop';
  for (let r = c * 0.42; r < c * 0.975; r += 2.4) {
    const jitter = (Math.sin(r * 12.9898 + 78.233) * 43758.5453) % 1 - 0.5;
    ctx.strokeStyle = grooveBase;
    ctx.globalAlpha = 0.05 + Math.random() * 0.13;
    ctx.lineWidth = 0.6 + Math.random() * 0.4;
    ctx.beginPath();
    ctx.arc(c, c, r + jitter, 0, Math.PI * 2);
    ctx.stroke();
  }
  // Mastering "band" rings — slightly deeper cuts every so often.
  ctx.globalAlpha = 0.35;
  for (let r = c * 0.44; r < c * 0.96; r += c * 0.08) {
    ctx.strokeStyle = 'rgba(0,0,0,0.5)';
    ctx.lineWidth = 2.2;
    ctx.beginPath();
    ctx.arc(c, c, r, 0, Math.PI * 2);
    ctx.stroke();
  }
  ctx.restore();

  // Edge bevel highlight (the polished rim catches light).
  ctx.strokeStyle = 'rgba(255,255,255,0.14)';
  ctx.lineWidth = 5;
  ctx.beginPath();
  ctx.arc(c, c, c - 4, 0, Math.PI * 2);
  ctx.stroke();
  ctx.strokeStyle = 'rgba(0,0,0,0.4)';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.arc(c, c, c - 1.5, 0, Math.PI * 2);
  ctx.stroke();

  // --- Centre label: printed paper with ring detail ---
  const lr = S * 0.385;
  const lay = ctx.createRadialGradient(c - lr * 0.25, c - lr * 0.3, lr * 0.05, c, c, lr);
  lay.addColorStop(0, '#f2e9dc');
  lay.addColorStop(0.4, mix(cfg.labelColor, '#f2e9dc', 0.72));
  lay.addColorStop(1, shade(cfg.labelColor, -0.35));
  ctx.fillStyle = lay;
  ctx.beginPath();
  ctx.arc(c, c, lr, 0, Math.PI * 2);
  ctx.fill();

  // Paper speckle.
  ctx.save();
  ctx.globalCompositeOperation = 'overlay';
  for (let i = 0; i < 260; i++) {
    ctx.fillStyle = `rgba(${120 + Math.random() * 60},${100 + Math.random() * 60},${80 + Math.random() * 50},${0.03 + Math.random() * 0.07})`;
    const a = Math.random() * Math.PI * 2;
    const rr = Math.sqrt(Math.random()) * lr;
    ctx.fillRect(c + Math.cos(a) * rr, c + Math.sin(a) * rr, 1.4, 1.4);
  }
  ctx.restore();

  // Printed rings.
  ctx.strokeStyle = cfg.brassAccent;
  ctx.globalAlpha = 0.85;
  ctx.lineWidth = 2.6;
  ctx.beginPath();
  ctx.arc(c, c, lr - 10, 0, Math.PI * 2);
  ctx.stroke();
  ctx.strokeStyle = 'rgba(255,255,255,0.5)';
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  ctx.arc(c, c, lr - 16, 0, Math.PI * 2);
  ctx.stroke();
  ctx.strokeStyle = shade(cfg.labelColor, 0.25);
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.arc(c, c, lr - 28, 0, Math.PI * 2);
  ctx.stroke();
  ctx.globalAlpha = 1;

  // Label typography.
  ctx.textAlign = 'center';
  ctx.fillStyle = '#fdf6e9';
  ctx.font = '600 26px Georgia, "Times New Roman", serif';
  const title = clip(cfg.title || 'A Voice Note', 24);
  ctx.fillText(title.toUpperCase(), c, c - lr * 0.08);

  ctx.font = '400 15px ui-monospace, "SF Mono", monospace';
  ctx.fillStyle = 'rgba(253,246,233,0.78)';
  ctx.fillText('33⅓ RPM · STEREO', c, c - lr * 0.4);
  ctx.fillText('SIDE A', c, c + lr * 0.32);

  if (cfg.recipientName) {
    ctx.font = 'italic 400 20px Georgia, serif';
    ctx.fillStyle = 'rgba(253,246,233,0.92)';
    ctx.fillText(`for ${clip(cfg.recipientName, 20)}`, c, c + lr * 0.46);
  }
  if (cfg.senderName) {
    ctx.font = 'italic 400 17px Georgia, serif';
    ctx.fillStyle = 'rgba(253,246,233,0.78)';
    ctx.fillText(`from ${clip(cfg.senderName, 20)}`, c, c + lr * 0.6);
  }
  ctx.font = '600 13px ui-monospace, monospace';
  ctx.fillStyle = cfg.brassAccent;
  ctx.fillText('VINYL VOICE NOTES', c, c + lr * 0.78);

  // Spindle hole.
  ctx.fillStyle = '#060504';
  ctx.beginPath();
  ctx.arc(c, c, S * 0.026, 0, Math.PI * 2);
  ctx.fill();

  const tex = new THREE.CanvasTexture(canvas);
  tex.anisotropy = 8;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache[key] = tex;
  return tex;
}

/** Grayscale relief used as the bump map so grooves catch light microscopically. */
function vinylBumpTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.bump) return texCache.bump;
  const S = 512;
  const c = S / 2;
  const { canvas, ctx } = ctx2d(S, S);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, S, S);
  ctx.save();
  ctx.globalCompositeOperation = 'source-over';
  for (let r = c * 0.42; r < c * 0.96; r += 2.0) {
    ctx.strokeStyle = `rgba(120,120,120,${0.25 + Math.random() * 0.3})`;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(c, c, r + (Math.random() - 0.5) * 0.6, 0, Math.PI * 2);
    ctx.stroke();
  }
  for (let r = c * 0.44; r < c * 0.94; r += c * 0.1) {
    ctx.strokeStyle = 'rgba(90,90,90,0.5)';
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(c, c, r, 0, Math.PI * 2);
    ctx.stroke();
  }
  ctx.restore();
  const tex = new THREE.CanvasTexture(canvas);
  texCache.bump = tex;
  return tex;
}

/** Brushed aluminium for the platter and trim. */
function platterBrushedTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.platter) return texCache.platter;
  const { canvas, ctx } = ctx2d(512, 512);
  ctx.fillStyle = '#b9b4ac';
  ctx.fillRect(0, 0, 512, 512);
  for (let i = 0; i < 1100; i++) {
    const y = Math.random() * 512;
    const light = Math.random() > 0.5;
    ctx.strokeStyle = light ? 'rgba(255,255,255,0.10)' : 'rgba(60,55,50,0.14)';
    ctx.lineWidth = 0.5 + Math.random() * 0.8;
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(512, y + (Math.random() - 0.5) * 3);
    ctx.stroke();
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(2, 2);
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.platter = tex;
  return tex;
}

/** Dark walnut grain for the plinth and studio table. */
function walnutTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.walnut) return texCache.walnut;
  const { canvas, ctx } = ctx2d(512, 512);
  const g = ctx.createLinearGradient(0, 0, 0, 512);
  g.addColorStop(0, '#4a2e1c');
  g.addColorStop(0.5, '#3c2415');
  g.addColorStop(1, '#2b1a10');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 512, 512);
  for (let i = 0; i < 130; i++) {
    const y = (i / 130) * 512 + (Math.random() - 0.5) * 6;
    ctx.strokeStyle = `rgba(${24 + Math.random() * 18},${13 + Math.random() * 10},${8},${0.08 + Math.random() * 0.16})`;
    ctx.lineWidth = 0.8 + Math.random() * 2.4;
    ctx.beginPath();
    for (let x = 0; x <= 512; x += 14) {
      ctx.lineTo(x, y + Math.sin(x * 0.014 + i * 1.7) * 4 + (Math.random() - 0.5) * 2);
    }
    ctx.stroke();
  }
  for (let i = 0; i < 5; i++) {
    ctx.strokeStyle = 'rgba(255,214,170,0.05)';
    ctx.lineWidth = 6 + Math.random() * 10;
    ctx.beginPath();
    const y = Math.random() * 512;
    ctx.moveTo(0, y);
    ctx.lineTo(512, y + (Math.random() - 0.5) * 30);
    ctx.stroke();
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.walnut = tex;
  return tex;
}

/** Fine dark brushed metal for the chassis top plate. */
function darkBrushedTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.darkBrush) return texCache.darkBrush;
  const { canvas, ctx } = ctx2d(512, 512);
  ctx.fillStyle = '#26252a';
  ctx.fillRect(0, 0, 512, 512);
  for (let i = 0; i < 1100; i++) {
    const y = Math.random() * 512;
    ctx.strokeStyle = Math.random() > 0.5 ? 'rgba(255,255,255,0.045)' : 'rgba(0,0,0,0.18)';
    ctx.lineWidth = 0.4 + Math.random() * 0.7;
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(512, y + (Math.random() - 0.5) * 2);
    ctx.stroke();
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.darkBrush = tex;
  return tex;
}

/** Soft radial blob used as a cheap contact shadow under the unit. */
function shadowTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.shadow) return texCache.shadow;
  const { canvas, ctx } = ctx2d(256, 128);
  const g = ctx.createRadialGradient(128, 64, 6, 128, 64, 122);
  g.addColorStop(0, 'rgba(0,0,0,0.62)');
  g.addColorStop(0.55, 'rgba(0,0,0,0.28)');
  g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 256, 128);
  const tex = new THREE.CanvasTexture(canvas);
  texCache.shadow = tex;
  return tex;
}

/** Static window-reflection streak that glides over the spinning grooves. */
function sheenTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.sheen) return texCache.sheen;
  const { canvas, ctx } = ctx2d(256, 256);
  ctx.clearRect(0, 0, 256, 256);
  const c = 128;
  // Broad soft pool.
  const pool = ctx.createRadialGradient(c, c, 8, c, c, 126);
  pool.addColorStop(0, 'rgba(255,255,255,0.5)');
  pool.addColorStop(0.55, 'rgba(255,255,255,0.12)');
  pool.addColorStop(1, 'rgba(255,255,255,0)');
  ctx.fillStyle = pool;
  ctx.fillRect(0, 0, 256, 256);
  // Two diagonal window streaks (fresnel-style).
  for (const [cx, cy, rx, ry, rot, a] of [
    [86, 118, 62, 8, -0.4, 0.55],
    [176, 96, 82, 5, -0.4, 0.38],
  ] as const) {
    ctx.save();
    ctx.translate(cx as number, cy as number);
    ctx.rotate(rot as number);
    ctx.scale(1, (ry as number) / (rx as number));
    const streak = ctx.createRadialGradient(0, 0, 2, 0, 0, rx as number);
    streak.addColorStop(0, `rgba(255,250,235,${a as number})`);
    streak.addColorStop(1, 'rgba(255,250,235,0)');
    ctx.fillStyle = streak;
    ctx.beginPath();
    ctx.arc(0, 0, rx as number, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }
  const tex = new THREE.CanvasTexture(canvas);
  texCache.sheen = tex;
  return tex;
}

/** Radial glow dot for the stylus light and LEDs. */
function glowTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.glow) return texCache.glow;
  const { canvas, ctx } = ctx2d(128, 128);
  const g = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, 'rgba(255,255,255,1)');
  g.addColorStop(0.25, 'rgba(255,228,170,0.8)');
  g.addColorStop(1, 'rgba(255,190,110,0)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 128, 128);
  const tex = new THREE.CanvasTexture(canvas);
  texCache.glow = tex;
  return tex;
}

/** Engraved brand plate text. */
function brandPlateTexture(): THREE.Texture | null {
  if (!canRender()) return null;
  if (texCache.brand) return texCache.brand;
  const { canvas, ctx } = ctx2d(256, 64);
  ctx.clearRect(0, 0, 256, 64);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.font = '700 26px Georgia, "Times New Roman", serif';
  ctx.fillStyle = 'rgba(30,20,10,0.9)';
  ctx.fillText('VYNYL', 128, 24);
  ctx.font = '600 11px ui-monospace, monospace';
  ctx.fillStyle = 'rgba(30,20,10,0.55)';
  ctx.fillText('VOICE MEMORIES PRESSED IN WAX', 128, 46);
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.brand = tex;
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

function mix(hex: string, other: string, t: number) {
  const parse = (h: string) => {
    const c = h.replace('#', '');
    const n = parseInt(c.length === 3 ? c.split('').map((x) => x + x).join('') : c, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  };
  const [r1, g1, b1] = parse(hex);
  const [r2, g2, b2] = parse(other);
  const ch = (a: number, b: number) => Math.round(a + (b - a) * t);
  return `rgb(${ch(r1, r2)},${ch(g1, g2)},${ch(b1, b2)})`;
}

/* ------------------------------------------------------------------ */
/* Scene helpers                                                       */
/* ------------------------------------------------------------------ */

/** Installs the procedural studio environment map for metal reflections. */
function ProceduralEnv() {
  const { scene } = useThree();
  const tex = useMemo(() => studioEnvTexture(), []);
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

/* ------------------------------------------------------------------ */
/* Fixed scene geometry (tuned so the tonearm arc lands on the wax)   */
/* ------------------------------------------------------------------ */

const RECORD_CENTER: [number, number] = [-0.55, 0.0]; // (x, z)
const RECORD_RADIUS = 1.225;
const ARM_PIVOT_XZ: [number, number] = [1.42, -1.3]; // back-right corner
// Land the stylus on the lead-in groove, ~72° around the record from the
// pivot axis. With the arm length derived from this point, the arm can never
// overshoot the wax.
const LAND_ANGLE = 1.25; // radians

/** Point on the record the stylus rides. */
function grooveLanding(): { x: number; z: number; length: number } {
  const dx = ARM_PIVOT_XZ[0] - RECORD_CENTER[0];
  const dz = ARM_PIVOT_XZ[1] - RECORD_CENTER[1];
  const len = Math.hypot(dx, dz) || 1;
  const ux = dx / len;
  const uz = dz / len;
  const a = LAND_ANGLE;
  const vx = ux * Math.cos(a) - uz * Math.sin(a);
  const vz = ux * Math.sin(a) + uz * Math.cos(a);
  const r = RECORD_RADIUS * 0.86;
  const x = RECORD_CENTER[0] + vx * r;
  const z = RECORD_CENTER[1] + vz * r;
  const length = Math.hypot(x - ARM_PIVOT_XZ[0], z - ARM_PIVOT_XZ[1]);
  return { x, z, length };
}

const LANDING = grooveLanding();
const ARM_LENGTH = LANDING.length;

/** Arm-rest post sits exactly one arm-length from the pivot. */
function restPost(): { x: number; z: number } {
  const [ux, uz] = [-0.3, 0.954];
  return {
    x: ARM_PIVOT_XZ[0] + ux * ARM_LENGTH,
    z: ARM_PIVOT_XZ[1] + uz * ARM_LENGTH,
  };
}
const REST = restPost();

/** Yaw (rotation about Y) that points local -z (the arm tube) at a world target. */
function solveYaw(tx: number, tz: number): number {
  const dx = tx - ARM_PIVOT_XZ[0];
  const dz = tz - ARM_PIVOT_XZ[1];
  const len = Math.hypot(dx, dz) || 1;
  return Math.atan2(-dx / len, -dz / len);
}

const YAW_GROOVE = solveYaw(LANDING.x, LANDING.z);
const YAW_REST = solveYaw(REST.x, REST.z);

/* ------------------------------------------------------------------ */
/* Record + platter                                                    */
/* ------------------------------------------------------------------ */

function PlatterAndRecord({
  isPlaying,
  isNeedleDropping,
  vinylStyle,
  title,
  recipientName,
  senderName,
  detail,
}: {
  isPlaying: boolean;
  isNeedleDropping: boolean;
  vinylStyle: VinylStyleType;
  title: string;
  recipientName?: string;
  senderName?: string;
  detail: 'high' | 'low';
}) {
  const spin = useRef<THREE.Group>(null);
  const sheen = useRef<THREE.Mesh>(null);
  const style = VINYL_STYLES.find((s) => s.id === vinylStyle) || VINYL_STYLES[0];
  const segs = detail === 'low' ? 64 : 128;

  const label = useMemo(
    () =>
      vinylFaceTexture({
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
  const bump = useMemo(() => vinylBumpTexture(), []);
  const platterTex = useMemo(() => platterBrushedTexture(), []);
  const sheenTex = useMemo(() => sheenTexture(), []);
  const castShadow = detail === 'high';

  const speed = useSpinRamp(isPlaying || isNeedleDropping ? BAND : 0);

  useFrame((state, delta) => {
    const d = Math.min(delta, 0.05);
    if (spin.current) spin.current.rotation.y += speed.current * d;
    // The glare (window reflection) stays put in world space while the grooves
    // rotate underneath — exactly how a real gloss surface behaves. It breathes
    // and drifts very slowly so it never reads as a frozen sticker.
    if (sheen.current) {
      const t = state.clock.elapsedTime;
      sheen.current.rotation.z = 0.35 + Math.sin(t * 0.05) * 0.12;
      (sheen.current.material as THREE.MeshBasicMaterial).opacity =
        (isPlaying ? 0.2 : 0.13) + Math.sin(t * 0.6) * 0.025;
    }
  });

  return (
    <group position={[RECORD_CENTER[0], 0, RECORD_CENTER[1]]}>
      {/* Fixed platter well ring on the deck */}
      <mesh position={[0, 0.5565, 0]} rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[1.365, 0.016, 10, 96]} />
        <meshStandardMaterial color="#131110" roughness={0.6} metalness={0.4} />
      </mesh>

      {/* Rotating group: platter disc, mat, record, spindle */}
      <group ref={spin} position={[0, 0.5375, 0]}>
        {/* Aluminium platter */}
        <mesh position={[0, 0.026, 0]} castShadow={castShadow}>
          <cylinderGeometry args={[1.315, 1.31, 0.055, segs]} />
          <meshPhysicalMaterial
            map={platterTex ?? undefined}
            color="#cfc9bf"
            metalness={0.9}
            roughness={0.32}
            envMapIntensity={0.9}
            clearcoat={0.4}
            clearcoatRoughness={0.3}
          />
        </mesh>
        {/* Rubber mat */}
        <mesh position={[0, 0.058, 0]}>
          <cylinderGeometry args={[1.29, 1.29, 0.012, segs]} />
          <meshStandardMaterial color="#17151a" roughness={0.94} metalness={0} />
        </mesh>
        {/* The record itself: glossy wax, grooved face, printed label */}
        <mesh position={[0, 0.075, 0]} castShadow={castShadow}>
          <cylinderGeometry args={[RECORD_RADIUS, RECORD_RADIUS, 0.034, segs]} />
          <meshPhysicalMaterial
            color={shade(style.baseColor, -0.72)}
            roughness={0.3}
            metalness={0.08}
            clearcoat={1}
            clearcoatRoughness={0.1}
            envMapIntensity={0.95}
          />
        </mesh>
        <mesh position={[0, 0.0924, 0]} rotation={[-Math.PI / 2, 0, 0]}>
          <circleGeometry args={[RECORD_RADIUS - 0.004, segs]} />
          {label ? (
            <meshPhysicalMaterial
              map={label}
              bumpMap={bump ?? undefined}
              bumpScale={0.09}
              roughness={0.32}
              metalness={0.05}
              clearcoat={0.9}
              clearcoatRoughness={0.14}
              envMapIntensity={0.9}
              side={THREE.DoubleSide}
            />
          ) : (
            <meshPhysicalMaterial color={shade(style.baseColor, -0.72)} roughness={0.3} clearcoat={1} />
          )}
        </mesh>
        {/* Spindle pin */}
        <mesh position={[0, 0.155, 0]} castShadow={castShadow}>
          <cylinderGeometry args={[0.028, 0.028, 0.21, 20]} />
          <meshStandardMaterial color="#e8e2d6" metalness={1} roughness={0.16} envMapIntensity={1.1} />
        </mesh>
      </group>

      {/* Static gloss streak over the grooves (see useFrame above) */}
      <mesh ref={sheen} position={[0, 0.0938, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[RECORD_RADIUS * 1.7, RECORD_RADIUS * 1.7]} />
        <meshBasicMaterial
          map={sheenTex ?? undefined}
          transparent
          opacity={0.14}
          blending={THREE.AdditiveBlending}
          depthWrite={false}
          color="#fff2dc"
        />
      </mesh>
    </group>
  );
}

/* ------------------------------------------------------------------ */
/* Tonearm                                                             */
/* ------------------------------------------------------------------ */

function Tonearm({
  isPlaying,
  isNeedleDropping,
  detail,
}: {
  isPlaying: boolean;
  isNeedleDropping: boolean;
  detail: 'high' | 'low';
}) {
  const yaw = useRef<THREE.Group>(null);
  const cue = useRef<THREE.Group>(null);
  const glow = useRef<THREE.Sprite>(null);
  const progress = useRef(0);
  const head = useRef<THREE.Group>(null);

  const glowTex = useMemo(() => glowTexture(), []);

  // J-shaped tonearm tube, built once.
  const tube = useMemo(() => {
    const end = ARM_LENGTH - 0.22;
    const curve = new THREE.CatmullRomCurve3([
      new THREE.Vector3(0.02, 0, -0.12), // pivot end
      new THREE.Vector3(0.02, 0.05, -end * 0.42),
      new THREE.Vector3(0.03, 0.04, -end * 0.72),
      new THREE.Vector3(0.035, 0, -end * 0.94),
      new THREE.Vector3(0.03, -0.02, -end),
    ]);
    const geo = new THREE.TubeGeometry(curve, 64, 0.028, 10, false);
    return geo;
  }, []);

  useFrame((state, delta) => {
    const d = Math.min(delta, 0.05);
    const armDown = isPlaying || isNeedleDropping;
    // Slow physical descent, swift return to rest.
    if (armDown) progress.current = Math.min(1, progress.current + d * 0.028);
    else progress.current = Math.max(0, progress.current - d * 0.6);

    const t = progress.current;
    const ease = t * t * (3 - 2 * t); // smoothstep

    // Swing from the rest post onto the lead-in groove, drifting inward while
    // the record turns (the groove spirals toward the label).
    const targetYaw = THREE.MathUtils.lerp(YAW_REST, YAW_GROOVE, ease) + t * 0.045;

    // Cueing: stylus rides slightly high out of the groove, settles flush on
    // contact, lifts on the way home. The last 15% of the drop is the "needle
    // drop" — a tiny overshoot reads as the stylus settling into the groove.
    let targetPitch = isPlaying ? -0.002 : 0.012;
    if (t > 0.85) targetPitch = -0.004 + Math.sin((t - 0.85) * 26) * 0.006 * (1 - t);
    if (!armDown) targetPitch = 0.03;

    if (yaw.current) {
      yaw.current.rotation.y = THREE.MathUtils.lerp(yaw.current.rotation.y, targetYaw, 1 - Math.exp(-d * 5));
    }
    if (cue.current) {
      cue.current.rotation.x = THREE.MathUtils.lerp(cue.current.rotation.x, targetPitch, 1 - Math.exp(-d * (armDown ? 3.2 : 6)));
    }

    // Stylus light blooms once the needle is in the groove.
    if (glow.current) {
      const mat = glow.current.material as THREE.SpriteMaterial;
      const lit = armDown && t > 0.9;
      mat.opacity = THREE.MathUtils.lerp(mat.opacity, lit ? 0.7 : 0, 1 - Math.exp(-d * 8));
      glow.current.scale.setScalar(THREE.MathUtils.lerp(glow.current.scale.x, lit ? 0.3 : 0.001, 1 - Math.exp(-d * 8)));
    }

    // Faint tracking shimmer: the cartridge breathes as the groove runs.
    if (head.current && isPlaying) {
      head.current.position.y = Math.sin(state.clock.elapsedTime * 27) * 0.0006;
    }
  });

  const pivotY = 0.755;
  const headshellZ = -(ARM_LENGTH - 0.02);

  return (
    <group position={[ARM_PIVOT_XZ[0], pivotY, ARM_PIVOT_XZ[1]]}>
      {/* Pivot post + bearing */}
      <mesh position={[0, -0.14, 0]} castShadow>
        <cylinderGeometry args={[0.055, 0.075, 0.16, 28]} />
        <meshPhysicalMaterial color="#2b2b2e" metalness={0.85} roughness={0.3} envMapIntensity={0.9} />
      </mesh>
      <mesh position={[0, -0.035, 0]}>
        <cylinderGeometry args={[0.085, 0.09, 0.06, 28]} />
        <meshPhysicalMaterial color="#c9c2b4" metalness={0.95} roughness={0.22} envMapIntensity={1} />
      </mesh>

      {/* Cue lever beside the pivot */}
      <group position={[0.16, -0.02, 0.05]}>
        <mesh position={[0, -0.08, 0]}>
          <cylinderGeometry args={[0.02, 0.03, 0.12, 16]} />
          <meshStandardMaterial color="#3a3a3e" metalness={0.7} roughness={0.4} />
        </mesh>
        <mesh position={[0.045, 0, 0]} rotation={[Math.PI / 2, 0, 0.5]}>
          <cylinderGeometry args={[0.015, 0.015, 0.11, 12]} />
          <meshPhysicalMaterial color="#e8e2d6" metalness={0.95} roughness={0.18} envMapIntensity={1} />
        </mesh>
        <mesh position={[0.09, 0.012, 0]}>
          <sphereGeometry args={[0.018, 12, 12]} />
          <meshPhysicalMaterial color="#2b2b2e" metalness={0.8} roughness={0.35} />
        </mesh>
      </group>

      {/* Arm rest post with cradle */}
      <mesh position={[REST.x - ARM_PIVOT_XZ[0], -0.09, REST.z - ARM_PIVOT_XZ[1]]}>
        <cylinderGeometry args={[0.024, 0.036, 0.18, 16]} />
        <meshPhysicalMaterial color="#8a7a5c" metalness={0.75} roughness={0.4} />
      </mesh>
      <mesh position={[REST.x - ARM_PIVOT_XZ[0], 0.02, REST.z - ARM_PIVOT_XZ[1]]} rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[0.052, 0.014, 8, 20, Math.PI]} />
        <meshPhysicalMaterial color="#c9c2b4" metalness={0.95} roughness={0.2} envMapIntensity={1} />
      </mesh>

      <group ref={yaw} rotation={[0, YAW_REST, 0]}>
        {/* Counterweight + fine-tune ring */}
        <group position={[0, -0.02, ARM_LENGTH * 0.16]} rotation={[Math.PI / 2, 0, 0]}>
          <mesh>
            <cylinderGeometry args={[0.075, 0.075, 0.14, 24]} />
            <meshStandardMaterial color="#1c1c1f" metalness={0.7} roughness={0.5} />
          </mesh>
          <mesh position={[0, 0, 0.075]}>
            <cylinderGeometry args={[0.079, 0.079, 0.018, 24]} />
            <meshPhysicalMaterial color="#c9c2b4" metalness={0.95} roughness={0.2} envMapIntensity={1} />
          </mesh>
        </group>

        {/* Counterweight stub behind */}
        <mesh position={[0, -0.02, ARM_LENGTH * 0.28]} rotation={[Math.PI / 2, 0, 0]}>
          <cylinderGeometry args={[0.02, 0.02, ARM_LENGTH * 0.24, 12]} />
          <meshPhysicalMaterial color="#c9c2b4" metalness={0.95} roughness={0.25} envMapIntensity={0.9} />
        </mesh>

        <group ref={cue} rotation={[0.03, 0, 0]}>
          {/* J-shaped arm tube */}
          <mesh geometry={tube} castShadow>
            <meshPhysicalMaterial color="#e6ded2" metalness={0.95} roughness={0.22} envMapIntensity={1.05} />
          </mesh>

          {/* Headshell + cartridge + stylus — stylus tip lands flush on the wax */}
          <group ref={head} position={[0.028, -0.02, headshellZ]} rotation={[-0.12, 0, 0]}>
            <mesh position={[0, 0.002, 0.02]}>
              <boxGeometry args={[0.085, 0.045, 0.14]} />
              <meshStandardMaterial color="#201f22" metalness={0.6} roughness={0.45} />
            </mesh>
            <group position={[0, -0.03, -0.005]}>
              <mesh position={[0, 0, 0]} rotation={[0.14, 0, 0]}>
                <boxGeometry args={[0.058, 0.028, 0.1]} />
                <meshPhysicalMaterial color="#8f8578" metalness={0.9} roughness={0.35} envMapIntensity={0.8} />
              </mesh>
              {/* Cantilever + stylus */}
              <mesh position={[0, -0.014, -0.02]} rotation={[0.3, 0, 0]}>
                <cylinderGeometry args={[0.005, 0.005, 0.034, 8]} />
                <meshPhysicalMaterial color="#d8d2c8" metalness={0.95} roughness={0.15} envMapIntensity={1} />
              </mesh>
              <mesh position={[0, -0.048, -0.024]} rotation={[0.6, 0, 0]}>
                <coneGeometry args={[0.0065, 0.026, 10]} />
                <meshPhysicalMaterial color="#fdfaf2" metalness={0.8} roughness={0.2} envMapIntensity={1.2} />
              </mesh>
            </group>
            {/* Warm glow where the stylus meets the wax */}
            <sprite ref={glow} position={[0, -0.098, -0.028]} scale={0.001}>
              <spriteMaterial
                map={glowTex ?? undefined}
                color="#ffd9a0"
                transparent
                opacity={0}
                depthWrite={false}
                blending={THREE.AdditiveBlending}
              />
            </sprite>
            {detail === 'high' && isPlaying && (
              <pointLight position={[0, -0.1, -0.028]} color="#ffcf8f" intensity={0.6} distance={1.1} decay={2} />
            )}
          </group>
        </group>
      </group>
    </group>
  );
}

/* ------------------------------------------------------------------ */
/* Chassis                                                             */
/* ------------------------------------------------------------------ */

function Chassis({ isRecording, isPlaying }: { isRecording: boolean; isPlaying: boolean }) {
  const wood = useMemo(() => walnutTexture(), []);
  const brushed = useMemo(() => darkBrushedTexture(), []);
  const led = useRef<THREE.MeshStandardMaterial>(null);
  const btn = useRef<THREE.MeshPhysicalMaterial>(null);

  useFrame((state) => {
    const t = state.clock.elapsedTime;
    if (led.current) {
      const color = isRecording ? '#ff4545' : isPlaying ? '#5ee8a0' : '#d8b06a';
      led.current.color.set(color);
      led.current.emissive.set(color);
      led.current.emissiveIntensity = isRecording
        ? 1.6 + Math.sin(t * 8) * 0.9
        : isPlaying
        ? 1.2 + Math.sin(t * 2.6) * 0.3
        : 0.45;
    }
    if (btn.current) {
      btn.current.emissiveIntensity = isPlaying ? 0.8 + Math.sin(t * 2.6) * 0.2 : 0.18;
    }
  });

  return (
    <group>
      {/* Walnut plinth */}
      <RoundedBox args={[4.5, 0.52, 3.2]} radius={0.09} smoothness={3} position={[0, 0.26, 0]} castShadow receiveShadow>
        <meshPhysicalMaterial
          map={wood ?? undefined}
          color="#8a6a50"
          roughness={0.45}
          metalness={0.05}
          clearcoat={0.35}
          clearcoatRoughness={0.5}
          envMapIntensity={0.25}
        />
      </RoundedBox>
      {/* Brushed dark top plate */}
      <RoundedBox args={[4.34, 0.035, 3.04]} radius={0.02} smoothness={2} position={[0, 0.5375, 0]} receiveShadow>
        <meshStandardMaterial map={brushed ?? undefined} color="#3a393e" metalness={0.7} roughness={0.45} envMapIntensity={0.5} />
      </RoundedBox>
      {/* Feet */}
      {([[-2.02, -0.05, -1.38], [2.02, -0.05, -1.38], [-2.02, -0.05, 1.38], [2.02, -0.05, 1.38]] as const).map((p, i) => (
        <mesh key={i} position={[p[0], -0.05, p[1]]}>
          <cylinderGeometry args={[0.14, 0.17, 0.1, 24]} />
          <meshStandardMaterial color="#151517" roughness={0.85} metalness={0.1} />
        </mesh>
      ))}

      {/* Power / start button + LED */}
      <group position={[1.35, 0.5575, 1.28]}>
        <mesh position={[0, 0.008, 0]}>
          <cylinderGeometry args={[0.165, 0.175, 0.02, 30]} />
          <meshStandardMaterial color="#3a3834" metalness={0.65} roughness={0.4} />
        </mesh>
        <mesh position={[0, 0.035, 0]}>
          <cylinderGeometry args={[0.125, 0.135, 0.035, 30]} />
          <meshPhysicalMaterial
            ref={btn}
            color="#3d3d41"
            emissive="#ff9d4d"
            emissiveIntensity={0.2}
            metalness={0.45}
            roughness={0.5}
            clearcoat={0.6}
          />
        </mesh>
      </group>
      {/* Selector knob */}
      <group position={[1.68, 0.5575, 1.45]} rotation={[0, 0.3, 0]}>
        <mesh position={[0, 0.012, 0]}>
          <cylinderGeometry args={[0.055, 0.06, 0.028, 24]} />
          <meshPhysicalMaterial color="#8f8578" metalness={0.9} roughness={0.3} envMapIntensity={0.8} />
        </mesh>
        <mesh position={[0.055, 0.022, 0]}>
          <boxGeometry args={[0.03, 0.012, 0.09]} />
          <meshPhysicalMaterial color="#3a2a18" metalness={0.6} roughness={0.4} />
        </mesh>
      </group>
      {/* Power LED */}
      <mesh position={[1.92, 0.585, 1.28]}>
        <sphereGeometry args={[0.03, 14, 14]} />
        <meshStandardMaterial ref={led} color="#d8b06a" emissive="#d8b06a" emissiveIntensity={0.4} roughness={0.3} />
      </mesh>
      {/* Brand plate on the front of the plinth */}
      <mesh position={[-0.2, 0.31, 1.6045]}>
        <planeGeometry args={[1.15, 0.14]} />
        <meshPhysicalMaterial color="#c4ab7d" metalness={0.9} roughness={0.26} envMapIntensity={0.8} />
      </mesh>
      <mesh position={[-0.2, 0.31, 1.6055]}>
        <planeGeometry args={[0.98, 0.09]} />
        <meshBasicMaterial map={brandPlateTexture() ?? undefined} transparent opacity={0.9} />
      </mesh>
    </group>
  );
}

/* ------------------------------------------------------------------ */
/* Studio table                                                        */
/* ------------------------------------------------------------------ */

function StudioTable({ detail }: { detail: 'high' | 'low' }) {
  const wood = useMemo(() => walnutTexture(), []);
  const shadow = useMemo(() => shadowTexture(), []);

  return (
    <group>
      {/* Tabletop the player rests on */}
      <RoundedBox args={[8.4, 0.3, 6.1]} radius={0.07} smoothness={2} position={[0, -0.17, 0]} receiveShadow>
        <meshPhysicalMaterial
          map={wood ?? undefined}
          color="#6b4a33"
          roughness={0.5}
          metalness={0.03}
          clearcoat={0.25}
          clearcoatRoughness={0.6}
        />
      </RoundedBox>
      {/* Soft contact shadow between plinth and table */}
      <mesh position={[0, -0.013, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[5.4, 3.9]} />
        <meshBasicMaterial map={shadow ?? undefined} transparent opacity={0.65} depthWrite={false} />
      </mesh>
      {/* A touch of depth behind the unit, only on big views */}
      {detail === 'high' && (
        <mesh position={[0, -0.33, -2.7]}>
          <planeGeometry args={[8.4, 1.6]} />
          <meshBasicMaterial color="#0b0806" transparent opacity={0.7} />
        </mesh>
      )}
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

  if (active) return 'always' as const;
  return parked ? ('never' as const) : ('always' as const);
}

/* ------------------------------------------------------------------ */
/* Public component                                                    */
/* ------------------------------------------------------------------ */

export function TurntablePlayer({
  isPlaying,
  isNeedleDropping = false,
  vinylStyle = 'classic_red',
  title = 'A Voice Note',
  recipientName,
  senderName,
  isRecording = false,
  detail = 'high',
  className = '',
}: TurntablePlayerProps) {
  const frameloop = useIdleFrameloop(isPlaying || isNeedleDropping || isRecording);
  const spinning = isPlaying || isNeedleDropping;

  return (
    <div
      className={`relative w-full h-full overflow-hidden ${className}`}
      style={{
        // Dark studio: warm tungsten above, cool shadow below — lets the
        // brushed metal and the glossy vinyl carry the scene.
        background:
          'radial-gradient(130% 95% at 50% -10%, #2b2320 0%, #211a17 40%, #151110 72%, #0b0908 100%)',
      }}
    >
      {/* Soft spotlight pool on the table */}
      <div
        className="pointer-events-none absolute inset-x-0 bottom-0"
        style={{
          background:
            'radial-gradient(60% 42% at 50% 108%, rgba(214,166,110,0.24), transparent 70%)',
        }}
      />
      <div
        className="pointer-events-none absolute inset-0"
        style={{
          boxShadow:
            'inset 0 -70px 130px -50px rgba(0,0,0,0.9), inset 0 46px 90px -60px rgba(255,215,170,0.14)',
        }}
      />

      <Canvas
        frameloop={frameloop}
        dpr={[1, 1.6]}
        shadows={{ type: THREE.PCFSoftShadowMap }}
        gl={{ antialias: true, alpha: true, powerPreference: 'high-performance' }}
        camera={{ position: [1.15, 2.75, 5.35], fov: 36 }}
      >
        <ProceduralEnv />

        <ambientLight intensity={0.55} color="#ffedda" />
        <hemisphereLight args={['#fff1e2', '#1a1412', 0.5]} />
        {/* Key light — soft, from the front-left */}
        <directionalLight
          position={[3.6, 6.2, 4.4]}
          intensity={2.3}
          color="#fff3e0"
          castShadow
          shadow-mapSize-width={1024}
          shadow-mapSize-height={1024}
          shadow-camera-left={-4.2}
          shadow-camera-right={4.2}
          shadow-camera-top={4.2}
          shadow-camera-bottom={-4.2}
          shadow-camera-far={18}
          shadow-bias={-0.0004}
        />
        {/* Cool rim light from the back to separate the plinth from the dark */}
        <directionalLight position={[-4.5, 3.2, -4.5]} intensity={0.9} color="#a8c8ff" />
        {/* Warm fill across the deck */}
        <pointLight position={[0, 1.6, 2.6]} intensity={0.6} distance={8} decay={2} color="#ffb877" />

        <group position={[0, 0, 0]}>
          <StudioTable detail={detail} />
          <Chassis isRecording={isRecording} isPlaying={spinning} />
          <PlatterAndRecord
            isPlaying={isPlaying}
            isNeedleDropping={isNeedleDropping}
            vinylStyle={vinylStyle}
            title={title}
            recipientName={recipientName}
            senderName={senderName}
            detail={detail}
          />
          <Tonearm isPlaying={isPlaying} isNeedleDropping={isNeedleDropping} detail={detail} />
        </group>

        <OrbitControls
          enablePan={false}
          enableDamping
          dampingFactor={0.08}
          minPolarAngle={Math.PI / 9}
          maxPolarAngle={Math.PI / 2.15}
          minDistance={3.4}
          maxDistance={8.6}
          target={[0, 0.5, 0]}
        />
      </Canvas>

      {/* Minimal status chrome */}
      <div className="pointer-events-none absolute left-3 top-3 flex items-center gap-2">
        <span className="rounded-full border border-white/15 bg-black/45 px-2.5 py-1 font-mono text-[10px] uppercase tracking-wider text-amber-100">
          {spinning ? (isNeedleDropping ? 'the needle lands…' : 'now playing') : 'drag to find your angle'}
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

export { TurntablePlayer as AnimeTurntablePlayer };
export default TurntablePlayer;