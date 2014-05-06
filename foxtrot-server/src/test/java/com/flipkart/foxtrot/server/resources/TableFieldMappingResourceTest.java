package com.flipkart.foxtrot.server.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.foxtrot.common.FieldType;
import com.flipkart.foxtrot.common.FieldTypeMapping;
import com.flipkart.foxtrot.common.TableFieldMapping;
import com.flipkart.foxtrot.core.MockElasticsearchServer;
import com.flipkart.foxtrot.core.TestUtils;
import com.flipkart.foxtrot.core.common.CacheUtils;
import com.flipkart.foxtrot.core.datastore.DataStore;
import com.flipkart.foxtrot.core.querystore.QueryExecutor;
import com.flipkart.foxtrot.core.querystore.QueryStore;
import com.flipkart.foxtrot.core.querystore.TableMetadataManager;
import com.flipkart.foxtrot.core.querystore.actions.spi.AnalyticsLoader;
import com.flipkart.foxtrot.core.querystore.impl.*;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.yammer.dropwizard.testing.ResourceTest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by rishabh.goyal on 06/05/14.
 */
public class TableFieldMappingResourceTest extends ResourceTest {

    private ObjectMapper mapper = new ObjectMapper();
    private HazelcastInstance hazelcastInstance;
    private MockElasticsearchServer elasticsearchServer;
    private QueryStore queryStore;

    public TableFieldMappingResourceTest() throws Exception {
        ElasticsearchUtils.setMapper(mapper);
        DataStore dataStore = TestUtils.getDataStore();

        //Initializing Cache Factory
        hazelcastInstance = new TestHazelcastInstanceFactory(1).newHazelcastInstance();
        HazelcastConnection hazelcastConnection = Mockito.mock(HazelcastConnection.class);
        when(hazelcastConnection.getHazelcast()).thenReturn(hazelcastInstance);
        CacheUtils.setCacheFactory(new DistributedCacheFactory(hazelcastConnection, mapper));

        elasticsearchServer = new MockElasticsearchServer(UUID.randomUUID().toString());
        ElasticsearchConnection elasticsearchConnection = Mockito.mock(ElasticsearchConnection.class);
        when(elasticsearchConnection.getClient()).thenReturn(elasticsearchServer.getClient());
        ElasticsearchUtils.initializeMappings(elasticsearchServer.getClient());

        Settings indexSettings = ImmutableSettings.settingsBuilder().put("number_of_replicas", 0).build();
        CreateIndexRequest createRequest = new CreateIndexRequest(TableMapStore.TABLE_META_INDEX).settings(indexSettings);
        elasticsearchServer.getClient().admin().indices().create(createRequest).actionGet();
        elasticsearchServer.getClient().admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        TableMetadataManager tableMetadataManager = Mockito.mock(TableMetadataManager.class);
        tableMetadataManager.start();
        when(tableMetadataManager.exists(TestUtils.TEST_TABLE)).thenReturn(true);


        AnalyticsLoader analyticsLoader = new AnalyticsLoader(dataStore, elasticsearchConnection);
        TestUtils.registerActions(analyticsLoader, mapper);
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        QueryExecutor queryExecutor = new QueryExecutor(analyticsLoader, executorService);
        queryStore = new ElasticsearchQueryStore(tableMetadataManager, elasticsearchConnection, dataStore, queryExecutor);
    }

    @Override
    protected void setUpResources() throws Exception {
        addResource(new TableFieldMappingResource(queryStore));
    }

    @After
    public void tearDown() throws IOException {
        elasticsearchServer.shutdown();
        hazelcastInstance.shutdown();
    }

    @Test
    public void testGet() throws Exception {
        queryStore.save(TestUtils.TEST_TABLE, TestUtils.getMappingDocuments(mapper));
        Thread.sleep(500);

        Set<FieldTypeMapping> mappings = new HashSet<FieldTypeMapping>();
        mappings.add(new FieldTypeMapping("word", FieldType.STRING));
        mappings.add(new FieldTypeMapping("data.data", FieldType.STRING));
        mappings.add(new FieldTypeMapping("header.hello", FieldType.STRING));
        mappings.add(new FieldTypeMapping("head.hello", FieldType.LONG));

        TableFieldMapping tableFieldMapping = new TableFieldMapping(TestUtils.TEST_TABLE, mappings);
        String response = client().resource(String.format("/foxtrot/v1/fields/%s", TestUtils.TEST_TABLE))
                .get(String.class);

        TableFieldMapping mapping = mapper.readValue(response, TableFieldMapping.class);
        assertEquals(tableFieldMapping.getTable(), mapping.getTable());
        assertTrue(tableFieldMapping.getFieldMappings().equals(mapping.getFieldMappings()));
    }

    @Test(expected = UniformInterfaceException.class)
    public void testGetInvalidTable() throws Exception {
        client().resource(String.format("/foxtrot/v1/fields/%s", TestUtils.TEST_TABLE + "-missing"))
                .get(String.class);
    }

    @Test
    public void testGetTableWithNoDocument() throws Exception {
        TableFieldMapping request = new TableFieldMapping(TestUtils.TEST_TABLE, new HashSet<FieldTypeMapping>());
        TableFieldMapping response = client().resource(String.format("/foxtrot/v1/fields/%s", TestUtils.TEST_TABLE))
                .get(TableFieldMapping.class);

        assertEquals(request.getTable(), response.getTable());
        assertTrue(request.getFieldMappings().equals(response.getFieldMappings()));
    }
}
