import type { Message } from "./api";

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

/** El último seq que el cliente tiene, o 0 si no tiene ninguno. */
export const lastSeqOf = (messages: Message[]) =>
  messages.length === 0 ? 0 : messages[messages.length - 1].seq;
