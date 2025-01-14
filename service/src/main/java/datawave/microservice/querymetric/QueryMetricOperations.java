package datawave.microservice.querymetric;

import static datawave.microservice.querymetric.QueryMetricOperations.DEFAULT_DATETIME.BEGIN;
import static datawave.microservice.querymetric.QueryMetricOperations.DEFAULT_DATETIME.END;
import static datawave.microservice.querymetric.QueryMetricOperationsStats.METERS;
import static datawave.microservice.querymetric.QueryMetricOperationsStats.TIMERS;
import static datawave.microservice.querymetric.config.HazelcastMetricCacheConfiguration.INCOMING_METRICS;
import static datawave.microservice.querymetric.config.HazelcastMetricCacheConfiguration.LAST_WRITTEN_METRICS;

import java.net.InetAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;
import javax.annotation.security.PermitAll;
import javax.inject.Named;

import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.security.VisibilityEvaluator;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codahale.metrics.Timer;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.map.IMap;
import com.hazelcast.spring.cache.HazelcastCacheManager;

import datawave.marking.MarkingFunctions;
import datawave.microservice.authorization.user.DatawaveUserDetails;
import datawave.microservice.querymetric.config.QueryMetricProperties;
import datawave.microservice.querymetric.config.QueryMetricProperties.Retry;
import datawave.microservice.querymetric.factory.BaseQueryMetricListResponseFactory;
import datawave.microservice.querymetric.function.QueryMetricSupplier;
import datawave.microservice.querymetric.handler.QueryGeometryHandler;
import datawave.microservice.querymetric.handler.ShardTableQueryMetricHandler;
import datawave.microservice.querymetric.handler.SimpleQueryGeometryHandler;
import datawave.microservice.security.util.DnUtils;
import datawave.query.jexl.visitors.JexlFormattedStringBuildingVisitor;
import datawave.security.authorization.DatawaveUser;
import datawave.webservice.query.exception.DatawaveErrorCode;
import datawave.webservice.query.exception.QueryException;
import datawave.webservice.query.map.QueryGeometryResponse;
import datawave.webservice.result.VoidResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The type Query metric operations.
 */
@Tag(name = "Query Metric Operations /v1",
                externalDocs = @ExternalDocumentation(description = "Query Metric Service Documentation",
                                url = "https://github.com/NationalSecurityAgency/datawave-query-metric-service"))
@EnableScheduling
@RestController
@RequestMapping(path = "/v1")
public class QueryMetricOperations {
    // Note: This must match 'confirmAckChannel' in the service configuration. Default set in bootstrap.yml.
    public static final String CONFIRM_ACK_CHANNEL = "confirmAckChannel";
    
    private Logger log = LoggerFactory.getLogger(QueryMetricOperations.class);
    
    private QueryMetricProperties queryMetricProperties;
    private ShardTableQueryMetricHandler handler;
    private QueryGeometryHandler geometryHandler;
    private CacheManager cacheManager;
    private Cache incomingQueryMetricsCache;
    private Cache lastWrittenQueryMetricCache;
    private MarkingFunctions markingFunctions;
    private BaseQueryMetricListResponseFactory queryMetricListResponseFactory;
    private MergeLockLifecycleListener mergeLock;
    private MetricUpdateEntryProcessorFactory entryProcessorFactory;
    private QueryMetricOperationsStats stats;
    
    private final QueryMetricSupplier queryMetricSupplier;
    private final DnUtils dnUtils;
    
    private static final Map<String,CountDownLatch> correlationLatchMap = new ConcurrentHashMap<>();
    
    /**
     * The enum Default datetime.
     */
    enum DEFAULT_DATETIME {
        /**
         * Begin default datetime.
         */
        BEGIN,
        /**
         * End default datetime.
         */
        END
    }
    
