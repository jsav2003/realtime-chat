import { type Attachment, type UploadTicket, api } from "./api";

/**
 * Los mismos topes que el servidor (`app.storage.max-size` y `max-per-message`). Son solo para
 * avisar antes de subir: quien manda es el servidor, que los vuelve a comprobar.
 */
export const MAX_FILE_BYTES = 10 * 1024 * 1024;
export const MAX_FILES_PER_MESSAGE = 4;

/** «1.5 MB», «320 KB», «12 B». */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Solo estas cuatro se muestran dentro de la página; el resto se descarga (igual que en el servidor). */
const INLINE_IMAGES = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);
export const isInlineImage = (contentType: string) => INLINE_IMAGES.has(contentType);

/** Por qué este archivo no se puede subir, o `null` si está bien. */
export function checkFile(file: { size: number }): string | null {
  if (file.size === 0) return "El archivo está vacío";
  if (file.size > MAX_FILE_BYTES) return `Pesa más de ${formatBytes(MAX_FILE_BYTES)}`;
  return null;
}

/**
 * Sube el archivo directo a S3 con el PUT firmado. Es un `XMLHttpRequest` y no un `fetch` porque
 * `fetch` no informa del progreso de la subida. El navegador pone solo el `Content-Length`; los
 * demás encabezados firmados (el tipo) se mandan tal cual los dio el servidor.
 */
export function putToStorage(
  ticket: UploadTicket,
  file: File,
  onProgress: (fraction: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", ticket.uploadUrl);
    for (const [name, value] of Object.entries(ticket.headers)) {
      xhr.setRequestHeader(name, value);
    }
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded / e.total);
    };
    xhr.onload = () =>
      xhr.status >= 200 && xhr.status < 300
        ? resolve()
        : reject(new Error(`El almacén respondió ${xhr.status}`));
    xhr.onerror = () => reject(new Error("No se pudo subir el archivo"));
    xhr.send(file);
  });
}

/** Los tres pasos de un adjunto antes de enviar el mensaje: pedir la URL, subir y confirmar. */
export async function uploadFile(
  channelId: number,
  file: File,
  onProgress: (fraction: number) => void,
): Promise<Attachment> {
  const ticket = await api.requestUpload(channelId, file);
  await putToStorage(ticket, file, onProgress);
  return api.completeUpload(ticket.id);
}
