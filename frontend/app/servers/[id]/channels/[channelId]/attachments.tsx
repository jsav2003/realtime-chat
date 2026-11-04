"use client";

import { useCallback, useEffect, useState } from "react";
import { type Attachment, api } from "@/lib/api";
import { formatBytes, isInlineImage } from "@/lib/uploads";

/**
 * Los adjuntos de un mensaje. Las imágenes se ven dentro del chat; lo demás es un botón que baja el
 * archivo. En ningún caso se guarda una URL: cada una se pide justo cuando hace falta, porque dura
 * un minuto y el servidor decide con el acceso de ese momento (ADR 0014).
 */
export function AttachmentList({ attachments }: { attachments: Attachment[] }) {
  if (attachments.length === 0) return null;
  return (
    <ul
      data-testid="attachments"
      style={{ listStyle: "none", padding: 0, margin: "0.25rem 0 0" }}
    >
      {attachments.map((a) => (
        <li key={a.id} style={{ margin: "0.25rem 0" }}>
          {isInlineImage(a.contentType) ? (
            <InlineImage attachment={a} />
          ) : (
            <FileLink attachment={a} />
          )}
        </li>
      ))}
    </ul>
  );
}

function InlineImage({ attachment }: { attachment: Attachment }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  /** Cuántas veces se renovó la URL tras un error; una vez basta (la anterior pudo vencer). */
  const [renewals, setRenewals] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .getAttachmentUrl(attachment.id)
      .then((r) => !cancelled && setSrc(r.url))
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [attachment.id, renewals]);

  if (failed) return <FileLink attachment={attachment} />;
  if (!src)
    return <small style={{ opacity: 0.6 }}>Cargando {attachment.filename}…</small>;
  return (
    // eslint-disable-next-line @next/next/no-img-element -- la URL firmada es de S3 y dura un minuto
    <img
      src={src}
      alt={attachment.filename}
      loading="lazy"
      onError={() => (renewals < 1 ? setRenewals((n) => n + 1) : setFailed(true))}
      style={{ maxWidth: "100%", maxHeight: 320, borderRadius: 4 }}
    />
  );
}

function FileLink({ attachment }: { attachment: Attachment }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);

  const download = useCallback(async () => {
    setBusy(true);
    setError(false);
    try {
      const { url } = await api.getAttachmentUrl(attachment.id);
      // Un enlace temporal y no `window.open`: el pedido es asíncrono y el navegador bloquearía la
      // ventana. S3 responde con `Content-Disposition: attachment`, así que no sale de la página.
      const a = document.createElement("a");
      a.href = url;
      a.download = attachment.filename;
      a.rel = "noopener";
      document.body.appendChild(a);
      a.click();
      a.remove();
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  }, [attachment.id, attachment.filename]);

  return (
    <>
      <button
        type="button"
        onClick={download}
        disabled={busy}
        data-attachment-id={attachment.id}
      >
        📎 {attachment.filename} <small>({formatBytes(attachment.size)})</small>
      </button>
      {error && (
        <small role="alert" style={{ marginLeft: "0.5rem" }}>
          No se pudo bajar el archivo
        </small>
      )}
    </>
  );
}
