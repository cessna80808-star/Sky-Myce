const id = /^[a-f0-9]{32}$/;
const observedRarities = new Set(['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY', 'MYTHIC', 'DIVINE', 'ULTIMATE', 'SPECIAL', 'VERY_SPECIAL', 'ADMIN']);
const itemNames = Object.freeze({ NECRON_HANDLE: "Necron's Handle", GIANTS_SWORD: "Giant's Sword", DARK_CLAYMORE: 'Dark Claymore',
  SHADOW_FURY: 'Shadow Fury', IMPLOSION_SCROLL: 'Implosion', WITHER_SHIELD_SCROLL: 'Wither Shield', SHADOW_WARP_SCROLL: 'Shadow Warp',
  WARDEN_HEART: 'Warden Heart', JUDGEMENT_CORE: 'Judgement Core', OVERFLUX_CAPACITOR: 'Overflux Capacitor' });
const unsafe = /[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/u;
function inert(value) { return value.replace(/[\\`*_~|><\[\]()]/g, '\\$&').replace(/@/g, '@\u200b').replace(/:/g, ':\u200b'); }

/** Shared production presentation; only the separately authorized tester chooses the test label. */
export function rngPresentation(event, now = Date.now(), testing = false) {
  if (!event || !id.test(event.id ?? '') || !/^[A-Z0-9_]{1,80}$/.test(event.item ?? '') ||
    !['dungeon', 'slayer', 'kuudra', 'fishing'].includes(event.activity) || !Number.isSafeInteger(event.occurredAt) ||
    event.occurredAt > now + 60000 || event.occurredAt < now - 900000 || event.unverified !== true ||
    !(event.player === 'Anonymous' || /^[A-Za-z0-9_]{1,16}$/.test(event.player ?? ''))) return null;
  if (event.rarity != null && !observedRarities.has(event.rarity) ||
    event.displayName != null && (typeof event.displayName !== 'string' || event.displayName.length < 1 || event.displayName.length > 120 ||
      event.displayName !== event.displayName.trim() || unsafe.test(event.displayName)) ||
    event.floor != null && (event.activity !== 'dungeon' || typeof event.floor !== 'string' || !/^(?:E|[FM][1-7])$/.test(event.floor))) return null;
  const display = event.displayName ?? itemNames[event.item] ?? event.item.toLowerCase().split('_').map(word => word[0].toUpperCase() + word.slice(1)).join(' ');
  const fields = [{ name: 'Activity', value: event.activity }];
  if (event.rarity != null) fields.push({ name: 'Observed rarity', value: event.rarity.toLowerCase().replace(/_/g, ' ') });
  if (event.floor != null) fields.push({ name: 'Observed floor', value: event.floor });
  fields.push({ name: 'Source', value: testing ? 'TEST fixture · no real acquisition, statistics or production publication' : 'Community submitted · unverified client acquisition report' });
  fields.push({ name: 'Item variant', value: inert(event.item) });
  return { embeds: [{ title: `${testing ? 'TEST · ' : ''}Rare drop · ${inert(display)}`, color: 0x8b61eb,
    description: testing ? 'Administrator test fixture. No genuine drop was recorded.' : `**${event.player.replace(/_/g, '\\_')}** reported a supported rare drop.`,
    fields, timestamp: new Date(event.occurredAt).toISOString(),
    footer: { text: testing ? 'Sky-Myce RNG TEST · isolated mock transport only' : 'Sky-Myce RNG · shared with consent; counts are not game drop probabilities' } }],
    allowedMentions: { parse: [], repliedUser: false } };
}
