package com.flipkart.foxtrot.core.querystore.actions;

import com.flipkart.foxtrot.common.ActionResponse;
import com.flipkart.foxtrot.common.query.Filter;
import com.flipkart.foxtrot.common.query.general.AnyFilter;
import com.flipkart.foxtrot.common.stats.StatsTrendRequest;
import com.flipkart.foxtrot.common.stats.StatsTrendResponse;
import com.flipkart.foxtrot.common.stats.StatsTrendValue;
import com.flipkart.foxtrot.core.common.Action;
import com.flipkart.foxtrot.core.datastore.DataStore;
import com.flipkart.foxtrot.core.querystore.QueryStoreException;
import com.flipkart.foxtrot.core.querystore.actions.spi.AnalyticsProvider;
import com.flipkart.foxtrot.core.querystore.impl.ElasticsearchConnection;
import com.flipkart.foxtrot.core.querystore.impl.ElasticsearchUtils;
import com.flipkart.foxtrot.core.querystore.query.ElasticSearchQueryGenerator;
import com.google.common.collect.Lists;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogram;
import org.elasticsearch.search.aggregations.metrics.percentiles.InternalPercentiles;
import org.elasticsearch.search.aggregations.metrics.percentiles.Percentiles;
import org.elasticsearch.search.aggregations.metrics.stats.extended.InternalExtendedStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Created by rishabh.goyal on 02/08/14.
 */

@AnalyticsProvider(opcode = "statstrend", request = StatsTrendRequest.class, response = StatsTrendResponse.class, cacheable = false)
public class StatsTrendAction extends Action<StatsTrendRequest> {

    private static final Logger logger = LoggerFactory.getLogger(StatsAction.class.getSimpleName());

    public StatsTrendAction(StatsTrendRequest parameter, DataStore dataStore, ElasticsearchConnection connection, String cacheToken) {
        super(parameter, dataStore, connection, cacheToken);
    }

    @Override
    protected String getRequestCacheKey() {
        StatsTrendRequest statsRequest = getParameter();
        long hashKey = 0L;
        if (statsRequest.getFilters() != null) {
            for (Filter filter : statsRequest.getFilters()) {
                hashKey += 31 * filter.hashCode();
            }
        }
        hashKey += 31 * statsRequest.getPeriod().name().hashCode();
        hashKey += 31 * statsRequest.getTimestamp().hashCode();
        hashKey += 31 * (statsRequest.getField() != null ? statsRequest.getField().hashCode() : "FIELD".hashCode());
        return String.format("stats-trend-%s-%s-%d-%d-%d", statsRequest.getTable(),
                statsRequest.getField(), statsRequest.getFrom() / 30000, statsRequest.getTo() / 30000, hashKey);
    }

    @Override
    public ActionResponse execute(StatsTrendRequest parameter) throws QueryStoreException {
        parameter.setTable(ElasticsearchUtils.getValidTableName(parameter.getTable()));
        if (null == parameter.getFilters()) {
            parameter.setFilters(Lists.<Filter>newArrayList(new AnyFilter(parameter.getTable())));
        }
        if (null == parameter.getTable()) {
            throw new QueryStoreException(QueryStoreException.ErrorCode.INVALID_REQUEST, "Invalid Table");
        }

        long currentTime = System.currentTimeMillis();
        if (0L == parameter.getFrom() || 0L == parameter.getTo()) {
            parameter.setFrom(currentTime - 86400000L);
            parameter.setTo(currentTime);
        }

        String field = parameter.getField();
        if (null == field || field.isEmpty()) {
            throw new QueryStoreException(QueryStoreException.ErrorCode.INVALID_REQUEST, "Invalid field name");
        }

        try {
            AbstractAggregationBuilder aggregation = buildAggregation(parameter);
            SearchResponse response = getConnection().getClient().prepareSearch(
                    ElasticsearchUtils.getIndices(parameter.getTable()))
                    .setTypes(ElasticsearchUtils.TYPE_NAME)
                    .setQuery(new ElasticSearchQueryGenerator(parameter.getCombiner()).genFilter(parameter.getFilters()))
                    .setSize(0)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .addAggregation(aggregation)
                    .execute()
                    .actionGet();

            Aggregations aggregations = response.getAggregations();
            if (aggregations != null) {
                return buildResponse(parameter, aggregations);
            }
            return null;
        } catch (Exception e) {
            logger.error("Error running stats query: ", e);
            throw new QueryStoreException(QueryStoreException.ErrorCode.QUERY_EXECUTION_ERROR,
                    "Error running stats query.", e);
        }
    }

    private AbstractAggregationBuilder buildAggregation(StatsTrendRequest request) {
        DateHistogram.Interval interval;
        switch (request.getPeriod()) {
            case seconds:
                interval = DateHistogram.Interval.SECOND;
                break;
            case minutes:
                interval = DateHistogram.Interval.MINUTE;
                break;
            case hours:
                interval = DateHistogram.Interval.HOUR;
                break;
            case days:
                interval = DateHistogram.Interval.DAY;
                break;
            default:
                interval = DateHistogram.Interval.HOUR;
                break;
        }

        String dateHistogramKey = Utils.getDateHistogramKey(request.getTimestamp());
        return AggregationBuilders.dateHistogram(dateHistogramKey)
                .field(request.getTimestamp())
                .interval(interval)
                .subAggregation(Utils.buildExtendedStatsAggregation(request.getField()))
                .subAggregation(Utils.buildPercentileAggregation(request.getField()));
    }

    private StatsTrendResponse buildResponse(StatsTrendRequest request, Aggregations aggregations) {
        String dateHistogramKey = Utils.getDateHistogramKey(request.getTimestamp());
        DateHistogram dateHistogram = aggregations.get(dateHistogramKey);
        Collection<? extends DateHistogram.Bucket> buckets = dateHistogram.getBuckets();

        String metricKey = Utils.getExtendedStatsAggregationKey(request.getField());
        String percentileMetricKey = Utils.getPercentileAggregationKey(request.getField());

        List<StatsTrendValue> statsValueList = new ArrayList<StatsTrendValue>();
        for (DateHistogram.Bucket bucket : buckets) {
            StatsTrendValue statsTrendValue = new StatsTrendValue();
            statsTrendValue.setPeriod(bucket.getKeyAsNumber());

            InternalExtendedStats extendedStats = InternalExtendedStats.class.cast(bucket.getAggregations().getAsMap().get(metricKey));
            Map<String, Number> stats = new HashMap<String, Number>();
            stats.put("avg", extendedStats.getAvg());
            stats.put("sum", extendedStats.getSum());
            stats.put("count", extendedStats.getCount());
            stats.put("min", extendedStats.getMin());
            stats.put("max", extendedStats.getMax());
            stats.put("sum_of_squares", extendedStats.getSumOfSquares());
            stats.put("variance", extendedStats.getVariance());
            stats.put("std_deviation", extendedStats.getStdDeviation());
            statsTrendValue.setStats(stats);

            InternalPercentiles internalPercentile = InternalPercentiles.class.cast(bucket.getAggregations().getAsMap().get(percentileMetricKey));
            Map<Number, Number> percentiles = new HashMap<Number, Number>();

            for (Percentiles.Percentile percentile : internalPercentile) {
                percentiles.put(percentile.getPercent(), percentile.getValue());
            }
            statsTrendValue.setPercentiles(percentiles);

            statsValueList.add(statsTrendValue);
        }
        return new StatsTrendResponse(statsValueList);
    }
}

