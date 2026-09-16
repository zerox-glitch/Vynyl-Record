/**
 * ai/provider.ts
 * ----------------------------------------------------------------------------
 * AI writing-assistant provider abstraction. The studio calls
 * ``getAiProvider().generateScript(...)`` and never has to branch on which
 * upstream model is configured.
 *
 * Provider selection (env vars, in priority order):
 *   1. OPENAI_API_KEY           -> OpenAIProvider (gpt-4o-mini)
 *   2. ANTHROPIC_API_KEY       -> AnthropicProvider (claude-3-5-haiku)
 *   3. no key                   -> DisabledProvider (returns a graceful, hand-
 *                                crafted prompt scaffold so the UI never sits
 *                                on an error).
 *
 * The product layers above never needs to know which one is wired in:
 *   await provider.generateScript({ recipient, occasion, mood, userHint })
 *
 * The DisabledProvider returns a deterministic sketch ('Say something
 * that would make them laugh. Or cry. Or both. Then start talking.')
 * so the UI renders the next step instead of failing closed.
 */
import { OccasionType } from '@/types';

export type Mood = 'loved' | 'nostalgic' | 'emotional' | 'playful' | 'grateful';

export interface GenerateScriptInput {
  recipient?: string;
  occasion?: OccasionType | string;
  mood?: Mood;
  /** Free-text what-they-want-them-to-feel hint from the user. */
  userHint?: string;
  /** Max characters target for the produced draft. */
  maxLength?: number;
}

export interface GenerateScriptResult {
  /** The provider that produced this (or 'disabled'). */
  provider: 'openai' | 'anthropic' | 'disabled';
  /** The suggested script (may be human-edited before recording). */
  script: string;
  /** Optional prompt-shaped advice shown next to the script. */
  notes?: string;
}

export interface AIProvider {
  readonly providerId: 'openai' | 'anthropic' | 'disabled';
  isConfigured(): boolean;
  generateScript(input: GenerateScriptInput): Promise<GenerateScriptResult>;
}

// ============================================================================

const OPENAI_KEY = process.env.OPENAI_API_KEY || '';
const ANTHROPIC_KEY = process.env.ANTHROPIC_API_KEY || '';

/**
 * Resolve the active AI provider once and reuse. The studio reads the
 * selected provider's id (for analytics) and falls back to DisabledProvider
 * automatically when no key is configured.
 */
export function getAiProvider(): AIProvider {
  if (OPENAI_KEY && !OPENAI_KEY.includes('your-openai')) {
    return new OpenAIProvider();
  }
  if (ANTHROPIC_KEY && !ANTHROPIC_KEY.includes('your-anthropic')) {
    return new AnthropicProvider();
  }
  return new DisabledProvider();
}

// ============================================================================

function buildPrompt(input: GenerateScriptInput): string {
  const moodLines: Record<Mood, string> = {
    loved:      'They should feel loved \u2014 like the way you do when you first knew it was them.',
    nostalgic:  'They should feel nostalgic \u2014 the version of you that could not imagine this day.',
    emotional:  'They are going to cry. Say it anyway. Don\u2019t perform; mean it.',
    playful:    'They should smile, then laugh, then realise you actually meant the soft part.',
    grateful:   'They should know what they gave you, even the parts you never told them about.',
  };
  const mood = input.mood || 'loved';
  const recipient = input.recipient ? `To ${input.recipient}.` : 'A voice note \u2014 no recipient named.';
  const occasion = input.occasion ? `Occasion: ${input.occasion}.` : '';
  const userHint = input.userHint ? `The human said: "${input.userHint}".` : '';
  return [
    'Write a first-person 60-120 second voice note script. No headers.',
    'Never use the words "heart", "heartbeat", "precious", "treasure", "journey", "forever", "always remember".',
    'Plain words. Short sentences. Sentences can be fragments.',
    'If a sentence sounds like a greeting card, throw it away.',
    recipient,
    occasion,
    moodLines[mood],
    userHint,
  ].filter(Boolean).join('\n');
}

