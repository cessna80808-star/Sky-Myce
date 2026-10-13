const snowflake = /^\d{17,20}$/;
const rooms = new Set(['friends', 'testing', 'global-chat']);

/** Bounded in-memory routing for a create that has not yet crossed the authenticated bridge. */
export class PendingDiscordCreates {
  constructor(maximum = 128, attempts = 4) {
    if (!Number.isSafeInteger(maximum) || maximum < 1 || maximum > 128 ||
        !Number.isSafeInteger(attempts) || attempts < 1 || attempts > 8) throw new Error('Invalid pending-create bound');
    this.maximum = maximum; this.attempts = attempts; this.entries = new Map();
  }
  begin(id, room, channelId) {
    if (!snowflake.test(id ?? '') || !snowflake.test(channelId ?? '') || !rooms.has(room) ||
        this.entries.has(id) || this.entries.size >= this.maximum) return null;
    const state = { id, room, channelId, revision: 0, deleted: false, status: 'pending' };
    this.entries.set(id, state); return state;
  }
  observe(id, room, channelId, event) {
    const state = this.entries.get(id);
    if (!state || state.room !== room || state.channelId !== channelId) return null;
    if (state.status === 'pending') {
      if (event === 'MESSAGE_UPDATE') state.revision++;
      if (event === 'MESSAGE_DELETE') { state.revision++; state.deleted = true; }
    }
    return state;
  }
  current(state) { return this.entries.get(state.id) === state && state.status === 'pending' && !state.deleted; }
  async publish(state, { resolve, prepare, send }) {
    try {
      for (let attempt = 0; attempt < this.attempts && this.current(state); attempt++) {
        const revision = state.revision;
        const message = await resolve(revision > 0);
        if (!this.current(state)) return false;
        if (revision !== state.revision) continue;
        const prepared = await prepare(message);
        if (!this.current(state)) return false;
        if (revision !== state.revision) continue;
        if (!prepared) return false;
        // No asynchronous boundary between the last update/deletion check and publication.
        state.status = 'published';
        if (send(prepared)) return true;
        state.status = 'cancelled'; return false;
      }
      return false; // A continuously changing source cannot publish an unverified stale snapshot.
    } finally { if (state.status === 'pending') state.status = 'cancelled'; }
  }
  finish(state) {
    if (state.status === 'pending') state.status = 'cancelled';
    if (this.entries.get(state.id) === state) this.entries.delete(state.id);
  }
  clear() {
    for (const state of this.entries.values()) if (state.status === 'pending') state.status = 'cancelled';
    this.entries.clear();
  }
}
