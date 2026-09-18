'use client';
import React, { useEffect, useState } from 'react';
import { Shield, Clock } from 'lucide-react';

export const AdminAuditTab: React.FC = () => {
  const [logs, setLogs] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch('/api/admin/audit-logs').then(r => r.json()).then(d => {
      if (d.logs) setLogs(d.logs);
      setLoading(false);
    }).catch(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-6 rounded-3xl bg-stone-900 border border-stone-800 text-sm text-stone-400">Loading audit logs…</div>;

  return (
    <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-4">
      <h3 className="font-serif font-bold text-amber-100 flex items-center gap-2"><Shield className="w-5 h-5" /> Admin Audit Log</h3>
      <p className="text-xs text-stone-400">Tracks role changes, suspension, entitlement grant/revoke, credit changes, plan changes, deletion.</p>
      {logs.length === 0 ? <p className="text-xs text-stone-500">No logs yet.</p> : (
        <ul className="space-y-2 max-h-[600px] overflow-auto">
          {logs.map((log: any) => (
            <li key={log.id} className="rounded-xl bg-stone-950 border border-stone-800 p-3 text-xs">
              <div className="flex justify-between">
                <span className="font-mono font-bold text-amber-300">{log.action}</span>
                <span className="text-[11px] text-stone-500 flex items-center gap-1"><Clock className="w-3 h-3" />{new Date(log.created_at).toLocaleString()}</span>
              </div>
              <div className="text-[11px] text-stone-400 mt-1">
                target_user: {log.target_user_id?.slice(0,8) || '—'} • plan: {log.target_plan_id?.slice(0,8) || '—'} • rec: {log.target_recording_id?.slice(0,8) || '—'}
              </div>
              {log.metadata && Object.keys(log.metadata).length > 0 && <pre className="mt-1 text-[10px] text-stone-500 whitespace-pre-wrap break-all">{JSON.stringify(log.metadata, null, 2).slice(0, 500)}</pre>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};
