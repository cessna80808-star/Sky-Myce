const id = /^[a-f0-9]{32}$/;
const observedRarities = new Set(['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY', 'MYTHIC', 'DIVINE', 'ULTIMATE', 'SPECIAL', 'VERY_SPECIAL', 'ADMIN']);
const itemNames = Object.freeze({ NECRON_HANDLE: "Necron's Handle", GIANTS_SWORD: "Giant's Sword", DARK_CLAYMORE: 'Dark Claymore',
  SHADOW_FURY: 'Shadow Fury', IMPLOSION_SCROLL: 'Implosion', WITHER_SHIELD_SCROLL: 'Wither Shield', SHADOW_WARP_SCROLL: 'Shadow Warp',
  WARDEN_HEART: 'Warden Heart', JUDGEMENT_CORE: 'Judgement Core', OVERFLUX_CAPACITOR: 'Overflux Capacitor' });
// Exact artwork from the wiki's pageimages metadata, verified as PNGs; unknown items share one fallback.
const artwork = Object.freeze({ NECRON_HANDLE: 'Enchanted_Stick.png?cb423', GIANTS_SWORD: 'Giant%27s_Sword.png?387f3',
  DARK_CLAYMORE: 'Dark_Claymore.png?04287', SHADOW_FURY: 'Shadow_Fury.png?a7b06', WARDEN_HEART: 'Warden_Heart.png?ffb79',
  JUDGEMENT_CORE: 'Judgement_Core.png?b5766', OVERFLUX_CAPACITOR: 'Nether_Quartz.png?8e779' });
const unsafe = /[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/u;
function inert(value) { return value.replace(/[\\`*_~|><\[\]()]/g, '\\$&').replace(/@/g, '@\u200b').replace(/:/g, ':\u200b'); }

/** Shared production presentation; only the separately authorized tester chooses the test label. */
export function rngPresentation(event, now = Date.now(), testing = false) {
  return present(event, now, testing, 900000);
}

/** Read-only carousel cards may show retained reports; publication still expires after fifteen minutes. */
export function rngRecentPresentation(event, now = Date.now()) {
  return present(event, now, false, 604800000);
}

function present(event, now, testing, maximumAge) {
  if (!event || !id.test(event.id ?? '') || !/^[A-Z0-9_]{1,80}$/.test(event.item ?? '') ||
    !['dungeon', 'slayer', 'kuudra', 'fishing'].includes(event.activity) || !Number.isSafeInteger(event.occurredAt) ||
    event.occurredAt > now + 60000 || event.occurredAt < now - maximumAge || event.unverified !== true ||
    !(event.player === 'Anonymous' || /^[A-Za-z0-9_]{1,16}$/.test(event.player ?? ''))) return null;
  if (event.rarity != null && !observedRarities.has(event.rarity) ||
    event.displayName != null && (typeof event.displayName !== 'string' || event.displayName.length < 1 || event.displayName.length > 120 ||
      event.displayName !== event.displayName.trim() || unsafe.test(event.displayName)) ||
    event.floor != null && (event.activity !== 'dungeon' || typeof event.floor !== 'string' || !/^(?:E|[FM][1-7])$/.test(event.floor))) return null;
  const display = event.displayName ?? itemNames[event.item] ?? event.item.toLowerCase().split('_').map(word => word[0].toUpperCase() + word.slice(1)).join(' ');
  const fields = [{ name: 'Activity', value: event.activity[0].toUpperCase() + event.activity.slice(1), inline: true }];
  if (event.rarity != null) fields.push({ name: 'Observed rarity', value: event.rarity.toLowerCase().replace(/_/g, ' '), inline: true });
  if (event.floor != null) fields.push({ name: 'Observed floor', value: event.floor, inline: true });
  fields.push({ name: 'Source', value: testing ? 'TEST fixture · no real acquisition, statistics or production publication' : 'Community submitted · unverified client acquisition report' });
  return { embeds: [{ title: `${testing ? 'TEST · ' : ''}${inert(display)}`, color: event.activity === 'dungeon' ? 0x8b61eb : 0x48cdd5,
    description: testing ? 'Administrator test fixture. No genuine drop was recorded.' : `**${event.player.replace(/_/g, '\\_')}** reported a supported rare drop.`,
    fields, thumbnail: { url: `https://hypixelskyblock.minecraft.wiki/images/${artwork[event.item] ?? 'Enchanted_Diamond.png?6a085'}` },
    timestamp: new Date(event.occurredAt).toISOString(),
    footer: { text: testing ? 'Sky-Myce RNG TEST · isolated mock transport only' : 'Sky-Myce RNG · community reported, unverified · artwork: SkyBlock Wiki' } }],
    ...(testing ? {} : { components: [{ type: 1, components: [{ type: 2, style: 2, label: 'Browse recent drops', custom_id: 'rng:browse:latest' }] }] }),
    allowedMentions: { parse: [], repliedUser: false } };
}