    /**
     * Instantiates a new QueryMetricOperations.
     *
     * @param queryMetricProperties
     *            the query metric properties
     * @param cacheManager
     *            the CacheManager
     * @param handler
     *            the QueryMetricHandler
     * @param geometryHandler
     *            the QueryGeometryHandler
     * @param markingFunctions
     *            the MarkingFunctions
     * @param queryMetricListResponseFactory
     *            the QueryMetricListResponseFactory
     * @param queryMetricSupplier
     *            the query metric object supplier
     * @param dnUtils
     *            the dnUtils
     * @param mergeLock
     *            the merge lock
     * @param entryProcessorFactory
     *            the entry processor factory
     * @param stats
     *            the stats
     */
    @Autowired
    public QueryMetricOperations(QueryMetricProperties queryMetricProperties, @Named("queryMetricCacheManager") CacheManager cacheManager,
                    ShardTableQueryMetricHandler handler, QueryGeometryHandler geometryHandler, MarkingFunctions markingFunctions,
                    BaseQueryMetricListResponseFactory queryMetricListResponseFactory, MergeLockLifecycleListener mergeLock,
                    MetricUpdateEntryProcessorFactory entryProcessorFactory, QueryMetricOperationsStats stats, QueryMetricSupplier queryMetricSupplier,
                    DnUtils dnUtils) {
        this.queryMetricProperties = queryMetricProperties;
        this.handler = handler;
        this.geometryHandler = geometryHandler;
        this.cacheManager = cacheManager;
        this.incomingQueryMetricsCache = cacheManager.getCache(INCOMING_METRICS);
        this.lastWrittenQueryMetricCache = cacheManager.getCache(LAST_WRITTEN_METRICS);
        this.markingFunctions = markingFunctions;
        this.queryMetricListResponseFactory = queryMetricListResponseFactory;
        this.mergeLock = mergeLock;
        this.entryProcessorFactory = entryProcessorFactory;
        this.stats = stats;
        this.queryMetricSupplier = queryMetricSupplier;
        this.dnUtils = dnUtils;
    }
    
    @PreDestroy
    public void shutdown() {
        this.stats.queueAggregatedQueryStatsForTimely();
        this.stats.writeQueryStatsToTimely();
    }
    
