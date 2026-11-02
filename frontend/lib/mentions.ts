/**
 * Qué cuenta como mención. Es la misma regla que `MentionParser` en el backend: un `@` que no va
 * pegado a una letra, un número, un guion bajo ni otro `@` (así `ana@correo.com` no menciona a
 * nadie), seguido de 3 a 20 letras ASCII, números o guion bajo, que no sigan con más caracteres de
 * palabra. Si se cambia una, hay que cambiar la otra: los vectores de prueba están en
 * `backend/.../mention/MentionParserTest.java`.
 */
const MENTION = /(?<![\p{L}\p{N}_@])@([A-Za-z0-9_]{3,20})(?![\p{L}\p{N}_])/gu;

export type Piece =
  | { kind: "text"; text: string }
  | { kind: "mention"; text: string; me: boolean };

/**
 * Parte un mensaje en trozos para resaltar las menciones. Solo se resalta a quien es miembro del
 * servidor (`usernames`, en minúsculas): un «@nadie» se queda como texto. Esto es solo la
 * interfaz; a quién se notifica lo decide el servidor, no esta función.
 */
export function splitMentions(
  body: string,
  usernames: ReadonlySet<string>,
  myUsername: string | null,
): Piece[] {
  const mine = myUsername?.toLowerCase() ?? null;
  const pieces: Piece[] = [];
  let last = 0;
  for (const match of body.matchAll(MENTION)) {
    const handle = match[1].toLowerCase();
    if (!usernames.has(handle)) continue;
    const start = match.index ?? 0;
    if (start > last) pieces.push({ kind: "text", text: body.slice(last, start) });
    pieces.push({ kind: "mention", text: match[0], me: handle === mine });
    last = start + match[0].length;
  }
  if (last < body.length) pieces.push({ kind: "text", text: body.slice(last) });
  return pieces;
}

/** `true` si el mensaje te menciona a ti. */
export function mentionsMe(pieces: Piece[]): boolean {
  return pieces.some((p) => p.kind === "mention" && p.me);
}
