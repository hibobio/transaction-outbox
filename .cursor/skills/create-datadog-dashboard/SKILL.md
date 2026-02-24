---
name: create-datadog-dashboard
description: Generates Datadog dashboard JSON files by inspecting service repositories and analyzing metric collection patterns. Use when the user asks to create a Datadog dashboard for a specific service/repository. Requires the user to specify the target repository name.
---

# Create Datadog Dashboard

Generates Datadog dashboard JSON files by inspecting service repositories, identifying metrics, and creating structured dashboards following established patterns.

## Input Requirements

**CRITICAL: User must specify target repository/service**

When the user requests dashboard creation:
- If repository/service not specified: Ask "Which service/repository should I create the dashboard for?"
- Extract service name from `service.datadog.yaml` (`dd-service` field) if file exists
- Fallback to repository name (converted to kebab-case)
- Verify repository exists before proceeding

**If user provides existing dashboard JSON:**
- Preserve all existing widgets/groups/charts exactly as provided
- Only add new items - never modify or delete existing ones (unless explicitly requested)
- Maintain all existing widget IDs, layouts, and configurations

## Hard Rules

**CRITICAL: Never use `env:prod` as a metric filter**

- **NEVER** add `env:prod` tag to any metric query
- **ALWAYS** use `{service:<service-name>}` tag in all metric queries
- This rule applies to ALL metric queries without exception

**CRITICAL: Preserve existing dashboard items when adding new ones**

- If the user provides an existing dashboard JSON and asks to add items:
  - **NEVER** delete, modify, or update existing widgets/groups/charts
  - **ONLY** add new widgets/groups to the dashboard
  - Preserve all existing widget IDs, layouts, and configurations exactly as they are
  - Append new items to the `widgets` array without touching existing items
  - Only delete or update existing items if the user explicitly requests it

**CRITICAL: Never use metric names containing hyphens in metric queries**

- **NEVER** include metric names that contain hyphens (e.g., `recruiting.events.job-opening-deleted.process`) in any metric query string
- Datadog's query parser interprets hyphens as minus operators, causing import failures with errors like "Rule 'scope_expr' didn't match" or "Rule 'metric' didn't match"
- Quoting the metric name (e.g., `avg:"metric-name".95percentile{...}`) does not resolve this parsing issue
- **Action when discovered metric contains hyphens:**
  - Omit that metric from the generated dashboard (use a widget with only non-hyphenated metrics, or remove the metric from the widget entirely)
  - If an equivalent metric with underscores exists in the codebase, use that name instead
  - Optionally add a brief note in the dashboard description that certain metrics were omitted due to Datadog parse limitations

## Workflow

### Step 1: Inspect Service Repository

Navigate to the target repository and identify:

**Service Characteristics:**
- Service name from `service.datadog.yaml` (`dd-service` field)
- Framework (Spring Boot vs Dropwizard)
- Database usage (Postgres, SingleStore, MongoDB)
- Kafka/Eventstream usage
- HikariCP usage

**Find Metrics Collection Patterns:**

Search for:
- **Micrometer**: `Metrics.globalRegistry`, `Metrics.timer()`, `Metrics.counter()`, `Metrics.gauge()`, `Metrics.distributionSummary()`
- **Hibob metrics lib** (`com.hibob.metrics.Metrics`): `Metrics.counter()`, `Metrics.timer()`, `Metrics.customTimer()`, `Metrics.gauge()`, `Metrics.distributionSummary()`, `Metrics.registry`
  - Kotlin/Scala services use this (wraps Micrometer). `Metrics.customTimer()` produces same metrics as timer
  - `Gauge.builder(...).register(Metrics.registry)` for dynamic gauges
- **Scala (hibob)**: `Metrics.INSTANCE`, `Meter.count()`, `Meter.measure()`, `Meter.time()` (delegate to Metrics)
- **Event-stream MetricsProvider**: `MetricsProvider.incrementCounter()`, `MetricsProvider.recordTime()` (KafkaPublisher, subscribers)
- **Metric constants**: `*Metrics` objects with `val.*METRIC_NAME = "..."` or `metricName` (e.g., `EventMetrics.ELAPSED_TIME_METRIC_NAME`)
- **Dropwizard**: `MetricRegistry`, `.timer()`, `.counter()`, `.gauge()`
- **HikariCP**: `HikariDataSource`, `hikaricp.connections.*` metrics
- **Kafka/Eventstream**: `kafka.subscriber.*`, eventstream library usage
- **Cache**: 
  - **Cachium:** `CacheFacade`, `CacheSpecFactory`, `cachium.cache_*` metrics
  - **GuavaCacheMetrics:** `@Cacheable`, `Cache`, `cache.gets`, `cache.size`, `cache.evictions` metrics
- **Custom/Service-Specific Metrics**: Search codebase for all above patterns
  - Extract metric names (first string argument or constant value)
  - Group by metric prefix (e.g., `s3.*`, `file.*`, `cloudinary.*`, `externalFile.*`, `rein.*`)
  - Identify tags used with each metric
- **Transactional Outbox**: Check for `transactional-outbox-spring-boot-starter` dependency or `TransactionalOutbox` imports
  - If found, include `tx.outbox.*` metrics (see Step 3.7 for details)

**Check Eventstream Version:**
- If Kafka metrics needed, verify eventstream library version supports metrics
- Check `build.gradle.kts` or `build.gradle` for eventstream dependency
- Older versions may not support metrics collection

### Step 1.5: Detect Log Markers (Optional)

**IMPORTANT**: There will be many log invocations - apply strict filtering to avoid being overwhelmed.

