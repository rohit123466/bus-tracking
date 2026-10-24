// Turns an axios/fetch failure into something a page can act on. Pure so it can be unit-tested.

export const isAbort = err =>
  err?.name === 'CanceledError' || err?.name === 'AbortError' || err?.code === 'ERR_CANCELED';

/**
 * { kind, status, message, retryable }. Kinds: aborted, offline, timeout, unreachable (no response
 * at all: server down, DNS, CORS), starting (gateway errors while the host boots or redeploys),
 * not-found, bad-request, server.
 */
export function classifyError(err, online = typeof navigator === 'undefined' ? true : navigator.onLine !== false) {
  if (isAbort(err)) return { kind: 'aborted', message: 'Request cancelled.', retryable: false };
  if (!online) return { kind: 'offline', message: 'You are offline. Reconnect to load live data.', retryable: true };
  if (err?.code === 'ECONNABORTED' || err?.code === 'ETIMEDOUT')
    return { kind: 'timeout', message: 'The server took too long to answer. It may be starting up.', retryable: true };
  const status = err?.response?.status;
  const serverMessage = err?.response?.data?.message;
  if (!status) return { kind: 'unreachable', message: "Can't reach the server right now.", retryable: true };
  if (status === 502 || status === 503 || status === 504)
    return { kind: 'starting', status, message: 'The server is starting up. Retrying shortly.', retryable: true };
  if (status === 404) return { kind: 'not-found', status, message: serverMessage || 'Not found.', retryable: false };
  if (status >= 400 && status < 500)
    return { kind: 'bad-request', status, message: serverMessage || 'The request was rejected.', retryable: false };
  return { kind: 'server', status, message: 'The server had a problem answering. Retrying shortly.', retryable: true };
}
