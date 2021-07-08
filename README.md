# WiseTime Allisa Connector

## About

The WiseTime Allisa Connector connects [WiseTime](https://wisetime.io) to [Allisa](https://allisa.software/), and will automatically:

* Create a new WiseTime tag whenever a new Allisa case is created and the action status in Allisa is set to pick up the WiseTime tag;
* Register worked time whenever a user posts time to WiseTime.

In order to use the WiseTime Allisa Connector, you will need a [WiseTime Connect](https://wisetime.io/docs/connect/) API key. The WiseTime Allisa Connector runs as a Docker container and is easy to set up and operate.

## Configuration

Configuration is done through environment variables. The following configuration options are required.

| Environment Variable                      | Description                                                              |
| ----------------------------------------  | ------------------------------------------------------------------------ |
| API_KEY                                   | Your WiseTime Connect API Key                                            |
| ALLISA_API_KEY                            | Your Allisa API Key (can be optained from Allisa user profile)           |
| ALLISA_BASE_URL                           | Base URL of your Allisa instance                                         |
| ALLISA_CASE_TYPE                          | Case type to be used when getting tags from Allisa                       |
| ALLISA_POST_TYPE                          | Case type to be used when posting time to Allisa                         |

The following configuration options are optional.

| Environment Variable                 | Description                                                                                                                                                                                                                   |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CALLER_KEY                           | The caller key that WiseTime should provide with post time webhook calls. The connector does not authenticate Webhook calls if not set.                                                                                       |
| TAG_UPSERT_PATH                      | The tag folder path to use during creating Wisetime tags. Defaults to `/Allisa/` (trailing slash required). Use `/` for root folder.                                                                                        |
| TAG_UPSERT_BATCH_SIZE                | Number of tags to upsert at a time. A large batch size mitigates API call latency. Defaults to 500.                                                                                                                           |
| DATA_DIR                             | If set, the connector will use the directory as the location for storing data to keep track on the Allisa cases it has synced. By default, WiseTime Connector will create a temporary dir under `/tmp` as its data storage. |
| TIMEZONE                             | The timezone to use when posting time to Allisa, e.g. `Australia/Perth`. Defaults to `UTC`.                                                                                                                                 |
| RECEIVE_POSTED_TIME                  | If unset, this defaults to `LONG_POLL`: use long polling to fetch posted time. Optional parameters are `WEBHOOK` to start up a server to listen for posted time. `DISABLED` no handling for posted time                       |
| TAG_SCAN                             | If unset, this defaults to `ENABLED`: Set mode for scanning external system for tags and uploading to WiseTime. Possible values: ENABLED, DISABLED.                                                                           |
| WEBHOOK_PORT                         | The connector will listen to this port e.g. 8090, if RECEIVE_POSTED_TIME is set to `WEBHOOK`. Defaults to 8080.                                                                                                               |                                                                                                                    
| LOG_LEVEL                            | Define log level. Available values are: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` and `OFF`. Default is `INFO`.                                                                                                               |
| ADD_SUMMARY_TO_NARRATIVE             | When `true`, adds total worked time, total chargeable time and experience weighting (if less than 100%) to the narrative when posting time to Allisa. Defaults to `false`.                                                  |
| ALLISA_POST_FIELD_MAPPING            | Mapping to be used when posting time to Allisa. Format: `<wisetimeFieldName1>:<allisaFieldName1>,<wisetimeFieldName2>:<allisaFieldName2>,...`. Required fields: `pid`, `userId`, `narrative`, `startDateTime`, `totalTimeSecs`, `chargeableTimeSecs`, `activityCode`. Defaults to identity mapping. |

## Building

To build a Docker image of the WiseTime Allisa Connector, run:

```text
make docker
```
