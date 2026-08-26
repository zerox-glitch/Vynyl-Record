'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Disc3, Mic, Sparkles, Shield, Menu, X, Music } from 'lucide-react';
import { clsx } from 'clsx';
import { Button } from './Button';

export const Navbar: React.FC = () => {
  const pathname = usePathname();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const navLinks = [
    { href: '/studio', label: 'Sender Studio', icon: <Mic className="w-4 h-4 text-amber-500" /> },
    { href: '/#experience', label: 'Sound Lab', icon: <Music className="w-4 h-4 text-amber-500" /> },
    { href: '/#memories', label: 'Master Vault', icon: <Sparkles className="w-4 h-4 text-amber-500" /> },
    { href: '/#pricing', label: 'Pricing Plans', icon: null },
    { href: '/admin', label: 'Admin Ops', icon: <Shield className="w-4 h-4 text-amber-500" /> },
  ];

  return (
    <header className="sticky top-0 z-40 w-full border-b border-stone-800/80 bg-stone-950/80 backdrop-blur-xl transition-all duration-200">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-20 flex items-center justify-between">
        {/* Brand Logo */}
        <Link href="/" className="flex items-center gap-3 group">
          <div className="relative w-11 h-11 rounded-full bg-gradient-to-br from-amber-600 via-amber-800 to-stone-900 p-0.5 shadow-lg shadow-amber-950/50 group-hover:scale-105 transition-transform duration-300">
            <div className="w-full h-full rounded-full bg-stone-950 flex items-center justify-center border border-amber-500/30">
              <Disc3 className="w-6 h-6 text-amber-400 group-hover:rotate-180 transition-transform duration-700 ease-in-out" />
            </div>
            <div className="absolute inset-0 rounded-full bg-amber-500/20 blur-sm group-hover:blur-md transition-all pointer-events-none" />
          </div>
          <div className="flex flex-col">
            <span className="font-serif text-lg sm:text-xl font-bold tracking-tight text-stone-100 group-hover:text-amber-300 transition-colors">
              Vinyl Voice Notes
            </span>
            <span className="text-[10px] tracking-widest uppercase text-amber-500/80 font-mono">
              Digital Wax Preserver
            </span>
          </div>
        </Link>

        {/* Desktop Navigation */}
        <nav className="hidden md:flex items-center gap-1 lg:gap-2">
          {navLinks.map((link) => {
            const isActive = pathname === link.href;
            return (
              <Link
                key={link.href}
                href={link.href}
                className={clsx(
                  'flex items-center gap-2 px-3.5 py-2 rounded-xl text-sm font-medium transition-all duration-200',
                  isActive
                    ? 'bg-amber-600/15 text-amber-300 border border-amber-500/30 shadow-inner shadow-amber-950/40'
                    : 'text-stone-300 hover:text-amber-200 hover:bg-stone-900/60'
                )}
              >
                {link.icon}
                <span>{link.label}</span>
              </Link>
            );
          })}
        </nav>

        {/* Header Right Actions */}
        <div className="hidden sm:flex items-center gap-3">
          <Link href="/studio">
            <Button
              variant="primary"
              size="md"
              leftIcon={<Mic className="w-4 h-4" />}
            >
              Record Wax Note
            </Button>
          </Link>
        </div>

        {/* Mobile Hamburger Button */}
        <div className="flex md:hidden items-center gap-2">
          <Link href="/studio">
            <Button variant="primary" size="sm" leftIcon={<Mic className="w-3.5 h-3.5" />}>
              Record
            </Button>
          </Link>
          <button
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            className="p-2 rounded-xl text-stone-300 hover:text-amber-400 hover:bg-stone-900 transition-colors"
            aria-label="Toggle menu"
          >
            {mobileMenuOpen ? <X className="w-6 h-6" /> : <Menu className="w-6 h-6" />}
          </button>
        </div>
      </div>

      {/* Mobile Menu Dropdown */}
      {mobileMenuOpen && (
        <div className="md:hidden border-b border-stone-800 bg-stone-950/95 backdrop-blur-2xl px-4 pt-2 pb-6 space-y-2">
          {navLinks.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              onClick={() => setMobileMenuOpen(false)}
              className="flex items-center gap-3 px-4 py-3 rounded-xl text-sm font-medium text-stone-200 hover:bg-stone-900 hover:text-amber-300"
            >
              {link.icon}
              <span>{link.label}</span>
            </Link>
          ))}
        </div>
      )}
    </header>
  );
};