Search codebase for log statements with markers:
- **Scala**: `logger.(info|debug|warn|error).*\"[^\"]+\".*->` (at least one marker)
- **Kotlin**: `logger.(info|debug|warn|error).*\"[^\"]+\".*to` (at least one marker)

**Extract from logs:**
- Log message (first string argument)
- Class name (from file path or `@logger.name` filter)
- Marker keys and values
- Identify numeric markers (size, count, duration, bytes, etc.)

**Filter criteria (apply in order of priority):**
1. **Must have at least 1 marker total**
2. **Must have at least 1 marker that is NOT `companyId` or `employeeId`**
3. **Prioritize logs with `metricName` marker** (indicates intentional logging for metrics)
4. **Prioritize logs with numeric markers** (size, count, duration, bytes, etc.) for aggregation
5. **Prioritize logs with 2+ markers** (more context for grouping/aggregation)
6. **Prioritize logs in metrics/telemetry classes** (e.g., `*Metrics.kt`, `*Telemetry.scala`)
7. **Limit to top 5-10 most valuable logs** per service (avoid dashboard overload)

**Skip logs that:**
- Only have `companyId` and/or `employeeId` markers
- Are in test files (`*Test.kt`, `*Test.scala`, `*Spec.kt`, `*Spec.scala`)
- Are debug/trace level logs (unless they have `metricName` marker)
- Have no numeric or categorical markers suitable for aggregation/grouping

**Prioritization Strategy:**
1. First: Logs with `metricName` marker (highest priority - intentional metrics)
2. Second: Logs in `*Metrics.kt` or `*Telemetry.scala` classes
3. Third: Logs with numeric markers + 2+ total markers
4. Fourth: Logs with 2+ non-ID markers
5. Limit: Select top 5-10 most valuable logs per service

**Note:** Log markers become `@bobData.*` fields in Datadog (e.g., `logger.info("message", "bucket" to bucketName)` becomes `@bobData.bucket` in Datadog logs).

### Step 1.8: Log Discovered Metrics

After identifying custom/service-specific metrics in Step 1, write them to a temp file before generating the dashboard:

- **Path**: `/tmp/{service-name}-metrics-discovered.txt`
- **Format**: One line per metric: `metric.name|type|tag1,tag2` (e.g., `file.upload.count|counter|bucket`)
- **Exclude**: Framework metrics (hikaricp, kafka, cachium, tx.outbox) - those have dedicated sections
- **Purpose**: Enables validation in Step 5 that every discovered metric has a chart

### Step 2: Categorize Metrics

Group metrics into categories:

- **Business Metrics**: Custom application metrics (e.g., `audit.events.*`, `tx.outbox.*`)
- **Database Metrics**: HikariCP connection pool metrics
- **Kafka Metrics**: Eventstream/Kafka subscriber metrics
- **Cache Metrics**: Cache hit rate, gets, size, evictions (if cache is used)
- **Resource Metrics**: CPU, memory, disk (via powerpack)
- **Error Logs**: Error log streams
- **AWS Metrics**: SQS, SNS metrics if applicable

### Step 3: Generate Dashboard JSON

**Required Sections (always include):**

**CRITICAL:** Always include service-specific custom metrics (Section 7) and transactional outbox metrics (Section 8) if detected. These are essential for monitoring the service's business logic.

1. **Resource Usage Group**: Powerpack `50dbb12a-c818-11ed-a984-da7ad0900005` with service-specific template variables
   ```json
   {
     "id": <unique_id>,
     "definition": {
       "title": "Resource usage",
       "background_color": "purple",
       "show_title": true,
       "powerpack_id": "50dbb12a-c818-11ed-a984-da7ad0900005",
       "template_variables": {
         "controlled_externally": [],
         "controlled_by_powerpack": [
           {"name": "kube_namespace", "prefix": "kube_namespace", "values": ["production"]},
           {"name": "kube_deployment", "prefix": "kube_deployment", "values": ["<service-name>"]}
         ]
       },
       "type": "powerpack"
     },
     "layout": {"x": 0, "y": 0, "width": 12, "height": 9, "is_column_break": true}
   }
   ```

2. **Error Logs Widget**: `list_stream` widget filtering `service:{service_name} status:error`
   ```json
   {
     "id": <unique_id>,
     "definition": {
       "title": "Errors",
       "title_size": "16",
       "title_align": "left",
       "requests": [{
         "response_format": "event_list",
         "query": {
           "data_source": "logs_stream",
           "query_string": "service:<service-name> status:error",
           "indexes": [],
           "storage": "hot"
         },
         "columns": [
           {"field": "status_line", "width": "auto"},
           {"field": "timestamp", "width": "auto"},
           {"field": "host", "width": "auto"},
           {"field": "service", "width": "auto"},
           {"field": "content", "width": "auto"}
         ]
       }],
       "type": "list_stream"
     },
     "layout": {"x": 0, "y": 0, "width": 8, "height": 4}
   }
   ```

3. **HikariCP Group** (if database used): Connection pool metrics group
   - Active connections: `avg:hikaricp.connections.active{service:<service-name>} by {host}`
   - Idle connections: `avg:hikaricp.connections.idle{service:<service-name>} by {host}`
   - Pending connections: `avg:hikaricp.connections.pending{service:<service-name>} by {host}`
   - Connection acquire P95: `avg:hikaricp.connections.acquire.95percentile{service:<service-name>} by {host}`
   - Connection creation P95: `avg:hikaricp.connections.creation.95percentile{service:<service-name>} by {host}`
   - Connection usage P95: `avg:hikaricp.connections.usage.95percentile{service:<service-name>} by {host}`
   - Connection timeouts: `sum:hikaricp.connections.timeout{service:<service-name>} by {host}.as_count()`

