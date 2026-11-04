# ADR 0014 · Adjuntos: subida prefirmada con el tamaño firmado, confirmación por HEAD y vínculo al enviar

**Estado:** aceptada · 2026-11-03 · Complementa DESIGN.md §5 (Adjuntos) y §7

## Contexto
DESIGN.md §5 fija que el archivo **nunca pasa por el backend**: el cliente sube directo a S3 con una
URL prefirmada y el backend solo registra el adjunto. Eso deja tres problemas abiertos:

- **El tamaño.** Una URL prefirmada no limita nada por sí sola: si no se firma el tamaño, cualquiera
  que la tenga sube lo que quiera. Y el cliente puede mentir al declarar cuánto pesa.
- **Un adjunto, un mensaje.** Entre pedir la subida y enviar el mensaje hay un rato, y dos mensajes
  podrían reclamar el mismo adjunto (o uno de otro usuario, u otro canal).
- **Descargar con la autorización de ahora.** Una URL de S3 es una credencial: quien la tiene baja el
  archivo hasta que venza. Si dura horas, un expulsado sigue bajando. Y todo lo que sale por el topic
  STOMP lo lee cualquiera que esté suscrito en ese momento.

## Decisión
1. **Tres pasos, y el envío del mensaje no habla con S3.**
   - `POST /channels/{id}/attachments` `{filename, contentType, size}` (puede escribir en el canal).
     Crea una fila pendiente y devuelve un **PUT prefirmado de 5 min con `Content-Length` y
     `Content-Type` dentro de la firma**: S3 rechaza un cuerpo de otra longitud.
   - `POST /attachments/{id}/complete` (solo el autor, y mientras pueda escribir). Hace un **`HEAD`**
     del objeto: si no existe, 409; si su tamaño no es el declarado o pasa del máximo, **borra el
     objeto y la fila** y responde 400. Repetirlo devuelve lo mismo (idempotente).
   - `POST /channels/{id}/messages` acepta `attachmentIds` (hasta 4, `app.storage.max-per-message`).
2. **El `HEAD` es lo que hace cumplir el tamaño**; la firma es la primera barrera, no la única. Un
   cliente que se salte la firma (o un emulador que no la valide) no consigue registrar un objeto de
   otro tamaño.
3. **Un adjunto pertenece a un solo mensaje**, y lo decide un `UPDATE` condicional dentro de la
   transacción del envío:
   `UPDATE attachments SET message_id = ? WHERE id IN (…) AND uploader_id = ? AND channel_id = ? AND uploaded_at IS NOT NULL AND message_id IS NULL`.
   Si las filas actualizadas no son todas, se lanza una excepción: se revierte el mensaje **con su
   `seq`** (como en la ADR 0005) y se responde 409. Antes del `seq` hay una revisión que da el error
   claro (400 si no existe, no es tuyo o es de otro canal, sin distinguir cuál; 409 si no está
   confirmado o ya se usó), pero esa revisión es solo cortesía: la que cierra la carrera es el `UPDATE`.
4. **El texto puede ir vacío si hay adjuntos** (migración V8: el `CHECK` de `messages.body` pasa de
   1–4000 a 0–4000). Que haya texto o adjuntos lo exige la validación de la solicitud, porque un
   `CHECK` no puede mirar otra tabla.
5. **Descargar es pedir una URL de 60 s**: `GET /attachments/{id}` devuelve JSON `{url, expiresAt}`.
   Si el adjunto ya está en un mensaje, lo baja quien **puede leer ese canal ahora** (`AccessPolicy`,
   que lee de la base); si todavía no, solo su autor. Todo lo demás es 404, y un expulsado también.
   Es JSON y no un 302 porque el JWT vive en `localStorage` y un `<img src>` no manda
   `Authorization`.
6. **Qué se abre en la página.** Solo `image/png`, `image/jpeg`, `image/gif` e `image/webp` van
   `inline`. Todo lo demás se firma con `Content-Disposition: attachment` y `Content-Type:
   application/octet-stream`: un HTML o un SVG subido por un usuario nunca se interpreta como página
   del sitio. El nombre del archivo no entra en la clave del objeto (`channels/{canal}/{uuid}`) y en
   el encabezado va saneado (RFC 6266, con `filename*` en UTF-8).
7. **Por el topic STOMP sale solo la descripción** (`id`, `filename`, `size`, `contentType`): nunca
   una URL ni la clave de S3. Para bajar hay que pasar por HTTP, donde se mira el acceso de ese momento.
