'use client';

import React, { useState, useEffect } from 'react';
import { Button } from '@/components/ui/Button';
import { Users, Shield, Crown, Trash2, Search, Clock, Mail, KeyRound, Ban, CheckCircle, Plus, Minus, Disc3, LogOut, Eye } from 'lucide-react';
import toast from 'react-hot-toast';

interface EnrichedUser {
  id: string;
  email: string;
  full_name: string | null;
  role: string;
  account_status: string;
  stripe_customer_id: string | null;
  recording_count: number;
  created_at: string;
  email_confirmed: boolean;
  last_sign_in_at: string | null;
  provider: string;
  providers: string[];
  entitlements: any[];
  effective_plan: any;
  effective_plan_name: string;
  entitlement_source: string | null;
  entitlement_status: string | null;
  entitlement_expires_at: string | null;
  remaining_recordings: number | null;
  purchases: any[];
}

interface AdminUsersTabProps {
  users: EnrichedUser[];
  onUpdateUser: (id: string, updates: any) => Promise<void>;
  onDeleteUser: (id: string, confirm: string) => Promise<void>;
  onRefresh?: () => Promise<void>;
}

export const AdminUsersTab: React.FC<AdminUsersTabProps> = ({
  users,
  onUpdateUser,
  onDeleteUser,
  onRefresh,
}) => {
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [selectedUser, setSelectedUser] = useState<EnrichedUser | null>(null);
  const [page, setPage] = useState(1);
  const [editingName, setEditingName] = useState('');
  const [grantPlanId, setGrantPlanId] = useState('');
  const [grantExpires, setGrantExpires] = useState('');
  const [grantStarts, setGrantStarts] = useState('');
  const [creditAmount, setCreditAmount] = useState(5);
  const [recordingIdForGrant, setRecordingIdForGrant] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState('');
  const [plans, setPlans] = useState<any[]>([]);
  const [recordings, setRecordings] = useState<any[]>([]);

  useEffect(() => {
    fetch('/api/pricing').then(r => r.json()).then(d => { if (d.plans) setPlans(d.plans); }).catch(() => {});
  }, []);

  useEffect(() => {
    if (selectedUser) {
      fetch(`/api/recordings?user_id=${selectedUser.id}`).then(r => r.json()).then(d => { if (d.recordings) setRecordings(d.recordings.filter((rec: any) => rec.user_id === selectedUser.id)); }).catch(() => {});
      setEditingName(selectedUser.full_name || '');
    }
  }, [selectedUser]);

  const filteredUsers = users.filter((u) => {
    const q = searchQuery.toLowerCase();
    return (
      u.email.toLowerCase().includes(q) ||
      (u.full_name && u.full_name.toLowerCase().includes(q)) ||
      u.id.toLowerCase().includes(q)
    );
  });

  const paginated = filteredUsers.slice((page - 1) * 20, page * 20);
  const totalPages = Math.ceil(filteredUsers.length / 20);

  async function handleAction(userId: string, action: string, data: any = {}) {
    try {
      const res = await fetch('/api/admin/users', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: userId, action, ...data }),
      });
      const json = await res.json();
      if (!res.ok) throw new Error(json.error || 'Action failed');
      toast.success(`${action} succeeded`);
      if (onRefresh) await onRefresh();
      // Update selected user if still selected
      if (selectedUser && selectedUser.id === userId) {
        // Refresh selected
        const refreshed = await fetch(`/api/admin/users?search=${encodeURIComponent(selectedUser.email)}`).then(r => r.json()).then(d => d.users?.[0]).catch(() => null);
        if (refreshed) setSelectedUser(refreshed);
      }
    } catch (e: any) {
      toast.error(e.message);
    }
  }

  return (
    <div className="space-y-6">
      <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-6 shadow-xl">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-stone-800 pb-4">
          <div>
            <h3 className="font-serif font-bold text-lg text-amber-100 flex items-center gap-2">
              <Users className="w-5 h-5 text-amber-500" />
              <span>User & Account Management ({users.length})</span>
            </h3>
            <p className="text-xs text-stone-400">Search, view entitlements, assign plans, grant credits, revoke sessions, delete with confirmation.</p>
          </div>
          <div className="flex gap-2">
            <div className="relative min-w-[240px]">
              <Search className="w-4 h-4 text-stone-500 absolute left-3 top-2.5" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => { setSearchQuery(e.target.value); setPage(1); }}
                placeholder="Search email, name, id..."
                className="w-full bg-stone-950 border border-stone-700 rounded-xl pl-9 pr-3 py-2 text-xs text-stone-100 focus:outline-none focus:border-amber-500"
              />
            </div>
            {onRefresh && <Button variant="outline" size="sm" onClick={onRefresh}>Refresh</Button>}
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs text-stone-300">
            <thead className="bg-stone-950 text-stone-400 font-mono uppercase tracking-wider border-b border-stone-800">
              <tr>
                <th className="p-3">User</th>
                <th className="p-3">Status / Role</th>
                <th className="p-3">Plan / Source</th>
                <th className="p-3">Credits</th>
                <th className="p-3">Auth</th>
                <th className="p-3">Joined</th>
                <th className="p-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-stone-800/60">
              {paginated.map((user) => (
                <tr key={user.id} className="hover:bg-stone-950/50 transition-colors">
                  <td className="p-3">
                    <div className="font-semibold text-stone-100">{user.full_name || 'Anonymous'}</div>
                    <div className="font-mono text-stone-400 text-[11px]">{user.email}</div>
                    <div className="font-mono text-[10px] text-stone-500">{user.id.slice(0, 8)}…</div>
                  </td>
                  <td className="p-3 space-y-1">
                    <span className={`inline-flex px-2 py-0.5 rounded-full text-[10px] border ${user.account_status === 'active' ? 'bg-emerald-950 border-emerald-700 text-emerald-300' : user.account_status === 'suspended' ? 'bg-amber-950 border-amber-700 text-amber-300' : 'bg-red-950 border-red-800 text-red-300'}`}>{user.account_status}</span>
                    <div><span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] border ${user.role === 'admin' ? 'bg-amber-950/60 border-amber-500 text-amber-300' : 'bg-stone-800 border-stone-700 text-stone-400'}`}><Shield className="w-3 h-3" />{user.role}</span></div>
                  </td>
                  <td className="p-3">
                    <div className="text-stone-100 font-medium">{user.effective_plan_name}</div>
                    <div className="text-[11px] text-stone-500">{user.entitlement_source || '—'} • {user.entitlement_status || '—'}</div>
                    {user.entitlement_expires_at && <div className="text-[10px] text-stone-500">exp {new Date(user.entitlement_expires_at).toLocaleDateString()}</div>}
                  </td>
                  <td className="p-3 font-mono">
                    <div>{user.remaining_recordings ?? '—'} credits</div>
                    <div className="text-[11px] text-stone-500">{user.recording_count} records</div>
                  </td>
                  <td className="p-3">
                    <div className="text-[11px]">{user.provider} {user.email_confirmed ? <CheckCircle className="inline w-3 h-3 text-emerald-400" /> : <span className="text-amber-400">unverified</span>}</div>
                    <div className="text-[10px] text-stone-500">{user.last_sign_in_at ? new Date(user.last_sign_in_at).toLocaleDateString() : 'never'}</div>
                  </td>
                  <td className="p-3 text-[11px] text-stone-400">{new Date(user.created_at).toLocaleDateString()}</td>
                  <td className="p-3 text-right">
                    <button onClick={() => setSelectedUser(user)} className="p-1.5 rounded-lg text-stone-400 hover:text-amber-300 hover:bg-stone-800" title="View"><Eye className="w-4 h-4" /></button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="flex justify-between items-center pt-2 text-xs text-stone-500">
          <span>Page {page} of {totalPages} • {filteredUsers.length} filtered</span>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage(p => Math.max(1, p - 1))}>Prev</Button>
            <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => setPage(p => Math.min(totalPages, p + 1))}>Next</Button>
          </div>
        </div>
      </div>

      {/* Selected user detail */}
      {selectedUser && (
        <div className="p-6 rounded-3xl bg-stone-900 border border-amber-600/30 space-y-6">
          <div className="flex items-center justify-between border-b border-stone-800 pb-3">
            <h4 className="font-serif font-bold text-amber-100">Manage {selectedUser.email}</h4>
            <button onClick={() => setSelectedUser(null)} className="text-xs text-stone-400 hover:text-stone-200">Close</button>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div className="space-y-4">
              <div>
                <label className="text-xs text-stone-400">Full name</label>
                <div className="flex gap-2 mt-1">
                  <input value={editingName} onChange={e => setEditingName(e.target.value)} className="flex-1 rounded-xl border border-stone-700 bg-stone-950 px-3 py-2 text-sm" />
                  <Button size="sm" onClick={() => handleAction(selectedUser.id, 'update_profile', { full_name: editingName })}>Save</Button>
                </div>
              </div>

              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="outline" onClick={() => handleAction(selectedUser.id, 'change_role', { role: selectedUser.role === 'admin' ? 'user' : 'admin' })} leftIcon={<Shield className="w-3 h-3" />}>
                  Toggle {selectedUser.role === 'admin' ? 'to user' : 'to admin'}
                </Button>
                {selectedUser.account_status === 'active' ? (
                  <Button size="sm" variant="outline" onClick={() => handleAction(selectedUser.id, 'suspend')} leftIcon={<Ban className="w-3 h-3" />} className="border-amber-800 text-amber-300">Suspend</Button>
                ) : (
                  <Button size="sm" variant="outline" onClick={() => handleAction(selectedUser.id, 'reactivate')} leftIcon={<CheckCircle className="w-3 h-3" />} className="border-emerald-800 text-emerald-300">Reactivate</Button>
                )}
                <Button size="sm" variant="outline" onClick={() => handleAction(selectedUser.id, 'send_reset')} leftIcon={<KeyRound className="w-3 h-3" />}>Send password reset</Button>
                <Button size="sm" variant="outline" onClick={() => handleAction(selectedUser.id, 'revoke_sessions')} leftIcon={<LogOut className="w-3 h-3" />}>Revoke sessions</Button>
              </div>

              <div className="rounded-xl bg-stone-950 border border-stone-800 p-3 space-y-2">
                <p className="text-xs font-bold text-stone-300">Entitlements</p>
                {selectedUser.entitlements.length === 0 ? <p className="text-xs text-stone-500">None</p> : (
                  <ul className="space-y-1 max-h-48 overflow-auto">
                    {selectedUser.entitlements.map((e: any) => (
                      <li key={e.id} className="flex justify-between items-center text-xs bg-stone-900 rounded-lg px-2 py-1">
                        <span>{e.plan?.name || e.plan_id} • {e.status} • {e.source} • {e.remaining_recordings ?? '∞'}</span>
                        <button onClick={() => handleAction(selectedUser.id, 'revoke_entitlement', { entitlement_id: e.id })} className="text-red-400 hover:text-red-300">Revoke</button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              <div className="rounded-xl bg-stone-950 border border-stone-800 p-3 space-y-2">
                <p className="text-xs font-bold text-stone-300">Grant credits</p>
                <div className="flex gap-2">
                  <input type="number" value={creditAmount} onChange={e => setCreditAmount(parseInt(e.target.value) || 0)} className="w-20 rounded-lg border border-stone-700 bg-stone-900 px-2 py-1 text-sm" />
                  <Button size="sm" onClick={() => handleAction(selectedUser.id, 'grant_credits', { amount: creditAmount })} leftIcon={<Plus className="w-3 h-3" />}>Add</Button>
                  <Button size="sm" variant="outline" onClick={() => handleAction(selectedUser.id, 'grant_credits', { amount: -creditAmount })} leftIcon={<Minus className="w-3 h-3" />}>Remove</Button>
                </div>
              </div>
            </div>

            <div className="space-y-4">
              <div className="rounded-xl bg-stone-950 border border-stone-800 p-3 space-y-2">
                <p className="text-xs font-bold text-stone-300">Assign plan (Free/Monthly/Lifetime)</p>
                <select value={grantPlanId} onChange={e => setGrantPlanId(e.target.value)} className="w-full rounded-lg border border-stone-700 bg-stone-900 px-2 py-1.5 text-sm">
                  <option value="">Select plan</option>
                  {plans.map((p: any) => <option key={p.id} value={p.id}>{p.name} • {p.billing_model} • ${p.price_cents / 100}</option>)}
                </select>
                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="text-[11px] text-stone-500">Starts at</label>
                    <input type="datetime-local" value={grantStarts} onChange={e => setGrantStarts(e.target.value)} className="w-full rounded-lg border border-stone-700 bg-stone-900 px-2 py-1 text-xs" />
                  </div>
                  <div>
                    <label className="text-[11px] text-stone-500">Expires at</label>
                    <input type="datetime-local" value={grantExpires} onChange={e => setGrantExpires(e.target.value)} className="w-full rounded-lg border border-stone-700 bg-stone-900 px-2 py-1 text-xs" />
                  </div>
                </div>
                <Button size="sm" className="w-full" onClick={() => handleAction(selectedUser.id, 'assign_entitlement', { plan_id: grantPlanId, starts_at: grantStarts ? new Date(grantStarts).toISOString() : undefined, expires_at: grantExpires ? new Date(grantExpires).toISOString() : null, status: 'active' })} disabled={!grantPlanId}>Grant access</Button>
              </div>

              <div className="rounded-xl bg-stone-950 border border-stone-800 p-3 space-y-2">
                <p className="text-xs font-bold text-stone-300">Grant per-recording premium</p>
                <select value={recordingIdForGrant} onChange={e => setRecordingIdForGrant(e.target.value)} className="w-full rounded-lg border border-stone-700 bg-stone-900 px-2 py-1.5 text-sm">
                  <option value="">Select recording</option>
                  {recordings.map((r: any) => <option key={r.id} value={r.id}>{r.title} • {r.id.slice(0, 8)}</option>)}
                </select>
                <select value={grantPlanId} onChange={e => setGrantPlanId(e.target.value)} className="w-full rounded-lg border border-stone-700 bg-stone-900 px-2 py-1.5 text-sm">
                  <option value="">Select plan for recording</option>
                  {plans.filter((p: any) => p.billing_model === 'per_recording' || p.billing_model === 'lifetime').map((p: any) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
                <Button size="sm" className="w-full" disabled={!recordingIdForGrant || !grantPlanId} onClick={() => handleAction(selectedUser.id, 'grant_recording_entitlement', { recording_id: recordingIdForGrant, plan_id: grantPlanId })} leftIcon={<Disc3 className="w-3 h-3" />}>Grant recording premium</Button>
              </div>

              <div className="rounded-xl bg-red-950/20 border border-red-900/30 p-3 space-y-2">
                <p className="text-xs font-bold text-red-200">Delete account (type DELETE)</p>
                <input value={deleteConfirm} onChange={e => setDeleteConfirm(e.target.value)} placeholder="DELETE" className="w-full rounded-lg border border-red-800 bg-stone-950 px-2 py-1 text-sm" />
                <Button size="sm" variant="outline" className="w-full border-red-800 text-red-300 hover:bg-red-950" disabled={deleteConfirm !== 'DELETE'} onClick={async () => {
                  try {
                    const res = await fetch(`/api/admin/users?id=${selectedUser.id}&confirm=${deleteConfirm}`, { method: 'DELETE' });
                    const j = await res.json();
                    if (!res.ok) throw new Error(j.error);
                    toast.success('Deleted');
                    setSelectedUser(null);
                    if (onRefresh) await onRefresh();
                  } catch (e: any) { toast.error(e.message); }
                }} leftIcon={<Trash2 className="w-3 h-3" />}>Delete permanently</Button>
              </div>

              <div className="rounded-xl bg-stone-950 border border-stone-800 p-3">
                <p className="text-xs font-bold text-stone-300">Purchases</p>
                {selectedUser.purchases.length === 0 ? <p className="text-xs text-stone-500">None</p> : (
                  <ul className="space-y-1 max-h-32 overflow-auto">
                    {selectedUser.purchases.map((p: any) => <li key={p.id} className="text-[11px] text-stone-400">{p.plan_id} • {p.status} • ${(p.amount_cents || 0) / 100} • {new Date(p.created_at).toLocaleDateString()}</li>)}
                  </ul>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