4. **Kafka Group** (if Kafka used):
   - Subscriber metrics: `kafka.subscriber.bulk.processing.time.*`, `kafka.subscriber.bulk.size.*`
   - Kafka Lag Powerpack: `02a5c08a-34b4-11f0-8eb6-da7ad0900005`
   - Kafka Throughput Powerpack: `b56f52e8-34b0-11f0-a297-da7ad0900005`

5. **Cache Group** (if cache is used): Cache monitoring group

   **CRITICAL: Detect Cache Implementation Type First**
   
   Cache metrics differ based on implementation. You MUST detect which type is used:
   
   **Detection:**
   - **Cachium:** Look for `cachium`, `CacheFacade`, `CacheSpecFactory` imports, or `cachium-spring-boot-starter` dependency
   - **GuavaCacheMetrics:** Look for `GuavaCacheMetrics`, `com.google.common.cache`, or Guava cache dependencies
   
   **If Cachium is detected:**
   - Use metrics: `cachium.cache_entries`, `cachium.cache_requests_total`, `cachium.cache_evictions_total`, `cachium.cache_get_latency_ms`, `cachium.cache_load_latency_ms`
   - Reference: `samples/cachium-generic-dashboard.json`
   - Metrics use `service` and `spec` tags (cache spec name, not cache name)
   - Cache Hit Rate: `(autosmooth(query1) / (autosmooth(query1) + autosmooth(query2))) * 100` where:
     - query1: `max:cachium.cache_requests_total{service:<service-name>,spec:<spec-name>,outcome:hit} by {service,spec}.as_count()`
     - query2: `max:cachium.cache_requests_total{service:<service-name>,spec:<spec-name>,outcome:miss} by {service,spec}.as_count()`
   - Cache Entries: `max:cachium.cache_entries{service:<service-name>,spec:<spec-name>} by {service,spec}`
   - Cache Requests Total: `max:cachium.cache_requests_total{service:<service-name>,spec:<spec-name>} by {service,spec,outcome}.as_count()`
   - Cache Evictions: `max:cachium.cache_evictions_total{service:<service-name>,spec:<spec-name>} by {service,spec}.as_count()`
   - Cache Get Latency P95: `max:cachium.cache_get_latency_ms.95percentile{service:<service-name>,spec:<spec-name>} by {service,spec,outcome}`
   - Cache Load Latency P95: `max:cachium.cache_load_latency_ms.95percentile{service:<service-name>,spec:<spec-name>} by {service,spec}`
   
   **If GuavaCacheMetrics is detected:**
   - Use metrics: `cache.gets`, `cache.size`, `cache.evictions`
   - Reference: `samples/toggles-dashboard.json`
   - Cache Hit Rate %: `(sum:cache.gets{cache:<cache-name>,result:hit} by {service}.as_count().rollup(avg) / (sum:cache.gets{cache:<cache-name>,result:hit} by {service}.as_count().rollup(avg) + sum:cache.gets{cache:<cache-name>,result:miss} by {service}.as_count().rollup(avg))) * 100`
   - Cache Gets: `sum:cache.gets{cache:<cache-name>} by {service}.as_count()`
   - Cache Size: `avg:cache.size{cache:<cache-name>} by {service}`
   - Cache Evictions: `avg:cache.evictions{cache:<cache-name>} by {service}.as_count()`
   - Cache Miss: `sum:cache.gets{cache:<cache-name>,result:miss} by {service}.as_count().rollup(avg)`
   
   **Cache Detection:**
   - Search codebase for cache usage: `@Cacheable`, `Cache`, `CacheFacade`, `CacheSpecFactory`
   - Check for cache library dependencies: `cachium-spring-boot-starter`, `GuavaCacheMetrics`, Caffeine, Guava Cache
   - Look for cache metric names in code: `cache.gets`, `cachium.cache_requests_total`, `cache.evictions`, `cache.size`
   - Identify cache names/specs from code:
     - Cachium: Look for `CacheSpecFactory.named()` calls to find spec names (e.g., `kms-keys-cache`, `user-settings-cache`)
     - GuavaCacheMetrics: Look for cache names in configuration or annotations (e.g., `compound_company_toggles`, `featuretoggles`)
   
   **Cache Group Templates:**
   
   **For GuavaCacheMetrics (reference: toggles-dashboard.json):**
   ```json
   {
     "id": <unique_id>,
     "definition": {
       "title": "Cache: <cache-name>",
       "show_title": true,
       "type": "group",
       "layout_type": "ordered",
       "widgets": [
         {
           "id": <unique_id>,
           "definition": {
             "title": "Cache Hit Rate %",
             "title_size": "16",
             "title_align": "left",
             "show_legend": true,
             "legend_layout": "auto",
             "legend_columns": ["avg", "min", "max", "value", "sum"],
             "type": "timeseries",
             "requests": [{
               "formulas": [{"formula": "query1 / (query1 + query2) * 100"}],
               "queries": [
                 {
                   "name": "query1",
                   "data_source": "metrics",
                   "query": "sum:cache.gets{cache:<cache-name>,result:hit,service:<service-name>} by {service}.as_count().rollup(avg)"
                 },
                 {
                   "name": "query2",
                   "data_source": "metrics",
                   "query": "sum:cache.gets{cache:<cache-name>,result:miss,service:<service-name>} by {service}.as_count().rollup(avg)"
                 }
               ],
               "response_format": "timeseries",
               "style": {
                 "palette": "dog_classic",
                 "line_type": "solid",
                 "line_width": "normal"
               },
               "display_type": "line"
             }],
             "yaxis": {"include_zero": false}
           },
           "layout": {"x": 0, "y": 0, "width": 6, "height": 2}
         },
         {
           "id": <unique_id>,
           "definition": {
             "title": "Cache Gets",
             "title_size": "16",
             "title_align": "left",
             "show_legend": false,
             "legend_layout": "auto",
             "legend_columns": ["avg", "min", "max", "value", "sum"],
             "type": "timeseries",
             "requests": [{
               "formulas": [{"formula": "query1"}],
               "queries": [{
                 "name": "query1",
                 "data_source": "metrics",
                 "query": "sum:cache.gets{cache:<cache-name>,service:<service-name>} by {service}.as_count()"
               }],
               "response_format": "timeseries",
               "style": {
                 "palette": "dog_classic",
                 "line_type": "solid",
                 "line_width": "normal"
               },
               "display_type": "line"
             }]
           },
           "layout": {"x": 6, "y": 0, "width": 6, "height": 2}
         },
         {
           "id": <unique_id>,
           "definition": {
             "title": "Cache Size",
             "title_size": "16",
             "title_align": "left",
             "show_legend": false,
             "legend_layout": "auto",
             "legend_columns": ["avg", "min", "max", "value", "sum"],
             "type": "timeseries",
             "requests": [{
               "formulas": [{"formula": "query1"}],
               "queries": [{
                 "name": "query1",
                 "data_source": "metrics",
                 "query": "avg:cache.size{cache:<cache-name>,service:<service-name>} by {service}"
               }],
               "response_format": "timeseries",
               "style": {
                 "palette": "dog_classic",
                 "line_type": "solid",
                 "line_width": "normal"
               },
               "display_type": "line"
             }]
           },
           "layout": {"x": 0, "y": 2, "width": 6, "height": 2}
         },
         {
           "id": <unique_id>,
           "definition": {
             "title": "Cache Evictions",
             "title_size": "16",
             "title_align": "left",
             "show_legend": false,
             "legend_layout": "auto",
             "legend_columns": ["avg", "min", "max", "value", "sum"],
             "type": "timeseries",
             "requests": [{
               "formulas": [{"formula": "query1"}],
               "queries": [{
                 "name": "query1",
                 "data_source": "metrics",
                 "query": "avg:cache.evictions{cache:<cache-name>,service:<service-name>} by {service}.as_count()"
               }],
               "response_format": "timeseries",
               "style": {
                 "palette": "dog_classic",
                 "line_type": "solid",
                 "line_width": "normal"
               },
               "display_type": "line"
             }]
           },
           "layout": {"x": 6, "y": 2, "width": 6, "height": 2}
         },
         {
           "id": <unique_id>,
           "definition": {
             "title": "Cache Miss",
             "title_size": "16",
             "title_align": "left",
             "show_legend": true,
             "legend_layout": "auto",
             "legend_columns": ["avg", "min", "max", "value", "sum"],
             "type": "timeseries",
             "requests": [{
               "formulas": [{"formula": "query1"}],
               "queries": [{
                 "name": "query1",
                 "data_source": "metrics",
                 "query": "sum:cache.gets{cache:<cache-name>,result:miss,service:<service-name>} by {service}.as_count().rollup(avg)"
               }],
               "response_format": "timeseries",
               "style": {
                 "palette": "dog_classic",
                 "line_type": "solid",
                 "line_width": "normal"
               },
               "display_type": "line"
             }],
             "yaxis": {"include_zero": false}
           },
           "layout": {"x": 0, "y": 4, "width": 6, "height": 2}
         }
       ]
     },
     "layout": {"x": 0, "y": 0, "width": 12, "height": 6}
   }
   ```
   
   **For Cachium (reference: cachium-generic-dashboard.json):**
   ```json
   {
     "id": <unique_id>,
     "definition": {
       "title": "Cache: <spec-name>",
       "show_title": true,
       "type": "group",
       "layout_type": "ordered",
       "widgets": [
         // Cache Entries, Cache Requests Total, Cache Evictions, 
         // Cache Get Latency P95, Cache Load Latency P95, Cache Hit Rate %
         // See cachium-generic-dashboard.json for complete template
       ]
     },
     "layout": {"x": 0, "y": 0, "width": 12, "height": 13}
   }
   ```
   
   **Note:** 
   - If multiple caches exist (client-side vs server-side, compound vs legacy), create separate groups for each cache type.
   - For Cachium, use `spec` tag (cache spec name) instead of `cache` tag (cache name).
   - Always reference the appropriate sample dashboard for the cache implementation type.

