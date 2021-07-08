/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class TimePostData {
  private long tagId;
  private String userId;
  private String narrative;
  private String startDateTime;
  private String activityCode;
  private long totalTimeSecs;
  private long chargeableTimeSecs;
}
