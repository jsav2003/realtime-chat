import type { Piece } from "@/lib/mentions";

/**
 * El texto de un mensaje con las menciones resaltadas. Las tuyas van más marcadas que las de otros.
 * Cada trozo se pinta como texto de React, nunca como HTML: el cuerpo de un mensaje es de otra
 * persona y no se interpreta.
 */
export function MessageBody({ pieces }: { pieces: Piece[] }) {
  return (
    <>
      {pieces.map((p, i) =>
        p.kind === "text" ? (
          <span key={i}>{p.text}</span>
        ) : (
          <strong
            key={i}
            data-mention={p.me ? "me" : "other"}
            style={{
              background: p.me ? "#ffd54a66" : "#8882",
              borderRadius: 3,
              padding: "0 0.15rem",
            }}
          >
            {p.text}
          </strong>
        ),
      )}
    </>
  );
}
