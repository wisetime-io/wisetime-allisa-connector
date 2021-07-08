/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa.model;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class ApiResponse<T> {
  @SerializedName(value="result")
  private Result<T> result;

  @SerializedName(value="message")
  private String message;

  @SerializedName(value="code")
  private int code;
}
