package datawave.microservice.querymetric.handler;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import datawave.ingest.data.config.NormalizedContentInterface;
import datawave.ingest.data.config.NormalizedFieldAndValue;
import datawave.ingest.data.config.ingest.CSVIngestHelper;
import datawave.ingest.data.config.ingest.TermFrequencyIngestHelperInterface;
import datawave.microservice.querymetric.BaseQueryMetric;
import datawave.microservice.querymetric.BaseQueryMetric.PageMetric;
import datawave.microservice.querymetric.BaseQueryMetric.Prediction;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.visitors.TreeFlatteningRebuildingVisitor;
import datawave.query.language.parser.jexl.LuceneToJexlQueryParser;
import datawave.query.language.tree.QueryNode;
import datawave.webservice.query.QueryImpl;
import datawave.webservice.query.util.QueryUtil;
import org.apache.commons.jexl2.parser.ASTEQNode;
import org.apache.commons.jexl2.parser.ASTJexlScript;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ContentQueryMetricsIngestHelper extends CSVIngestHelper implements TermFrequencyIngestHelperInterface {
    
    private static final Logger log = LoggerFactory.getLogger(ContentQueryMetricsIngestHelper.class);
    private static final Integer MAX_FIELD_VALUE_LENGTH = 500000;
    
    private Set<String> contentIndexFields = new HashSet<>();
    private HelperDelegate<BaseQueryMetric> delegate;
    
    public ContentQueryMetricsIngestHelper(boolean deleteMode) {
        this(deleteMode, new HelperDelegate<>());
    }
    
    public ContentQueryMetricsIngestHelper(boolean deleteMode, HelperDelegate<BaseQueryMetric> delegate) {
        this.deleteMode = deleteMode;
        this.delegate = delegate;
    }
    
    public Multimap<String,NormalizedContentInterface> getEventFieldsToDelete(BaseQueryMetric updatedQueryMetric, BaseQueryMetric storedQueryMetric) {
        return normalize(delegate.getEventFieldsToDelete(updatedQueryMetric, storedQueryMetric));
    }
    
    @Override
    public Multimap<String,NormalizedContentInterface> normalize(Multimap<String,String> fields) {
        Multimap<String,NormalizedContentInterface> results = HashMultimap.create();
        
        for (Map.Entry<String,String> e : fields.entries()) {
            if (e.getValue() != null) {
                String field = e.getKey();
                NormalizedFieldAndValue nfv = null;
                int x = field.indexOf('.');
                if (x > -1) {
                    String baseFieldName = field.substring(0, x);
                    String group = field.substring(x + 1);
                    nfv = new NormalizedFieldAndValue(baseFieldName, e.getValue(), group, null);
                } else {
                    nfv = new NormalizedFieldAndValue(field, e.getValue());
                }
                applyNormalizationAndAddToResults(results, nfv);
            } else
                log.warn(this.getType().typeName() + " has key " + e.getKey() + " with a null value.");
        }
        return results;
    }
    
    public Multimap<String,NormalizedContentInterface> getEventFieldsToWrite(BaseQueryMetric updatedQueryMetric) {
        return normalize(delegate.getEventFieldsToWrite(updatedQueryMetric));
    }
    
    @Override
    public boolean isTermFrequencyField(String field) {
        return contentIndexFields.contains(field);
    }
    
    @Override
    public String getTokenFieldNameDesignator() {
        return "";
    }
    
    @Override
    public boolean isIndexOnlyField(String fieldName) {
        return false;
    }
    
    public int getFieldSizeThreshold() {
        return helper.getFieldSizeThreshold();
    }
    
    public static class HelperDelegate<T extends BaseQueryMetric> {
        
        public Multimap<String,String> getEventFieldsToWrite(T updatedQueryMetric) {
            
            HashMultimap<String,String> fields = HashMultimap.create();
            
            SimpleDateFormat sdf_date_time1 = new SimpleDateFormat("yyyyMMdd HHmmss");
            SimpleDateFormat sdf_date_time2 = new SimpleDateFormat("yyyyMMdd HHmmss");
            
            String type = updatedQueryMetric.getQueryType();
            // this is time consuming - we only need to parse the query and write the selectors once
            if (type != null && type.equalsIgnoreCase("RunningQuery") && updatedQueryMetric.getNumUpdates() == 0) {
                
                String query = updatedQueryMetric.getQuery();
                if (query != null) {
                    ASTJexlScript jexlScript = null;
                    try {
                        // Parse and flatten here before visitors visit.
                        jexlScript = JexlASTHelper.parseAndFlattenJexlQuery(query);
                    } catch (Throwable t1) {
                        // not JEXL, try LUCENE
                        try {
                            LuceneToJexlQueryParser luceneToJexlParser = new LuceneToJexlQueryParser();
                            QueryNode node = luceneToJexlParser.parse(query);
                            String jexlQuery = node.getOriginalQuery();
                            jexlScript = JexlASTHelper.parseAndFlattenJexlQuery(jexlQuery);
                        } catch (Throwable t2) {
                            
                        }
                    }
                    
                    jexlScript = TreeFlatteningRebuildingVisitor.flatten(jexlScript);
                    
                    if (jexlScript != null) {
                        List<ASTEQNode> positiveEQNodes = JexlASTHelper.getPositiveEQNodes(jexlScript);
                        for (ASTEQNode pos : positiveEQNodes) {
                            String identifier = JexlASTHelper.getIdentifier(pos);
                            Object literal = JexlASTHelper.getLiteralValue(pos);
                            if (identifier != null && literal != null) {
                                fields.put("POSITIVE_SELECTORS", identifier + ":" + literal);
                            }
                        }
                        List<ASTEQNode> negativeEQNodes = JexlASTHelper.getNegativeEQNodes(jexlScript);
                        for (ASTEQNode neg : negativeEQNodes) {
                            String identifier = JexlASTHelper.getIdentifier(neg);
                            Object literal = JexlASTHelper.getLiteralValue(neg);
                            if (identifier != null && literal != null) {
                                fields.put("NEGATIVE_SELECTORS", identifier + ":" + literal);
                            }
                        }
                    }
                }
            }
            
            if (updatedQueryMetric.getQueryAuthorizations() != null) {
                fields.put("AUTHORIZATIONS", updatedQueryMetric.getQueryAuthorizations());
            }
            if (updatedQueryMetric.getBeginDate() != null) {
                fields.put("BEGIN_DATE", sdf_date_time1.format(updatedQueryMetric.getBeginDate()));
            }
            fields.put("CREATE_CALL_TIME", Long.toString(updatedQueryMetric.getCreateCallTime()));
            if (updatedQueryMetric.getCreateDate() != null) {
                fields.put("CREATE_DATE", sdf_date_time2.format(updatedQueryMetric.getCreateDate()));
            }
            fields.put("DOC_RANGES", Long.toString(updatedQueryMetric.getDocRanges()));
            fields.put("ELAPSED_TIME", Long.toString(updatedQueryMetric.getElapsedTime()));
            if (updatedQueryMetric.getEndDate() != null) {
                fields.put("END_DATE", sdf_date_time1.format(updatedQueryMetric.getEndDate()));
            }
            if (updatedQueryMetric.getErrorCode() != null) {
                fields.put("ERROR_CODE", updatedQueryMetric.getErrorCode());
            }
            if (updatedQueryMetric.getErrorMessage() != null) {
                fields.put("ERROR_MESSAGE", updatedQueryMetric.getErrorMessage());
            }
            fields.put("FI_RANGES", Long.toString(updatedQueryMetric.getFiRanges()));
            if (updatedQueryMetric.getHost() != null) {
                fields.put("HOST", updatedQueryMetric.getHost());
            }
            if (updatedQueryMetric.getLastUpdated() != null) {
                fields.put("LAST_UPDATED", sdf_date_time2.format(updatedQueryMetric.getLastUpdated()));
            }
            if (updatedQueryMetric.getLifecycle() != null) {
                fields.put("LIFECYCLE", updatedQueryMetric.getLifecycle().toString());
            }
            fields.put("LOGIN_TIME", Long.toString(updatedQueryMetric.getLoginTime()));
            fields.put("NEXT_COUNT", Long.toString(updatedQueryMetric.getNextCount()));
            fields.put("NUM_RESULTS", Long.toString(updatedQueryMetric.getNumResults()));
            fields.put("NUM_PAGES", Long.toString(updatedQueryMetric.getNumPages()));
            fields.put("NUM_UPDATES", Long.toString(updatedQueryMetric.getNumUpdates()));
            Set<QueryImpl.Parameter> parameters = updatedQueryMetric.getParameters();
            if (parameters != null && !parameters.isEmpty()) {
                fields.put("PARAMETERS", QueryUtil.toParametersString(parameters));
            }
            if (updatedQueryMetric.getPlan() != null) {
                fields.put("PLAN", updatedQueryMetric.getPlan());
            }
            if (updatedQueryMetric.getProxyServers() != null) {
                fields.put("PROXY_SERVERS", StringUtils.join(updatedQueryMetric.getProxyServers(), ","));
            }
            List<PageMetric> pageMetrics = updatedQueryMetric.getPageTimes();
            if (pageMetrics != null && !pageMetrics.isEmpty()) {
                for (PageMetric p : pageMetrics) {
                    fields.put("PAGE_METRICS." + p.getPageNumber(), p.toEventString());
                }
            }
            Set<Prediction> predictions = updatedQueryMetric.getPredictions();
            if (predictions != null && !predictions.isEmpty()) {
                for (Prediction prediction : predictions) {
                    fields.put("PREDICTION", prediction.getName() + ":" + prediction.getPrediction());
                }
            }
            if (updatedQueryMetric.getQuery() != null) {
                fields.put("QUERY", updatedQueryMetric.getQuery());
            }
            if (updatedQueryMetric.getQueryId() != null) {
                fields.put("QUERY_ID", updatedQueryMetric.getQueryId());
            }
            if (updatedQueryMetric.getQueryLogic() != null) {
                fields.put("QUERY_LOGIC", updatedQueryMetric.getQueryLogic());
            }
            if (updatedQueryMetric.getQueryName() != null) {
                fields.put("QUERY_NAME", updatedQueryMetric.getQueryName());
            }
            if (updatedQueryMetric.getQueryType() != null) {
                fields.put("QUERY_TYPE", updatedQueryMetric.getQueryType());
            }
            fields.put("SETUP_TIME", Long.toString(updatedQueryMetric.getSetupTime()));
            fields.put("SEEK_COUNT", Long.toString(updatedQueryMetric.getSeekCount()));
            fields.put("SOURCE_COUNT", Long.toString(updatedQueryMetric.getSourceCount()));
            if (updatedQueryMetric.getUser() != null) {
                fields.put("USER", updatedQueryMetric.getUser());
            }
            if (updatedQueryMetric.getUserDN() != null) {
                fields.put("USER_DN", updatedQueryMetric.getUserDN());
            }
            if (updatedQueryMetric.getVersion() != null) {
                fields.put("VERSION", updatedQueryMetric.getVersion());
            }
            fields.put("YIELD_COUNT", Long.toString(updatedQueryMetric.getYieldCount()));
            
            putExtendedFieldsToWrite(updatedQueryMetric, fields);
            
            fields.entries().forEach(e -> {
                if (e.getValue().length() > MAX_FIELD_VALUE_LENGTH) {
                    e.setValue(e.getValue().substring(0, MAX_FIELD_VALUE_LENGTH) + "<truncated>");
                }
            });
            return fields;
        }
        
        protected void putExtendedFieldsToWrite(T updatedQueryMetric, Multimap<String,String> fields) {
            
        }
        
        public Multimap<String,String> getEventFieldsToDelete(T updatedQueryMetric, T storedQueryMetric) {
            
            HashMultimap<String,String> fields = HashMultimap.create();
            
            SimpleDateFormat sdf_date_time2 = new SimpleDateFormat("yyyyMMdd HHmmss");
            
            if (updatedQueryMetric.getCreateCallTime() != storedQueryMetric.getCreateCallTime()) {
                fields.put("CREATE_CALL_TIME", Long.toString(storedQueryMetric.getCreateCallTime()));
            }
            if (updatedQueryMetric.getDocRanges() != storedQueryMetric.getDocRanges()) {
                fields.put("DOC_RANGES", Long.toString(storedQueryMetric.getDocRanges()));
            }
            if (updatedQueryMetric.getElapsedTime() != storedQueryMetric.getElapsedTime()) {
                fields.put("ELAPSED_TIME", Long.toString(storedQueryMetric.getElapsedTime()));
            }
            if (updatedQueryMetric.getFiRanges() != storedQueryMetric.getFiRanges()) {
                fields.put("FI_RANGES", Long.toString(storedQueryMetric.getFiRanges()));
            }
            if (storedQueryMetric.getLastUpdated() != null && updatedQueryMetric.getLastUpdated() != null) {
                String storedValue = sdf_date_time2.format(storedQueryMetric.getLastUpdated());
                String updatedValue = sdf_date_time2.format(updatedQueryMetric.getLastUpdated());
                if (!updatedValue.equals(storedValue)) {
                    fields.put("LAST_UPDATED", storedValue);
                }
            }
            if (!updatedQueryMetric.getLifecycle().equals(storedQueryMetric.getLifecycle())) {
                if (storedQueryMetric.getLifecycle() != null) {
                    fields.put("LIFECYCLE", storedQueryMetric.getLifecycle().toString());
                }
            }
            if (updatedQueryMetric.getLoginTime() != storedQueryMetric.getLoginTime()) {
                fields.put("LOGIN_TIME", Long.toString(storedQueryMetric.getLoginTime()));
            }
            fields.put("NUM_UPDATES", Long.toString(storedQueryMetric.getNumUpdates()));
            if (updatedQueryMetric.getNextCount() != storedQueryMetric.getNextCount()) {
                fields.put("NEXT_COUNT", Long.toString(storedQueryMetric.getNextCount()));
            }
            if (updatedQueryMetric.getNumPages() != storedQueryMetric.getNumPages()) {
                fields.put("NUM_PAGES", Long.toString(storedQueryMetric.getNumPages()));
            }
            if (updatedQueryMetric.getNumResults() != storedQueryMetric.getNumResults()) {
                fields.put("NUM_RESULTS", Long.toString(storedQueryMetric.getNumResults()));
            }
            Map<Long,PageMetric> storedPageMetricMap = new HashMap<>();
            List<PageMetric> storedPageMetrics = storedQueryMetric.getPageTimes();
            if (storedPageMetrics != null) {
                for (PageMetric p : storedPageMetrics) {
                    storedPageMetricMap.put(p.getPageNumber(), p);
                }
            }
            List<PageMetric> updatedPageMetrics = updatedQueryMetric.getPageTimes();
            if (updatedPageMetrics != null) {
                for (PageMetric p : updatedPageMetrics) {
                    long pageNum = p.getPageNumber();
                    PageMetric storedPageMetric = storedPageMetricMap.get(pageNum);
                    if (storedPageMetric != null && !storedPageMetric.equals(p)) {
                        fields.put("PAGE_METRICS." + storedPageMetric.getPageNumber(), storedPageMetric.toEventString());
                    }
                }
            }
            if (updatedQueryMetric.getSeekCount() != storedQueryMetric.getSeekCount()) {
                fields.put("SEEK_COUNT", Long.toString(storedQueryMetric.getSeekCount()));
            }
            if (updatedQueryMetric.getSetupTime() != storedQueryMetric.getSetupTime()) {
                fields.put("SETUP_TIME", Long.toString(storedQueryMetric.getSetupTime()));
            }
            if (updatedQueryMetric.getSourceCount() != storedQueryMetric.getSourceCount()) {
                fields.put("SOURCE_COUNT", Long.toString(storedQueryMetric.getSourceCount()));
            }
            if (updatedQueryMetric.getYieldCount() != storedQueryMetric.getYieldCount()) {
                fields.put("YIELD_COUNT", Long.toString(storedQueryMetric.getYieldCount()));
            }
            putExtendedFieldsToDelete(updatedQueryMetric, fields);
            
            fields.entries().forEach(e -> {
                if (e.getValue().length() > MAX_FIELD_VALUE_LENGTH) {
                    e.setValue(e.getValue().substring(0, MAX_FIELD_VALUE_LENGTH) + "<truncated>");
                }
            });
            return fields;
        }
        
        protected void putExtendedFieldsToDelete(T updatedQueryMetric, Multimap<String,String> fields) {
            
        }
    }
}
