import { ApiError, api, type Message } from "./api";

/**
 * Une dos listas de mensajes de un mismo canal. El seq identifica al mensaje dentro del canal,
 * así que un seq repetido se descarta y el resultado queda ordenado por seq, nunca por fecha.
 */
export function mergeBySeq(current: Message[], incoming: Message[]): Message[] {
  const bySeq = new Map<number, Message>();
  for (const m of current) bySeq.set(m.seq, m);
  for (const m of incoming) if (!bySeq.has(m.seq)) bySeq.set(m.seq, m);
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
}

/** El último seq que el cliente tiene, o 0 si no tiene ninguno. Puede haber huecos por debajo. */
export const lastSeqOf = (messages: Message[]) =>
  messages.length === 0 ? 0 : messages[messages.length - 1].seq;

/**
 * El final de la racha sin huecos que empieza en el primer mensaje que se tiene, o 0 si no hay
 * ninguno. Es el cursor correcto para resincronizar: pedir "lo posterior al último seq" dejaría un
 * hueco para siempre si un mensaje en vivo llegó antes que los anteriores (por ejemplo, justo al
 * reconectar) y el pedido que lo iba a llenar falló. `messages` va ordenado por seq.
 */
export function contiguousSeqOf(messages: Message[]): number {
  if (messages.length === 0) return 0;
  let end = messages[0].seq;
  for (let i = 1; i < messages.length; i++) {
    if (messages[i].seq !== end + 1) break;
    end = messages[i].seq;
  }
  return end;
}

const RETRY_DELAYS_MS = [500, 1500];

/**
 * Cuántas veces seguidas se espera un 429 antes de rendirse y mostrar el error. Con el límite por
 * defecto (30 por minuto) cada espera es de unos 2 s, así que alcanza para más de un minuto.
 */
const MAX_RATE_LIMIT_WAITS = 30;

/** Aunque el servidor pida esperar más, no se deja la pantalla colgada tanto tiempo. */
const MAX_RATE_LIMIT_WAIT_S = 60;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Envía un mensaje y, si se pierde la red o el servidor falla (5xx), lo reintenta con el mismo
 * clientMsgId. Si el primer intento sí se guardó y solo se perdió la respuesta, el reintento
 * recibe el mismo mensaje en vez de crear uno nuevo. Un 4xx no se reintenta: no va a cambiar.
 *
 * La excepción es el 429 (límite de mensajes, ADR 0009): no es una falla sino «espera y vuelve».
 * Se espera lo que pide `Retry-After`, se avisa con `onRateLimited(segundos)` para que la pantalla
 * lo muestre, y se reintenta con el mismo clientMsgId sin gastar los reintentos de arriba.
 */
export async function sendWithRetry(
  channelId: number,
  clientMsgId: string,
  body: string,
  onRateLimited?: (waitSeconds: number) => void,
): Promise<Message> {
  let failures = 0;
  let rateLimited = 0;
  for (;;) {
    try {
      return await api.sendMessage(channelId, clientMsgId, body);
    } catch (err) {
      if (err instanceof ApiError && err.body.status === 429) {
        if (++rateLimited > MAX_RATE_LIMIT_WAITS) throw err;
        const wait = Math.min(Math.max(err.retryAfterSeconds, 1), MAX_RATE_LIMIT_WAIT_S);
        onRateLimited?.(wait);
        await sleep(wait * 1000);
        continue;
      }
      const retryable = !(err instanceof ApiError) || err.body.status >= 500;
      if (!retryable || failures >= RETRY_DELAYS_MS.length) throw err;
      await sleep(RETRY_DELAYS_MS[failures++]);
    }
  }
}
