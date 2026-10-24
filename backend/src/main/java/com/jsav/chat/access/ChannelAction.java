package com.jsav.chat.access;

import com.jsav.chat.server.Role;

/** Lo que alguien puede intentar hacer dentro de un canal. WRITE llega en el hito 3. */
public enum ChannelAction {
  READ,
  MANAGE_MEMBERS;

  boolean allows(Role role) {
    return switch (this) {
      case READ -> true;
      case MANAGE_MEMBERS -> role.canAdminister();
    };
  }
}