6. **Log Marker Charts Group** (if applicable): Important operations monitored via logs with markers

   **CRITICAL: Apply strict filtering to avoid being overwhelmed**
   
   Only include top 5-10 most valuable logs per service (prioritized by filtering rules from Step 1.5).
   
   **Structure:**
   - Create a group titled "Log Marker Charts" or "Important Operations"
   - For each identified log with markers:
     - Create 1-3 timeseries widgets maximum per log (avoid overloading):
       - Count by grouping field (if categorical marker exists)
       - Average/Max of numeric markers (if numeric markers exist)
     - Use log message as base query
     - Add `@logger.name:ClassName` filter if class can be identified
   
   **Widget Template:**
   ```json
   {
     "id": <unique_id>,
     "definition": {
       "title": "<Operation Name> - <Metric Name>",
       "title_size": "16",
       "title_align": "left",
       "show_legend": true,
       "legend_layout": "auto",
       "legend_columns": ["avg", "min", "max", "value", "sum"],
       "type": "timeseries",
       "requests": [{
         "response_format": "timeseries",
         "queries": [{
           "name": "query1",
           "data_source": "logs",
           "search": {
             "query": "<log-message> @logger.name:*.<ClassName>"
           },
           "indexes": ["*"],
           "group_by": {
             "fields": ["@bobData.<marker-field>"],
             "limit": 1000,
             "sort": {
               "aggregation": "count",
               "metric": "count",
               "order": "desc"
             }
           },
           "compute": {
             "aggregation": "<avg|max|count>",
             "metric": "@bobData.<numeric-field>"
           },
           "storage": "hot"
         }],
         "formulas": [{"formula": "query1"}],
         "style": {
           "palette": "dog_classic",
           "order_by": "values",
           "line_type": "solid",
           "line_width": "normal"
         },
         "display_type": "line"
       }]
     },
     "layout": {"x": 0, "y": 0, "width": 6, "height": 3}
   }
   ```
   
   **Example Log Marker Patterns:**
   - **Scala**: `logger.info("report metadata retrieval", "companyId" -> companyId, "viewType" -> viewType, "responseSizeBytes" -> responseSizeBytes)`
     - Query: `"report metadata retrieval @logger.name:*.ReportMetaDataController"`
     - Group by: `["@bobData.viewType"]`
     - Compute: `{"aggregation": "avg", "metric": "@bobData.responseSizeBytes"}`
   
   - **Kotlin**: `logger.info("S3 PUT operation to bucket", "operation" to "PUT", "bucket" to bucketName, "status" to status, "count" to count)`
     - Query: `"S3 PUT operation to bucket @logger.name:*.S3Metrics"`
     - Group by: `["@bobData.operation"]` or `["@bobData.status"]`
     - Compute: `{"aggregation": "count"}` or `{"aggregation": "avg", "metric": "@bobData.count"}`
   
   - **Kotlin**: `logger.info("File uploaded to S3 bucket", "bucket" to bucketName, "folder" to folder.name, "sizeInBytes" to fileSizeInBytes)`
     - Query: `"File uploaded to S3 bucket @logger.name:*.FileMetrics"`
     - Group by: `["@bobData.bucket"]` or `["@bobData.folder"]`
     - Compute: `{"aggregation": "avg", "metric": "@bobData.sizeInBytes"}`
   
   **Important:** Always use `*.ClassName` pattern (not full package path) because logs may be configured with abbreviated package names (e.g., `c.h.f.common.FileMetrics` instead of `com.hibob.files.common.FileMetrics`).
   
   **Notes:**
   - Log markers become `@bobData.*` fields in Datadog
   - Use `@logger.name:*.ClassName` filter to narrow down logs (wildcard pattern because logs may have abbreviated package names)
   - Prefer numeric markers for aggregation (avg, max)
   - Use categorical markers for grouping (bucket, operation, status)
   - Avoid charts with only companyId/employeeId grouping (too granular)
   - Quality over quantity - only include logs that provide meaningful insights