8. **Huérfanos.** `AttachmentCleaner` borra cada 10 min los adjuntos sin mensaje de más de 1 h
   (`app.storage.orphan-age`, que debe ser mayor que la vigencia de la URL de subida). Una vuelta
   bloquea un lote con `FOR UPDATE SKIP LOCKED`, **borra primero los objetos y luego las filas**, todo
   en una transacción: si el proceso muere en medio, las filas siguen ahí y se repite el borrado. Es
   seguro con dos instancias, igual que el publicador de la outbox (ADR 0012).
9. **S3 local: Adobe S3Mock** (`adobe/s3mock:5.2.3`). Hay dos endpoints en la configuración: el
   `endpoint` que usa el backend (`http://s3:9090`) y el `public-endpoint` que se firma en las URLs
   que recibe el navegador (`http://localhost:9090`), porque la firma incluye el host.

## Alternativas descartadas
- **Subir el archivo por el backend:** es lo que §5 descarta; el backend pasaría a cargar con los bytes.
- **POST con *policy* (`content-length-range`):** limitaría el tamaño sin saber de antemano cuánto
  pesa, pero el SDK v2 de Java no firma *policies*; habría que armarla a mano.
- **Confiar en el tamaño declarado:** sin el `HEAD`, un cliente que se salte la firma registraría
  cualquier cosa.
- **Registrar el adjunto al enviar el mensaje (un solo paso):** obliga a que el envío hable con S3
  dentro de la transacción que tiene bloqueada la fila del canal, y un S3 lento frenaría a todo el canal.
- **Un 302 a una URL larga, o URLs de horas:** una URL de S3 es una credencial; con vigencia larga
  sobrevive a una expulsión.
- **MinIO:** era la primera opción, pero ya no se puede descargar (Docker Hub dice que el repositorio
  no existe y quay.io responde 401 para la etiqueta que probé el 03/11). LocalStack pide cuenta en
  las versiones recientes y SeaweedFS es pesado de configurar.

## Consecuencias
- **Límite conocido, medido:** **Adobe S3Mock no valida la firma de las URLs**. Por eso no hay una
  prueba que muestre a S3 rechazando con 403 un cuerpo de otro tamaño; lo que se prueba es que
  `content-length` y `content-type` **van dentro de la firma** (`S3ObjectStorageIntegrationTest`) y que
  el `HEAD` atrapa el tamaño equivocado (`AttachmentCompleteIntegrationTest`, que sube a propósito sin
  respetar lo firmado). Que S3 de verdad rechace el cuerpo queda para verificarlo en el hito 9.
- Un adjunto confirmado que nadie envía en una hora se borra: quien redacte un mensaje durante más
  de una hora recibe un 400 al enviar y tiene que volver a subir.
- Los objetos de un canal o un mensaje borrados en cascada (`ON DELETE CASCADE`) no se borran de S3.
  Hoy no hay forma de borrar canales ni mensajes; si la hubiera, haría falta una limpieza (`docs/v2.md`).
- No hay límite de subidas pendientes por usuario: el rate limit cobra mensajes, no URLs. El limpiador
  acota el daño, pero un usuario puede crear muchas filas en una hora (`docs/v2.md`).
- **Pruebas** (todas contra Postgres y S3 reales, en contenedores):
  `S3ObjectStorageIntegrationTest` (3), `AttachmentUploadIntegrationTest` (5),
  `AttachmentCompleteIntegrationTest` (6), `AttachmentSendIntegrationTest` (6),
  `AttachmentClaimConcurrencyIntegrationTest` (10 mensajes a la vez por un adjunto: entra
  exactamente uno y `last_seq` queda en 1), `AttachmentDownloadIntegrationTest` (9),
  `DispositionTest` (4), `AttachmentCleanerIntegrationTest` (5, con dos limpiadores a la vez y una
  fila bloqueada por otra transacción), `AttachmentsOverStompIntegrationTest` (2) y `AttachmentControllerTest`
  (10). La matriz HTTP gana 7 filas (42 celdas): subir (3), confirmar (1) y bajar (3).
- **Comprobado que los tests muerden (03/11):**
  1. Quitar `AND message_id IS NULL` del `UPDATE`: la prueba de concurrencia da **10** mensajes con el
     adjunto en vez de 1.
  2. Quitar `contentLength` de la firma: falla `uploadUrlSignsContentLengthAndContentType`.
  3. Saltarse el `HEAD` en `complete`: fallan 2 de las 6 pruebas de `AttachmentCompleteIntegrationTest`
     (tamaño distinto y subida sin hacer).
  4. Bajar sin `AccessPolicy`: fallan 3 de las 9 de `AttachmentDownloadIntegrationTest` (un extraño,
     un privado ajeno y un expulsado).
