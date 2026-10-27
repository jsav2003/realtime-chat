import { Client } from "@stomp/stompjs";
import { getToken, type Message } from "./api";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "ws://localhost:8080/ws";

/** Segundos entre intentos de reconexión. Corto a propósito: al volver, se resincroniza por seq. */
const RECONNECT_DELAY_MS = 2000;

export type ConnectionState = "connecting" | "live" | "reconnecting" | "closed";

export type ChannelSocketHandlers = {
  /** Llegó un mensaje en vivo. Puede venir repetido o con un hueco: lo resuelve quien lo reciba. */
  onMessage: (message: Message) => void;
  /**
   * El servidor confirmó la suscripción (RECEIPT), la primera vez y después de cada reconexión.
   * Es el momento de pedir por HTTP lo que se pudo perder: antes de esto no hay garantía de
   * estar recibiendo, y pedirlo antes dejaría un hueco entre el historial y lo vivo.
   */
  onSubscribed: () => void;
  onState: (state: ConnectionState) => void;
  /**
   * El servidor rechazó la conexión o la suscripción (frame ERROR). 401: el token no vale;
   * 404: el canal no existe o ya no se puede ver (por ejemplo, tras una expulsión). No se
   * reintenta: seguirá igual.
   */
  onRejected: (status: number, message: string) => void;
};

/** "404: Canal no encontrado" → { status: 404, message: "Canal no encontrado" }. */
export function parseErrorHeader(header: string | undefined): {
  status: number;
  message: string;
} {
  const match = /^(\d{3}):\s*(.*)$/.exec(header ?? "");
  return match
    ? { status: Number(match[1]), message: match[2] }
    : { status: 0, message: header ?? "Error de conexión" };
}

/**
 * Abre un WebSocket STOMP y se suscribe a `/topic/channel.{id}`. El JWT va en el frame CONNECT
 * (el navegador no puede ponerlo en el handshake). Reconecta solo; en cada reconexión vuelve a
 * leer el token y a suscribirse. Devuelve la función que lo cierra.
 */
export function openChannelSocket(
  channelId: number,
  handlers: ChannelSocketHandlers,
): () => void {
  let closedByUs = false;
  let subscriptions = 0;

  const client = new Client({
    brokerURL: WS_URL,
    reconnectDelay: RECONNECT_DELAY_MS,
    beforeConnect: () => {
      client.connectHeaders = { Authorization: `Bearer ${getToken() ?? ""}` };
    },
    onConnect: () => {
      const receipt = `channel-${channelId}-${++subscriptions}`;
      client.watchForReceipt(receipt, () => {
        handlers.onState("live");
        handlers.onSubscribed();
      });
      client.subscribe(
        `/topic/channel.${channelId}`,
        (frame) => handlers.onMessage(JSON.parse(frame.body) as Message),
        { receipt },
      );
    },
    onStompError: (frame) => {
      const { status, message } = parseErrorHeader(frame.headers["message"]);
      if (status === 401 || status === 403 || status === 404) {
        closedByUs = true;
        void client.deactivate();
        handlers.onState("closed");
        handlers.onRejected(status, message);
      }
    },
    onWebSocketClose: () => {
      if (!closedByUs) handlers.onState("reconnecting");
    },
  });

  handlers.onState("connecting");
  client.activate();

  return () => {
    closedByUs = true;
    void client.deactivate();
  };
}
