/**
 * Marcadores con los que el servidor encierra cada palabra encontrada en `snippet` (ADR 0016).
 * Son caracteres de uso privado de Unicode: el servidor los quita del texto del mensaje antes de
 * resaltar, así que nadie puede escribirlos para fingir un resaltado.
 */
export const SEARCH_MARK_START = "";
export const SEARCH_MARK_END = "";

export type SnippetPiece = { text: string; hit: boolean };

/**
 * Parte un fragmento en trozos normales y resaltados. La pantalla pinta los resaltados con
 * `<mark>` como elementos de React: el texto del mensaje nunca se interpreta como HTML.
 */
export function splitSnippet(snippet: string): SnippetPiece[] {
  const pieces: SnippetPiece[] = [];
  let hit = false;
  let current = "";
  for (const ch of snippet) {
    if (ch === SEARCH_MARK_START || ch === SEARCH_MARK_END) {
      if (current) pieces.push({ text: current, hit });
      current = "";
      hit = ch === SEARCH_MARK_START;
      continue;
    }
    current += ch;
  }
  if (current) pieces.push({ text: current, hit });
  return pieces;
}