7. **Service-Specific Metrics Groups** (if custom metrics found): Custom application metrics

   **CRITICAL: Always include service-specific metrics**
   
   After detecting custom metrics in Step 1, create groups for each metric category/prefix.
   
   **Detection:**
   - Search codebase using all patterns from Step 1 (Metrics.counter/timer/customTimer/gauge/distributionSummary, Meter.count/measure/time, MetricsProvider, metric constants)
   - Extract metric names (first string argument to Metrics methods)
   - Group metrics by prefix (e.g., `s3.*`, `file.*`, `cloudinary.*`, `externalFile.*`, `rein.*`)
   - Identify tags used with each metric from the code
   
   **For each metric category, create a group with widgets:**
   - **Counters**: Use `sum:metric.name{service:<service-name>} by {tag}.as_count()`
   - **Timers**: Use `avg:metric.name.95percentile{service:<service-name>} by {tag}` for P95, `avg:metric.name.avg{service:<service-name>} by {tag}` for average, `sum:metric.name.count{service:<service-name>} by {tag}.as_count()` for count
   - **Distribution Summaries**: Use `avg:metric.name{service:<service-name>} by {tag}` for average, `avg:metric.name.95percentile{service:<service-name>} by {tag}` for P95, `sum:metric.name.count{service:<service-name>} by {tag}.as_count()` for count
   - **Gauges**: Use `avg:metric.name{service:<service-name>} by {tag}`
   
   **Example Group Structure:**
   ```json
   {
     "id": <unique_id>,
     "definition": {
       "title": "<Category Name> Metrics",
       "show_title": true,
       "type": "group",
       "layout_type": "ordered",
       "widgets": [
         {
           "id": <unique_id>,
           "definition": {
             "title": "<Metric Display Name>",
             "title_size": "16",
             "title_align": "left",
             "show_legend": true,
             "legend_layout": "auto",
             "legend_columns": ["avg", "min", "max", "value", "sum"],
             "type": "timeseries",
             "requests": [{
               "formulas": [{"formula": "query1"}],
               "queries": [{
                 "name": "query1",
                 "data_source": "metrics",
                 "query": "<metric-query-with-service-and-env-tags>"
               }],
               "response_format": "timeseries",
               "style": {
                 "palette": "dog_classic",
                 "order_by": "values",
                 "line_type": "solid",
                 "line_width": "normal"
               },
               "display_type": "line"
             }]
           },
           "layout": {"x": 0, "y": 0, "width": 4, "height": 2}
         }
         // Add more widgets for other metrics in this category
       ]
     },
     "layout": {"x": 0, "y": 0, "width": 12, "height": <calculated_height>}
   }
   ```
   
   **Common Metric Patterns:**
   - **S3 Operations**: `s3.operation.count`, `s3.operation.error.count`, `s3.operation.throttled.count` (group by `bucket`, `operation`, `status`)
   - **File Uploads**: `file.upload.count`, `file.upload.size` (group by `bucket`)
   - **Cloudinary**: `cloudinary.upload.count`, `cloudinary.fetch.count`
   - **External Files**: `externalFile.rejected`, `externalFile.processed`
   - **Rein Security**: `rein.security.check.duration`, `rein.security.check.requests`, `rein.security.check.errors` (group by `result`, `path`, `method`)
   - **Redis**: `redis.sliding_window` (timer)
   
   **Important:** 
   - Always include `service:<service-name>` tag in queries (NEVER use `env:prod`)
   - Use appropriate grouping (`by {tag}`) based on tags found in code
   - Create separate groups for each metric category/prefix
   - Include all detected custom metrics - don't skip any