    /**
     * Update metrics void response.
     *
     * @param queryMetrics
     *            the list of query metric updates
     * @param metricType
     *            the metric type
     * @return the void response
     */
    // Messages that arrive via http/https get placed on the message queue
    // to ensure a quick response and to maintain a single queue of work
    @Operation(summary = "Submit a list of metrics updates.", description = "Metrics updates will be placed on a message queue to ensure a quick response.")
    @Secured({"Administrator", "JBossAdministrator", "MetricsAdministrator"})
    @RequestMapping(path = "/updateMetrics", method = {RequestMethod.POST}, consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
                    produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public VoidResponse updateMetrics(@RequestBody List<BaseQueryMetric> queryMetrics,
                    @RequestParam(value = "metricType", defaultValue = "DISTRIBUTED") QueryMetricType metricType) {
        if (!this.mergeLock.isAllowedReadLock()) {
            throw new IllegalStateException("service unavailable");
        }
        stats.getMeter(METERS.REST).mark(queryMetrics.size());
        VoidResponse response = new VoidResponse();
        for (BaseQueryMetric m : queryMetrics) {
            if (log.isTraceEnabled()) {
                log.trace("received metric update via REST: " + m.toString());
            } else {
                log.debug("received metric update via REST: " + m.getQueryId());
            }
            if (!updateMetric(new QueryMetricUpdate<>(m, metricType))) {
                throw new RuntimeException("Unable to process query metric update for query [" + m.getQueryId() + "]");
            }
        }
        return response;
    }
    
    /**
     * Update metric void response.
     *
     * @param queryMetric
     *            the query metric update
     * @param metricType
     *            the metric type
     * @return the void response
     */
    // Messages that arrive via http/https get placed on the message queue
    // to ensure a quick response and to maintain a single queue of work
    @Operation(summary = "Submit a single metric update.", description = "The metric update will be placed on a message queue to ensure a quick response.")
    @Secured({"Administrator", "JBossAdministrator", "MetricsAdministrator"})
    @RequestMapping(path = "/updateMetric", method = {RequestMethod.POST}, consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
                    produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public VoidResponse updateMetric(@RequestBody BaseQueryMetric queryMetric,
                    @RequestParam(value = "metricType", defaultValue = "DISTRIBUTED") QueryMetricType metricType) {
        if (!this.mergeLock.isAllowedReadLock()) {
            throw new IllegalStateException("service unavailable");
        }
        stats.getMeter(METERS.REST).mark();
        if (log.isTraceEnabled()) {
            log.trace("received metric update via REST: " + queryMetric.toString());
        } else {
            log.debug("received metric update via REST: " + queryMetric.getQueryId());
        }
        if (!updateMetric(new QueryMetricUpdate(queryMetric, metricType))) {
            throw new RuntimeException("Unable to process query metric update for query [" + queryMetric.getQueryId() + "]");
        }
        return new VoidResponse();
    }
    
    /**
     * Receives producer confirm acks, and disengages the latch associated with the given correlation ID.
     *
     * @param message
     *            the confirmation ack message
     */
    @ConditionalOnProperty(value = "datawave.query.metric.confirmAckEnabled", havingValue = "true", matchIfMissing = true)
    @ServiceActivator(inputChannel = CONFIRM_ACK_CHANNEL)
    public void processConfirmAck(Message<?> message) {
        Object headerObj = message.getHeaders().get(IntegrationMessageHeaderAccessor.CORRELATION_ID);
        
        if (headerObj != null) {
            String correlationId = headerObj.toString();
            if (correlationLatchMap.containsKey(correlationId)) {
                correlationLatchMap.get(correlationId).countDown();
            } else {
                log.warn("Unable to decrement latch for ID [{}]", correlationId);
            }
        } else {
            log.warn("No correlation ID found in confirm ack message");
        }
    }
    
    private boolean updateMetric(QueryMetricUpdate update) {
        
        boolean success;
        final long updateStartTime = System.currentTimeMillis();
        long currentTime;
        int attempts = 0;
        
        Retry retry = queryMetricProperties.getRetry();
        
        do {
            if (attempts++ > 0) {
                try {
                    Thread.sleep(retry.getBackoffIntervalMillis());
                } catch (InterruptedException e) {
                    // Ignore -- we'll just end up retrying a little too fast
                }
            }
            
            if (log.isDebugEnabled()) {
                log.debug("Update attempt {} of {} for query {}", attempts, retry.getMaxAttempts(), update.getMetric().getQueryId());
            }
            
            success = sendMessage(update);
            currentTime = System.currentTimeMillis();
        } while (!success && (currentTime - updateStartTime) < retry.getFailTimeoutMillis() && attempts < retry.getMaxAttempts());
        
        if (!success) {
            log.warn("Update for query {} failed. {attempts = {}, elapsedMillis = {}}", update.getMetric().getQueryId(), attempts,
                            (currentTime - updateStartTime));
        } else {
            log.info("Update for query {} successful. {attempts = {}, elapsedMillis = {}}", update.getMetric().getQueryId(), attempts,
                            (currentTime - updateStartTime));
        }
        
        return success;
    }
    
    /**
     * Passes query metric messages to the messaging infrastructure.
     * <p>
     * The metric ID is used as a correlation ID in order to ensure that a producer confirm ack is received. If a producer confirm ack is not received within
     * the specified amount of time, a 500 Internal Server Error will be returned to the caller.
     *
     * @param update
     *            The query metric update to be sent
     */
    private boolean sendMessage(QueryMetricUpdate update) {
        String correlationId = UUID.randomUUID().toString();
        
        CountDownLatch latch = null;
        if (queryMetricProperties.isConfirmAckEnabled()) {
            latch = new CountDownLatch(1);
            correlationLatchMap.put(correlationId, latch);
        }
        
        boolean success = queryMetricSupplier.send(MessageBuilder.withPayload(update).setCorrelationId(correlationId).build());
        
        if (queryMetricProperties.isConfirmAckEnabled()) {
            try {
                success = success && latch.await(queryMetricProperties.getConfirmAckTimeoutMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                success = false;
            } finally {
                correlationLatchMap.remove(correlationId);
            }
        }
        
        return success;
    }
    
    /**
     * Handle event.
     *
     * @param update
     *            the query metric update
     */
    public void storeMetric(QueryMetricUpdate update) {
        stats.getMeter(METERS.MESSAGE).mark();
        String queryId = update.getMetric().getQueryId();
        this.stats.queueTimelyMetrics(update);
        log.debug("storing update for {}", queryId);
        if (update.getMetric().getPositiveSelectors() == null) {
            this.handler.populateMetricSelectors(update.getMetric());
        }
        storeMetricUpdate(new QueryMetricUpdateHolder(update));
    }
    
    private String getClusterLocalMemberUuid() {
        return ((HazelcastCacheManager) this.cacheManager).getHazelcastInstance().getCluster().getLocalMember().getUuid().toString();
    }
    
    private void storeMetricUpdate(QueryMetricUpdateHolder metricUpdate) {
        Timer.Context storeTimer = this.stats.getTimer(TIMERS.STORE).time();
        String queryId = metricUpdate.getMetric().getQueryId();
        try {
            IMap<String,QueryMetricUpdateHolder> incomingQueryMetricsCacheHz = ((IMap<String,QueryMetricUpdateHolder>) incomingQueryMetricsCache
                            .getNativeCache());
            this.mergeLock.lock();
            try {
                incomingQueryMetricsCacheHz.executeOnKey(queryId, this.entryProcessorFactory.createEntryProcessor(metricUpdate));
            } finally {
                this.mergeLock.unlock();
            }
        } catch (Exception e) {
            if (!this.mergeLock.isShuttingDown()) {
                if (e instanceof HazelcastInstanceNotActiveException) {
                    log.error("HazelcastInstanceNotActiveException - OK if shutting down");
                } else {
                    log.error(e.getMessage(), e);
                }
            }
            // fail the handling of the message
            throw new RuntimeException(e.getMessage());
        }
        storeTimer.stop();
    }
    
    /**
     * Returns metrics for the current users queries that are identified by the id
     *
     * @param currentUser
     *            the current user
     * @param queryId
     *            the query id
     * @return the base query metric list response
     * @HTTP 200 success
     * @HTTP 500 internal server error
     */
    @Operation(summary = "Get the metrics for a given query ID.")
    @PermitAll
    @RequestMapping(path = "/id/{queryId}", method = {RequestMethod.GET},
                    produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_HTML_VALUE})
    public BaseQueryMetricListResponse query(@AuthenticationPrincipal DatawaveUserDetails currentUser,
                    @Parameter(description = "queryId to return") @PathVariable("queryId") String queryId) {
        
        BaseQueryMetricListResponse response = this.queryMetricListResponseFactory.createDetailedResponse();
        List<BaseQueryMetric> metricList = new ArrayList<>();
        try {
            BaseQueryMetric metric;
            QueryMetricUpdateHolder metricUpdate = incomingQueryMetricsCache.get(queryId, QueryMetricUpdateHolder.class);
            if (metricUpdate != null && metricUpdate.isNewMetric()) {
                metric = metricUpdate.getMetric();
            } else {
                metric = this.handler.getQueryMetric(queryId);
            }
            if (metric != null) {
                boolean allowAllMetrics = false;
                boolean sameUser = false;
                if (currentUser != null) {
                    String metricUser = metric.getUser();
                    String requestingUser = dnUtils.getShortName(currentUser.getPrimaryUser().getName());
                    sameUser = metricUser != null && metricUser.equals(requestingUser);
                    allowAllMetrics = currentUser.getPrimaryUser().getRoles().contains("MetricsAdministrator");
                }
                // since we are using the incomingQueryMetricsCache, we need to make sure that the
                // requesting user has the necessary Authorizations to view the requested query metric
                if (sameUser || allowAllMetrics) {
                    ColumnVisibility columnVisibility = this.markingFunctions.translateToColumnVisibility(metric.getMarkings());
                    boolean userCanSeeVisibility = true;
                    for (DatawaveUser user : currentUser.getProxiedUsers()) {
                        Authorizations authorizations = new Authorizations(user.getAuths().toArray(new String[0]));
                        VisibilityEvaluator visibilityEvaluator = new VisibilityEvaluator(authorizations);
                        if (visibilityEvaluator.evaluate(columnVisibility) == false) {
                            userCanSeeVisibility = false;
                            break;
                        }
                    }
                    if (userCanSeeVisibility) {
                        metricList.add(metric);
                    }
                }
            }
        } catch (Exception e) {
            response.addException(new QueryException(e.getMessage(), 500));
        }
        // Set the result to have the formatted query and query plan
        // StackOverflowErrors seen in JexlFormattedStringBuildingVisitor.formatMetrics, so protect
        // this call for each metric with try/catch and add original metric if formatMetrics fails
        List<BaseQueryMetric> fmtMetricList = new ArrayList<>();
        for (BaseQueryMetric m : metricList) {
            List<BaseQueryMetric> formatted = null;
            try {
                formatted = JexlFormattedStringBuildingVisitor.formatMetrics(Collections.singletonList(m));
            } catch (StackOverflowError | Exception e) {
                log.warn(String.format("%s while formatting metric %s: %s", e.getClass().getCanonicalName(), m.getQueryId(), e.getMessage()));
            }
            if (formatted == null || formatted.isEmpty()) {
                fmtMetricList.add(m);
            } else {
                fmtMetricList.addAll(formatted);
            }
        }
        response.setResult(fmtMetricList);
        if (fmtMetricList.isEmpty()) {
            response.setHasResults(false);
        } else {
            response.setGeoQuery(fmtMetricList.stream().anyMatch(SimpleQueryGeometryHandler::isGeoQuery));
            response.setHasResults(true);
        }
        return response;
    }
    
    /**
     * Get a map for the given query represented by the query ID, if applicable.
     *
     * @param currentUser
     *            the current user
     * @param queryId
     *            the query id
     * @return the query geometry response
     * @HTTP 200 success
     * @HTTP 500 internal server error
     */
    @Operation(summary = "Get a map for the given query represented by the query ID, if applicable.")
    @PermitAll
    @RequestMapping(path = "/id/{queryId}/map", method = {RequestMethod.GET, RequestMethod.POST},
                    produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_HTML_VALUE})
    public QueryGeometryResponse map(@AuthenticationPrincipal DatawaveUserDetails currentUser, @PathVariable("queryId") String queryId) {
        QueryGeometryResponse queryGeometryResponse = new QueryGeometryResponse();
        BaseQueryMetricListResponse metricResponse = query(currentUser, queryId);
        if (!metricResponse.getExceptions().isEmpty()) {
            metricResponse.getExceptions().forEach(e -> queryGeometryResponse.addException(new QueryException(e.getMessage(), e.getCause(), e.getCode())));
            return queryGeometryResponse;
        } else {
            return geometryHandler.getQueryGeometryResponse(queryId, metricResponse.getResult());
        }
    }
    
    private static Date parseDate(String dateString, DEFAULT_DATETIME defaultDateTime) throws IllegalArgumentException {
        if (StringUtils.isBlank(dateString)) {
            return null;
        } else {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd HHmmss.SSS");
                sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
                dateString = dateString.trim();
                if (dateString.length() == 8) {
                    dateString = dateString + (defaultDateTime.equals(BEGIN) ? " 000000.000" : " 235959.999");
                } else if (dateString.length() == 15) {
                    dateString = dateString + (defaultDateTime.equals(BEGIN) ? ".000" : ".999");
                }
                return sdf.parse(dateString);
            } catch (ParseException e) {
                String parameter = defaultDateTime.equals(BEGIN) ? "begin" : "end";
                throw new IllegalArgumentException(
                                "Unable to parse parameter '" + parameter + "' - valid formats: yyyyMMdd | yyyyMMdd HHmmss | yyyyMMdd HHmmss.SSS");
            }
        }
    }
    
