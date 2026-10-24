package com.jsav.chat.channel;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "channel_members")
public class ChannelMember {

  @EmbeddedId private ChannelMemberId id;

  protected ChannelMember() {}

  public ChannelMember(Long channelId, Long userId) {
    this.id = new ChannelMemberId(channelId, userId);
  }

  public ChannelMemberId getId() {
    return id;
  }
}
