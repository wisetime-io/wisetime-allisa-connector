/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa.model;

import com.google.gson.annotations.SerializedName;
import io.wisetime.generated.connect.UpsertTagRequest;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class AllisaCase {
  @SerializedName(value="caseId", alternate = {"ID"})
  private long caseId;

  @SerializedName(value="caseReference", alternate = {"az"})
  private String caseReference;

  @SerializedName(value="caseDescription", alternate = {"prname"})
  private String caseDescription;

  public UpsertTagRequest toUpsertTagRequest(String tagUpsertPath, String prefixUrl) {
    return new UpsertTagRequest()
        .name(caseReference)
        .description(caseDescription)
        .path(tagUpsertPath)
        .url(prefixUrl + caseId);
  }
}