8. **Transactional Outbox Group** (if transactional outbox is used): Outbox metrics group

   **CRITICAL: Two Different Implementations**
   
   There are two different transactional outbox implementations with different metrics:
   
   **1. Objects Service Implementation** (custom):
   - Uses `tx.outbox.s2.*` metrics
   - Reference: `samples/tx-outbox.json`
   - Metrics include: `tx.outbox.s2.postgres.row.count`, `tx.outbox.s2.items.added`, `tx.outbox.s2.batch.publish.time.*`, etc.
   - Query format: `avg:tx.outbox.s2.*{service:<service-name>}` or `sum:tx.outbox.s2.*{service:<service-name>} by {topic}.as_count()`
   
   **2. Libraries Repo Implementation** (`transactional-outbox-spring-boot-starter`):
   - Used by most services (e.g., files service)
   - Uses `tx.outbox.*` metrics (without `.s2.` prefix)
   - Reference: `samples/tx-outbox-libraries.json`
   - Metrics include: `tx.outbox.success.count`, `tx.outbox.scheduled.count`, `tx.outbox.invocation.time.*`, `tx.outbox.queue.size`
   - Metrics use `service` tag (include `service:<service-name>` in queries)
   
   **Detection:**
   - Check for `transactional-outbox-spring-boot-starter` dependency → Libraries repo version
   - Check for `txno_outbox` table migrations → Could be either version
   - Check for `tx.outbox.s2.*` metrics in codebase → Objects service version
   
   **If Objects Service Version Detected:**
   - Queue Health: `tx.outbox.s2.postgres.row.count`, `tx.outbox.s2.singlestore.row.count`, `tx.outbox.s2.postgres.partition.count`, `tx.outbox.s2.postgres.default.partition.row.count`
   - Outbox Items Flows: `tx.outbox.s2.items.added`, `tx.outbox.s2.items.published`, `tx.outbox.s2.items.failed` (group by `topic`)
   - Postgres Operations: `tx.outbox.s2.postgres.insert.time.*`, `tx.outbox.s2.postgres.select.time.*` (P95, count)
   - SingleStore Operations: `tx.outbox.s2.singlestore.insert.time.*`, `tx.outbox.s2.singlestore.select.time.*`, `tx.outbox.s2.singlestore.delete.time.*` (P95, count)
   - Publishing & Locking: `tx.outbox.s2.batch.publish.time.*`, `tx.outbox.s2.partition.lock.*`, `tx.outbox.s2.transaction.time.*` (various percentiles and counts)
   - Partition Cleanup: `tx.outbox.s2.partition.cleanup.*`
   - Query format: `avg:tx.outbox.s2.*{service:<service-name>}` or `sum:tx.outbox.s2.*{service:<service-name>} by {topic}.as_count()`
   - Reference: `samples/tx-outbox.json` for complete patterns
   
   **If Libraries Repo Version Detected:**
   - Use metrics: `tx.outbox.success.count`, `tx.outbox.scheduled.count`, `tx.outbox.invocation.time.*`, `tx.outbox.queue.size`
   - Reference: `samples/tx-outbox-libraries.json`
   - Metrics use `service` tag (include `service:<service-name>` in queries)
   - Successfully processed items: `avg:tx.outbox.success.count{service:<service-name>} by {service,topic}.as_count()`
   - Scheduled items: `avg:tx.outbox.scheduled.count{service:<service-name>} by {service,topic}.as_count()`
   - Average invocation time P95: `avg:tx.outbox.invocation.time.95percentile{service:<service-name>} by {service}`
   - Queue size: `avg:tx.outbox.queue.size{service:<service-name>} by {service,topic}`
   - Scheduled/Success rate: `sum:tx.outbox.scheduled.count{service:<service-name>} by {service,topic}.as_count() / sum:tx.outbox.success.count{service:<service-name>} by {service,topic}.as_count()`
   - Total items processed (toplist): `sum:tx.outbox.success.count{service:<service-name>} by {topic,service}.as_count()`
   - Group by `service` and `topic` as appropriate
   - **Note:** The sample uses `{! service:files}` to exclude files service, but for service-specific dashboards use `{service:<service-name>}` instead
   
   **Note:** 
   - Always include `service:<service-name>` tag in queries for both implementations
   - If metrics are not found, skip this section and note in dashboard description that tx-outbox metrics need to be identified

**Dashboard Structure:**

