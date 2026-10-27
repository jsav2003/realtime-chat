package com.jsav.chat.message;

import com.jsav.chat.message.MessageDtos.MessageResponse;

/**
 * Un mensaje nuevo que se guardó. Se publica dentro de la transacción del envío, pero quien lo
 * escuche para avisar a los clientes debe hacerlo con {@code AFTER_COMMIT} (ADR 0003): si la
 * transacción se revierte, el evento se descarta y nadie se entera de un mensaje que no existe. Un
 * reintento que devuelve un mensaje ya guardado no publica nada.
 */
public record MessageCreated(MessageResponse message) {}
