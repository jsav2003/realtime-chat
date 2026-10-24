package com.jsav.chat.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ChannelMemberId implements Serializable {

  @Column(name = "channel_id")
  private Long channelId;

  @Column(name = "user_id")
  private Long userId;

  protected ChannelMemberId() {}

  public ChannelMemberId(Long channelId, Long userId) {
    this.channelId = channelId;
    this.userId = userId;
  }

  public Long getChannelId() {
    return channelId;
  }

  public Long getUserId() {
    return userId;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ChannelMemberId other
        && Objects.equals(channelId, other.channelId)
        && Objects.equals(userId, other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(channelId, userId);
  }
}