- Root level: `"layout_type": "ordered"`
- Use groups (`"type": "group"`) to organize related widgets
- Each group: `"layout_type": "ordered"` with `widgets` array
- Widget IDs: Use unique IDs (timestamp-based or sequential)
- Layout coordinates: `{"x": 0-11, "y": 0+, "width": 1-12, "height": 1+}`

**Standard Timeseries Widget Template:**

**IMPORTANT: Metric Name Characters**

- When building metric queries, use only metric names that do not contain hyphens
- If the codebase uses a metric with hyphens (e.g., `recruiting.events.job-opening-deleted.process`), do not add it to any `query` string
- Omit hyphenated metrics from widgets or use only non-hyphenated metrics in that widget

```json
{
  "id": <unique_id>,
  "definition": {
    "title": "Metric Name",
    "title_size": "16",
    "title_align": "left",
    "show_legend": true,
    "legend_layout": "auto",
    "legend_columns": ["avg", "min", "max", "value", "sum"],
    "type": "timeseries",
    "requests": [{
      "formulas": [{"formula": "query1"}],
      "queries": [{
        "name": "query1",
        "data_source": "metrics",
        "query": "avg:metric.name{service:<service-name>} by {tag}"
      }],
      "response_format": "timeseries",
      "style": {
        "palette": "dog_classic",
        "order_by": "values",
        "line_type": "solid",
        "line_width": "normal"
      },
      "display_type": "line"
    }]
  },
  "layout": {"x": 0, "y": 0, "width": 4, "height": 2}
}
```

**Group Template:**

```json
{
  "id": <unique_id>,
  "definition": {
    "title": "Group Title",
    "show_title": true,
    "type": "group",
    "layout_type": "ordered",
    "widgets": [
      // Widget definitions here
    ]
  },
  "layout": {"x": 0, "y": 0, "width": 12, "height": <calculated_height>}
}
```

### Step 4: Environment Handling

- **CRITICAL: NEVER use `env:prod` tag in metric queries**
- **ALWAYS use `{service:<service-name>}` tag in all metric queries**
- Service name: Extract from `service.datadog.yaml` or use repository name (kebab-case)
- Metric queries: `service:{service_name}` (without `env:prod`)
- Database size metrics: Use `avg:postgresql.database_size{db:<database-name>}` (no service tag, no env tag)

### Step 5: Validate Dashboard JSON

Before outputting the dashboard JSON, validate it:

**Chart Coverage Validation:**

Ensure every discovered metric has at least one chart in the dashboard. For each metric in `/tmp/{service-name}-metrics-discovered.txt`:

```bash
# For each metric name (first column, before |), grep the dashboard JSON
grep -Fq "metric.name" {service-name}-dashboard.json
```

- Use `-F` for literal string matching (no regex)
- Metric names appear in query strings (e.g., `"query": "sum:file.upload.count{service:files}..."`)
- If any metric is not found, add the missing chart(s) or report to the user

**JSON Syntax Validation (try in order):**

**Method 1: Python (if available)**
```bash
python3 -m json.tool {service-name}-dashboard.json > /dev/null && echo "✓ Valid JSON" || echo "✗ Invalid JSON"
```

**Method 2: Node.js (if available)**
```bash
node -e "JSON.parse(require('fs').readFileSync('{service-name}-dashboard.json', 'utf8'))" && echo "✓ Valid JSON" || echo "✗ Invalid JSON"
```

**Method 3: jq (if available)**
```bash
jq . {service-name}-dashboard.json > /dev/null && echo "✓ Valid JSON" || echo "✗ Invalid JSON"
```

**Method 4: Online Validator**
- Use online JSON validators (e.g., jsonlint.com, jsonformatter.org)
- Copy-paste the JSON content for validation

**Common JSON Errors:**
- Trailing commas: `{"key": "value",}` ❌ → `{"key": "value"}` ✅
- Unquoted keys: `{key: "value"}` ❌ → `{"key": "value"}` ✅
- Single quotes: `{'key': 'value'}` ❌ → `{"key": "value"}` ✅
- Comments: `{"key": "value"} // comment` ❌ → `{"key": "value"}` ✅

**Structural Validation Checklist:**
- [ ] JSON syntax is valid (no parse errors)
- [ ] Root object has required fields: `title`, `widgets`, `layout_type`
- [ ] `widgets` is an array
- [ ] All widgets have `id` and `definition` fields
- [ ] All widgets have `layout` with `x`, `y`, `width`, `height`
- [ ] Layout coordinates are valid (`x`: 0-11, `y`: 0+, `width`: 1-12, `height`: 1+)
- [ ] Widget IDs are unique across the dashboard
- [ ] Groups contain `widgets` array in their `definition`
- [ ] Powerpack widgets have `powerpack_id` field
- [ ] Timeseries widgets have `requests` array with queries
- [ ] List_stream widgets have `query` object with `query_string`

**Validation Command (with fallback):**
Run validation after generating the dashboard:
```bash
# Try Python first, then Node.js, then jq, or use online validator
if command -v python3 &> /dev/null; then
  python3 -m json.tool {service-name}-dashboard.json > /dev/null && echo "✓ JSON syntax valid" || (echo "✗ JSON syntax error" && exit 1)
elif command -v node &> /dev/null; then
  node -e "JSON.parse(require('fs').readFileSync('{service-name}-dashboard.json', 'utf8'))" && echo "✓ JSON syntax valid" || (echo "✗ JSON syntax error" && exit 1)
elif command -v jq &> /dev/null; then
  jq . {service-name}-dashboard.json > /dev/null && echo "✓ JSON syntax valid" || (echo "✗ JSON syntax error" && exit 1)
else
  echo "⚠ No JSON validator found. Use online validator or check manually."
fi

# Check file exists and is not empty
[ -s {service-name}-dashboard.json ] && echo "✓ File exists and is not empty" || (echo "✗ File missing or empty" && exit 1)
```

