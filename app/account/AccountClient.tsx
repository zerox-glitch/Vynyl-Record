'use client';
import { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Disc3, Mail, Shield, Crown, LogOut, Trash2, Edit2, Check, X, AlertTriangle, Clock, User, KeyRound } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { createSupabaseBrowserClient } from '@/lib/supabase/browser';
import { Navbar } from '@/components/ui/Navbar';
import { Footer } from '@/components/ui/Footer';
import toast from 'react-hot-toast';

export default function AccountClient({
  user,
  profile,
  entitlements,
  recordingEntitlements,
  resolved,
  recordingCount,
}: {
  user: { id: string; email: string; emailVerified: boolean; provider: string; providers: string[]; lastSignIn: string | null; createdAt: string };
  profile: any;
  entitlements: any[];
  recordingEntitlements: any[];
  resolved: any;
  recordingCount: number;
}) {
  const router = useRouter();
  const [fullName, setFullName] = useState(profile?.full_name || '');
  const [editingName, setEditingName] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState('');
  const [showDelete, setShowDelete] = useState(false);

  async function handleSaveName() {
    setSaving(true);
    const supabase = createSupabaseBrowserClient();
    if (!supabase) { toast.error('Auth not configured'); setSaving(false); return; }
    const { error } = await supabase.from('profiles').update({ full_name: fullName.trim(), updated_at: new Date().toISOString() }).eq('id', user.id);
    if (error) toast.error(error.message);
    else { toast.success('Name updated'); setEditingName(false); router.refresh(); }
    setSaving(false);
  }

  async function handleSignOut() {
    const supabase = createSupabaseBrowserClient();
    if (supabase) await supabase.auth.signOut();
    // Also clear customer api session if exists
    await fetch('/api/auth/customer', { method: 'DELETE' }).catch(() => {});
    router.push('/');
    router.refresh();
  }

  async function handlePasswordResetEmail() {
    const supabase = createSupabaseBrowserClient();
    if (!supabase) { toast.error('Auth not configured'); return; }
    const origin = window.location.origin;
    const redirectTo = `${origin}/auth/callback?next=${encodeURIComponent('/reset-password')}`;
    const { error } = await supabase.auth.resetPasswordForEmail(user.email, { redirectTo });
    if (error) toast.error(error.message);
    else toast.success('Password reset email sent if account exists.');
  }

  async function handleDeleteAccount() {
    if (deleteConfirm !== 'DELETE') { toast.error('Type DELETE to confirm'); return; }
    const supabase = createSupabaseBrowserClient();
    if (!supabase) { toast.error('Auth not configured'); return; }
    // Call admin API? For now, client deletes own profile via RPC? We'll call a dedicated endpoint
    const res = await fetch('/api/account/delete', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ confirm: deleteConfirm }) });
    const data = await res.json();
    if (!res.ok) { toast.error(data.error || 'Delete failed'); return; }
    toast.success('Account deletion requested');
    await supabase.auth.signOut();
    router.push('/');
  }

  const effectivePlan = resolved?.effectivePlan;
  const isPremium = resolved?.isPremium;

  return (
    <div className="min-h-screen bg-[#0c0a09] text-stone-100 flex flex-col">
      <Navbar />
      <main className="flex-1 max-w-5xl w-full mx-auto px-4 py-10 sm:px-6 lg:px-8 space-y-8">
        <div className="flex items-center justify-between border-b border-amber-900/30 pb-6">
          <div className="flex items-center gap-3">
            <div className="h-12 w-12 rounded-full bg-amber-900/30 border border-amber-700/30 grid place-items-center">
              <User className="h-6 w-6 text-amber-400" />
            </div>
            <div>
              <h1 className="font-serif text-2xl font-bold">Your account</h1>
              <p className="text-xs text-stone-400">{user.email} • {user.provider}</p>
            </div>
          </div>
          <Button variant="outline" size="sm" onClick={handleSignOut} leftIcon={<LogOut className="h-4 w-4" />}>Sign out</Button>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Profile */}
          <div className="lg:col-span-2 space-y-6">
            <div className="rounded-3xl border border-stone-800 bg-stone-900/60 p-6 space-y-4">
              <h3 className="font-serif font-bold text-amber-100 flex items-center gap-2"><Edit2 className="h-4 w-4" /> Profile</h3>
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <label className="text-xs text-stone-400">Full name</label>
                  {!editingName ? (
                    <button onClick={() => setEditingName(true)} className="text-xs text-amber-300 hover:text-amber-200">Edit</button>
                  ) : (
                    <div className="flex gap-2">
                      <button onClick={() => setEditingName(false)} className="p-1 rounded bg-stone-800"><X className="h-3 w-3" /></button>
                      <button onClick={handleSaveName} disabled={saving} className="p-1 rounded bg-amber-600 text-stone-950"><Check className="h-3 w-3" /></button>
                    </div>
                  )}
                </div>
                {editingName ? (
                  <input value={fullName} onChange={e => setFullName(e.target.value)} className="w-full rounded-xl border border-stone-700 bg-stone-950 px-3 py-2 text-sm" />
                ) : (
                  <p className="text-sm">{profile?.full_name || fullName || '—'}</p>
                )}

                <div className="grid grid-cols-2 gap-4 pt-2">
                  <div>
                    <p className="text-xs text-stone-500">Email</p>
                    <p className="text-sm flex items-center gap-2"><Mail className="h-3 w-3" /> {user.email} {user.emailVerified ? <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-emerald-900/50 text-emerald-300 border border-emerald-700/30">verified</span> : <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-amber-900/50 text-amber-300">unverified</span>}</p>
                  </div>
                  <div>
                    <p className="text-xs text-stone-500">Provider</p>
                    <p className="text-sm">{user.providers.join(', ')}</p>
                  </div>
                  <div>
                    <p className="text-xs text-stone-500">Account status</p>
                    <p className="text-sm capitalize">{profile?.account_status || 'active'}</p>
                  </div>
                  <div>
                    <p className="text-xs text-stone-500">Member since</p>
                    <p className="text-sm">{new Date(user.createdAt).toLocaleDateString()}</p>
                  </div>
                </div>

                <div className="pt-3 flex gap-2">
                  <Button variant="outline" size="sm" onClick={handlePasswordResetEmail} leftIcon={<KeyRound className="h-4 w-4" />}>Send password reset email</Button>
                </div>
              </div>
            </div>

            <div className="rounded-3xl border border-stone-800 bg-stone-900/60 p-6 space-y-4">
              <h3 className="font-serif font-bold text-amber-100 flex items-center gap-2"><Disc3 className="h-4 w-4" /> Usage</h3>
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div className="rounded-xl bg-stone-950 border border-stone-800 p-3">
                  <p className="text-xs text-stone-500">Recordings</p>
                  <p className="text-xl font-mono font-bold">{recordingCount}</p>
                </div>
                <div className="rounded-xl bg-stone-950 border border-stone-800 p-3">
                  <p className="text-xs text-stone-500">Remaining credits</p>
                  <p className="text-xl font-mono font-bold">{resolved?.remainingUsage ?? '—'}</p>
                </div>
              </div>
              <div className="text-xs text-stone-400">
                <p>Duration limit: {resolved?.durationLimit ? `${Math.floor(resolved.durationLimit / 60)} min` : '—'}</p>
                <p>Source: {resolved?.source || '—'} • Status: {resolved?.status || '—'}</p>
                {resolved?.expiration && <p>Expires: {new Date(resolved.expiration).toLocaleString()}</p>}
                {resolved?.reason && <p className="text-stone-500">Reason: {resolved.reason}</p>}
              </div>
            </div>

            <div className="rounded-3xl border border-red-900/30 bg-red-950/10 p-6 space-y-4">
              <h3 className="font-serif font-bold text-red-200 flex items-center gap-2"><Trash2 className="h-4 w-4" /> Danger zone</h3>
              {!showDelete ? (
                <Button variant="outline" size="sm" onClick={() => setShowDelete(true)} className="border-red-800 text-red-300 hover:bg-red-950">Request account deletion</Button>
              ) : (
                <div className="space-y-3">
                  <p className="text-xs text-stone-300">Type <span className="font-mono font-bold">DELETE</span> to confirm. This will permanently remove your account and recordings where possible.</p>
                  <input value={deleteConfirm} onChange={e => setDeleteConfirm(e.target.value)} placeholder="Type DELETE" className="w-full rounded-xl border border-red-800 bg-stone-950 px-3 py-2 text-sm" />
                  <div className="flex gap-2">
                    <Button variant="outline" size="sm" onClick={() => setShowDelete(false)}>Cancel</Button>
                    <Button variant="primary" size="sm" onClick={handleDeleteAccount} className="bg-red-600 hover:bg-red-700">Confirm deletion</Button>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Entitlements */}
          <div className="space-y-6">
            <div className="rounded-3xl border border-amber-700/30 bg-stone-900/80 p-6 space-y-4">
              <h3 className="font-serif font-bold text-amber-100 flex items-center gap-2"><Crown className="h-4 w-4 text-amber-400" /> Current plan</h3>
              {effectivePlan ? (
                <div className="space-y-2">
                  <p className="font-bold text-stone-100">{effectivePlan.name}</p>
                  <p className="text-xs text-stone-400">{effectivePlan.description}</p>
                  <div className="flex items-baseline gap-1">
                    <span className="text-2xl font-mono font-bold text-amber-400">${(effectivePlan.price_cents / 100).toFixed(2)}</span>
                    <span className="text-xs text-stone-500">{effectivePlan.billing_model === 'monthly' ? '/month' : effectivePlan.billing_model === 'lifetime' ? '/once' : effectivePlan.billing_model === 'per_recording' ? '/record' : ''}</span>
                  </div>
                  <div className="pt-2 space-y-1 text-[11px] text-stone-400">
                    <p className="flex items-center gap-1"><Shield className="h-3 w-3" /> {isPremium ? 'Premium features unlocked' : 'Free tier'}</p>
                    <p>Filters: {effectivePlan.allowed_filter_presets?.join(', ')}</p>
                    <p>Vinyl: {effectivePlan.allowed_vinyl_styles?.length} styles</p>
                    <p>Download: {effectivePlan.can_download ? 'Yes' : 'No'} • Private: {effectivePlan.can_use_private_visibility ? 'Yes' : 'No'}</p>
                  </div>
                  <Link href="/#pricing"><Button variant="outline" size="sm" className="w-full mt-3">Upgrade or change plan</Button></Link>
                </div>
              ) : (
                <p className="text-sm text-stone-400">No plan found.</p>
              )}
            </div>

            <div className="rounded-3xl border border-stone-800 bg-stone-900/60 p-6 space-y-3">
              <h4 className="text-sm font-bold text-stone-200 flex items-center gap-2"><Clock className="h-4 w-4" /> Entitlement history</h4>
              {entitlements.length === 0 ? <p className="text-xs text-stone-500">No account entitlements yet.</p> : (
                <ul className="space-y-2 max-h-64 overflow-auto">
                  {entitlements.map((e: any) => (
                    <li key={e.id} className="rounded-xl bg-stone-950 border border-stone-800 p-2 text-xs">
                      <p className="font-mono">{e.plan?.name || e.plan_id} • {e.status}</p>
                      <p className="text-[11px] text-stone-500">{e.source} • {new Date(e.created_at).toLocaleDateString()} {e.expires_at ? `→ ${new Date(e.expires_at).toLocaleDateString()}` : ''}</p>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="rounded-3xl border border-stone-800 bg-stone-900/60 p-6 space-y-3">
              <h4 className="text-sm font-bold text-stone-200">Per-recording unlocks</h4>
              {recordingEntitlements.length === 0 ? <p className="text-xs text-stone-500">No per-recording purchases.</p> : (
                <ul className="space-y-2 max-h-64 overflow-auto">
                  {recordingEntitlements.map((r: any) => (
                    <li key={r.id} className="rounded-xl bg-stone-950 border border-stone-800 p-2 text-xs">
                      <p className="font-mono">{r.recording_id.slice(0, 8)} • {r.status}</p>
                      <p className="text-[11px] text-stone-500">{r.plan?.name || r.plan_id} • {new Date(r.created_at).toLocaleDateString()}</p>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </div>
      </main>
      <Footer />
    </div>
  );
}
