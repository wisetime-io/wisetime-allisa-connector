/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa.util;

/**
 * General error designed to contain connector's specific meaningful message which can be shown to a user.
 *
 * @author pascal
 */
public class ConnectorException extends RuntimeException {

  public ConnectorException(String message) {
    super(message);
  }
}
