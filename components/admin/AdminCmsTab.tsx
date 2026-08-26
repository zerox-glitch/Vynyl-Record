'use client';

import React, { useState } from 'react';
import { SiteSettings } from '@/types';
import { Button } from '@/components/ui/Button';
import { Save, Sparkles, Palette, HelpCircle, Plus, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';

interface AdminCmsTabProps {
  settings: SiteSettings;
  onSave: (updated: SiteSettings) => Promise<void>;
}

export const AdminCmsTab: React.FC<AdminCmsTabProps> = ({ settings, onSave }) => {
  const [formData, setFormData] = useState<SiteSettings>(settings);
  const [isSaving, setIsSaving] = useState<boolean>(false);

  const handleHeadlineChange = (val: string) => {
    setFormData((prev) => ({
      ...prev,
      hero_copy: { ...prev.hero_copy, headline: val },
    }));
  };

  const handleSubheadlineChange = (val: string) => {
    setFormData((prev) => ({
      ...prev,
      hero_copy: { ...prev.hero_copy, subheadline: val },
    }));
  };

  const handleCtaChange = (val: string) => {
    setFormData((prev) => ({
      ...prev,
      hero_copy: { ...prev.hero_copy, cta_text: val },
    }));
  };

  const handleColorChange = (key: 'primary_color' | 'bg_color' | 'accent_color', val: string) => {
    setFormData((prev) => ({
      ...prev,
      branding_theme: { ...prev.branding_theme, [key]: val },
    }));
  };

  const handleAddFaq = () => {
    setFormData((prev) => ({
      ...prev,
      faqs: [
        ...(prev.faqs || []),
        { q: 'New Frequently Asked Question', a: 'Detailed answer explanation.' },
      ],
    }));
  };

  const handleUpdateFaq = (index: number, q: string, a: string) => {
    setFormData((prev) => {
      const copy = [...(prev.faqs || [])];
      copy[index] = { q, a };
      return { ...prev, faqs: copy };
    });
  };

  const handleRemoveFaq = (index: number) => {
    setFormData((prev) => {
      const copy = [...(prev.faqs || [])];
      copy.splice(index, 1);
      return { ...prev, faqs: copy };
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setIsSaving(true);
      await onSave(formData);
      toast.success('Site settings and branding updated!');
    } catch (err: any) {
      toast.error('Failed to save settings: ' + err.message);
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-8">
      {/* Hero Section Copy CMS */}
      <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-4">
        <div className="flex items-center gap-2 border-b border-stone-800 pb-3">
          <Sparkles className="w-5 h-5 text-amber-500" />
          <h3 className="font-serif font-bold text-lg text-amber-100">
            Landing Page Hero Copy
          </h3>
        </div>

        <div className="space-y-4">
          <div>
            <label className="block text-xs font-mono text-stone-300 mb-1">
              Headline
            </label>
            <input
              type="text"
              value={formData.hero_copy.headline}
              onChange={(e) => handleHeadlineChange(e.target.value)}
              className="w-full bg-stone-950 border border-stone-700 focus:border-amber-500 rounded-xl px-4 py-2 text-sm text-stone-100 focus:outline-none"
            />
          </div>

          <div>
            <label className="block text-xs font-mono text-stone-300 mb-1">
              Subheadline / Emotional Hook
            </label>
            <textarea
              rows={3}
              value={formData.hero_copy.subheadline}
              onChange={(e) => handleSubheadlineChange(e.target.value)}
              className="w-full bg-stone-950 border border-stone-700 focus:border-amber-500 rounded-xl px-4 py-2 text-sm text-stone-100 focus:outline-none"
            />
          </div>

          <div>
            <label className="block text-xs font-mono text-stone-300 mb-1">
              Primary CTA Button Text
            </label>
            <input
              type="text"
              value={formData.hero_copy.cta_text}
              onChange={(e) => handleCtaChange(e.target.value)}
              className="w-full bg-stone-950 border border-stone-700 focus:border-amber-500 rounded-xl px-4 py-2 text-sm text-stone-100 focus:outline-none"
            />
          </div>
        </div>
      </div>

      {/* Dynamic Theme Color Picker */}
      <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-4">
        <div className="flex items-center gap-2 border-b border-stone-800 pb-3">
          <Palette className="w-5 h-5 text-amber-500" />
          <h3 className="font-serif font-bold text-lg text-amber-100">
            Branding Theme & Colors
          </h3>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <div className="p-3 bg-stone-950 rounded-2xl border border-stone-800 space-y-2">
            <span className="text-xs font-mono text-stone-300 block">Primary Amber</span>
            <div className="flex items-center gap-3">
              <input
                type="color"
                value={formData.branding_theme.primary_color}
                onChange={(e) => handleColorChange('primary_color', e.target.value)}
                className="w-10 h-10 rounded-lg cursor-pointer bg-transparent border-0"
              />
              <span className="font-mono text-xs text-amber-400">
                {formData.branding_theme.primary_color}
              </span>
            </div>
          </div>

          <div className="p-3 bg-stone-950 rounded-2xl border border-stone-800 space-y-2">
            <span className="text-xs font-mono text-stone-300 block">Deep Obsidian Base</span>
            <div className="flex items-center gap-3">
              <input
                type="color"
                value={formData.branding_theme.bg_color}
                onChange={(e) => handleColorChange('bg_color', e.target.value)}
                className="w-10 h-10 rounded-lg cursor-pointer bg-transparent border-0"
              />
              <span className="font-mono text-xs text-stone-400">
                {formData.branding_theme.bg_color}
              </span>
            </div>
          </div>

          <div className="p-3 bg-stone-950 rounded-2xl border border-stone-800 space-y-2">
            <span className="text-xs font-mono text-stone-300 block">Accent Brass / Warmth</span>
            <div className="flex items-center gap-3">
              <input
                type="color"
                value={formData.branding_theme.accent_color}
                onChange={(e) => handleColorChange('accent_color', e.target.value)}
                className="w-10 h-10 rounded-lg cursor-pointer bg-transparent border-0"
              />
              <span className="font-mono text-xs text-amber-500">
                {formData.branding_theme.accent_color}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* FAQ Management */}
      <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-4">
        <div className="flex items-center justify-between border-b border-stone-800 pb-3">
          <div className="flex items-center gap-2">
            <HelpCircle className="w-5 h-5 text-amber-500" />
            <h3 className="font-serif font-bold text-lg text-amber-100">
              Frequently Asked Questions (CMS)
            </h3>
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleAddFaq}
            leftIcon={<Plus className="w-4 h-4" />}
          >
            Add FAQ
          </Button>
        </div>

        <div className="space-y-4">
          {(formData.faqs || []).map((faq, idx) => (
            <div key={idx} className="p-4 bg-stone-950 rounded-2xl border border-stone-800 space-y-3 relative">
              <div className="flex items-center justify-between">
                <span className="text-xs font-mono text-amber-500 font-bold">
                  Question #{idx + 1}
                </span>
                <button
                  type="button"
                  onClick={() => handleRemoveFaq(idx)}
                  className="text-stone-500 hover:text-red-400 p-1 rounded"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>

              <input
                type="text"
                value={faq.q}
                onChange={(e) => handleUpdateFaq(idx, e.target.value, faq.a)}
                placeholder="Question headline"
                className="w-full bg-stone-900 border border-stone-700 rounded-xl px-3 py-1.5 text-xs text-stone-100"
              />

              <textarea
                rows={2}
                value={faq.a}
                onChange={(e) => handleUpdateFaq(idx, faq.q, e.target.value)}
                placeholder="Answer text"
                className="w-full bg-stone-900 border border-stone-700 rounded-xl px-3 py-1.5 text-xs text-stone-100"
              />
            </div>
          ))}
        </div>
      </div>

      {/* Save Button */}
      <div className="flex justify-end">
        <Button
          type="submit"
          variant="primary"
          size="lg"
          isLoading={isSaving}
          leftIcon={<Save className="w-4 h-4" />}
        >
          Save CMS Settings
        </Button>
      </div>
    </form>
  );
};