If validation fails, review the JSON structure and compare with sample dashboards.

### Step 6: Output

Generate JSON file:
- Filename: `{service-name}-dashboard.json`
- **Dashboard title**: Always append " - generated by Cursor" to the service name (e.g., `"Files Service - generated by Cursor"`)
- Valid JSON structure matching sample dashboards
- All widgets have unique IDs
- Correct layout coordinates
- Can be imported directly into Datadog UI

### Step 7: Import Dashboard into Datadog

**How to import the dashboard JSON:**

1. **Create a new dashboard:**
   - Go to Datadog → Dashboards → New Dashboard
   - Choose "New Dashboard" (not "New Screenboard")

2. **Import the JSON:**
   - Click the **"Configure"** button (gear icon) in the top right
   - Select **"Import dashboard JSON"** from the dropdown menu
   - Either:
     - **Copy-paste:** Paste the contents of `{service-name}-dashboard.json` into the text area
     - **Upload file:** Click "Upload file" and select the `{service-name}-dashboard.json` file

3. **Save the dashboard:**
   - Review the imported dashboard
   - Click **"Save"** to save the dashboard
   - The dashboard will be available in your Datadog dashboards list

**Note:** If the import fails, check the JSON syntax using the validation methods in Step 5.

## Sample Dashboard Patterns

Reference sample dashboards in `samples/` directory:

- **audit-service.json**: Groups for business metrics, SNS/SQS, Kafka subscriber, resource usage powerpack
- **hikaricp-metrics.json**: Individual widgets for each connection pool metric
- **tx-outbox.json**: Groups for queue health, operations, publishing/locking (Objects service version with `tx.outbox.s2.*` metrics). This is for the objects service custom implementation.
- **tx-outbox-libraries.json**: Groups for transactional outbox metrics from libraries repo (`transactional-outbox-spring-boot-starter`). Uses `tx.outbox.success.count`, `tx.outbox.scheduled.count`, `tx.outbox.invocation.time.*`, `tx.outbox.queue.size` metrics with `service` tag. Use this when service uses `transactional-outbox-spring-boot-starter` dependency.
- **toggles-dashboard.json**: Template variables, APM metrics, extensive cache monitoring using GuavaCacheMetrics (client-side compound/legacy, server-side), SQS metrics. Shows patterns for multiple cache types with separate groups for each cache.
- **cachium-generic-dashboard.json**: Cachium cache metrics pattern using `cachium.cache_*` metrics with `service` and `spec` tags. Use this as reference when service uses Cachium library.
- **email-service.json**: Service-specific patterns
- **ReportsDashboard**: Log marker charts using `data_source: "logs"` with `@bobData.*` fields. Shows pattern for creating charts from logs with log markers (not metrics).

## Troubleshooting

**If Dashboard Doesn't Load:**

1. Validate JSON syntax first (see Step 5 validation methods)
2. Create minimal version with single group containing 2-3 widgets
3. Ask user to test import in Datadog UI
4. If successful, incrementally add more groups/widgets
5. If fails, check for common JSON errors (trailing commas, unquoted keys, etc.)

**If Import Fails with "Rule 'scope_expr' didn't match" or "Rule 'metric' didn't match" at a hyphen:**

1. **Problem:** A metric name in a query contains a hyphen (e.g., `recruiting.events.job-opening-deleted.process`)
2. **Root cause:** Datadog's query parser interprets hyphens as minus operators, causing parsing failures
3. **Solution:**
   - Locate the problematic widget using the error message (e.g., "widget at position N" and "inner widget at position M")
   - Remove the metric containing hyphens from that widget's queries
   - If the widget has multiple queries and one contains a hyphen, remove only that query (or the entire query if it's the only one)
   - Re-generate or manually edit the JSON so no metric name in any `"query": "..."` string contains a hyphen
   - Re-import the dashboard
4. **Prevention:** When generating dashboards, automatically omit any discovered metrics that contain hyphens from all metric queries

**If No Data Appears:**

1. Verify metrics exist in Datadog (check metric explorer)
2. Confirm metrics are running in prod/stage (not other envs)
3. Verify metric names match exactly (case-sensitive)
4. Check service name matches `dd-service` from `service.datadog.yaml`
5. Ensure time range includes periods when metrics were collected

**If Eventstream/Kafka Metrics Are Not Showing:**

1. Check if the service uses eventstream/Kafka library
2. Verify the eventstream library version supports metrics collection
3. Older versions of the eventstream library may not support metrics
4. Check `build.gradle.kts` or `build.gradle` for eventstream dependency version
5. If using an old version, recommend upgrading the eventstream library to a version that supports metrics
6. Verify Kafka subscriber metrics are being collected (check for `kafka.subscriber.*` metrics in Datadog)

**If Database Size Metric Shows No Data:**

1. **Problem:** Using `env:prod` or `service` tags with `postgresql.database_size` metric
2. **Fix:** Remove all tags except `db` - database size metrics only use database name
3. **Example:**
   - ❌ Wrong: `avg:postgresql.database_size{db:files,env:prod}` or `avg:postgresql.database_size{db:files,service:files}`
   - ✅ Correct: `avg:postgresql.database_size{db:files}`

## Reference

For detailed JSON structure, widget types, and powerpack IDs, see [reference.md](reference.md).