    private QueryMetricsSummaryResponse queryMetricsSummary(Date begin, Date end, DatawaveUserDetails currentUser, boolean onlyCurrentUser) {
        
        if (null == end) {
            end = new Date();
        }
        Calendar ninetyDaysBeforeEnd = Calendar.getInstance();
        ninetyDaysBeforeEnd.setTime(end);
        ninetyDaysBeforeEnd.add(Calendar.DATE, -90);
        if (null == begin) {
            // ninety days before end
            begin = ninetyDaysBeforeEnd.getTime();
        }
        QueryMetricsSummaryResponse response;
        if (end.before(begin)) {
            response = new QueryMetricsSummaryResponse();
            String s = "begin date can not be after end date";
            response.addException(new QueryException(DatawaveErrorCode.BEGIN_DATE_AFTER_END_DATE, new IllegalArgumentException(s), s));
        } else {
            response = handler.getQueryMetricsSummary(begin, end, currentUser, onlyCurrentUser);
        }
        return response;
    }
    
    /**
     * Returns a summary of the query metrics
     *
     * @param currentUser
     *            the current user
     * @param begin
     *            formatted date/time (yyyyMMdd | yyyyMMdd HHmmss | yyyyMMdd HHmmss.SSS)
     * @param end
     *            formatted date/time (yyyyMMdd | yyyyMMdd HHmmss | yyyyMMdd HHmmss.SSS)
     * @return the query metrics summary
     * @HTTP 200 success
     * @HTTP 500 internal server error
     */
    @Operation(summary = "Get a summary of the query metrics.")
    @Secured({"Administrator", "JBossAdministrator", "MetricsAdministrator"})
    @RequestMapping(path = "/summary/all", method = {RequestMethod.GET},
                    produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_HTML_VALUE})
    public QueryMetricsSummaryResponse getQueryMetricsSummary(@AuthenticationPrincipal DatawaveUserDetails currentUser,
                    @RequestParam(required = false) String begin, @RequestParam(required = false) String end) {
        
        try {
            return queryMetricsSummary(parseDate(begin, BEGIN), parseDate(end, END), currentUser, false);
        } catch (Exception e) {
            QueryMetricsSummaryResponse response = new QueryMetricsSummaryResponse();
            response.addException(e);
            return response;
        }
    }
    
    /**
     * Returns a summary of the requesting user's query metrics
     *
     * @param currentUser
     *            the current user
     * @param begin
     *            formatted date/time (yyyyMMdd | yyyyMMdd HHmmss | yyyyMMdd HHmmss.SSS)
     * @param end
     *            formatted date/time (yyyyMMdd | yyyyMMdd HHmmss | yyyyMMdd HHmmss.SSS)
     * @return the query metrics user summary
     * @HTTP 200 success
     * @HTTP 500 internal server error
     */
    @Operation(summary = "Get a summary of the query metrics for the given user.")
    @PermitAll
    @RequestMapping(path = "/summary/user", method = {RequestMethod.GET},
                    produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_HTML_VALUE})
    public QueryMetricsSummaryResponse getQueryMetricsUserSummary(@AuthenticationPrincipal DatawaveUserDetails currentUser,
                    @RequestParam(required = false) String begin, @RequestParam(required = false) String end) {
        try {
            return queryMetricsSummary(parseDate(begin, BEGIN), parseDate(end, END), currentUser, true);
        } catch (Exception e) {
            QueryMetricsSummaryResponse response = new QueryMetricsSummaryResponse();
            response.addException(e);
            return response;
        }
    }
    
    /**
     * Returns cache stats for the local part of the distributed Hazelcast cache
     *
     * @return the cache stats
     * @HTTP 200 success
     * @HTTP 500 internal server error
     */
    @Operation(summary = "Get the query metrics cache stats.")
    @Secured({"Administrator", "JBossAdministrator", "MetricsAdministrator"})
    @RequestMapping(path = "/cacheStats", method = {RequestMethod.GET}, produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public CacheStats getCacheStats() {
        CacheStats cacheStats = new CacheStats();
        IMap<Object,Object> incomingCacheHz = ((IMap<Object,Object>) incomingQueryMetricsCache.getNativeCache());
        cacheStats.setIncomingQueryMetrics(this.stats.getLocalMapStats(incomingCacheHz.getLocalMapStats()));
        IMap<Object,Object> lastWrittenCacheHz = ((IMap<Object,Object>) lastWrittenQueryMetricCache.getNativeCache());
        cacheStats.setLastWrittenQueryMetrics(this.stats.getLocalMapStats(lastWrittenCacheHz.getLocalMapStats()));
        cacheStats.setServiceStats(this.stats.formatStats(this.stats.getServiceStats(), true));
        cacheStats.setMemberUuid(getClusterLocalMemberUuid());
        try {
            cacheStats.setHost(InetAddress.getLocalHost().getCanonicalHostName());
        } catch (Exception e) {
            
        }
        return cacheStats;
    }
    
    @Scheduled(fixedRateString = "${datawave.query.metric.stats.logServiceStatsRateMs:300000}")
    public void logStats() {
        this.stats.logServiceStats();
    }
    
    @Scheduled(fixedRateString = "${datawave.query.metric.stats.publishServiceStatsToTimelyRateMs:60000}")
    public void publishServiceStatsToTimely() {
        this.stats.writeServiceStatsToTimely();
    }
    
    @Scheduled(fixedRateString = "${datawave.query.metric.stats.publishQueryStatsToTimelyRateMs:60000}")
    public void publishQueryStatsToTimely() {
        this.stats.queueAggregatedQueryStatsForTimely();
        this.stats.writeQueryStatsToTimely();
    }
}
