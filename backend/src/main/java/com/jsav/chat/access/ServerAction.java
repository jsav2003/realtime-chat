package com.jsav.chat.access;

import com.jsav.chat.server.Role;

/** Lo que alguien puede intentar hacer dentro de un servidor. */
public enum ServerAction {
  VIEW,
  MANAGE_CHANNELS,
  CREATE_INVITE,
  KICK,
  CHANGE_ROLE;

  boolean allows(Role role) {
    return switch (this) {
      case VIEW -> true;
      case MANAGE_CHANNELS, CREATE_INVITE, KICK -> role.canAdminister();
      case CHANGE_ROLE -> role == Role.OWNER;
    };
  }
}
