'use client';

import React, { useState } from 'react';
import { Profile } from '@/types';
import { Button } from '@/components/ui/Button';
import { Users, Shield, Crown, Trash2, Search } from 'lucide-react';
import toast from 'react-hot-toast';

interface AdminUsersTabProps {
  users: Profile[];
  onUpdateUser: (id: string, updates: Partial<Profile>) => Promise<void>;
  onDeleteUser: (id: string) => Promise<void>;
}

export const AdminUsersTab: React.FC<AdminUsersTabProps> = ({
  users,
  onUpdateUser,
  onDeleteUser,
}) => {
  const [searchQuery, setSearchQuery] = useState<string>('');

  const filteredUsers = users.filter((u) => {
    const q = searchQuery.toLowerCase();
    return (
      u.email.toLowerCase().includes(q) ||
      (u.full_name && u.full_name.toLowerCase().includes(q))
    );
  });

  const handleToggleAdmin = async (user: Profile) => {
    const nextRole = user.role === 'admin' ? 'user' : 'admin';
    try {
      await onUpdateUser(user.id, { role: nextRole });
      toast.success(`User role updated to ${nextRole}`);
    } catch {
      toast.error('Failed to update role');
    }
  };

  const handleTogglePremium = async (user: Profile) => {
    const nextPrem = !user.is_premium;
    try {
      await onUpdateUser(user.id, { is_premium: nextPrem });
      toast.success(`Premium status toggled to ${nextPrem ? 'Active' : 'Inactive'}`);
    } catch {
      toast.error('Failed to toggle premium');
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to delete this user account?')) return;
    try {
      await onDeleteUser(id);
      toast.success('User account removed');
    } catch {
      toast.error('Failed to delete user');
    }
  };

  return (
    <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-6 shadow-xl">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-stone-800 pb-4">
        <div>
          <h3 className="font-serif font-bold text-lg text-amber-100 flex items-center gap-2">
            <Users className="w-5 h-5 text-amber-500" />
            <span>User & Account Management ({users.length})</span>
          </h3>
          <p className="text-xs text-stone-400">
            Inspect customer accounts, toggle admin privileges, and grant Gold Master access.
          </p>
        </div>

        {/* Search Input */}
        <div className="relative min-w-[240px]">
          <Search className="w-4 h-4 text-stone-500 absolute left-3 top-2.5" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search email or name..."
            className="w-full bg-stone-950 border border-stone-700 rounded-xl pl-9 pr-3 py-1.5 text-xs text-stone-100 focus:outline-none focus:border-amber-500"
          />
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs text-stone-300">
          <thead className="bg-stone-950 text-stone-400 font-mono uppercase tracking-wider border-b border-stone-800">
            <tr>
              <th className="p-3">User / Email</th>
              <th className="p-3">Role</th>
              <th className="p-3">Membership</th>
              <th className="p-3">Recordings</th>
              <th className="p-3">Joined Date</th>
              <th className="p-3 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-stone-800/60">
            {filteredUsers.map((user) => (
              <tr key={user.id} className="hover:bg-stone-950/50 transition-colors">
                <td className="p-3">
                  <div className="font-semibold text-stone-100">{user.full_name || 'Anonymous Creator'}</div>
                  <div className="font-mono text-stone-400 text-[11px]">{user.email}</div>
                </td>
                <td className="p-3">
                  <button
                    onClick={() => handleToggleAdmin(user)}
                    className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full font-mono text-[10px] uppercase font-bold border transition-colors ${
                      user.role === 'admin'
                        ? 'bg-amber-950/60 border-amber-500 text-amber-300'
                        : 'bg-stone-800 border-stone-700 text-stone-400'
                    }`}
                  >
                    <Shield className="w-3 h-3" />
                    <span>{user.role}</span>
                  </button>
                </td>
                <td className="p-3">
                  <button
                    onClick={() => handleTogglePremium(user)}
                    className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full font-mono text-[10px] font-bold border transition-colors ${
                      user.is_premium
                        ? 'bg-amber-500/20 border-amber-400 text-amber-300'
                        : 'bg-stone-800 border-stone-700 text-stone-400'
                    }`}
                  >
                    <Crown className="w-3 h-3" />
                    <span>{user.is_premium ? 'Gold Master' : 'Free Tier'}</span>
                  </button>
                </td>
                <td className="p-3 font-mono">{user.recording_count || 1} Wax Notes</td>
                <td className="p-3 font-mono text-stone-400">
                  {new Date(user.created_at).toLocaleDateString()}
                </td>
                <td className="p-3 text-right">
                  <button
                    onClick={() => handleDelete(user.id)}
                    className="p-1.5 rounded-lg text-stone-500 hover:text-red-400 hover:bg-stone-800 transition-colors"
                    title="Delete Account"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};
