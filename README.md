# Datadog Backend Listener for Apache JMeter

![screenshot](images/screenshot.png)

## Overview
Datadog Backend Listener for Apache JMeter is a JMeter plugin used to send test results to the Datadog platform. It includes the following features:
- Real time reporting of test metrics (latency, bytes sent and more. See the `metrics` section.
- Real time reporting of test results as Datadog log events.
- Ability to include sub results.

## Installation

You can install the plugin either manually or with JMeter Plugins Manager.

### Manual installation
1. Download the Datadog plugin JAR file from the [release page](https://github.com/DataDog/jmeter-datadog-backend-listener/releases)
2. Place the JAR in the `lib/ext` directory within your JMeter installation.
3. Launch JMeter (or quit and re-open the application).

### JMeter plugins Manager
1. If not already configured, download the [JMeter Plugins Manager JAR](https://jmeter-plugins.org/wiki/PluginsManager/).
2. Once you've completed the download, place the `.jar` in the `lib/ext` directory within your JMeter installation. 
3. Launch JMeter (or quit and re-open the application). 
4. Go to `Options > Plugins Manager > Available Plugins`. 
5. Search for "Datadog Backend Listener".
6. Click the checbox next to the Datadog Backend Listener plugin.
7. Click "Apply Changes and Restart JMeter".

## Configuration
To start reporting metrics to Datadog:

1. Right click on the thread group for which you want to send metrics to Datadog. 
2. Go to `Add > Listener > Backend Listener`.
3. Modify the `Backend Listener Implementation` and select `org.datadog.jmeter.plugins.DatadogBackendClient` from the drop-down. 
4. Set the `apiKey` variable to [your Datadog API key](https://app.datadoghq.com/account/settings#api).
5. Run your test and validate that metrics have appeared in Datadog.

The plugin has the following configuration options:

| Name       | Required | Default value | description|
|------------|:--------:|---------------|------------|
|apiKey | true | NA | Your Datadog API key.|
|datadogUrl | false | https://api.datadoghq.com/api/ | You can configure a different endpoint, for instance https://api.datadoghq.eu/api/ if your datadog instance is in the EU|
|logIntakeUrl | false | https://http-intake.logs.datadoghq.com/v1/input/ | You can configure a different endpoint, for instance https://http-intake.logs.datadoghq.eu/v1/input/ if your datadog instance is in the EU|
|metricsMaxBatchSize|false|200|Metrics are submitted every 10 seconds in batches of size `metricsMaxBatchSize`|
|logsBatchSize|false|500|Logs are submitted in batches of size `logsBatchSize` as soon as this size is reached.|
|sendResultsAsLogs|false|false|By default only metrics are reported to Datadog. To report individual test results as log events, set this field to `true`.|
|includeSubresults|false|false|A subresult is for instance when an individual HTTP request has to follow redirects. By default subresults are ignored.|
|customTags|false|`""`|Comma-separated list of tags to add to every metric

## Troubleshooting

If for whatever reason you are not seeing JMeter metrics in Datadog, check your `jmeter.log` file, which should be in the `/bin` folder of your JMeter installation. 

## Contributing

### Reporting a bug and feature requests
- **Ensure the bug was not already reported**
- If you're unable to find an open issue addressing the problem, [open a new one](https://github.com/DataDog/jmeter-datadog-backend-listene/issues/new).
- If you have a feature request, it is encouraged to contact the [Datadog support](https://docs.datadoghq.com/help) so the request can be prioritized and properly tracked.
- **Do not open an issue if you have a question**, instead contact the [Datadog support](https://docs.datadoghq.com/help).

### Pull requests
Have you fixed an issue or adding a new feature? Many thanks for your work and for letting other to benefit from it.

Here are some generic guidelines:
- Avoid changing too many things at once.
- **Write tests** for the code you wrote.
- Make sure **all tests pass locally**.
- Summarize your PR with a **meaningful title** and **write a meaningful description for it**.

Your pull request must pass the CI before we can merge it. If you're seeing an error and don't think it's your fault, it may not be. Let us know in the PR and we'll get it sorted out.

