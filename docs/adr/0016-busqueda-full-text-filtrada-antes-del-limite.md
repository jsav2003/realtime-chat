# ADR 0016 · Búsqueda: `tsvector` generado con índice GIN, filtrada por acceso antes del límite

**Estado:** aceptada · 2026-11-25 · Complementa DESIGN.md §5 (Búsqueda) y §7

## Contexto
DESIGN.md §9 cierra el hito 8 con «full-text de Postgres», sin más detalle. Una búsqueda en un chat
multi-servidor tiene un problema que no es de texto sino de **autorización**: una página de
resultados no puede dejar ver, ni insinuar, un mensaje de un canal que la persona no puede leer.

La versión ingenua busca en el servidor entero, toma los primeros N y después descarta en Java lo
que no se puede ver. Tiene dos fallas:

- **La página sale corta, o vacía,** cuando lo más nuevo que coincide está en un privado ajeno. Con
  `limit=1` y el privado más nuevo, member recibe cero resultados y `hasMore = true`: acaba de
  saber que en lo oculto hay algo con esa palabra.
- **Copia la regla.** Si el filtro es una consulta propia (`kind = 'text' OR …`), tarde o temprano
  deja de coincidir con `AccessPolicy` (el caso del admin que ve los privados, por ejemplo).

## Decisión
1. **Columna generada `messages.search tsvector GENERATED ALWAYS AS (to_tsvector('spanish', body))
   STORED`** con un índice GIN (migración V10). Postgres la calcula en cada `INSERT`: el envío no
   cambia y no puede olvidarse de llenarla. El diccionario `spanish` deriva las palabras («mensajes»
   encuentra «mensaje») y descarta las vacías («de», «la»).
2. **`GET /servers/{id}/search?q=…&channel_id=&before_id=&limit=`.**
   - `q` obligatorio, de 1 a 200 caracteres después de quitar espacios; `limit` de 1 a 50 (20 por
     defecto); `before_id >= 1`. Fuera de eso, 400.
   - Los canales salen de **`AccessPolicy.readableChannels`** (ADR 0015): 404 a extraños y
     expulsados. Con `channel_id`, ese canal tiene que estar en esa lista; uno de otro servidor, un
     privado ajeno y uno que no existe dan el mismo 404.
3. **El filtro de canales va en el `WHERE`, antes del `LIMIT`:**
   `where m.channel_id in (:visibles) and m.search @@ websearch_to_tsquery('spanish', :q) and m.id <
   :before order by m.id desc limit :limit + 1`. Una página siempre está llena si hay con qué
   llenarla, y `hasMore` solo cuenta lo que se puede ver.
4. **Los más nuevos primero, con cursor por id.** La página siguiente pide `before_id` = el último
   `messageId`. Sin `OFFSET`: no se repite ni se salta nada si llegan mensajes mientras se pagina.
   No se ordena por relevancia (`ts_rank`), porque en un chat lo reciente suele ser lo buscado y
   porque un orden por puntaje no da un cursor estable.
5. **`websearch_to_tsquery`** en vez de `to_tsquery`: acepta cualquier cosa (comillas sin cerrar,
   `&`, `!`, `'`) sin error de sintaxis, y entiende `"frase exacta"`, `or` y `-palabra`. Una consulta
   de solo palabras vacías queda vacía y no coincide con nada.
6. **El fragmento resaltado se arma en el servidor y se pinta sin HTML.** `ts_headline` se aplica en
   una consulta externa, **solo a las filas de la página** (es caro: vuelve a analizar el texto). Cada
   palabra encontrada va entre dos caracteres de uso privado de Unicode, U+E000 y U+E001, que antes
   se quitan del cuerpo (`translate`): si un autor los escribe, no finge un resaltado. El frontend
   parte el fragmento por esos caracteres y pinta `<mark>` como elementos de React; nunca
   `dangerouslySetInnerHTML`.
7. **Los resultados enlazan al canal, no al mensaje.** Saltar al mensaje (`?seq=N`) queda en
   `docs/v2.md`.

## Alternativas descartadas
- **Filtrar después del límite, en Java:** es la falla del contexto. La prueba con `limit=1` y el
  oculto más nuevo existe para atraparla.
- **Una consulta con la regla de visibilidad escrita en SQL:** sería la tercera copia de la regla
  (ya se borró `findVisibleTo` por eso). `readableChannels` usa el mismo predicado que
  `requireChannel`, y `AccessPolicyConsistencyTest` comprueba que coinciden.
- **`LIKE '%palabra%'`:** recorre la tabla entera, no deriva palabras y no sabe de palabras vacías.
- **Elasticsearch u OpenSearch:** otro sistema que mantener en sincronía (con su propia outbox) y
  con su propia copia de los permisos; DESIGN.md §2 deja fuera lo que Postgres ya resuelve.
- **`ts_headline` con `<b>` y `dangerouslySetInnerHTML`:** `ts_headline` no escapa el texto del
  mensaje, así que un `<img onerror>` escrito por alguien se ejecutaría en el navegador de quien busca.
- **Columna llenada por un trigger:** hace lo mismo que la columna generada, con más código.

## Consecuencias
- **`ADD COLUMN … STORED` reescribe la tabla entera con un lock exclusivo.** Con la base de
  desarrollo es instantáneo; con millones de mensajes bloquearía los envíos durante la migración. En
  producción se haría con una columna normal, un trigger para lo nuevo, un relleno por lotes y
  `CREATE INDEX CONCURRENTLY`.
- **Sin `unaccent`:** «canción» no encuentra «cancion». Agregarlo obliga a una configuración de
  búsqueda propia (`unaccent` no es inmutable, y una columna generada exige que lo sea)
  (`docs/v2.md`).
- **El plan de la consulta depende de los datos.** Con pocos mensajes por canal Postgres puede
  preferir recorrer `messages` por id hacia atrás y filtrar, en vez de usar el GIN; con muchos usa
  el índice. Ninguno de los dos cambia el resultado: el `WHERE` es el mismo. Medirlo es del hito 9.
- **No hay rate limit de búsqueda:** cobra mensajes, no búsquedas (`docs/v2.md`).
- Los nombres de archivo de los adjuntos no se buscan (`docs/v2.md`).
- **Pruebas** (Postgres real): `SearchIntegrationTest` (22: derivación, `or` y `-`, palabras vacías,
  entrada rara sin error, orden y cursor sin repetidos ni saltos, resaltado, marcadores escritos por
  el autor, límites de `q`, `limit` y `before_id`), `SearchAccessIntegrationTest` (9: privado ajeno,
  `limit=1` con el oculto más nuevo, sacado del privado, expulsado, extraño, filtro por canal propio,
  ajeno, de otro servidor e inexistente) y `SearchControllerTest` (6). La matriz HTTP gana 2 filas (12
  celdas): buscar en el servidor y filtrar por un privado ajeno.
- **Comprobado que los tests muerden:** ver `docs/DELIVERY.md`, «No leídos y búsqueda (hito 8)».
