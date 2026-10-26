package com.jsav.chat.access;

import com.jsav.chat.server.Role;

/**
 * Lo que alguien puede intentar hacer dentro de un canal. Leer y escribir mensajes lo puede hacer
 * cualquiera que vea el canal; quién lo ve lo decide {@link AccessPolicy#requireChannel}.
 */
public enum ChannelAction {
  READ,
  WRITE,
  MANAGE_MEMBERS;

  boolean allows(Role role) {
    return switch (this) {
      case READ, WRITE -> true;
      case MANAGE_MEMBERS -> role.canAdminister();
    };
  }
}
