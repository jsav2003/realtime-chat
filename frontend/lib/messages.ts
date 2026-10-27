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
 * Envía un mensaje y, si se pierde la red o el servidor falla (5xx), lo reintenta con el mismo
 * clientMsgId. Si el primer intento sí se guardó y solo se perdió la respuesta, el reintento
 * recibe el mismo mensaje en vez de crear uno nuevo. Un 4xx no se reintenta: no va a cambiar.
 */
export async function sendWithRetry(
  channelId: number,
  clientMsgId: string,
  body: string,
): Promise<Message> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await api.sendMessage(channelId, clientMsgId, body);
    } catch (err) {
      const retryable = !(err instanceof ApiError) || err.body.status >= 500;
      if (!retryable || attempt >= RETRY_DELAYS_MS.length) throw err;
      await new Promise((r) => setTimeout(r, RETRY_DELAYS_MS[attempt]));
    }
  }
}
