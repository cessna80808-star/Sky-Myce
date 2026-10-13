/** Existing feature fixtures isolate their own limits; traffic checks use production budgets. */
export function configureTrafficLimits(options, { requests = 1000, service = 1000, downloads = 1000 } = {}) {
  for (const [index, name, limit] of [[0, 'REQUEST_LIMIT', requests], [1, 'SERVICE_LIMIT', service], [2, 'DOWNLOAD_LIMIT', downloads]]) {
    options.workers[0].config.env[name] = { type: 'rate-limit', namespace: String(7112050 + index), simple: { limit, period: 60 } };
  }
}
