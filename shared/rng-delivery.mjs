const id = /^[a-f0-9]{32}$/, snowflake = /^\d{17,20}$/;

/** One real feed publication/retry boundary. All transport and receipt access is supplied by its caller. */
export async function deliverRngPublication(delivery, hooks) {
  if (!id.test(delivery?.id ?? '') || !/^[A-Za-z0-9_-]{43}$/.test(delivery.claim ?? '') ||
    delivery.channelId !== hooks.channelId || delivery.operation !== 'publish' || delivery.event?.id !== delivery.id)
    throw new Error('Invalid RNG publication');
  const card = hooks.present(delivery.event, hooks.now());
  if (!card) throw new Error('Invalid RNG publication');
  let messageId = hooks.receipts.get(delivery.id);
  if (!messageId) {
    hooks.receipts.assertCapacity?.(delivery.id);
    const message = await hooks.send(card);
    if (!snowflake.test(message?.id ?? '')) throw new Error('RNG publication returned no message');
    messageId = message.id;
    try { await hooks.receipts.record(delivery.id, messageId); }
    catch (error) { await hooks.remove(messageId); throw error; }
  }
  const result = await hooks.ack({ action: 'ack', id: delivery.id, claim: delivery.claim, operation: 'publish', messageId });
  if (result.deleteRequired) {
    await hooks.remove(messageId);
    await hooks.receipts.forget?.(delivery.id);
  } else await hooks.receipts.acknowledge?.(delivery.id);
}
