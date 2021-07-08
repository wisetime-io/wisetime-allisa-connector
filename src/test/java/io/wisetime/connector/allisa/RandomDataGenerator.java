/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa;

import com.github.javafaker.Faker;
import io.wisetime.connector.allisa.model.AllisaCase;
import io.wisetime.connector.allisa.model.TimePostData;

/**
 * @author pascal
 */
public class RandomDataGenerator {

  private static final Faker FAKER = new Faker();

  public AllisaCase randomAllisaCase() {
    return randomAllisaCase(FAKER.crypto().md5());
  }

  public AllisaCase randomAllisaCase(String caseNumber) {
    return new AllisaCase()
        .setCaseId(FAKER.number().numberBetween(1, 10_000))
        .setCaseDescription(FAKER.lorem().word())
        .setCaseReference(caseNumber);
  }

  public TimePostData randomTimePostData() {
    return new TimePostData()
        .setChargeableTimeSecs(FAKER.number().numberBetween(1, 10_000))
        .setNarrative(FAKER.lorem().sentence())
        .setStartDateTime(FAKER.date().birthday().toString())
        .setTagId(FAKER.number().numberBetween(1, 10_000))
        .setTotalTimeSecs(FAKER.number().numberBetween(1, 10_000))
        .setUserId(Integer.toString(FAKER.number().numberBetween(1, 10_000)))
        .setActivityCode(FAKER.lorem().word());
  }
}