function fallbackScript(input: GenerateScriptInput): string {
  const recipient = input.recipient || 'them';
  const lower = recipient.toLowerCase();
  const opener =
    lower === 'them' ? 'Hey.'
      : lower === 'wife' || lower === 'husband' || lower === 'partner' ? 'Hey, love.'
      : lower === 'mom' || lower === 'mother' || lower === 'mum' ? 'Hey mom.'
      : lower === 'dad' || lower === 'father' || lower === 'pop' ? 'Hey dad.'
      : `Hey, ${recipient}.`;
  return [
    opener,
    'I\u2019m not writing this because something happened.',
    'I\u2019m writing this because I wanted you to know a thing, while I can still say it\u2019s true.',
    '',
    'I want you to know \u2014 at this minute, in this room \u2014 that you are exactly what I needed.',
    'Not in a big way. The way a floor is what a chair needs.',
    '',
    'So when you listen to this, hear it the way I would say it across the kitchen at 7pm: plainly.',
  ].join('\n');
}

// ============================================================================

class DisabledProvider implements AIProvider {
  readonly providerId = 'disabled' as const;
  isConfigured() { return false; }
  async generateScript(input: GenerateScriptInput): Promise<GenerateScriptResult> {
    return {
      provider: 'disabled',
      script: fallbackScript(input),
      notes: 'No AI key configured \u2014 suggesting a starting template. Edit freely before recording.',
    };
  }
}

class OpenAIProvider implements AIProvider {
  readonly providerId = 'openai' as const;
  isConfigured() { return Boolean(OPENAI_KEY); }
  async generateScript(input: GenerateScriptInput): Promise<GenerateScriptResult> {
    const prompt = buildPrompt(input);
    const max = input.maxLength ?? 600;
    try {
      const res = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${OPENAI_KEY}`,
        },
        body: JSON.stringify({
          model: 'gpt-4o-mini',
          messages: [
            {
              role: 'system',
              content:
                'You write short, plain, sincere voice-note scripts for a digital vinyl keepsake product. ' +
                'Never use greeting-card language. Never speak in the second person about the recipient\u2019s flaws. ' +
                'Never propose unethical or manipulative content. If the user asks for something you cannot honour, decline and offer a tonal reframe.',
            },
            { role: 'user', content: prompt },
          ],
          temperature: 0.85,
          max_tokens: Math.ceil(max / 3),
        }),
      });
      if (!res.ok) throw new Error(`OpenAI ${res.status}`);
      const json: any = await res.json();
      const text = String(json?.choices?.[0]?.message?.content || '').trim();
      if (!text) throw new Error('OpenAI returned empty');
      return { provider: 'openai', script: text };
    } catch (err: any) {
      // Fail soft to local fallback so the studio never breaks first paint.
      return { provider: 'openai', script: fallbackScript(input), notes: `AI failed: ${err?.message || 'unknown'}` };
    }
  }
}

class AnthropicProvider implements AIProvider {
  readonly providerId = 'anthropic' as const;
  isConfigured() { return Boolean(ANTHROPIC_KEY); }
  async generateScript(input: GenerateScriptInput): Promise<GenerateScriptResult> {
    const prompt = buildPrompt(input);
    const max = input.maxLength ?? 600;
    try {
      const res = await fetch('https://api.anthropic.com/v1/messages', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-api-key': ANTHROPIC_KEY,
          'anthropic-version': '2023-06-01',
        },
        body: JSON.stringify({
          model: 'claude-3-5-haiku-latest',
          max_tokens: Math.ceil(max / 3),
          system:
            'You write short, plain, sincere voice-note scripts for a digital vinyl keepsake product. ' +
            'Never use greeting-card language.',
          messages: [{ role: 'user', content: prompt }],
        }),
      });
      if (!res.ok) throw new Error(`Anthropic ${res.status}`);
      const json: any = await res.json();
      const text = (json?.content?.[0]?.text || '').trim();
      if (!text) throw new Error('Anthropic returned empty');
      return { provider: 'anthropic', script: text };
    } catch (err: any) {
      return { provider: 'anthropic', script: fallbackScript(input), notes: `AI failed: ${err?.message || 'unknown'}` };
    }
  }
}

// ============================================================================

/** Convenience used by the studio: "is AI enabled at all right now?" */
export function isAIConfigured(): boolean {
  return getAiProvider().isConfigured();
}
